package cn.zhangyis.minidb.storage.transaction.purge;

import cn.zhangyis.minidb.storage.transaction.undo.UndoLogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Undo 空间监控器
 *
 * <p>监控 5 个关键指标，用于自适应 Purge 决策：
 * <ol>
 *   <li><b>undo_space_ratio</b>：已使用 Undo 空间占比 (0-100%)</li>
 *   <li><b>history_list_length</b>：待 purge 版本数</li>
 *   <li><b>purge_lag</b>：purge 处理速度跟不上产生速度的滞后</li>
 *   <li><b>oldest_read_view_age</b>：最老一致性读年龄（秒）</li>
 *   <li><b>(可选) trx_throttle</b>：空间危急时限制新事务</li>
 * </ol>
 * </p>
 *
 * <h2>设计目标</h2>
 * <ul>
 *   <li>实时收集关键指标</li>
 *   <li>支持指标查询和统计</li>
 *   <li>为自适应 Purge 提供决策依据</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * UndoSpaceMonitor monitor = new UndoSpaceMonitor(undoLogMgr, purgeCoordinator);
 *
 * // 定期更新指标
 * monitor.updateMetrics();
 *
 * // 查询指标
 * double spaceRatio = monitor.getUndoSpaceRatio();
 * long historyLen = monitor.getHistoryListLength();
 * long readViewAge = monitor.getOldestReadViewAge();
 *
 * // 获取监控快照
 * UndoSpaceMonitor.Metrics metrics = monitor.getMetrics();
 * }</pre>
 *
 * @author MiniDB
 * @version 1.0
 * @see PurgeCoordinator
 * @see AdaptivePurgeScheduler
 */
public class UndoSpaceMonitor {

    private static final Logger logger = LoggerFactory.getLogger(UndoSpaceMonitor.class);

    // ==================== 常量 ====================

    /**
     * 默认最大 Undo 空间（字节）
     * 假设每个 Rollback Segment 最多 1GB，128 个 Segment = 128GB
     */
    private static final long DEFAULT_MAX_UNDO_SPACE = 128L * 1024 * 1024 * 1024;

    /**
     * 空间危急阈值（80%）
     */
    public static final double CRITICAL_SPACE_RATIO = 0.80;

    /**
     * 空间警告阈值（60%）
     */
    public static final double WARNING_SPACE_RATIO = 0.60;

    /**
     * 长事务阈值（60 秒）
     */
    public static final long LONG_TRX_THRESHOLD_MS = 60 * 1000;

    /**
     * History List 长度警告阈值
     */
    public static final long HISTORY_LIST_WARNING_THRESHOLD = 100000;

    /**
     * Purge Lag 警告阈值（产生速度 / 清理速度 > 2）
     */
    public static final double PURGE_LAG_WARNING_THRESHOLD = 2.0;

    // ==================== 字段 ====================

    /**
     * Undo Log Manager 引用
     */
    private final UndoLogManager undoLogManager;

    /**
     * Purge 协调器引用
     */
    private final PurgeCoordinator purgeCoordinator;

    /**
     * 最大 Undo 空间（字节）
     */
    private final long maxUndoSpace;

    /**
     * 当前指标快照
     */
    private final AtomicReference<Metrics> currentMetrics;

    /**
     * 上次更新时间
     */
    private final AtomicLong lastUpdateTime;

    /**
     * 上次 Purge 的记录数
     */
    private final AtomicLong lastPurgedRecords;

    /**
     * 上次产生的 Undo 记录数
     */
    private final AtomicLong lastGeneratedRecords;

    // ==================== 内部类 ====================

    /**
     * Undo 空间监控指标快照
     */
    public static class Metrics {
        /**
         * 已使用 Undo 空间占比 (0.0 - 1.0)
         */
        public final double undoSpaceRatio;

        /**
         * 待 purge 版本数
         */
        public final long historyListLength;

        /**
         * Purge 滞后比率（产生速度 / 清理速度）
         */
        public final double purgeLag;

        /**
         * 最老一致性读年龄（毫秒）
         */
        public final long oldestReadViewAgeMs;

        /**
         * 是否需要限流
         */
        public final boolean needsThrottle;

        /**
         * 是否处于危急状态
         */
        public final boolean isCritical;

        /**
         * 是否处于警告状态
         */
        public final boolean isWarning;

        /**
         * 更新时间戳
         */
        public final long timestamp;

