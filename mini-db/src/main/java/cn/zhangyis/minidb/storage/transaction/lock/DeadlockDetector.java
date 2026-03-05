package cn.zhangyis.minidb.storage.transaction.lock;

import cn.zhangyis.minidb.storage.transaction.core.Transaction;
import cn.zhangyis.minidb.storage.transaction.core.TransactionId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

/**
 * Wait-for Graph 死锁检测器
 *
 * <p>可选的后台线程，周期性扫描 wait-for 关系，通过 DFS 检测环，
 * 选择 {@link Transaction#getTotalModifications()} 最小的事务作为牺牲者。</p>
 *
 * <h2>检测流程</h2>
 * <ol>
 *   <li>逐 segment 加锁（不同时持有多个 segment 锁），遍历所有 LockRequestQueue</li>
 *   <li>对每个 queue: waitingList 中的事务等待 grantedList 中的事务 → 构建有向边</li>
 *   <li>DFS 检测环</li>
 *   <li>选择牺牲者: getTotalModifications() 最小的事务</li>
 *   <li>标记 ABORTED + unpark 牺牲者线程</li>
 * </ol>
 *
 * <h2>设计要点</h2>
 * <ul>
 *   <li>逐 segment 加锁: 避免同时锁多个 segment 导致基础设施死锁</li>
 *   <li>WFG 是快照: 扫描期间状态可能变化，false positive 可接受（事务会被回滚并重试）</li>
 *   <li>默认关闭: 仅靠 timeout 即可处理死锁，主动检测是可选优化</li>
 * </ul>
 */
public class DeadlockDetector {

    private final LockTableSegment[] segments;
    private final ConcurrentHashMap<TransactionId, Transaction> transactionMap;
    private final ConcurrentHashMap<TransactionId, LockRequest> waitingRequests;
    private final long detectIntervalMs;

    private volatile boolean running;
    private Thread detectorThread;

    /**
     * 创建死锁检测器
     *
     * @param segments           segment 数组
     * @param transactionMap     事务 ID → Transaction 映射
     * @param waitingRequests    事务 ID → 当前等待的 LockRequest 映射
     * @param detectIntervalMs   检测间隔毫秒数
     */
    public DeadlockDetector(LockTableSegment[] segments,
                            ConcurrentHashMap<TransactionId, Transaction> transactionMap,
                            ConcurrentHashMap<TransactionId, LockRequest> waitingRequests,
                            long detectIntervalMs) {
        this.segments = segments;
        this.transactionMap = transactionMap;
        this.waitingRequests = waitingRequests;
        this.detectIntervalMs = detectIntervalMs;
    }

