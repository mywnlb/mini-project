package cn.zhangyis.minidb.storage.transaction.purge;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.btree.IndexManager;
import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.undo.UndoLogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Purge 和压缩集成管理器
 *
 * <p>协调 PurgeThread 和 CompressionThread 的执行，提供统一的生命周期管理。</p>
 *
 * <h2>工作流程</h2>
 * <pre>
 * 1. 启动 PurgeThread
 *    - 后台定期清理过期的 Undo 记录
 *    - 更新 Purge 边界
 *
 * 2. 启动 CompressionThread
 *    - 后台定期压缩版本链
 *    - 减少链深度
 *
 * 3. 协调执行
 *    - 压缩优先级低于 Purge
 *    - 共享 Purge 边界
 *    - 支持暂停/恢复
 * </pre>
 * </p>
 *
 * <h2>设计约束</h2>
 * <ul>
 *   <li><b>P1</b>：不能清理活跃 ReadView 需要的 Undo</li>
 *   <li><b>P4</b>：Purge 操作幂等性</li>
 *   <li><b>U1</b>：Undo 记录不可修改</li>
 *   <li><b>U2</b>：版本链完整性</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * PurgeThreadIntegration integration = new PurgeThreadIntegration(
 *     coordinator,
 *     undoLogManager,
 *     bufferPool,
 *     indexManager
 * );
 *
 * // 启动 Purge 和压缩
 * integration.start();
 *
 * // 获取统计信息
 * PurgeThreadIntegration.IntegrationStats stats = integration.getStats();
 *
 * // 关闭
 * integration.shutdown();
 * }</pre>
 *
 * @author MiniDB
 * @version 1.0
 * @see PurgeThread
 * @see CompressionThread
 * @see PurgeCoordinator
 */
public class PurgeThreadIntegration {

    private static final Logger logger = LoggerFactory.getLogger(PurgeThreadIntegration.class);

    // ==================== 字段 ====================

    /**
     * Purge 协调器
     */
    private final PurgeCoordinator coordinator;

    /**
     * Undo Log 管理器
     */
    private final UndoLogManager undoLogManager;

    /**
     * Buffer Pool
     */
    private final BufferPool bufferPool;

    /**
     * 索引管理器
     */
    private final IndexManager indexManager;

    /**
     * Purge 线程
     */
    private final PurgeThread purgeThread;

    /**
     * 压缩线程
     */
    private final CompressionThread compressionThread;

    /**
     * 是否已启动
     */
    private final AtomicBoolean started;

    // ==================== 构造函数 ====================

    /**
     * 创建 Purge 和压缩集成管理器
     *
     * @param coordinator Purge 协调器
     * @param undoLogManager Undo Log 管理器
     * @param bufferPool Buffer Pool
     * @param indexManager 索引管理器
     */
    public PurgeThreadIntegration(PurgeCoordinator coordinator,
                                 UndoLogManager undoLogManager,
                                 BufferPool bufferPool,
                                 IndexManager indexManager) {
        this(coordinator, undoLogManager, bufferPool, indexManager,
                PurgeThread.DEFAULT_PURGE_INTERVAL_MS,
                PurgeThread.DEFAULT_MAX_RECORDS_PER_ROUND,
                CompressionThread.DEFAULT_COMPRESSION_INTERVAL_MS,
                CompressionThread.DEFAULT_MAX_COMPRESSIONS_PER_ROUND);
    }

    /**
     * 创建 Purge 和压缩集成管理器
     *
     * @param coordinator Purge 协调器
     * @param undoLogManager Undo Log 管理器
     * @param bufferPool Buffer Pool
     * @param indexManager 索引管理器
     * @param purgeIntervalMs Purge 间隔（毫秒）
     * @param maxRecordsPerRound 每轮 Purge 的最大记录数
     * @param compressionIntervalMs 压缩间隔（毫秒）
     * @param maxCompressionsPerRound 每轮压缩的最大链段数
     */
    public PurgeThreadIntegration(PurgeCoordinator coordinator,
                                 UndoLogManager undoLogManager,
                                 BufferPool bufferPool,
                                 IndexManager indexManager,
                                 long purgeIntervalMs,
                                 int maxRecordsPerRound,
                                 long compressionIntervalMs,
                                 int maxCompressionsPerRound) {
        if (coordinator == null || undoLogManager == null || bufferPool == null || indexManager == null) {
            throw new NullPointerException("Arguments cannot be null");
        }

        this.coordinator = coordinator;
        this.undoLogManager = undoLogManager;
        this.bufferPool = bufferPool;
        this.indexManager = indexManager;
        this.purgeThread = new PurgeThread(coordinator, undoLogManager,
                purgeIntervalMs, maxRecordsPerRound);
        this.compressionThread = new CompressionThread(coordinator, undoLogManager, bufferPool,
                indexManager,
                compressionIntervalMs, maxCompressionsPerRound);
        this.started = new AtomicBoolean(false);
    }

    // ==================== 生命周期管理 ====================

    /**
     * 启动 Purge 和压缩线程
     */
    public void start() {
        if (started.getAndSet(true)) {
            logger.warn("Purge and compression threads already started");
            return;
        }

        logger.info("Starting Purge and Compression threads");

        try {
            // 启动 Purge 线程
            purgeThread.start();
            logger.info("Purge thread started");

            // 启动压缩线程
            compressionThread.start();
            logger.info("Compression thread started");

        } catch (Exception e) {
            logger.error("Failed to start Purge and Compression threads", e);
            started.set(false);
            throw e;
        }
    }

