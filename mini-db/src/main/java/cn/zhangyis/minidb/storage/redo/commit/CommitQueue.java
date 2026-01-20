package cn.zhangyis.minidb.storage.redo.commit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Commit Queue - Group Commit 提交队列
 *
 * <p>实现 Phase 5 的 Group Commit 机制核心数据结构。
 * 管理等待 fsync 的提交线程，支持 Leader/Follower 模式。</p>
 *
 * <h2>核心机制</h2>
 * <pre>
 * 1. 提交队列: 按 commit_sn 排序存储等待者
 * 2. Leader 选举: CAS 抢占 leaderLock
 * 3. 批量 flush: Leader 执行一次 fsync，覆盖所有 <= flush_sn 的事务
 * 4. 精准唤醒: Leader 只唤醒本批次的 follower，避免惊群
 * </pre>
 *
 * <h2>数据流</h2>
 * <pre>
 * T1: commit(sn=100) → tryBeLeader() → 成为 leader
 * T2: commit(sn=200) → joinQueue() → 成为 follower
 * T3: commit(sn=300) → joinQueue() → 成为 follower
 *
 * Leader (T1):
 *   1. 等待短暂时间 (凑批次)
 *   2. flush_up_to_sn = min(writeSn, 队列最大 sn)
 *   3. fsync()
 *   4. wakeupBatch(flush_up_to_sn)
 *
 * 结果: 一次 fsync 完成 3 个事务提交
 * </pre>
 *
 * <h2>线程安全</h2>
 * <p>使用 ConcurrentSkipListMap 保证并发安全，Leader 选举使用 CAS。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class CommitQueue {

    private static final Logger logger = LoggerFactory.getLogger(CommitQueue.class);

    // ==================== 队列状态 ====================

    /**
     * 按 commit_sn 排序的 waiter 列表
     *
     * <p>使用 ConcurrentSkipListMap 保证：
     * <ul>
     *   <li>并发安全</li>
     *   <li>按 key 排序 (用于批量唤醒)</li>
     *   <li>O(log n) 的插入和删除</li>
     * </ul>
     * </p>
     */
    private final ConcurrentSkipListMap<Long, CommitWaiter> waiters = new ConcurrentSkipListMap<>();

    /**
     * Leader 锁 (CAS 抢占)
     *
     * <p>只有一个线程可以成为 leader 执行 fsync。</p>
     */
    private final AtomicBoolean leaderLock = new AtomicBoolean(false);

    // ==================== 统计计数器 ====================

    /** Leader 次数 */
    private final AtomicLong leaderCount = new AtomicLong(0);

    /** Follower 次数 */
    private final AtomicLong followerCount = new AtomicLong(0);

    /** 总唤醒次数 */
    private final AtomicLong wakeupCount = new AtomicLong(0);

    /** 总批量大小 (用于计算平均批量) */
    private final AtomicLong totalBatchSize = new AtomicLong(0);

    // ==================== Leader/Follower 控制 ====================

    /**
     * 尝试成为 leader
     *
     * <p>使用 CAS 抢占 leaderLock。第一个调用的线程成为 leader，
     * 其他线程成为 follower。</p>
     *
     * @return true 如果成功成为 leader
     */
    public boolean tryBeLeader() {
        boolean success = leaderLock.compareAndSet(false, true);
        if (success) {
            leaderCount.incrementAndGet();
            logger.trace("Thread {} became leader", Thread.currentThread().getName());
        } else {
            followerCount.incrementAndGet();
            logger.trace("Thread {} became follower", Thread.currentThread().getName());
        }
        return success;
    }

    /**
     * 释放 leader 角色
     *
     * <p>Leader 完成 fsync 和唤醒后调用，允许下一个 leader 产生。</p>
     */
    public void releaseLeader() {
        leaderLock.set(false);
        logger.trace("Leader released by thread {}", Thread.currentThread().getName());
    }

    /**
     * 检查当前是否有 leader
     *
     * @return true 如果有 leader 正在工作
     */
    public boolean hasLeader() {
        return leaderLock.get();
    }

    // ==================== 队列操作 ====================

    /**
     * 加入提交队列
     *
     * <p>提交线程在 waitForFlush 时调用，登记自己的 commit_sn。</p>
     *
     * @param commitSn     提交的 SN (redo log 结束位置)
     * @param waiterThread 等待的线程
     * @return 创建的 CommitWaiter
     */
    public CommitWaiter joinQueue(long commitSn, Thread waiterThread) {
        CommitWaiter waiter = new CommitWaiter(commitSn, waiterThread);
        waiters.put(commitSn, waiter);
        logger.trace("Thread {} joined queue with sn={}", waiterThread.getName(), commitSn);
        return waiter;
    }

    /**
     * 从队列移除
     *
     * <p>用于超时或取消时清理。</p>
     *
     * @param commitSn 要移除的 SN
     */
    public void removeFromQueue(long commitSn) {
        waiters.remove(commitSn);
    }

    /**
     * 获取队列大小
     *
     * @return 当前等待的线程数
     */
    public int size() {
        return waiters.size();
    }

    /**
     * 检查队列是否为空
     *
     * @return true 如果没有等待者
     */
    public boolean isEmpty() {
        return waiters.isEmpty();
    }

    /**
     * 获取队列中最大 SN
     *
     * <p>用于 Leader 确定 flush 上界。</p>
     *
     * @return 队列中最大的 commit_sn，如果队列为空返回 0
     */
    public long getMaxCommitSn() {
        Map.Entry<Long, CommitWaiter> last = waiters.lastEntry();
        return last != null ? last.getKey() : 0;
    }

    /**
     * 获取队列中最小 SN
     *
     * @return 队列中最小的 commit_sn，如果队列为空返回 Long.MAX_VALUE
     */
    public long getMinCommitSn() {
        Map.Entry<Long, CommitWaiter> first = waiters.firstEntry();
        return first != null ? first.getKey() : Long.MAX_VALUE;
    }

    // ==================== 批量唤醒 ====================

    /**
     * 移除并唤醒 sn <= flushUpToSn 的所有 waiters
     *
     * <p>Leader 在 fsync 完成后调用，精准唤醒本批次的 follower。
     * 使用 LockSupport.unpark() 避免 synchronized 开销。</p>
     *
     * @param flushUpToSn 已 flush 的 SN 上界
     * @return 唤醒的线程数
     */
    public int wakeupBatch(long flushUpToSn) {
        List<CommitWaiter> toWakeup = new ArrayList<>();

        // 收集需要唤醒的 waiters
        Iterator<Map.Entry<Long, CommitWaiter>> it = waiters.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, CommitWaiter> entry = it.next();
            if (entry.getKey() <= flushUpToSn) {
                toWakeup.add(entry.getValue());
                it.remove();
            } else {
                break;  // 已排序，后面的都 > flushUpToSn
            }
        }

        // 精准唤醒 (避免惊群)
        for (CommitWaiter waiter : toWakeup) {
            waiter.markCompleted();
            LockSupport.unpark(waiter.getThread());
        }

        int wakeupSize = toWakeup.size();
        if (wakeupSize > 0) {
            wakeupCount.addAndGet(wakeupSize);
            totalBatchSize.addAndGet(wakeupSize);
            logger.debug("Woke up {} waiters (sn <= {})", wakeupSize, flushUpToSn);
        }

        return wakeupSize;
    }

    // ==================== 统计信息 ====================

    /**
     * 获取 leader 次数
     */
    public long getLeaderCount() {
        return leaderCount.get();
    }

    /**
     * 获取 follower 次数
     */
    public long getFollowerCount() {
        return followerCount.get();
    }

    /**
     * 获取总唤醒次数
     */
    public long getWakeupCount() {
        return wakeupCount.get();
    }

    /**
     * 获取平均批量大小
     *
     * <p>平均每次 fsync 覆盖多少个事务。</p>
     *
     * @return 平均批量大小
     */
    public double getAvgBatchSize() {
        long leaders = leaderCount.get();
        if (leaders == 0) {
            return 0;
        }
        return (double) totalBatchSize.get() / leaders;
    }

    /**
     * 获取 leader/follower 比例
     *
     * @return leader 占比
     */
    public double getLeaderRatio() {
        long total = leaderCount.get() + followerCount.get();
        if (total == 0) {
            return 0;
        }
        return (double) leaderCount.get() / total;
    }

    /**
     * 重置统计信息
     */
    public void resetStats() {
        leaderCount.set(0);
        followerCount.set(0);
        wakeupCount.set(0);
        totalBatchSize.set(0);
    }

    /**
     * 获取统计信息字符串
     */
    public String getStats() {
        return String.format("leader=%d, follower=%d, wakeup=%d, avgBatch=%.2f, leaderRatio=%.2f%%",
                leaderCount.get(), followerCount.get(), wakeupCount.get(),
                getAvgBatchSize(), getLeaderRatio() * 100);
    }

    @Override
    public String toString() {
        return String.format("CommitQueue{size=%d, hasLeader=%s, %s}",
                size(), hasLeader(), getStats());
    }
}