    /**
     * 启动检测线程
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        detectorThread = new Thread(this::detectLoop, "minidb-deadlock-detector");
        detectorThread.setDaemon(true);
        detectorThread.start();
    }

    /**
     * 停止检测线程
     */
    public void stop() {
        running = false;
        if (detectorThread != null) {
            detectorThread.interrupt();
            try {
                detectorThread.join(detectIntervalMs * 2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ==================== 检测循环 ====================

    private void detectLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(detectIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (waitingRequests.isEmpty()) {
                continue; // 没有等待中的请求，跳过
            }

            detectAndResolve();
        }
    }

    /**
     * 执行一轮死锁检测
     *
     * <p>Package-private，供测试直接调用。</p>
     */
    void detectAndResolve() {
        // 1. 构建 wait-for graph
        Map<TransactionId, Set<TransactionId>> waitForGraph = buildWaitForGraph();
        if (waitForGraph.isEmpty()) {
            return;
        }

        // 2. 检测环
        List<TransactionId> cycle = findCycle(waitForGraph);
        if (cycle == null) {
            return;
        }

        // 3. 选择牺牲者
        TransactionId victim = selectVictim(cycle);
        if (victim == null) {
            return;
        }

        // 4. 中止牺牲者
        abortVictim(victim);
    }

    // ==================== WFG 构建 ====================

    /**
     * 构建 wait-for graph
     *
     * <p>逐 segment 加锁（不同时持有多个 segment 锁），
     * 遍历每个 queue 的 waitingList 和 grantedList，构建有向边。</p>
     *
     * <p>边: waiter.trxId → blocker.trxId（waiter 等待 blocker 释放锁）</p>
     *
     * @return 邻接表表示的 wait-for graph
     */
    private Map<TransactionId, Set<TransactionId>> buildWaitForGraph() {
        Map<TransactionId, Set<TransactionId>> graph = new HashMap<>();

        for (LockTableSegment segment : segments) {
            segment.forEachQueue((target, queue) -> {
                List<LockRequest> grantedList = queue.getGrantedList();
                List<LockRequest> waitingList = queue.getWaitingList();

                if (grantedList.isEmpty() || waitingList.isEmpty()) {
                    return;
                }

                // 对每个 waiter，它等待所有 blocker (grantedList 中与其不兼容的事务)
                for (LockRequest waiter : waitingList) {
                    TransactionId waiterId = waiter.getTrxId();
                    for (LockRequest blocker : grantedList) {
                        TransactionId blockerId = blocker.getTrxId();
                        if (!waiterId.equals(blockerId)
                                && !blocker.isCompatibleWith(waiter)) {
                            graph.computeIfAbsent(waiterId, k -> new HashSet<>())
                                    .add(blockerId);
                        }
                    }
                }
            });
        }

        return graph;
    }

    // ==================== 环检测 ====================

    /**
     * DFS 检测 wait-for graph 中的环
     *
     * @param graph 邻接表
     * @return 环中的事务 ID 列表（如果存在），否则返回 null
     */
    private List<TransactionId> findCycle(Map<TransactionId, Set<TransactionId>> graph) {
        Set<TransactionId> visited = new HashSet<>();
        Set<TransactionId> inStack = new HashSet<>();
        List<TransactionId> path = new ArrayList<>();

        for (TransactionId node : graph.keySet()) {
            if (!visited.contains(node)) {
                List<TransactionId> cycle = dfs(node, graph, visited, inStack, path);
                if (cycle != null) {
                    return cycle;
                }
            }
        }
        return null;
    }

    private List<TransactionId> dfs(TransactionId current,
                                    Map<TransactionId, Set<TransactionId>> graph,
                                    Set<TransactionId> visited,
                                    Set<TransactionId> inStack,
                                    List<TransactionId> path) {
        visited.add(current);
        inStack.add(current);
        path.add(current);

        Set<TransactionId> neighbors = graph.get(current);
        if (neighbors != null) {
            for (TransactionId neighbor : neighbors) {
                if (inStack.contains(neighbor)) {
                    // 发现环，提取环路径
                    int startIdx = path.indexOf(neighbor);
                    return new ArrayList<>(path.subList(startIdx, path.size()));
                }
                if (!visited.contains(neighbor)) {
                    List<TransactionId> cycle = dfs(neighbor, graph, visited, inStack, path);
                    if (cycle != null) {
                        return cycle;
                    }
                }
            }
        }

        path.remove(path.size() - 1);
        inStack.remove(current);
        return null;
    }

    // ==================== 牺牲者选择 ====================

    /**
     * 从环中选择牺牲者
     *
     * <p>选择 {@link Transaction#getTotalModifications()} 最小的事务，
     * 使回滚代价最小。如果无法获取 Transaction 对象，回退到最新事务（最大 TrxId）。</p>
     */
    private TransactionId selectVictim(List<TransactionId> cycle) {
        TransactionId victim = null;
        int minMods = Integer.MAX_VALUE;

        for (TransactionId trxId : cycle) {
            Transaction trx = transactionMap.get(trxId);
            if (trx != null) {
                int mods = trx.getTotalModifications();
                if (mods < minMods) {
                    minMods = mods;
                    victim = trxId;
                }
            }
        }

        // 回退: 如果无法获取 Transaction，选择最新的事务（TrxId 最大）
        if (victim == null) {
            for (TransactionId trxId : cycle) {
                if (victim == null || trxId.compareTo(victim) > 0) {
                    victim = trxId;
                }
            }
        }

        return victim;
    }

    // ==================== 牺牲者中止 ====================

    /**
     * 中止牺牲者事务
     *
     * <p>CAS 标记其等待中的 LockRequest 为 ABORTED，
     * 并 unpark 等待线程使其从 waitForLock 循环中退出。</p>
     */
    private void abortVictim(TransactionId victimId) {
        LockRequest request = waitingRequests.get(victimId);
        if (request == null) {
            return;
        }

        if (!request.markAborted()) {
            return; // 已不在 WAITING（可能已授予或已中止）
        }

        Thread t = request.getWaitingThread();
        if (t != null) {
            LockSupport.unpark(t);
        }
    }
}
