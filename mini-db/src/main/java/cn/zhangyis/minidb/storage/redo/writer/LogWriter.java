package cn.zhangyis.minidb.storage.redo.writer;

import cn.zhangyis.minidb.storage.redo.RedoLogConfig;
import cn.zhangyis.minidb.storage.redo.buffer.LogBlockFormatter;
import cn.zhangyis.minidb.storage.redo.buffer.RedoLogBuffer;
import cn.zhangyis.minidb.storage.redo.fileset.LsnMapper;
import cn.zhangyis.minidb.storage.redo.fileset.RedoLogFileSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Log Writer 后台线程
 *
 * <p>负责将 RedoLogBuffer 中的数据写入到 redo log 文件 (OS cache)。
 * 这是写路径的关键组件，连接内存 buffer 和磁盘文件。</p>
 *
 * <h2>核心职责</h2>
 * <ul>
 *   <li><b>读取 buffer</b>: 从 RedoLogBuffer 获取待写入数据</li>
 *   <li><b>格式化</b>: 调用 LogBlockFormatter 将 payload 转换为 log blocks</li>
 *   <li><b>写入文件</b>: 通过 RedoLogFileSet 写入磁盘 (到 OS cache)</li>
 *   <li><b>推进水位</b>: 更新 writeSn，通知等待线程</li>
 * </ul>
 *
 * <h2>工作流程</h2>
 * <pre>
 * while (running) {
 *     1. 检查是否有待写入数据 (writeReadySn > writeSn)
 *     2. 如果没有数据，等待通知或超时
 *     3. 读取 buffer 中的 payload
 *     4. 调用 LogBlockFormatter.format() 转换为 log blocks
 *     5. 写入 RedoLogFileSet
 *     6. 推进 writeSn
 *     7. 通知 LogFlusher
 * }
 * </pre>
 *
 * <h2>唤醒机制</h2>
 * <ul>
 *   <li>MTR commit 后调用 notifyNewData() 唤醒 writer</li>
 *   <li>超时机制防止无限等待 (默认 1ms)</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class LogWriter implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(LogWriter.class);

    // ==================== 依赖组件 ====================

    /** Redo Log Buffer */
    private final RedoLogBuffer buffer;

    /** Redo Log 文件集 */
    private final RedoLogFileSet fileSet;

    /** Log Flusher (写入后通知) */
    private final LogFlusher flusher;

    // ==================== 配置 ====================

    /** 检查间隔 (毫秒) */
    private final long intervalMs;

    // ==================== 同步控制 ====================

    /** 控制锁 */
    private final ReentrantLock lock;

    /** 有新数据可写的条件 */
    private final Condition newDataAvailable;

    /** 运行状态 */
    private volatile boolean running = true;

    /** 工作线程 */
    private Thread workerThread;

    // ==================== 统计信息 ====================

    /** 总写入字节数 */
    private volatile long totalBytesWritten = 0;

    /** 总写入次数 */
    private volatile long totalWriteCount = 0;

    // ==================== 构造函数 ====================

    /**
     * 创建 LogWriter
     *
     * @param buffer Redo Log Buffer
     * @param fileSet Redo Log 文件集
     * @param flusher Log Flusher
     */
    public LogWriter(RedoLogBuffer buffer, RedoLogFileSet fileSet, LogFlusher flusher) {
        this.buffer = buffer;
        this.fileSet = fileSet;
        this.flusher = flusher;
        this.intervalMs = RedoLogConfig.LOG_WRITER_INTERVAL_MS;
        this.lock = new ReentrantLock();
        this.newDataAvailable = lock.newCondition();

        logger.info("LogWriter created: intervalMs={}", intervalMs);
    }

    // ==================== 生命周期管理 ====================

    /**
     * 启动 LogWriter 线程
     */
    public void start() {
        if (workerThread != null && workerThread.isAlive()) {
            logger.warn("LogWriter already running");
            return;
        }

        running = true;
        workerThread = new Thread(this, "RedoLogWriter");
        workerThread.setDaemon(true);
        workerThread.start();

        logger.info("LogWriter started");
    }

    /**
     * 停止 LogWriter 线程
     */
    public void stop() {
        running = false;
        notifyNewData();  // 唤醒等待的线程

        if (workerThread != null) {
            try {
                workerThread.join(5000);  // 等待最多 5 秒
                if (workerThread.isAlive()) {
                    logger.warn("LogWriter did not stop gracefully, interrupting...");
                    workerThread.interrupt();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        logger.info("LogWriter stopped: totalBytesWritten={}, totalWriteCount={}",
                totalBytesWritten, totalWriteCount);
    }

    // ==================== 主循环 ====================

    @Override
    public void run() {
        logger.info("LogWriter thread started");

        while (running) {
            try {
                // 1. 获取待写入数据的 SN 范围
                long writeSn = buffer.getWriteSn();
                long writeReadySn = buffer.getWriteReadySn();
                long toWrite = writeReadySn - writeSn;

                if (toWrite <= 0) {
                    // 没有数据，等待通知
                    waitForData();
                    continue;
                }

                // 2. 执行写入
                writeBatch(writeSn, writeReadySn);

            } catch (InterruptedException e) {
                if (running) {
                    logger.warn("LogWriter interrupted unexpectedly");
                }
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("LogWriter error", e);
                // 短暂休眠后继续
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // 关闭前处理剩余数据
        try {
            flushRemaining();
        } catch (Exception e) {
            logger.error("Failed to flush remaining data on shutdown", e);
        }

        logger.info("LogWriter thread stopped");
    }

    /**
     * 等待新数据
     */
    private void waitForData() throws InterruptedException {
        lock.lock();
        try {
            // 等待通知或超时
            newDataAvailable.awaitNanos(intervalMs * 1_000_000);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 写入一批数据
     *
     * @param startSn 起始 SN
     * @param endSn 结束 SN
     */
    private void writeBatch(long startSn, long endSn) throws IOException {
        long writeStart = System.nanoTime();

        // 1. 从 buffer 读取 payload
        ByteBuffer payload = buffer.getWriteReadyData();
        if (payload.remaining() == 0) {
            return;
        }

        int payloadSize = payload.remaining();

        // 2. 格式化为 log blocks
        ByteBuffer blocks = LogBlockFormatter.format(startSn, payload);

        // 3. 计算起始 block 的 LSN (SN → block-aligned LSN)
        long startLsn = LsnMapper.snToBlockLsn(startSn);

        // 4. 写入文件
        fileSet.writeBlocks(startLsn, blocks);

        // 5. 推进 writeSn
        buffer.advanceWriteSn(endSn);

        // 6. 通知 flusher
        if (flusher != null) {
            flusher.notifyNewWrite();
        }

        // 7. 更新统计
        totalBytesWritten += payloadSize;
        totalWriteCount++;

        long elapsed = (System.nanoTime() - writeStart) / 1000;
        logger.debug("LogWriter: wrote {} bytes ({} blocks) in {}μs, sn: {} -> {}, lsn: {}",
                payloadSize, blocks.limit() / 512, elapsed, startSn, endSn, startLsn);
    }

    /**
     * 关闭前刷新剩余数据
     */
    private void flushRemaining() throws IOException {
        long writeSn = buffer.getWriteSn();
        long writeReadySn = buffer.getWriteReadySn();

        if (writeReadySn > writeSn) {
            logger.info("Flushing remaining {} bytes before shutdown", writeReadySn - writeSn);
            writeBatch(writeSn, writeReadySn);
        }
    }

    // ==================== 通知方法 ====================

    /**
     * 通知有新数据可写
     *
     * <p>由 MTR commit 后调用，唤醒等待的 writer 线程。</p>
     */
    public void notifyNewData() {
        lock.lock();
        try {
            newDataAvailable.signal();
        } finally {
            lock.unlock();
        }
    }

    // ==================== 状态查询 ====================

    /**
     * 是否正在运行
     */
    public boolean isRunning() {
        return running && workerThread != null && workerThread.isAlive();
    }

    /**
     * 获取总写入字节数
     */
    public long getTotalBytesWritten() {
        return totalBytesWritten;
    }

    /**
     * 获取总写入次数
     */
    public long getTotalWriteCount() {
        return totalWriteCount;
    }
}
