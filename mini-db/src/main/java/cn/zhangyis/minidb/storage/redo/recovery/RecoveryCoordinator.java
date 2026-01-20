package cn.zhangyis.minidb.storage.redo.recovery;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.redo.checkpoint.CheckpointManager;
import cn.zhangyis.minidb.storage.redo.checkpoint.CheckpointRecord;
import cn.zhangyis.minidb.storage.redo.fileset.LsnMapper;
import cn.zhangyis.minidb.storage.redo.fileset.RedoLogFileSet;
import cn.zhangyis.minidb.storage.redo.record.MultiRecEndRecord;
import cn.zhangyis.minidb.storage.redo.record.RedoRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Recovery Coordinator - 崩溃恢复协调器
 *
 * <p>协调整个崩溃恢复流程，包括：</p>
 * <ol>
 *   <li>读取最新的有效 checkpoint</li>
 *   <li>从 checkpoint LSN 开始扫描 redo log</li>
 *   <li>解析并重放 redo records</li>
 *   <li>处理不完整的 redo group</li>
 *   <li>刷新所有恢复的脏页</li>
 * </ol>
 *
 * <h2>恢复流程</h2>
 * <pre>
 * 1. 读取 checkpoint (从 ib_logfile0/1 的文件头)
 * 2. 创建 RedoLogScanner (从 checkpoint.lsn 开始)
 * 3. 创建 RedoLogApplier (关联 BufferPool)
 * 4. 遍历 redo records:
 *    - 遇到 data record: 应用到页面
 *    - 遇到 MLOG_MULTI_REC_END: 更新 lastValidLsn
 *    - 遇到损坏: 截断到 lastValidLsn
 * 5. 刷新所有脏页到磁盘
 * </pre>
 *
 * <h2>使用方式</h2>
 * <pre>
 * RecoveryCoordinator coordinator = new RecoveryCoordinator(fileSet, bufferPool);
 * if (coordinator.needRecovery()) {
 *     coordinator.recover();
 * }
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class RecoveryCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(RecoveryCoordinator.class);

    // ==================== 依赖 ====================

    /** Redo Log 文件集 */
    private final RedoLogFileSet fileSet;

    /** Buffer Pool */
    private final BufferPool bufferPool;

    // ==================== 状态 ====================

    /** 最新的 checkpoint */
    private CheckpointRecord checkpoint;

    /** 恢复统计 */
    private RecoveryStats stats;

    // ==================== 构造函数 ====================

    /**
     * 创建 RecoveryCoordinator
     *
     * @param fileSet    Redo Log 文件集
     * @param bufferPool Buffer Pool
     */
    public RecoveryCoordinator(RedoLogFileSet fileSet, BufferPool bufferPool) {
        this.fileSet = fileSet;
        this.bufferPool = bufferPool;
        this.stats = new RecoveryStats();
    }

    // ==================== 核心方法 ====================

    /**
     * 检查是否需要恢复
     *
     * <p>通过检查 checkpoint 是否存在来判断是否需要恢复。
     * 如果没有有效的 checkpoint，可能是新数据库或严重损坏。</p>
     *
     * @return true 如果需要恢复
     */
    public boolean needRecovery() {
        try {
            checkpoint = readLatestValidCheckpoint();
            return checkpoint != null;
        } catch (Exception e) {
            logger.warn("Failed to check recovery status: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 执行崩溃恢复
     *
     * @throws RecoveryException 如果恢复失败
     */
    public void recover() throws RecoveryException {
        logger.info("=== Starting Crash Recovery ===");
        long startTime = System.currentTimeMillis();

        try {
            // 1. 读取 checkpoint
            if (checkpoint == null) {
                checkpoint = readLatestValidCheckpoint();
            }

            if (checkpoint == null) {
                logger.info("No valid checkpoint found, nothing to recover");
                return;
            }

            logger.info("Found checkpoint: lsn={}, no={}, timestamp={}",
                    checkpoint.getCheckpointLsn(),
                    checkpoint.getCheckpointNo(),
                    checkpoint.getTimestamp());

            // 2. 创建 scanner 和 applier
            long startLsn = checkpoint.getCheckpointLsn();
            RedoLogScanner scanner = new RedoLogScanner(fileSet, startLsn);
            RedoLogApplier applier = new RedoLogApplier(bufferPool);

            // 3. 扫描并重放 redo records
            long lastValidLsn = startLsn;
            long currentLsn = startLsn;
            int groupCount = 0;

            logger.info("Scanning redo log from LSN {}...", startLsn);

            while (scanner.hasNext()) {
                try {
                    RedoRecord record = scanner.next();
                    currentLsn = scanner.getCurrentLsn();

                    if (record instanceof MultiRecEndRecord) {
                        // Redo group 结束标记
                        lastValidLsn = currentLsn;
                        groupCount++;
                        stats.completedGroups++;
                        logger.trace("Redo group {} completed at LSN {}", groupCount, currentLsn);
                        continue;
                    }

                    // 应用 redo record
                    boolean applied = applier.apply(record, currentLsn);
                    if (applied) {
                        stats.recordsApplied++;
                    } else {
                        stats.recordsSkipped++;
                    }

                } catch (Exception e) {
                    // 遇到损坏或解析错误
                    logger.warn("Error at LSN {}: {}, truncating to last valid LSN {}",
                            currentLsn, e.getMessage(), lastValidLsn);
                    stats.incompleteGroups++;
                    break;
                }
            }

            // 4. 记录统计
            stats.scannerStats = scanner.getStats();
            stats.applierStats = applier.getStats();

            logger.info("Redo scan completed: {} groups, {} records applied, {} skipped",
                    groupCount, stats.recordsApplied, stats.recordsSkipped);

            // 5. 刷新所有脏页
            logger.info("Flushing recovered dirty pages...");
            bufferPool.flushAllPages();

            long duration = System.currentTimeMillis() - startTime;
            stats.recoveryTimeMs = duration;

            logger.info("=== Crash Recovery Completed in {} ms ===", duration);
            logger.info("Recovery stats: {}", stats);

        } catch (IOException e) {
            throw new RecoveryException("Recovery failed due to I/O error", e);
        } catch (MiniDbException e) {
            throw new RecoveryException("Recovery failed", e);
        }
    }

    // ==================== Checkpoint 读取 ====================

    /**
     * 读取最新的有效 checkpoint
     *
     * <p>从 ib_logfile0 和 ib_logfile1 读取 checkpoint，
     * 选择序号更大的那个。</p>
     *
     * @return 最新的有效 CheckpointRecord，或 null
     * @throws IOException 如果读取失败
     */
    private CheckpointRecord readLatestValidCheckpoint() throws IOException {
        CheckpointRecord cp0 = readCheckpointFromFile(0);
        CheckpointRecord cp1 = readCheckpointFromFile(1);

        if (cp0 == null && cp1 == null) {
            logger.info("No valid checkpoint found in either log file");
            return null;
        }

        if (cp0 == null) {
            logger.debug("Using checkpoint from file 1");
            return cp1;
        }

        if (cp1 == null) {
            logger.debug("Using checkpoint from file 0");
            return cp0;
        }

        // 选择序号更大的
        if (cp0.getCheckpointNo() > cp1.getCheckpointNo()) {
            logger.debug("Using checkpoint from file 0 (no={} > {})",
                    cp0.getCheckpointNo(), cp1.getCheckpointNo());
            return cp0;
        } else {
            logger.debug("Using checkpoint from file 1 (no={} >= {})",
                    cp1.getCheckpointNo(), cp0.getCheckpointNo());
            return cp1;
        }
    }

    /**
     * 从指定文件读取 checkpoint
     */
    private CheckpointRecord readCheckpointFromFile(int fileIndex) {
        try {
            ByteBuffer header = fileSet.readCheckpointHeader(fileIndex);
            if (header == null) {
                return null;
            }

            byte[] data = new byte[CheckpointRecord.CHECKPOINT_RECORD_SIZE];
            header.get(data);

            CheckpointRecord record = CheckpointRecord.deserialize(data);
            if (record != null) {
                logger.debug("Read checkpoint from file {}: {}", fileIndex, record);
            }
            return record;

        } catch (Exception e) {
            logger.warn("Failed to read checkpoint from file {}: {}", fileIndex, e.getMessage());
            return null;
        }
    }

    // ==================== 状态查询 ====================

    /**
     * 获取恢复统计
     */
    public RecoveryStats getStats() {
        return stats;
    }

    /**
     * 获取使用的 checkpoint
     */
    public CheckpointRecord getCheckpoint() {
        return checkpoint;
    }

    // ==================== 统计类 ====================

    /**
     * 恢复统计信息
     */
    public static class RecoveryStats {
        /** 完成的 redo group 数量 */
        public int completedGroups = 0;

        /** 不完整的 redo group 数量 */
        public int incompleteGroups = 0;

        /** 应用的 record 数量 */
        public long recordsApplied = 0;

        /** 跳过的 record 数量 */
        public long recordsSkipped = 0;

        /** 恢复耗时 (毫秒) */
        public long recoveryTimeMs = 0;

        /** Scanner 统计 */
        public String scannerStats = "";

        /** Applier 统计 */
        public String applierStats = "";

        @Override
        public String toString() {
            return String.format(
                    "RecoveryStats{groups=%d (incomplete=%d), applied=%d, skipped=%d, time=%dms}",
                    completedGroups, incompleteGroups, recordsApplied, recordsSkipped, recoveryTimeMs);
        }
    }
}