    /**
     * 关闭 Purge 和压缩线程
     */
    public void shutdown() {
        if (!started.getAndSet(false)) {
            logger.warn("Purge and compression threads not started");
            return;
        }

        logger.info("Shutting down Purge and Compression threads");

        try {
            // 先暂停压缩线程（优先级低）
            compressionThread.pause();
            logger.debug("Compression thread paused");

            // 关闭压缩线程
            compressionThread.shutdown();
            logger.info("Compression thread stopped");

            // 关闭 Purge 线程
            purgeThread.shutdown();
            logger.info("Purge thread stopped");

        } catch (Exception e) {
            logger.error("Error during shutdown", e);
        }
    }

    /**
     * 暂停 Purge 和压缩
     */
    public void pause() {
        logger.info("Pausing Purge and Compression");
        purgeThread.pause();
        compressionThread.pause();
    }

    /**
     * 恢复 Purge 和压缩
     */
    public void resume() {
        logger.info("Resuming Purge and Compression");
        purgeThread.resumePurge();
        compressionThread.resume();
    }

    // ==================== 状态查询 ====================

    /**
     * 是否已启动
     *
     * @return 如果已启动返回 true
     */
    public boolean isStarted() {
        return started.get();
    }

    /**
     * Purge 线程是否正在运行
     *
     * @return 如果正在运行返回 true
     */
    public boolean isPurgeRunning() {
        return purgeThread.isRunning();
    }

    /**
     * 压缩线程是否正在运行
     *
     * @return 如果正在运行返回 true
     */
    public boolean isCompressionRunning() {
        return compressionThread.isRunning();
    }

    /**
     * Purge 线程是否暂停
     *
     * @return 如果暂停返回 true
     */
    public boolean isPurgePaused() {
        return purgeThread.isPaused();
    }

    /**
     * 压缩线程是否暂停
     *
     * @return 如果暂停返回 true
     */
    public boolean isCompressionPaused() {
        return compressionThread.isPaused();
    }

    // ==================== 统计信息 ====================

    /**
     * 获取集成统计信息
     *
     * @return 统计信息
     */
    public IntegrationStats getStats() {
        return new IntegrationStats(
                purgeThread.getTotalPurgedRecords(),
                purgeThread.getPurgeRounds(),
                purgeThread.getLastPurgeLimit(),
                compressionThread.getTotalCompressions(),
                compressionThread.getCompressionRounds(),
                compressionThread.getTotalSpaceSavings(),
                compressionThread.getLastCompressionLimit(),
                compressionThread.getCompressionStats()
        );
    }

    /**
     * 强制执行一轮 Purge（用于测试）
     *
     * @return 清理的记录数
     */
    public int forcePurgeRound() {
        return purgeThread.forcePurgeRound();
    }

    /**
     * 强制执行一轮压缩（用于测试）
     *
     * @return 压缩的链段数
     */
    public int forceCompressionRound() {
        return compressionThread.forceCompressionRound();
    }

    @Override
    public String toString() {
        return String.format("PurgeThreadIntegration{started=%s, purge=%s, compression=%s}",
                started.get(), purgeThread, compressionThread);
    }

    // ==================== 内部类 ====================

    /**
     * 集成统计信息
     */
    public static class IntegrationStats {
        /**
         * Purge 的记录总数
         */
        public final long totalPurgedRecords;

        /**
         * Purge 轮数
         */
        public final long purgeRounds;

        /**
         * 上次 Purge 边界
         */
        public final TransactionId lastPurgeLimit;

        /**
         * 压缩的链段总数
         */
        public final long totalCompressions;

        /**
         * 压缩轮数
         */
        public final long compressionRounds;

        /**
         * 总节省空间（字节）
         */
        public final long totalSpaceSavings;

        /**
         * 上次压缩边界
         */
        public final TransactionId lastCompressionLimit;

        /**
         * 压缩统计信息
         */
        public final CompressionThread.CompressionStats compressionStats;

        public IntegrationStats(long totalPurgedRecords, long purgeRounds,
                               TransactionId lastPurgeLimit,
                               long totalCompressions, long compressionRounds,
                               long totalSpaceSavings, TransactionId lastCompressionLimit,
                               CompressionThread.CompressionStats compressionStats) {
            this.totalPurgedRecords = totalPurgedRecords;
            this.purgeRounds = purgeRounds;
            this.lastPurgeLimit = lastPurgeLimit;
            this.totalCompressions = totalCompressions;
            this.compressionRounds = compressionRounds;
            this.totalSpaceSavings = totalSpaceSavings;
            this.lastCompressionLimit = lastCompressionLimit;
            this.compressionStats = compressionStats;
        }

        /**
         * 获取总空间回收（Purge + 压缩）
         *
         * @return 总空间回收（字节）
         */
        public long getTotalSpaceRecovered() {
            // 这里假设 Purge 也有空间回收统计
            // 实际实现需要从 UndoLogManager 获取
            return totalSpaceSavings;
        }

        @Override
        public String toString() {
            return String.format(
                    "IntegrationStats{purged=%d(%d rounds), compressed=%d(%d rounds), savings=%dB, success=%.2f%%}",
                    totalPurgedRecords, purgeRounds,
                    totalCompressions, compressionRounds,
                    totalSpaceSavings,
                    compressionStats.getSuccessRate() * 100
            );
        }
    }
}
