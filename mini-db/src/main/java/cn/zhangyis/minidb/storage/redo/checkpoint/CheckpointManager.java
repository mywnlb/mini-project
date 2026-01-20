package cn.zhangyis.minidb.storage.redo.checkpoint;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.redo.RedoLogConfig;
import cn.zhangyis.minidb.storage.redo.buffer.RedoLogBuffer;
import cn.zhangyis.minidb.storage.redo.fileset.LsnMapper;
import cn.zhangyis.minidb.storage.redo.fileset.RedoLogFileSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Checkpoint Manager - 管理 Checkpoint 生命周期
 *
 * <p>Checkpoint 是数据库恢复的关键机制，用于：</p>
 * <ul>
 *   <li>确定崩溃恢复的起始 LSN</li>
 *   <li>回收不再需要的 redo log 空间</li>
 *   <li>减少恢复时间</li>
 * </ul>
 *
 * <h2>Checkpoint 安全边界 (Phase 1-2)</h2>
 * <pre>
 * checkpoint_lsn = min(FlushList.getOldestLsn(), current_flushed_lsn)
 *
 * 含义: checkpoint_lsn 之前的所有修改都已持久化到数据文件，
 *       恢复时只需重放 checkpoint_lsn 之后的 redo log。
 * </pre>
 *
 * <h2>触发时机</h2>
 * <ul>
 *   <li>定期触发 (默认每 10 秒)</li>
 *   <li>Redo log 空间不足 (使用率 > 80%)</li>
 *   <li>数据库关闭前</li>
 *   <li>手动触发</li>
 * </ul>
 *
 * <h2>Checkpoint 存储</h2>
 * <p>Checkpoint 信息交替写入 ib_logfile0 和 ib_logfile1 的文件头，
 * 使用 checkpointNo % 2 决定写入哪个文件。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class CheckpointManager {

    private static final Logger logger = LoggerFactory.getLogger(CheckpointManager.class);

    // ==================== 配置 ====================

    /** 默认 checkpoint 间隔 (毫秒) */
    private static final long DEFAULT_CHECKPOINT_INTERVAL_MS = 10_000;

    /** Redo log 使用率阈值 (触发强制 checkpoint) */
    private static final double LOG_USAGE_THRESHOLD = 0.8;

    // ==================== 依赖组件 ====================

    /** Buffer Pool */
    private final BufferPool bufferPool;

    /** Redo Log Buffer */
    private final RedoLogBuffer redoLogBuffer;

    /** Redo Log 文件集 */
    private final RedoLogFileSet fileSet;

    /** 配置 */
    private final RedoLogConfig config;

    // ==================== 状态 ====================

    /** Checkpoint 序号 (递增) */
    private final AtomicLong checkpointNo = new AtomicLong(0);

    /** 最后一次 checkpoint 的 LSN */
    private volatile long lastCheckpointLsn = 0;

    /** 后台线程运行标志 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 后台 checkpoint 线程 */
    private Thread checkpointThread;

    /** Checkpoint 间隔 (毫秒) */
    private final long checkpointIntervalMs;

    // ==================== 构造函数 ====================

    /**
     * 创建 CheckpointManager
     *
     * @param bufferPool    Buffer Pool
     * @param redoLogBuffer Redo Log Buffer
     * @param fileSet       Redo Log 文件集
     * @param config        配置
     */
    public CheckpointManager(BufferPool bufferPool, RedoLogBuffer redoLogBuffer,
                             RedoLogFileSet fileSet, RedoLogConfig config) {
        this(bufferPool, redoLogBuffer, fileSet, config, DEFAULT_CHECKPOINT_INTERVAL_MS);
    }

    /**
     * 创建 CheckpointManager (自定义间隔)
     *
     * @param bufferPool           Buffer Pool
     * @param redoLogBuffer        Redo Log Buffer
     * @param fileSet              Redo Log 文件集
     * @param config               配置
     * @param checkpointIntervalMs Checkpoint 间隔 (毫秒)
     */
    public CheckpointManager(BufferPool bufferPool, RedoLogBuffer redoLogBuffer,
                             RedoLogFileSet fileSet, RedoLogConfig config,
                             long checkpointIntervalMs) {
        this.bufferPool = bufferPool;
        this.redoLogBuffer = redoLogBuffer;
        this.fileSet = fileSet;
        this.config = config;
        this.checkpointIntervalMs = checkpointIntervalMs;

        logger.info("CheckpointManager created: interval={}ms", checkpointIntervalMs);
    }

    // ==================== 核心方法 ====================

    /**
     * 执行 Checkpoint
     *
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>计算 checkpoint LSN (最老脏页 LSN 或当前 flushed LSN)</li>
     *   <li>创建 CheckpointRecord</li>
     *   <li>写入文件头 (交替写入 ib_logfile0/1)</li>
     *   <li>更新内部状态</li>
     * </ol>
     *
     * @return checkpoint LSN
     * @throws IOException 如果写入失败
     */
    public synchronized long doCheckpoint() throws IOException {
        logger.info("Starting checkpoint...");
        long startTime = System.nanoTime();

        // ===== Step 1: 计算 checkpoint LSN =====
        long checkpointLsn = calculateCheckpointLsn();

        // 如果和上次相同，跳过
        if (checkpointLsn == lastCheckpointLsn && checkpointLsn > 0) {
            logger.debug("Checkpoint LSN unchanged ({}), skipping", checkpointLsn);
            return checkpointLsn;
        }

        // ===== Step 2: 创建 CheckpointRecord =====
        long currentNo = checkpointNo.incrementAndGet();
        long flushedLsn = LsnMapper.snToLsn(redoLogBuffer.getFlushedSn());

        CheckpointRecord record = new CheckpointRecord(
                checkpointLsn,
                currentNo,
                flushedLsn,
                (int) config.getLogFileSize()
        );

        // ===== Step 3: 写入文件头 =====
        int targetFile = (int) (currentNo % 2);
        writeCheckpointRecord(record, targetFile);

        // ===== Step 4: 更新状态 =====
        lastCheckpointLsn = checkpointLsn;

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        logger.info("Checkpoint completed: lsn={}, no={}, flushedLsn={}, file={}, took={}ms",
                checkpointLsn, currentNo, flushedLsn, targetFile, durationMs);

        return checkpointLsn;
    }

    /**
     * 计算 checkpoint LSN
     *
     * <p>Phase 1-2 简化版：</p>
     * <ul>
     *   <li>如果有脏页：checkpoint_lsn = 最老脏页的 LSN</li>
     *   <li>如果无脏页：checkpoint_lsn = 当前 flushed LSN</li>
     * </ul>
     *
     * @return checkpoint LSN
     */
    private long calculateCheckpointLsn() {
        long oldestDirtyLsn = bufferPool.getOldestDirtyPageLsn();
        long flushedLsn = LsnMapper.snToLsn(redoLogBuffer.getFlushedSn());

        if (oldestDirtyLsn == Long.MAX_VALUE) {
            // 无脏页，所有修改都已持久化
            logger.debug("No dirty pages, checkpoint at flushed LSN: {}", flushedLsn);
            return flushedLsn;
        } else {
            // 有脏页，checkpoint 不能超过最老脏页
            logger.debug("Oldest dirty page LSN: {}, flushed LSN: {}", oldestDirtyLsn, flushedLsn);
            return oldestDirtyLsn;
        }
    }

    /**
     * 写入 CheckpointRecord 到文件头
     *
     * @param record     CheckpointRecord
     * @param fileIndex  目标文件索引 (0 或 1)
     * @throws IOException 如果写入失败
     */
    private void writeCheckpointRecord(CheckpointRecord record, int fileIndex) throws IOException {
        byte[] data = record.serialize();

        // 创建 2KB 的 header buffer (只使用前 64 字节，其余为 0)
        ByteBuffer header = ByteBuffer.allocate(LsnMapper.CHECKPOINT_HEADER_SIZE);
        header.put(data);
        // 填充剩余部分为 0 (ByteBuffer 默认已是 0)
        // 重置 position 到 0，保持 limit 为 capacity (2048)
        header.position(0);

        fileSet.writeCheckpointHeader(fileIndex, header);
    }

    // ==================== 后台线程 ====================

    /**
     * 启动后台 checkpoint 线程
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            checkpointThread = new Thread(this::checkpointLoop, "CheckpointThread");
            checkpointThread.setDaemon(true);
            checkpointThread.start();
            logger.info("Checkpoint thread started");
        }
    }

    /**
     * 停止后台 checkpoint 线程
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (checkpointThread != null) {
                checkpointThread.interrupt();
                try {
                    checkpointThread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // 关闭前执行最后一次 checkpoint
            try {
                logger.info("Performing final checkpoint before shutdown...");
                doCheckpoint();
            } catch (IOException e) {
                logger.error("Final checkpoint failed", e);
            }

            logger.info("Checkpoint thread stopped");
        }
    }

    /**
     * Checkpoint 循环
     */
    private void checkpointLoop() {
        while (running.get()) {
            try {
                Thread.sleep(checkpointIntervalMs);

                if (!running.get()) {
                    break;
                }

                // 检查是否需要强制 checkpoint (redo log 空间不足)
                if (shouldForceCheckpoint()) {
                    logger.info("Forcing checkpoint due to high redo log usage");
                }

                doCheckpoint();

            } catch (InterruptedException e) {
                logger.debug("Checkpoint thread interrupted");
                break;
            } catch (Exception e) {
                logger.error("Checkpoint failed", e);
            }
        }
    }

    /**
     * 检查是否需要强制 checkpoint
     *
     * @return true 如果 redo log 使用率超过阈值
     */
    private boolean shouldForceCheckpoint() {
        long currentSn = redoLogBuffer.getCurrentSn();
        long flushedSn = redoLogBuffer.getFlushedSn();
        long used = currentSn - flushedSn;
        long total = config.getLogBufferSize();

        double usage = (double) used / total;
        return usage > LOG_USAGE_THRESHOLD;
    }

    // ==================== 恢复相关 ====================

    /**
     * 从文件读取最新的 CheckpointRecord
     *
     * <p>比较两个文件中的 checkpoint，返回 checkpointNo 更大的那个。</p>
     *
     * @return 最新的 CheckpointRecord，或 null 如果没有有效的 checkpoint
     * @throws IOException 如果读取失败
     */
    public CheckpointRecord readLatestCheckpoint() throws IOException {
        CheckpointRecord record0 = readCheckpointFromFile(0);
        CheckpointRecord record1 = readCheckpointFromFile(1);

        if (record0 == null && record1 == null) {
            logger.info("No valid checkpoint found");
            return null;
        }

        if (record0 == null) {
            logger.info("Using checkpoint from file 1: {}", record1);
            return record1;
        }

        if (record1 == null) {
            logger.info("Using checkpoint from file 0: {}", record0);
            return record0;
        }

        // 返回 checkpointNo 更大的
        if (record0.getCheckpointNo() > record1.getCheckpointNo()) {
            logger.info("Using checkpoint from file 0 (newer): {}", record0);
            return record0;
        } else {
            logger.info("Using checkpoint from file 1 (newer): {}", record1);
            return record1;
        }
    }

    /**
     * 从指定文件读取 CheckpointRecord
     *
     * @param fileIndex 文件索引 (0 或 1)
     * @return CheckpointRecord 或 null
     * @throws IOException 如果读取失败
     */
    private CheckpointRecord readCheckpointFromFile(int fileIndex) throws IOException {
        ByteBuffer header = fileSet.readCheckpointHeader(fileIndex);
        byte[] data = new byte[CheckpointRecord.CHECKPOINT_RECORD_SIZE];
        header.get(data);
        return CheckpointRecord.deserialize(data);
    }

    /**
     * 初始化 checkpoint 状态 (从文件恢复)
     *
     * @throws IOException 如果读取失败
     */
    public void initialize() throws IOException {
        CheckpointRecord latest = readLatestCheckpoint();
        if (latest != null) {
            checkpointNo.set(latest.getCheckpointNo());
            lastCheckpointLsn = latest.getCheckpointLsn();
            logger.info("Initialized from checkpoint: lsn={}, no={}",
                    lastCheckpointLsn, checkpointNo.get());
        } else {
            logger.info("No previous checkpoint, starting fresh");
        }
    }

    // ==================== 状态查询 ====================

    /**
     * 获取最后一次 checkpoint 的 LSN
     *
     * @return checkpoint LSN
     */
    public long getLastCheckpointLsn() {
        return lastCheckpointLsn;
    }

    /**
     * 获取 checkpoint 序号
     *
     * @return checkpoint 序号
     */
    public long getCheckpointNo() {
        return checkpointNo.get();
    }

    /**
     * 是否正在运行
     *
     * @return true 如果后台线程正在运行
     */
    public boolean isRunning() {
        return running.get();
    }
}