        public Metrics(double undoSpaceRatio, long historyListLength, double purgeLag,
                      long oldestReadViewAgeMs, boolean needsThrottle, boolean isCritical,
                      boolean isWarning, long timestamp) {
            this.undoSpaceRatio = undoSpaceRatio;
            this.historyListLength = historyListLength;
            this.purgeLag = purgeLag;
            this.oldestReadViewAgeMs = oldestReadViewAgeMs;
            this.needsThrottle = needsThrottle;
            this.isCritical = isCritical;
            this.isWarning = isWarning;
            this.timestamp = timestamp;
        }

        @Override
        public String toString() {
            return String.format(
                    "Metrics{spaceRatio=%.2f%%, historyLen=%d, purgeLag=%.2f, readViewAge=%dms, " +
                    "throttle=%s, critical=%s, warning=%s}",
                    undoSpaceRatio * 100, historyListLength, purgeLag, oldestReadViewAgeMs,
                    needsThrottle, isCritical, isWarning
            );
        }
    }

    // ==================== 构造函数 ====================

    /**
     * 创建 Undo 空间监控器
     *
     * @param undoLogManager   Undo Log Manager
     * @param purgeCoordinator Purge 协调器
     */
    public UndoSpaceMonitor(UndoLogManager undoLogManager, PurgeCoordinator purgeCoordinator) {
        this(undoLogManager, purgeCoordinator, DEFAULT_MAX_UNDO_SPACE);
    }

    /**
     * 创建 Undo 空间监控器（指定最大空间）
     *
     * @param undoLogManager   Undo Log Manager
     * @param purgeCoordinator Purge 协调器
     * @param maxUndoSpace     最大 Undo 空间（字节）
     */
    public UndoSpaceMonitor(UndoLogManager undoLogManager, PurgeCoordinator purgeCoordinator,
                           long maxUndoSpace) {
        this.undoLogManager = undoLogManager;
        this.purgeCoordinator = purgeCoordinator;
        this.maxUndoSpace = maxUndoSpace;
        this.currentMetrics = new AtomicReference<>(createEmptyMetrics());
        this.lastUpdateTime = new AtomicLong(System.currentTimeMillis());
        this.lastPurgedRecords = new AtomicLong(0);
        this.lastGeneratedRecords = new AtomicLong(0);

        logger.info("UndoSpaceMonitor created: maxUndoSpace={}MB", maxUndoSpace / (1024 * 1024));
    }

    // ==================== 指标更新 ====================

    /**
     * 更新所有监控指标
     *
     * <p>此方法应该定期调用（例如每秒一次）。</p>
     */
    public void updateMetrics() {
        long now = System.currentTimeMillis();

        // 1. 计算 Undo 空间占比
        double undoSpaceRatio = calculateUndoSpaceRatio();

        // 2. 获取 History List 长度
        long historyListLength = computeHistoryListLength();

        // 3. 计算 Purge 滞后比率
        double purgeLag = calculatePurgeLag();

        // 4. 获取最老 ReadView 年龄
        long oldestReadViewAgeMs = computeOldestReadViewAgeMs();

        // 5. 判断是否需要限流
        boolean needsThrottle = undoSpaceRatio > CRITICAL_SPACE_RATIO;

        // 6. 判断是否处于危急状态
        boolean isCritical = undoSpaceRatio > CRITICAL_SPACE_RATIO ||
                oldestReadViewAgeMs > LONG_TRX_THRESHOLD_MS ||
                purgeLag > PURGE_LAG_WARNING_THRESHOLD;

        // 7. 判断是否处于警告状态
        boolean isWarning = undoSpaceRatio > WARNING_SPACE_RATIO ||
                historyListLength > HISTORY_LIST_WARNING_THRESHOLD;

        // 创建新的指标快照
        Metrics metrics = new Metrics(
                undoSpaceRatio,
                historyListLength,
                purgeLag,
                oldestReadViewAgeMs,
                needsThrottle,
                isCritical,
                isWarning,
                now
        );

        currentMetrics.set(metrics);
        lastUpdateTime.set(now);

        // 记录日志
        if (isCritical) {
            logger.warn("Undo space critical: {}", metrics);
        } else if (isWarning) {
            logger.info("Undo space warning: {}", metrics);
        } else {
            logger.debug("Undo space metrics: {}", metrics);
        }
    }

    // ==================== 指标计算 ====================

