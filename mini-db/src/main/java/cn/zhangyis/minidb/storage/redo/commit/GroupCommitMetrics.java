package cn.zhangyis.minidb.storage.redo.commit;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Group Commit Metrics - Group Commit 性能指标
 *
 * <p>收集 Phase 5 Group Commit 机制的运行时统计信息，
 * 包括 leader/follower 计数、批量大小、延迟等。</p>
 *
 * <h2>核心指标</h2>
 * <ul>
 *   <li><b>Leader/Follower 比例</b>: 理想情况 1:9 (10 并发时)</li>
 *   <li><b>平均批量大小</b>: 每次 fsync 覆盖的事务数</li>
 *   <li><b>Group Commit 延迟</b>: 批量等待 + fsync 时间</li>
 *   <li><b>等待队列大小</b>: 当前等待的事务数</li>
 * </ul>
 *
 * <h2>期望指标 (10 并发线程)</h2>
 * <pre>
 * - 平均批量大小: 5-10 个事务/次 fsync
 * - Leader/Follower 比例: 1:9
 * - P99 延迟: < 1ms
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class GroupCommitMetrics {

    // ==================== Leader/Follower 统计 ====================

    /** Leader 次数 */
    private final LongAdder leaderCount = new LongAdder();

    /** Follower 次数 */
    private final LongAdder followerCount = new LongAdder();

    // ==================== 批量统计 ====================

    /** 总批量大小 (用于计算平均) */
    private final LongAdder totalBatchSize = new LongAdder();

    /** 最大批量大小 */
    private final AtomicLong maxBatchSize = new AtomicLong(0);

    /** fsync 次数 */
    private final LongAdder fsyncCount = new LongAdder();

    // ==================== 延迟统计 ====================

    /** 总批量等待时间 (纳秒) */
    private final LongAdder totalBatchWaitNanos = new LongAdder();

    /** 总 fsync 时间 (纳秒) */
    private final LongAdder totalFsyncNanos = new LongAdder();

    /** 总唤醒时间 (纳秒) */
    private final LongAdder totalWakeupNanos = new LongAdder();

    /** Follower 等待时间 (纳秒) */
    private final LongAdder totalFollowerWaitNanos = new LongAdder();

    // ==================== 记录方法 ====================

    /**
     * 记录成为 leader
     */
    public void recordLeader() {
        leaderCount.increment();
    }

    /**
     * 记录成为 follower
     */
    public void recordFollower() {
        followerCount.increment();
    }

    /**
     * 记录一次 group commit
     *
     * @param batchSize      批量大小 (事务数)
     * @param batchWaitNanos 批量等待时间 (纳秒)
     * @param fsyncNanos     fsync 时间 (纳秒)
     * @param wakeupNanos    唤醒时间 (纳秒)
     */
    public void recordGroupCommit(int batchSize, long batchWaitNanos, long fsyncNanos, long wakeupNanos) {
        totalBatchSize.add(batchSize);
        fsyncCount.increment();
        totalBatchWaitNanos.add(batchWaitNanos);
        totalFsyncNanos.add(fsyncNanos);
        totalWakeupNanos.add(wakeupNanos);

        // 更新最大批量
        updateMax(maxBatchSize, batchSize);
    }

    /**
     * 记录 follower 等待时间
     *
     * @param waitNanos 等待时间 (纳秒)
     */
    public void recordFollowerWait(long waitNanos) {
        totalFollowerWaitNanos.add(waitNanos);
    }

    // ==================== 查询方法 ====================

    /**
     * 获取 leader 次数
     */
    public long getLeaderCount() {
        return leaderCount.sum();
    }

    /**
     * 获取 follower 次数
     */
    public long getFollowerCount() {
        return followerCount.sum();
    }

    /**
     * 获取 fsync 次数
     */
    public long getFsyncCount() {
        return fsyncCount.sum();
    }

    /**
     * 获取 leader 比例
     *
     * <p>理想情况下，高并发时 leader 比例应该较低 (< 20%)，
     * 说明 group commit 效果好。</p>
     */
    public double getLeaderRatio() {
        long total = leaderCount.sum() + followerCount.sum();
        if (total == 0) {
            return 0;
        }
        return (double) leaderCount.sum() / total;
    }

    /**
     * 获取平均批量大小
     *
     * <p>每次 fsync 覆盖的平均事务数。值越大，group commit 效果越好。</p>
     */
    public double getAvgBatchSize() {
        long fsyncs = fsyncCount.sum();
        if (fsyncs == 0) {
            return 0;
        }
        return (double) totalBatchSize.sum() / fsyncs;
    }

    /**
     * 获取最大批量大小
     */
    public long getMaxBatchSize() {
        return maxBatchSize.get();
    }

    /**
     * 获取平均批量等待时间 (微秒)
     */
    public double getAvgBatchWaitUs() {
        long fsyncs = fsyncCount.sum();
        if (fsyncs == 0) {
            return 0;
        }
        return totalBatchWaitNanos.sum() / 1000.0 / fsyncs;
    }

    /**
     * 获取平均 fsync 时间 (微秒)
     */
    public double getAvgFsyncUs() {
        long fsyncs = fsyncCount.sum();
        if (fsyncs == 0) {
            return 0;
        }
        return totalFsyncNanos.sum() / 1000.0 / fsyncs;
    }

    /**
     * 获取平均唤醒时间 (微秒)
     */
    public double getAvgWakeupUs() {
        long fsyncs = fsyncCount.sum();
        if (fsyncs == 0) {
            return 0;
        }
        return totalWakeupNanos.sum() / 1000.0 / fsyncs;
    }

    /**
     * 获取平均 follower 等待时间 (微秒)
     */
    public double getAvgFollowerWaitUs() {
        long followers = followerCount.sum();
        if (followers == 0) {
            return 0;
        }
        return totalFollowerWaitNanos.sum() / 1000.0 / followers;
    }

    /**
     * 获取总 group commit 时间 (微秒)
     *
     * <p>= 批量等待 + fsync + 唤醒</p>
     */
    public double getAvgGroupCommitUs() {
        long fsyncs = fsyncCount.sum();
        if (fsyncs == 0) {
            return 0;
        }
        long totalNanos = totalBatchWaitNanos.sum() + totalFsyncNanos.sum() + totalWakeupNanos.sum();
        return totalNanos / 1000.0 / fsyncs;
    }

    // ==================== 重置 ====================

    /**
     * 重置所有统计信息
     */
    public void reset() {
        leaderCount.reset();
        followerCount.reset();
        totalBatchSize.reset();
        maxBatchSize.set(0);
        fsyncCount.reset();
        totalBatchWaitNanos.reset();
        totalFsyncNanos.reset();
        totalWakeupNanos.reset();
        totalFollowerWaitNanos.reset();
    }

    // ==================== 辅助方法 ====================

    /**
     * 原子更新最大值
     */
    private void updateMax(AtomicLong max, long value) {
        long current;
        do {
            current = max.get();
            if (value <= current) {
                return;
            }
        } while (!max.compareAndSet(current, value));
    }

    // ==================== 输出 ====================

    /**
     * 获取简要统计信息
     */
    public String getSummary() {
        return String.format(
                "leader=%d, follower=%d, ratio=%.1f%%, avgBatch=%.1f, maxBatch=%d, fsync=%d",
                getLeaderCount(), getFollowerCount(),
                getLeaderRatio() * 100, getAvgBatchSize(),
                getMaxBatchSize(), getFsyncCount());
    }

    /**
     * 获取延迟统计信息
     */
    public String getLatencySummary() {
        return String.format(
                "avgBatchWait=%.1fμs, avgFsync=%.1fμs, avgWakeup=%.1fμs, avgFollowerWait=%.1fμs",
                getAvgBatchWaitUs(), getAvgFsyncUs(),
                getAvgWakeupUs(), getAvgFollowerWaitUs());
    }

    @Override
    public String toString() {
        return String.format("GroupCommitMetrics{%s, %s}", getSummary(), getLatencySummary());
    }
}
