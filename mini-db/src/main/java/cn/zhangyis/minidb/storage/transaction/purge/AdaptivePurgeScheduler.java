package cn.zhangyis.minidb.storage.transaction.purge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 自适应 Purge 调度器
 *
 * <p>根据 UndoSpaceMonitor 提供的多个指标，动态调整 Purge 的频率和强度。</p>
 *
 * <h2>决策规则</h2>
 * <pre>
 * if (oldest_read_view_age > 60s) {
 *     // 长事务拖死 purge，需要激进清理
 *     purge_batch_size = MAX;
 *     purge_sleep_ms = MIN;
 * }
 * if (undo_space_ratio > 80%) {
 *     // 空间危急
 *     purge_batch_size = AGGRESSIVE;
 *     (可选) block_new_transactions();
 * }
 * if (history_list_length > threshold) {
 *     // 待清理版本堆积
 *     purge_batch_size = INCREASE;
 * }
 * </pre>
 *
 * <h2>设计目标</h2>
 * <ul>
 *   <li>防止长事务拖死 Purge</li>
 *   <li>及时回收 Undo 空间</li>
 *   <li>避免过度清理导致 CPU 浪费</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * UndoSpaceMonitor monitor = new UndoSpaceMonitor(undoLogMgr, coordinator);
 * AdaptivePurgeScheduler scheduler = new AdaptivePurgeScheduler(monitor);
 *
 * // 定期更新调度策略
 * scheduler.updateSchedule();
 *
 * // 获取当前 Purge 参数
 * long interval = scheduler.getPurgeIntervalMs();
 * int batchSize = scheduler.getPurgeBatchSize();
 * boolean throttle = scheduler.shouldThrottleNewTransactions();
 * }</pre>
 *
 * @author MiniDB
 * @version 1.0
 * @see UndoSpaceMonitor
 * @see PurgeThread
 */
public class AdaptivePurgeScheduler {

    private static final Logger logger = LoggerFactory.getLogger(AdaptivePurgeScheduler.class);

    // ==================== 常量 ====================

    /**
     * 默认 Purge 间隔（毫秒）
     */
    public static final long DEFAULT_PURGE_INTERVAL_MS = 1000;

    /**
     * 最小 Purge 间隔（毫秒）- 激进清理时
     */
    public static final long MIN_PURGE_INTERVAL_MS = 100;

    /**
     * 最大 Purge 间隔（毫秒）- 空闲时
     */
    public static final long MAX_PURGE_INTERVAL_MS = 5000;

    /**
     * 默认每轮 Purge 的最大记录数
     */
    public static final int DEFAULT_BATCH_SIZE = 10000;

    /**
     * 最小批量大小
     */
    public static final int MIN_BATCH_SIZE = 1000;

    /**
     * 最大批量大小
     */
    public static final int MAX_BATCH_SIZE = 100000;

    /**
     * 激进批量大小（空间危急时）
     */
    public static final int AGGRESSIVE_BATCH_SIZE = 50000;

    // ==================== 字段 ====================

    /**
     * Undo 空间监控器
     */
    private final UndoSpaceMonitor monitor;

    /**
     * 当前 Purge 间隔（毫秒）
     */
    private final AtomicLong currentPurgeIntervalMs;

    /**
     * 当前批量大小
     */
    private final AtomicLong currentBatchSize;

    /**
     * 是否应该限流新事务
     */
    private final AtomicReference<Boolean> shouldThrottle;

    /**
     * 上次调度更新时间
     */
    private final AtomicLong lastScheduleUpdateTime;

    /**
     * 调度策略
     */
    private final AtomicReference<SchedulePolicy> currentPolicy;

    // ==================== 内部类 ====================

    /**
     * Purge 调度策略
     */
    public enum SchedulePolicy {
        /**
         * 空闲模式：低频率清理
         */
        IDLE("IDLE", DEFAULT_PURGE_INTERVAL_MS, DEFAULT_BATCH_SIZE),

        /**
         * 正常模式：标准清理
         */
        NORMAL("NORMAL", DEFAULT_PURGE_INTERVAL_MS, DEFAULT_BATCH_SIZE),

        /**
         * 警告模式：加速清理
         */
        WARNING("WARNING", 500, 20000),

        /**
         * 激进模式：最大速度清理
         */
        AGGRESSIVE("AGGRESSIVE", MIN_PURGE_INTERVAL_MS, AGGRESSIVE_BATCH_SIZE),

        /**
         * 危急模式：全力清理 + 限流
         */
        CRITICAL("CRITICAL", MIN_PURGE_INTERVAL_MS, MAX_BATCH_SIZE);

        public final String name;
        public final long intervalMs;
        public final int batchSize;

        SchedulePolicy(String name, long intervalMs, int batchSize) {
            this.name = name;
            this.intervalMs = intervalMs;
            this.batchSize = batchSize;
        }
    }

    // ==================== 构造函数 ====================

