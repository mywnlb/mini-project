package cn.zhangyis.minidb.storage.transaction.purge;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * History List - 已提交事务的 Undo 版本链管理
 *
 * <p>维护按提交顺序排列的已提交 UPDATE Undo Segment 列表，
 * Purge 线程从头部开始按序清理。</p>
 *
 * <h2>InnoDB History List 概念</h2>
 * <pre>
 * 事务提交顺序: T1 → T2 → T3 → T4 → T5
 *                ↓
 * History List: [T1] → [T2] → [T3] → [T4] → [T5]
 *                ↑
 *           Purge 从这里开始清理
 * </pre>
 *
 * <h2>核心特性</h2>
 * <ul>
 *   <li><b>FIFO 顺序</b>: 先提交的事务先被清理</li>
 *   <li><b>线程安全</b>: 使用 ConcurrentLinkedDeque 保证并发安全</li>
 *   <li><b>按 Rollback Segment 分组</b>: 减少锁竞争</li>
 * </ul>
 *
 * <h2>设计约束 (Invariants)</h2>
 * <ul>
 *   <li><b>H1</b>: 只有已提交的 UPDATE Undo 才加入 History List</li>
 *   <li><b>H2</b>: 清理必须按提交顺序进行（保证 MVCC 一致性）</li>
 *   <li><b>H3</b>: 从 History List 移除后，Undo 页面可以被回收</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * HistoryList historyList = new HistoryList();
 *
 * // 事务提交时添加到 History List
 * historyList.add(trxId, rsegId);
 *
 * // Purge 时获取可清理的事务
 * TransactionId purgeLimit = coordinator.getPurgeLimit();
 * List<HistoryEntry> toPurge = historyList.getPurgableEntries(purgeLimit, 100);
 *
 * for (HistoryEntry entry : toPurge) {
 *     undoLogManager.purgeUpdateUndo(entry.getTrxId());
 *     historyList.remove(entry);
 * }
 * }</pre>
 *
 * @author MiniDB
 * @version 1.0
 * @see PurgeThread
 * @see PurgeCoordinator
 */
public class HistoryList {

    private static final Logger logger = LoggerFactory.getLogger(HistoryList.class);

    // ==================== 数据结构 ====================

    /**
     * 全局 History List（按提交顺序）
     *
     * <p>使用双端队列实现 FIFO 语义：
     * <ul>
     *   <li>add(): 添加到尾部（新提交的事务）</li>
     *   <li>poll(): 从头部取出（最早提交的事务）</li>
     * </ul></p>
     */
    private final ConcurrentLinkedDeque<HistoryEntry> globalList;

    /**
     * 按 Rollback Segment 分组的索引
     *
     * <p>用于批量清理时按 rsegId 分组，减少锁竞争。</p>
     */
    private final Map<Integer, Deque<HistoryEntry>> rsegIndex;

    /**
     * 当前 History List 长度
     */
    private final AtomicInteger length;

    // ==================== 构造函数 ====================

    /**
     * 创建 History List
     */
    public HistoryList() {
        this.globalList = new ConcurrentLinkedDeque<>();
        this.rsegIndex = new HashMap<>();
        this.length = new AtomicInteger(0);
    }

    // ==================== 核心操作 ====================

    /**
     * 添加已提交事务到 History List
     *
     * <p>在事务提交时调用，将 UPDATE Undo Segment 加入清理队列。</p>
     *
     * @param trxId  事务 ID
     * @param rsegId Rollback Segment ID
     */
    public void add(TransactionId trxId, int rsegId) {
        HistoryEntry entry = new HistoryEntry(trxId, rsegId, System.currentTimeMillis());

        // 添加到全局列表尾部
        globalList.addLast(entry);

        // 添加到 rseg 索引
        synchronized (rsegIndex) {
            rsegIndex.computeIfAbsent(rsegId, k -> new LinkedList<>()).addLast(entry);
        }

        length.incrementAndGet();

        logger.trace("Added to history list: trxId={}, rsegId={}", trxId, rsegId);
    }

    /**
     * 获取可清理的条目列表
     *
     * <p>返回所有 TRX_ID 小于 purgeLimit 的条目，按提交顺序排列。</p>
     *
     * @param purgeLimit Purge 边界（不包含）
     * @param maxCount   最大返回数量
     * @return 可清理的条目列表（不从 History List 移除）
     */
    public List<HistoryEntry> getPurgableEntries(TransactionId purgeLimit, int maxCount) {
        List<HistoryEntry> result = new ArrayList<>();

        for (HistoryEntry entry : globalList) {
            if (entry.getTrxId().getValue() >= purgeLimit.getValue()) {
                // 遇到不可清理的，停止（因为是按顺序排列的）
                break;
            }

            result.add(entry);

            if (result.size() >= maxCount) {
                break;
            }
        }

        return result;
    }

