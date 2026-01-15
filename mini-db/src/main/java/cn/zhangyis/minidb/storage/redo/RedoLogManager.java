package cn.zhangyis.minidb.storage.redo;

import cn.zhangyis.minidb.storage.redo.buffer.RedoLogBuffer;
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
 *     └── LogFlusher         - 后台刷盘线程 (fsync)
 * </pre>
 *
 * <h2>MTR 提交流程 (Phase 1-2 串行模型)</h2>
 * <pre>
 * MTR.commit()
 *     │
 *     ├─ 1. 获取 commitLock (串行化)
 *     │
 *     ├─ 2. 序列化 redo records → payload
 *     │
 *     ├─ 3. redoLogManager.write(payload)
 *     │      ├─ reserveSpace() 预留空间
 *     │      ├─ writeRecord() 写入 buffer
 *     │      └─ advanceWriteReadySn() 推进水位
 *     │
 *     ├─ 4. 通知 LogWriter
 *     │
 *     ├─ 5. (可选) 等待 fsync
 *     │
 *     └─ 6. 释放 commitLock
 * </pre>
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 初始化
 * RedoLogConfig config = new RedoLogConfig.Builder()
 *     .dataDir("/data/minidb")
 *     .logFileSize(50 * 1024 * 1024)
 *     .build();
 * RedoLogManager manager = new RedoLogManager(config);
 * manager.start();
 *
 * // MTR commit 时调用
 * List&lt;RedoRecord&gt; records = ...;
 * long endSn = manager.write(records);
 *
 * // 等待持久化 (innodb_flush_log_at_trx_commit = 1)
 * manager.waitForFlush(endSn);
 *
 * // 关闭
 * manager.shutdown();
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class RedoLogManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RedoLogManager.class);

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
        this.config = config;

        // 创建组件
        this.buffer = new RedoLogBuffer((int) config.getLogBufferSize());
        this.fileSet = new RedoLogFileSet(config);
        this.flusher = new LogFlusher(buffer, fileSet, config);
        this.writer = new LogWriter(buffer, fileSet, flusher);

        this.commitLock = new ReentrantLock();

        logger.info("RedoLogManager created: bufferSize={}, fileSize={}, flushMode={}",
                config.getLogBufferSize(), config.getLogFileSize(), config.getFlushLogAtTrxCommit());
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
     * @param targetSn 目标 SN
     * @throws InterruptedException 如果等待被中断
     * @throws IOException 如果 fsync 失败
     */
    public void waitForFlush(long targetSn) throws InterruptedException, IOException {
        if (config.getFlushLogAtTrxCommit() == RedoLogConfig.FLUSH_AT_TRX_COMMIT_SYNC) {
            // 同步模式：直接调用 flusher.syncFlush
            flusher.syncFlush(targetSn);
        } else {
            // 异步模式：等待 buffer 的 flushedSn 推进
            buffer.waitForFlush(targetSn);
        }
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

    // ==================== 辅助方法 ====================

    /**
     * 检查是否正在运行
     */
    private void checkRunning() {
        if (!running) {
            throw new IllegalStateException("RedoLogManager is not running");
        }
    }
}