    /**
     * 创建自适应 Purge 调度器
     *
     * @param monitor Undo 空间监控器
     */
    public AdaptivePurgeScheduler(UndoSpaceMonitor monitor) {
        this.monitor = monitor;
        this.currentPurgeIntervalMs = new AtomicLong(DEFAULT_PURGE_INTERVAL_MS);
        this.currentBatchSize = new AtomicLong(DEFAULT_BATCH_SIZE);
        this.shouldThrottle = new AtomicReference<>(false);
        this.lastScheduleUpdateTime = new AtomicLong(System.currentTimeMillis());
        this.currentPolicy = new AtomicReference<>(SchedulePolicy.NORMAL);

        logger.info("AdaptivePurgeScheduler created");
    }

    // ==================== 调度更新 ====================

    /**
     * 更新调度策略
     *
     * <p>根据当前监控指标，动态调整 Purge 的频率和强度。</p>
     */
    public void updateSchedule() {
        UndoSpaceMonitor.Metrics metrics = monitor.getMetrics();
        if (metrics == null) {
            return;
        }

        SchedulePolicy newPolicy = decidePolicy(metrics);
        SchedulePolicy oldPolicy = currentPolicy.getAndSet(newPolicy);

        // 应用新策略
        currentPurgeIntervalMs.set(newPolicy.intervalMs);
        currentBatchSize.set(newPolicy.batchSize);
        shouldThrottle.set(metrics.needsThrottle);

        lastScheduleUpdateTime.set(System.currentTimeMillis());

        // 记录策略变化
        if (oldPolicy != newPolicy) {
            logger.info("Purge policy changed: {} -> {}, metrics={}",
                    oldPolicy.name, newPolicy.name, metrics);
        } else {
            logger.debug("Purge policy maintained: {}, metrics={}", newPolicy.name, metrics);
        }
    }

    /**
     * 根据指标决定调度策略
     *
     * @param metrics 监控指标
     * @return 调度策略
     */
    private SchedulePolicy decidePolicy(UndoSpaceMonitor.Metrics metrics) {
        // 优先级 1：危急状态 - 全力清理 + 限流
        if (metrics.isCritical) {
            if (metrics.undoSpaceRatio > UndoSpaceMonitor.CRITICAL_SPACE_RATIO) {
                logger.warn("Entering CRITICAL mode: space ratio = {:.2f}%", metrics.undoSpaceRatio * 100);
                return SchedulePolicy.CRITICAL;
            }

            if (metrics.oldestReadViewAgeMs > UndoSpaceMonitor.LONG_TRX_THRESHOLD_MS) {
                logger.warn("Entering CRITICAL mode: long transaction detected, age = {}ms",
                        metrics.oldestReadViewAgeMs);
                return SchedulePolicy.CRITICAL;
            }

            if (metrics.purgeLag > UndoSpaceMonitor.PURGE_LAG_WARNING_THRESHOLD) {
                logger.warn("Entering CRITICAL mode: purge lag = {:.2f}", metrics.purgeLag);
                return SchedulePolicy.CRITICAL;
            }
        }

        // 优先级 2：警告状态 - 加速清理
        if (metrics.isWarning) {
            if (metrics.undoSpaceRatio > UndoSpaceMonitor.WARNING_SPACE_RATIO) {
                logger.info("Entering AGGRESSIVE mode: space ratio = {:.2f}%", metrics.undoSpaceRatio * 100);
                return SchedulePolicy.AGGRESSIVE;
            }

            if (metrics.historyListLength > UndoSpaceMonitor.HISTORY_LIST_WARNING_THRESHOLD) {
                logger.info("Entering WARNING mode: history list length = {}", metrics.historyListLength);
                return SchedulePolicy.WARNING;
            }
        }

        // 优先级 3：正常状态
        if (metrics.historyListLength > 0) {
            return SchedulePolicy.NORMAL;
        }

        // 优先级 4：空闲状态
        return SchedulePolicy.IDLE;
    }

    // ==================== 参数查询 ====================

    /**
     * 获取当前 Purge 间隔
     *
     * @return 间隔（毫秒）
     */
    public long getPurgeIntervalMs() {
        return currentPurgeIntervalMs.get();
    }

    /**
     * 获取当前批量大小
     *
     * @return 批量大小
     */
    public int getPurgeBatchSize() {
        return (int) currentBatchSize.get();
    }

    /**
     * 是否应该限流新事务
     *
     * @return 如果应该限流返回 true
     */
    public boolean shouldThrottleNewTransactions() {
        return shouldThrottle.get();
    }

    /**
     * 获取当前调度策略
     *
     * @return 调度策略
     */
    public SchedulePolicy getCurrentPolicy() {
        return currentPolicy.get();
    }

    /**
     * 获取上次调度更新时间
     *
     * @return 时间戳（毫秒）
     */
    public long getLastScheduleUpdateTime() {
        return lastScheduleUpdateTime.get();
    }

    // ==================== 统计信息 ====================

    /**
     * 获取调度统计信息
     *
     * @return 统计字符串
     */
    public String getStats() {
        SchedulePolicy policy = currentPolicy.get();
        return String.format(
                "AdaptivePurgeScheduler{policy=%s, interval=%dms, batchSize=%d, throttle=%s}",
                policy.name,
                currentPurgeIntervalMs.get(),
                currentBatchSize.get(),
                shouldThrottle.get()
        );
    }

    @Override
    public String toString() {
        return getStats();
    }
}