    /**
     * 获取指定 Rollback Segment 的可清理条目
     *
     * <p>用于按 rsegId 分组的批量清理。</p>
     *
     * @param rsegId     Rollback Segment ID
     * @param purgeLimit Purge 边界
     * @param maxCount   最大返回数量
     * @return 可清理的条目列表
     */
    public List<HistoryEntry> getPurgableEntriesByRseg(int rsegId, TransactionId purgeLimit, int maxCount) {
        List<HistoryEntry> result = new ArrayList<>();

        synchronized (rsegIndex) {
            Deque<HistoryEntry> rsegList = rsegIndex.get(rsegId);
            if (rsegList == null || rsegList.isEmpty()) {
                return result;
            }

            for (HistoryEntry entry : rsegList) {
                if (entry.getTrxId().getValue() >= purgeLimit.getValue()) {
                    break;
                }

                result.add(entry);

                if (result.size() >= maxCount) {
                    break;
                }
            }
        }

        return result;
    }

    /**
     * 移除条目（清理完成后调用）
     *
     * <p>从全局列表和 rseg 索引中移除。</p>
     *
     * @param entry 要移除的条目
     * @return true 如果成功移除
     */
    public boolean remove(HistoryEntry entry) {
        // 从全局列表移除
        boolean removed = globalList.remove(entry);

        if (removed) {
            // 从 rseg 索引移除
            synchronized (rsegIndex) {
                Deque<HistoryEntry> rsegList = rsegIndex.get(entry.getRsegId());
                if (rsegList != null) {
                    rsegList.remove(entry);
                    if (rsegList.isEmpty()) {
                        rsegIndex.remove(entry.getRsegId());
                    }
                }
            }

            length.decrementAndGet();
            logger.trace("Removed from history list: trxId={}", entry.getTrxId());
        }

        return removed;
    }

    /**
     * 批量移除条目
     *
     * @param entries 要移除的条目列表
     * @return 成功移除的数量
     */
    public int removeAll(List<HistoryEntry> entries) {
        int removedCount = 0;
        for (HistoryEntry entry : entries) {
            if (remove(entry)) {
                removedCount++;
            }
        }
        return removedCount;
    }

    /**
     * 从头部取出并移除一个条目
     *
     * @return 头部条目，如果为空返回 null
     */
    public HistoryEntry poll() {
        HistoryEntry entry = globalList.pollFirst();

        if (entry != null) {
            // 从 rseg 索引移除
            synchronized (rsegIndex) {
                Deque<HistoryEntry> rsegList = rsegIndex.get(entry.getRsegId());
                if (rsegList != null) {
                    rsegList.remove(entry);
                    if (rsegList.isEmpty()) {
                        rsegIndex.remove(entry.getRsegId());
                    }
                }
            }

            length.decrementAndGet();
        }

        return entry;
    }

    /**
     * 查看头部条目（不移除）
     *
     * @return 头部条目，如果为空返回 null
     */
    public HistoryEntry peek() {
        return globalList.peekFirst();
    }

    // ==================== 状态查询 ====================

    /**
     * 获取 History List 长度
     *
     * @return 当前长度
     */
    public int size() {
        return length.get();
    }

    /**
     * 是否为空
     *
     * @return true 如果为空
     */
    public boolean isEmpty() {
        return length.get() == 0;
    }

    /**
     * 获取最老条目的事务 ID（头部）
     *
     * @return 最老的事务 ID，如果为空返回 null
     */
    public TransactionId getOldestTrxId() {
        HistoryEntry oldest = globalList.peekFirst();
        return oldest != null ? oldest.getTrxId() : null;
    }

    /**
     * 获取最新条目的事务 ID（尾部）
     *
     * @return 最新的事务 ID，如果为空返回 null
     */
    public TransactionId getNewestTrxId() {
        HistoryEntry newest = globalList.peekLast();
        return newest != null ? newest.getTrxId() : null;
    }

    /**
     * 获取活跃的 Rollback Segment ID 列表
     *
     * @return rsegId 列表
     */
    public Set<Integer> getActiveRsegIds() {
        synchronized (rsegIndex) {
            return new HashSet<>(rsegIndex.keySet());
        }
    }

    /**
     * 清空 History List
     */
    public void clear() {
        globalList.clear();
        synchronized (rsegIndex) {
            rsegIndex.clear();
        }
        length.set(0);
    }

    @Override
    public String toString() {
        return String.format("HistoryList{size=%d, oldest=%s, newest=%s}",
                size(), getOldestTrxId(), getNewestTrxId());
    }

    // ==================== 内部类 ====================

    /**
     * History List 条目
     *
     * <p>记录一个已提交事务的 Undo Segment 信息。</p>
     */
    public static class HistoryEntry {
        private final TransactionId trxId;
        private final int rsegId;
        private final long commitTime;

        public HistoryEntry(TransactionId trxId, int rsegId, long commitTime) {
            this.trxId = trxId;
            this.rsegId = rsegId;
            this.commitTime = commitTime;
        }

        public TransactionId getTrxId() {
            return trxId;
        }

        public int getRsegId() {
            return rsegId;
        }

        public long getCommitTime() {
            return commitTime;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            HistoryEntry that = (HistoryEntry) o;
            return Objects.equals(trxId, that.trxId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(trxId);
        }

        @Override
        public String toString() {
            return String.format("HistoryEntry{trxId=%s, rsegId=%d}", trxId, rsegId);
        }
    }
}
