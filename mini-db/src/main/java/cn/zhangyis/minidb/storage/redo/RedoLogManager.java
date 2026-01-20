package cn.zhangyis.minidb.storage.redo;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.redo.buffer.RedoLogBuffer;
import cn.zhangyis.minidb.storage.redo.checkpoint.CheckpointManager;
import cn.zhangyis.minidb.storage.redo.commit.CommitQueue;
import cn.zhangyis.minidb.storage.redo.commit.CommitWaiter;
import cn.zhangyis.minidb.storage.redo.commit.GroupCommitMetrics;
import cn.zhangyis.minidb.storage.redo.fileset.RedoLogFileSet;
import cn.zhangyis.minidb.storage.redo.record.RedoRecord;
import cn.zhangyis.minidb.storage.redo.record.RedoRecordSerializer;
import cn.zhangyis.minidb.storage.redo.writer.LogFlusher;
import cn.zhangyis.minidb.storage.redo.writer.LogWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Redo Log Manager - Redo Log 子系统的统一入口
 *
 * <p>RedoLogManager 是 Redo Log 子系统的门面(Facade)，
 * 协调管理所有 redo log 相关组件的生命周期和交互。</p>
 *
 * <h2>核心组件</h2>
 * <pre>
 * RedoLogManager
 *     ├── RedoLogBuffer      - 环形缓冲区 (SN 空间)
 *     ├── RedoLogFileSet     - 文件管理 (ib_logfile0/1)
 *     ├── LogWriter          - 后台写入线程 (buffer → file)
 *     ├── LogFlusher         - 后台刷盘线程 (fsync)
 *     └── CommitQueue        - Group Commit 提交队列 (Phase 5)
 * </pre>
 *
 * <h2>提交模型</h2>
 * <ul>
 *   <li><b>Phase 1-2 串行模型</b>: commitLock 串行化，周期性 flusher</li>
 *   <li><b>Phase 5 Group Commit</b>: Leader/Follower 机制，精准唤醒</li>
 * </ul>
 *
 * <h2>MTR 提交流程 (Phase 5 Group Commit)</h2>
 * <pre>
 * MTR.commit()
 *     │
 *     ├─ 1. 写入 buffer (并发)
 *     │
 *     ├─ 2. 加入 CommitQueue
 *     │
 *     ├─ 3. 尝试成为 Leader
 *     │      ├─ 成功 → 执行 group commit
 *     │      └─ 失败 → 等待 Leader 唤醒
 *     │
 *     └─ 4. 返回
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class RedoLogManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RedoLogManager.class);

    // ==================== 配置常量 ====================

    /** Group Commit 默认批量等待时间 (微秒) */
    private static final long DEFAULT_BATCH_WAIT_MICROS = 100;

    /** Follower 最大等待时间 (毫秒) */
    private static final long MAX_FOLLOWER_WAIT_MS = 100;

    // ==================== 组件 ====================

    /** 配置 */
    private final RedoLogConfig config;

    /** Redo Log Buffer */
    private final RedoLogBuffer buffer;

    /** Redo Log 文件集 */
    private final RedoLogFileSet fileSet;

    /** Log Writer 线程 */
    private final LogWriter writer;

    /** Log Flusher 线程 */
    private final LogFlusher flusher;

    /** Checkpoint Manager (可选，需要 BufferPool) */
    private CheckpointManager checkpointManager;

    // ==================== Phase 5: Group Commit ====================

    /** Group Commit 提交队列 */
    private final CommitQueue commitQueue;

    /** Group Commit 性能指标 */
    private final GroupCommitMetrics groupCommitMetrics;

    /** 是否启用 Group Commit (Phase 5) */
    private final boolean groupCommitEnabled;

    /** 批量等待时间 (微秒) */
    private final long batchWaitMicros;

    // ==================== 同步控制 ====================

    /**
     * MTR 提交锁
     *
     * <p>Phase 1-2 使用串行提交模型，所有 MTR 提交必须持有此锁。
     * 这确保 redo log 的顺序与提交顺序一致。</p>
     */
    private final Lock commitLock;

    /** 运行状态 */
    private volatile boolean running = false;

    // ==================== 构造函数 ====================

    /**
     * 创建 RedoLogManager
     *
     * @param config 配置
     * @throws IOException 如果文件创建失败
     */
    public RedoLogManager(RedoLogConfig config) throws IOException {
        this(config, false);
    }

    /**
     * 创建 RedoLogManager (可选启用 Group Commit)
     *
     * @param config             配置
     * @param groupCommitEnabled 是否启用 Group Commit (Phase 5)
     * @throws IOException 如果文件创建失败
     */
    public RedoLogManager(RedoLogConfig config, boolean groupCommitEnabled) throws IOException {
        this.config = config;
        this.groupCommitEnabled = groupCommitEnabled;
        this.batchWaitMicros = DEFAULT_BATCH_WAIT_MICROS;

        // 创建核心组件
        this.buffer = new RedoLogBuffer((int) config.getLogBufferSize());
        this.fileSet = new RedoLogFileSet(config);
        this.flusher = new LogFlusher(buffer, fileSet, config);
        this.writer = new LogWriter(buffer, fileSet, flusher);

        this.commitLock = new ReentrantLock();

        // Phase 5: Group Commit 组件
        this.commitQueue = new CommitQueue();
        this.groupCommitMetrics = new GroupCommitMetrics();

        logger.info("RedoLogManager created: bufferSize={}, fileSize={}, flushMode={}, groupCommit={}",
                config.getLogBufferSize(), config.getLogFileSize(),
                config.getFlushLogAtTrxCommit(), groupCommitEnabled);
    }

    // ==================== 生命周期管理 ====================

    /**
     * 启动 RedoLogManager
     *
     * <p>启动后台线程 (LogWriter, LogFlusher)。</p>
     */
    public void start() {
        if (running) {
            logger.warn("RedoLogManager already running");
            return;
        }

        writer.start();
        flusher.start();
        running = true;

        logger.info("RedoLogManager started");
    }

    /**
     * 关闭 RedoLogManager
     *
     * <p>关闭顺序：</p>
     * <ol>
     *   <li>停止 CheckpointManager (执行最后一次 checkpoint)</li>
     *   <li>停止 LogWriter (等待 buffer 清空)</li>
     *   <li>停止 LogFlusher (最后一次 fsync)</li>
     *   <li>关闭文件</li>
     * </ol>
     */
    public void shutdown() {
        if (!running) {
            return;
        }

        logger.info("RedoLogManager shutting down...");

        running = false;

        // 停止 CheckpointManager (先执行最后一次 checkpoint)
        if (checkpointManager != null) {
            checkpointManager.stop();
        }

        // 停止后台线程
        writer.stop();
        flusher.stop();

        // 关闭文件
        try {
            fileSet.close();
        } catch (IOException e) {
            logger.error("Failed to close file set", e);
        }

        logger.info("RedoLogManager shutdown complete");
    }

    @Override
    public void close() {
        shutdown();
    }

    // ==================== 核心 API ====================

    /**
     * 写入 redo log 记录
     *
     * <p>这是 MTR commit 时调用的核心方法。将 redo records 写入 buffer，
     * 并返回结束 SN，供后续 waitForFlush 使用。</p>
     *
     * <p>Phase 1-2 串行模型：调用前必须持有 commitLock。</p>
     *
     * @param records redo log 记录列表
     * @return 结束 SN (下一次写入的起始 SN)
     * @throws InterruptedException 如果等待空间被中断
     * @throws IllegalStateException 如果 manager 未启动
     */
    public long write(List<RedoRecord> records) throws InterruptedException {
        checkRunning();

        if (records == null || records.isEmpty()) {
            return buffer.getCurrentSn();
        }

        // 1. 序列化 records (包含 MLOG_MULTI_REC_END)
        byte[] payload = RedoRecordSerializer.serialize(records);

        // 2. 预留空间
        long startSn = buffer.reserveSpace(payload.length);

        // 3. 写入 buffer
        buffer.writeRecord(startSn, payload);

        // 4. 推进 writeReadySn
        long endSn = startSn + payload.length;
        buffer.advanceWriteReadySn(endSn);

        // 5. 通知 LogWriter
        writer.notifyNewData();

        logger.debug("RedoLogManager: wrote {} records ({} bytes), sn: {} -> {}",
                records.size(), payload.length, startSn, endSn);

        return endSn;
    }

    /**
     * 等待 redo log 持久化
     *
     * <p>阻塞直到指定 SN 的 redo log 已经 fsync 到磁盘。
     * 当 innodb_flush_log_at_trx_commit = 1 时，MTR commit 后必须调用此方法。</p>
     *
     * <h3>执行策略</h3>
     * <ul>
     *   <li>Phase 1-2: 周期性 flusher 或同步 flush</li>
     *   <li>Phase 5: Group Commit (Leader/Follower 机制)</li>
     * </ul>
     *
     * @param targetSn 目标 SN
     * @throws InterruptedException 如果等待被中断
     * @throws IOException          如果 fsync 失败
     */
    public void waitForFlush(long targetSn) throws InterruptedException, IOException {
        // 如果已经 flush，直接返回
        if (buffer.getFlushedSn() >= targetSn) {
            return;
        }

        if (groupCommitEnabled) {
            // Phase 5: Group Commit
            waitForFlushGroupCommit(targetSn);
        } else {
            // Phase 1-2: 原有逻辑
            waitForFlushLegacy(targetSn);
        }
    }

    /**
     * Phase 1-2: 原有等待逻辑
     */
    private void waitForFlushLegacy(long targetSn) throws InterruptedException, IOException {
        if (config.getFlushLogAtTrxCommit() == RedoLogConfig.FLUSH_AT_TRX_COMMIT_SYNC) {
            // 同步模式：直接调用 flusher.syncFlush
            flusher.syncFlush(targetSn);
        } else {
            // 异步模式：等待 buffer 的 flushedSn 推进
            buffer.waitForFlush(targetSn);
        }
    }

    /**
     * Phase 5: Group Commit 等待逻辑
     *
     * <h3>流程</h3>
     * <ol>
     *   <li>加入提交队列</li>
     *   <li>尝试成为 Leader</li>
     *   <li>Leader 执行 group commit</li>
     *   <li>Follower 等待唤醒</li>
     * </ol>
     */
    private void waitForFlushGroupCommit(long targetSn) throws IOException, InterruptedException {
        // 1. 加入提交队列
        CommitWaiter waiter = commitQueue.joinQueue(targetSn, Thread.currentThread());

        // 2. 尝试成为 leader
        if (commitQueue.tryBeLeader()) {
            // ===== Leader 路径 =====
            groupCommitMetrics.recordLeader();
            try {
                performGroupCommit(targetSn);
            } finally {
                commitQueue.releaseLeader();
            }
        } else {
            // ===== Follower 路径 =====
            groupCommitMetrics.recordFollower();
            waitAsFollower(targetSn, waiter);
        }
    }

    /**
     * Leader 执行 group commit
     *
     * @param leaderCommitSn Leader 的 commit SN
     */
    private void performGroupCommit(long leaderCommitSn) throws IOException, InterruptedException {
        long startTime = System.nanoTime();

        // ===== Step 1: 等待短暂时间凑批次 =====
        LockSupport.parkNanos(batchWaitMicros * 1000);
        long batchWaitNanos = System.nanoTime() - startTime;

        // ===== Step 2: 确定本批次的 flush 上界 =====
        long maxQueueSn = commitQueue.getMaxCommitSn();
        long writeSn = buffer.getWriteSn();

        // flush_up_to_sn = min(队列最大 sn, write_sn)
        // 确保只 flush 已经写入文件的数据
        long flushUpToSn = Math.min(maxQueueSn, writeSn);

        // 如果 writeSn < 需要的 sn，先等待 writer
        if (writeSn < maxQueueSn) {
            // 等待 writer 追上
            int waitCount = 0;
            while (buffer.getWriteSn() < maxQueueSn && waitCount < 100) {
                writer.notifyNewData();
                LockSupport.parkNanos(10_000); // 10μs
                waitCount++;
            }
            writeSn = buffer.getWriteSn();
            flushUpToSn = Math.min(maxQueueSn, writeSn);
        }

        logger.debug("Group commit: leader={}, maxQueue={}, writeSn={}, flushUpTo={}",
                leaderCommitSn, maxQueueSn, writeSn, flushUpToSn);

        // ===== Step 3: 执行 fsync (Leader 直接执行) =====
        long fsyncStart = System.nanoTime();
        flusher.leaderFlush(flushUpToSn);
        long fsyncNanos = System.nanoTime() - fsyncStart;

        // ===== Step 4: 精准唤醒本批次 =====
        long wakeupStart = System.nanoTime();
        int wakeupCount = commitQueue.wakeupBatch(flushUpToSn);
        long wakeupNanos = System.nanoTime() - wakeupStart;

        // ===== Step 5: 记录指标 =====
        groupCommitMetrics.recordGroupCommit(wakeupCount, batchWaitNanos, fsyncNanos, wakeupNanos);

        logger.debug("Group commit completed: batch={}, batchWait={}μs, fsync={}μs, wakeup={}μs",
                wakeupCount, batchWaitNanos / 1000, fsyncNanos / 1000, wakeupNanos / 1000);
    }

    /**
     * Follower 等待 Leader 完成
     *
     * @param targetSn 目标 SN
     * @param waiter   等待者
     */
    private void waitAsFollower(long targetSn, CommitWaiter waiter) {
        long startTime = System.nanoTime();

        // 循环等待，直到完成或超时
        long deadline = System.currentTimeMillis() + MAX_FOLLOWER_WAIT_MS;

        while (!waiter.isCompleted() && buffer.getFlushedSn() < targetSn) {
            // 检查超时
            if (System.currentTimeMillis() >= deadline) {
                // 超时，从队列移除
                commitQueue.removeFromQueue(targetSn);
                logger.warn("Follower wait timeout: targetSn={}", targetSn);
                break;
            }

            // park 等待唤醒
            LockSupport.parkNanos(1_000_000); // 1ms
        }

        long waitNanos = System.nanoTime() - startTime;
        groupCommitMetrics.recordFollowerWait(waitNanos);

        logger.trace("Follower wait completed: targetSn={}, wait={}μs", targetSn, waitNanos / 1000);
    }

    /**
     * 获取 MTR 提交锁
     *
     * <p>Phase 1-2 串行模型：MTR 必须获取此锁后才能写入 redo log。</p>
     *
     * @return 提交锁
     */
    public Lock getCommitLock() {
        return commitLock;
    }

    // ==================== 状态查询 ====================

    /**
     * 是否正在运行
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 获取当前 SN (下一次写入的位置)
     */
    public long getCurrentSn() {
        return buffer.getCurrentSn();
    }

    /**
     * 获取已写入 OS cache 的 SN
     */
    public long getWriteSn() {
        return buffer.getWriteSn();
    }

    /**
     * 获取已 fsync 到磁盘的 SN
     */
    public long getFlushedSn() {
        return buffer.getFlushedSn();
    }

    /**
     * 获取 buffer 使用量 (字节)
     */
    public long getBufferUsed() {
        return buffer.getCurrentSn() - buffer.getFlushedSn();
    }

    /**
     * 获取 LogWriter 统计信息
     */
    public String getWriterStats() {
        return String.format("bytesWritten=%d, writeCount=%d",
                writer.getTotalBytesWritten(), writer.getTotalWriteCount());
    }

    /**
     * 获取 LogFlusher 统计信息
     */
    public String getFlusherStats() {
        return String.format("fsyncCount=%d, avgFsyncTimeUs=%d",
                flusher.getTotalFsyncCount(), flusher.getAverageFsyncTimeUs());
    }

    // ==================== Phase 5: Group Commit 状态查询 ====================

    /**
     * 是否启用 Group Commit
     */
    public boolean isGroupCommitEnabled() {
        return groupCommitEnabled;
    }

    /**
     * 获取 Group Commit 提交队列
     *
     * @return CommitQueue
     */
    public CommitQueue getCommitQueue() {
        return commitQueue;
    }

    /**
     * 获取 Group Commit 性能指标
     *
     * @return GroupCommitMetrics
     */
    public GroupCommitMetrics getGroupCommitMetrics() {
        return groupCommitMetrics;
    }

    /**
     * 获取 Group Commit 统计信息
     */
    public String getGroupCommitStats() {
        if (!groupCommitEnabled) {
            return "Group Commit disabled";
        }
        return String.format("enabled=true, queue=%d, %s, %s",
                commitQueue.size(), commitQueue.getStats(), groupCommitMetrics.getSummary());
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查是否正在运行
     */
    private void checkRunning() {
        if (!running) {
            throw new IllegalStateException("RedoLogManager is not running");
        }
    }

    // ==================== Checkpoint 管理 ====================

    /**
     * 初始化 CheckpointManager
     *
     * <p>必须在 start() 之后调用。需要 BufferPool 来计算 checkpoint LSN。</p>
     *
     * @param bufferPool Buffer Pool 实例
     * @throws IOException 如果初始化失败
     */
    public void initCheckpoint(BufferPool bufferPool) throws IOException {
        if (checkpointManager != null) {
            logger.warn("CheckpointManager already initialized");
            return;
        }

        checkpointManager = new CheckpointManager(bufferPool, buffer, fileSet, config);
        checkpointManager.initialize();

        logger.info("CheckpointManager initialized");
    }

    /**
     * 启动 Checkpoint 后台线程
     *
     * <p>必须先调用 initCheckpoint()。</p>
     */
    public void startCheckpoint() {
        if (checkpointManager == null) {
            throw new IllegalStateException("CheckpointManager not initialized, call initCheckpoint() first");
        }

        checkpointManager.start();
    }

    /**
     * 手动触发 Checkpoint
     *
     * @return checkpoint LSN
     * @throws IOException 如果执行失败
     */
    public long doCheckpoint() throws IOException {
        if (checkpointManager == null) {
            throw new IllegalStateException("CheckpointManager not initialized");
        }

        return checkpointManager.doCheckpoint();
    }

    /**
     * 获取最后一次 checkpoint 的 LSN
     *
     * @return checkpoint LSN，如果没有 checkpoint 则返回 0
     */
    public long getLastCheckpointLsn() {
        return checkpointManager != null ? checkpointManager.getLastCheckpointLsn() : 0;
    }

    /**
     * 获取 CheckpointManager
     *
     * @return CheckpointManager 或 null
     */
    public CheckpointManager getCheckpointManager() {
        return checkpointManager;
    }

    /**
     * 获取 RedoLogBuffer (供 CheckpointManager 使用)
     *
     * @return RedoLogBuffer
     */
    public RedoLogBuffer getBuffer() {
        return buffer;
    }

    /**
     * 获取 RedoLogFileSet (供 CheckpointManager 使用)
     *
     * @return RedoLogFileSet
     */
    public RedoLogFileSet getFileSet() {
        return fileSet;
    }

    /**
     * 获取配置
     *
     * @return RedoLogConfig
     */
    public RedoLogConfig getConfig() {
        return config;
    }
}
