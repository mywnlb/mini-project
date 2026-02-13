package cn.zhangyis.minidb.storage.transaction.lock;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 分段锁表 — 单个 segment
 *
 * <p>参考 BufferPool 的 PageHashSegment 分段设计。
 * 每个 segment 拥有独立的 {@link ReentrantLock} 和 {@code Map<LockTarget, LockRequestQueue>}，
 * 通过 {@link LockTarget#hashCode()} 路由到不同 segment，降低锁竞争。</p>
 *
 * <h2>并发模型</h2>
 * <p>所有操作（tryAcquire / release / cancelWait）在进入前获取 segment 锁，
 * 在 finally 中释放。{@link LockRequestQueue} 本身不做同步。</p>
 *
 * <h2>设计约束</h2>
 * <ul>
 *   <li><b>L7</b>: release / cancelWait 后检查队列是否为空，如果为空则从 map 中移除，防止内存泄漏</li>
 * </ul>
 *
 * <h2>Phase 3 集成</h2>
 * <p>Phase 3 的 LockManager 将持有 {@code LockTableSegment[]} 数组，
 * 通过 {@code LockTarget.hashCode()} 路由到 segment。</p>
 *
 * <p>等待机制（park/unpark）由 Phase 3 的 LockManager 在 segment 锁外处理：</p>
 * <ol>
 *   <li>segment.lock() → tryAcquire(request) → 如果 WAIT，设置 waitingThread → segment.unlock()</li>
 *   <li>LockSupport.parkNanos (在 segment 锁外)</li>
 *   <li>唤醒后检查 request.isGranted() / isAborted()</li>
 * </ol>
 */
public class LockTableSegment {

    /** segment 级别的互斥锁 */
    private final ReentrantLock lock = new ReentrantLock();

    /** 锁目标 → 请求队列 映射 */
    private final Map<LockTarget, LockRequestQueue> map;

    /**
     * 创建 segment
     *
     * @param initialCapacity 初始 HashMap 容量
     */
    public LockTableSegment(int initialCapacity) {
        this.map = new HashMap<>(initialCapacity);
    }

    /**
     * 创建 segment（默认容量）
     */
    public LockTableSegment() {
        this(16);
    }

    // ==================== 核心操作 ====================

    /**
     * 尝试获取锁
     *
     * <p>在 segment 锁保护下，找到或创建目标的 {@link LockRequestQueue}，
     * 调用 {@link LockRequestQueue#tryGrant}。
     * 如果结果为 WAIT，设置 {@code request.waitingThread} 为当前线程，
     * 以便 {@link LockRequestQueue#grantWaiters} 中 unpark。</p>
     *
     * @param request 锁请求
     * @return GRANTED / WAIT / DEADLOCK
     */
    public LockResult tryAcquire(LockRequest request) {
        lock.lock();
        try {
            LockTarget target = request.getTarget();
            LockRequestQueue queue = map.computeIfAbsent(target, k -> new LockRequestQueue());
            LockResult result = queue.tryGrant(request);

            if (result == LockResult.WAIT) {
                request.setWaitingThread(Thread.currentThread());
            }

            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 释放指定事务在指定目标上的锁
     *
     * <p>L7: 释放后如果队列为空，从 map 中移除。</p>
     *
     * @param trxId  事务 ID
     * @param target 锁目标
     */
    public void release(TransactionId trxId, LockTarget target) {
        lock.lock();
        try {
            LockRequestQueue queue = map.get(target);
            if (queue != null) {
                queue.release(trxId);
                // L7: 空队列清理，防止内存泄漏
                if (queue.isEmpty()) {
                    map.remove(target);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 释放指定事务在本 segment 中的所有锁
     *
     * <p>批量释放入口，遍历事务持有的锁请求列表，逐目标释放。
     * L7: 释放后清理空队列。</p>
     *
     * @param trxId    事务 ID
     * @param requests 该事务在本 segment 中持有的锁请求列表
     */
    public void releaseAll(TransactionId trxId, List<LockRequest> requests) {
        lock.lock();
        try {
            for (LockRequest request : requests) {
                LockTarget target = request.getTarget();
                LockRequestQueue queue = map.get(target);
                if (queue != null) {
                    queue.release(trxId);
                    // L7: 空队列清理
                    if (queue.isEmpty()) {
                        map.remove(target);
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 取消指定事务在指定目标上的等待
     *
     * <p>用于锁等待超时或死锁检测中止。
     * L7: 取消后如果队列为空，从 map 中移除。</p>
     *
     * @param trxId  事务 ID
     * @param target 锁目标
     */
    public void cancelWait(TransactionId trxId, LockTarget target) {
        lock.lock();
        try {
            LockRequestQueue queue = map.get(target);
            if (queue != null) {
                queue.cancelWait(trxId);
                // L7: 空队列清理
                if (queue.isEmpty()) {
                    map.remove(target);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    // ==================== 锁访问（供 Phase 3 使用） ====================

    /**
     * 获取 segment 锁
     *
     * <p>Phase 3 的 LockManager 和 DeadlockDetector 可能需要直接获取 segment 锁，
     * 在锁保护下遍历队列构建 wait-for graph。</p>
     */
    public void segmentLock() {
        lock.lock();
    }

    /**
     * 释放 segment 锁
     */
    public void segmentUnlock() {
        lock.unlock();
    }

    /**
     * 获取指定目标的请求队列（调用方必须持有 segment 锁）
     *
     * @param target 锁目标
     * @return 请求队列，不存在返回 null
     */
    public LockRequestQueue getQueue(LockTarget target) {
        return map.get(target);
    }

    // ==================== 遍历（供 DeadlockDetector 使用） ====================

    /**
     * 遍历本 segment 中的所有请求队列
     *
     * <p>在 segment 锁保护下执行 visitor。用于 DeadlockDetector 构建 wait-for graph。</p>
     *
     * @param visitor 对每个 (LockTarget, LockRequestQueue) 执行的操作
     */
    public void forEachQueue(java.util.function.BiConsumer<LockTarget, LockRequestQueue> visitor) {
        lock.lock();
        try {
            map.forEach(visitor);
        } finally {
            lock.unlock();
        }
    }

    // ==================== 状态查询 ====================

    /**
     * 获取 segment 中活跃的锁目标数量
     *
     * @return 活跃队列数量
     */
    public int getQueueCount() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        return String.format("LockTableSegment(queues=%d)", map.size());
    }
}