    /**
     * 计算 Undo 空间占比
     *
     * @return 占比 (0.0 - 1.0)
     */
    private double calculateUndoSpaceRatio() {
        long usedSpace = undoLogManager.getTotalUndoSpaceUsed();
        if (maxUndoSpace <= 0) {
            return 0.0;
        }
        double ratio = (double) usedSpace / maxUndoSpace;
        return Math.min(ratio, 1.0);
    }

    /**
     * 获取 History List 长度
     *
     * @return 待 purge 的版本数
     */
    private long computeHistoryListLength() {
        HistoryList historyList = undoLogManager.getHistoryList();
        if (historyList == null) {
            return 0;
        }
        return historyList.size();
    }

    /**
     * 计算 Purge 滞后比率
     *
     * <p>滞后比率 = 产生速度 / 清理速度</p>
     *
     * @return 滞后比率
     */
    private double calculatePurgeLag() {
        // 获取当前 Undo 记录总数（近似为 History List 长度）
        long currentHistoryLen = computeHistoryListLength();

        // 如果 History List 为空，滞后比率为 0
        if (currentHistoryLen == 0) {
            return 0.0;
        }

        // 简单启发式：如果 History List 长度 > 阈值，则滞后比率 > 1
        // 更精确的计算需要追踪产生速度和清理速度
        if (currentHistoryLen > HISTORY_LIST_WARNING_THRESHOLD) {
            return (double) currentHistoryLen / HISTORY_LIST_WARNING_THRESHOLD;
        }

        return 0.0;
    }

    /**
     * 获取最老 ReadView 的年龄
     *
     * @return 年龄（毫秒）
     */
    private long computeOldestReadViewAgeMs() {
        // 从 PurgeCoordinator 获取活跃 ReadView 列表
        var readViews = purgeCoordinator.getActiveReadViews();

        if (readViews.isEmpty()) {
            return 0;
        }

        // 找最老的 ReadView（创建时间最早）
        long oldestCreateTime = Long.MAX_VALUE;
        for (var rv : readViews) {
            long createTime = rv.getCreateTime();
            if (createTime < oldestCreateTime) {
                oldestCreateTime = createTime;
            }
        }

        if (oldestCreateTime == Long.MAX_VALUE) {
            return 0;
        }

        return System.currentTimeMillis() - oldestCreateTime;
    }

    // ==================== 指标查询 ====================

    /**
     * 获取当前指标快照
     *
     * @return 指标快照
     */
    public Metrics getMetrics() {
        return currentMetrics.get();
    }

    /**
     * 获取 Undo 空间占比
     *
     * @return 占比 (0.0 - 1.0)
     */
    public double getUndoSpaceRatio() {
        Metrics m = currentMetrics.get();
        return m != null ? m.undoSpaceRatio : 0.0;
    }

    /**
     * 获取 History List 长度
     *
     * @return 待 purge 的版本数
     */
    public long getHistoryListLength() {
        Metrics m = currentMetrics.get();
        return m != null ? m.historyListLength : 0;
    }

    /**
     * 获取 Purge 滞后比率
     *
     * @return 滞后比率
     */
    public double getPurgeLag() {
        Metrics m = currentMetrics.get();
        return m != null ? m.purgeLag : 0.0;
    }

    /**
     * 获取最老 ReadView 年龄
     *
     * @return 年龄（毫秒）
     */
    public long getOldestReadViewAgeMs() {
        Metrics m = currentMetrics.get();
        return m != null ? m.oldestReadViewAgeMs : 0;
    }

    /**
     * 是否需要限流
     *
     * @return 如果需要限流返回 true
     */
    public boolean needsThrottle() {
        Metrics m = currentMetrics.get();
        return m != null && m.needsThrottle;
    }

    /**
     * 是否处于危急状态
     *
     * @return 如果处于危急状态返回 true
     */
    public boolean isCritical() {
        Metrics m = currentMetrics.get();
        return m != null && m.isCritical;
    }

    /**
     * 是否处于警告状态
     *
     * @return 如果处于警告状态返回 true
     */
    public boolean isWarning() {
        Metrics m = currentMetrics.get();
        return m != null && m.isWarning;
    }

    /**
     * 获取上次更新时间
     *
     * @return 时间戳（毫秒）
     */
    public long getLastUpdateTime() {
        return lastUpdateTime.get();
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建空的指标快照
     */
    private static Metrics createEmptyMetrics() {
        return new Metrics(0.0, 0, 0.0, 0, false, false, false, System.currentTimeMillis());
    }

    @Override
    public String toString() {
        Metrics m = currentMetrics.get();
        return m != null ? m.toString() : "UndoSpaceMonitor{empty}";
    }
}
