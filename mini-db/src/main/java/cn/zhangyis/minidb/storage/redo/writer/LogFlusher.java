package cn.zhangyis.minidb.storage.redo.writer;

import cn.zhangyis.minidb.storage.redo.RedoLogConfig;
import cn.zhangyis.minidb.storage.redo.buffer.RedoLogBuffer;
import cn.zhangyis.minidb.storage.redo.fileset.RedoLogFileSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Log Flusher 后台线程
 *
 * <p>负责将 redo log 从 OS cache fsync 到磁盘。
 * 这是持久化保证的关键组件，确保 WAL 原则。</p>
 *
 * <h2>核心职责</h2>
 * <ul>
 *   <li><b>fsync 调用</b>: 将已写入的 log blocks 刷盘</li>
 *   <li><b>推进水位</b>: 更新 flushedSn，通知等待线程</li>
 *   <li><b>刷盘策略</b>: 根据配置决定刷盘时机</li>
 * </ul>
 *
 * <h2>工作流程</h2>
 * <pre>
 * while (running) {
 *     1. 检查是否有待 fsync 数据 (writeSn > flushedSn)
 *     2. 如果没有数据，等待通知或超时
 *     3. 调用 fileSet.fsync()
 *     4. 推进 flushedSn
 *     5. 通知等待 flush 的线程
 * }
 * </pre>
 *
 * <h2>刷盘策略 (innodb_flush_log_at_trx_commit)</h2>
 * <ul>
 *   <li><b>0</b>: 每秒刷一次 (性能优先，可能丢 1 秒数据)</li>
 *   <li><b>1</b>: 每次 commit 都 fsync (最安全，默认)</li>
 *   <li><b>2</b>: 每次 commit 写到 OS cache，每秒 fsync</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class LogFlusher implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(LogFlusher.class);

    // ==================== 依赖组件 ====================

    /** Redo Log Buffer */
    private final RedoLogBuffer buffer;

    /** Redo Log 文件集 */
    private final RedoLogFileSet fileSet;

    // ==================== 配置 ====================

    /** 刷盘策略 */
    private final int flushLogAtTrxCommit;

    /** 检查间隔 (毫秒) */
    private final long intervalMs;

    // ==================== 同步控制 ====================

    /** 控制锁 */
    private final ReentrantLock lock;

    /** 有新数据需要 flush 的条件 */
    private final Condition newWriteAvailable;

    /** 运行状态 */
    private volatile boolean running = true;

    /** 工作线程 */
    private Thread workerThread;

    // ==================== 统计信息 ====================

    /** 总 fsync 次数 */
    private volatile long totalFsyncCount = 0;

    /** 总 fsync 耗时 (纳秒) */
    private volatile long totalFsyncTimeNanos = 0;

    // ==================== 构造函数 ====================

    /**
     * 创建 LogFlusher
     *
     * @param buffer Redo Log Buffer
     * @param fileSet Redo Log 文件集
     * @param config 配置
     */
    public LogFlusher(RedoLogBuffer buffer, RedoLogFileSet fileSet, RedoLogConfig config) {
        this.buffer = buffer;
        this.fileSet = fileSet;
        this.flushLogAtTrxCommit = config.getFlushLogAtTrxCommit();
        this.intervalMs = RedoLogConfig.LOG_FLUSHER_INTERVAL_MS;
        this.lock = new ReentrantLock();
        this.newWriteAvailable = lock.newCondition();

        logger.info("LogFlusher created: flushLogAtTrxCommit={}, intervalMs={}",
                flushLogAtTrxCommit, intervalMs);
    }

    // ==================== 生命周期管理 ====================

    /**
     * 启动 LogFlusher 线程
     */
    public void start() {
        if (workerThread != null && workerThread.isAlive()) {
            logger.warn("LogFlusher already running");
            return;
        }

        running = true;
        workerThread = new Thread(this, "RedoLogFlusher");
        workerThread.setDaemon(true);
        workerThread.start();

        logger.info("LogFlusher started");
    }

    /**
     * 停止 LogFlusher 线程
     */
    public void stop() {
        running = false;
        notifyNewWrite();  // 唤醒等待的线程

        if (workerThread != null) {
            try {
                workerThread.join(5000);  // 等待最多 5 秒
                if (workerThread.isAlive()) {
                    logger.warn("LogFlusher did not stop gracefully, interrupting...");
                    workerThread.interrupt();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        logger.info("LogFlusher stopped: totalFsyncCount={}, avgFsyncTimeUs={}",
                totalFsyncCount,
                totalFsyncCount > 0 ? totalFsyncTimeNanos / totalFsyncCount / 1000 : 0);
    }

    // ==================== 主循环 ====================

    @Override
    public void run() {
        logger.info("LogFlusher thread started");

        while (running) {
            try {
                // 1. 获取待 fsync 的 SN 范围
                long flushedSn = buffer.getFlushedSn();
                long writeSn = buffer.getWriteSn();
                long toFlush = writeSn - flushedSn;

                if (toFlush <= 0) {
                    // 没有数据，等待通知
                    waitForWrite();
                    continue;
                }

                // 2. 执行 fsync
                doFsync(flushedSn, writeSn);

            } catch (InterruptedException e) {
                if (running) {
                    logger.warn("LogFlusher interrupted unexpectedly");
                }
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("LogFlusher error", e);
                // 短暂休眠后继续
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // 关闭前执行最后一次 fsync
        try {
            finalFsync();
        } catch (Exception e) {
            logger.error("Failed to fsync on shutdown", e);
        }

        logger.info("LogFlusher thread stopped");
    }

    /**
     * 等待新的写入
     */
    private void waitForWrite() throws InterruptedException {
        lock.lock();
        try {
            // 等待通知或超时
            newWriteAvailable.awaitNanos(intervalMs * 1_000_000);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 执行 fsync
     *
     * @param startSn 起始 SN (flushedSn)
     * @param endSn 结束 SN (writeSn)
     */
    private void doFsync(long startSn, long endSn) throws IOException {
        long fsyncStart = System.nanoTime();

        // 1. 调用 fsync
        fileSet.fsync();

        // 2. 推进 flushedSn
        buffer.advanceFlushedSn(endSn);

        // 3. 更新统计
        long elapsed = System.nanoTime() - fsyncStart;
        totalFsyncCount++;
        totalFsyncTimeNanos += elapsed;

        logger.debug("LogFlusher: fsync completed in {}μs, sn: {} -> {}",
                elapsed / 1000, startSn, endSn);
    }

    /**
     * 关闭前执行最后一次 fsync
     */
    private void finalFsync() throws IOException {
        long flushedSn = buffer.getFlushedSn();
        long writeSn = buffer.getWriteSn();

        if (writeSn > flushedSn) {
            logger.info("Final fsync: {} bytes pending", writeSn - flushedSn);
            doFsync(flushedSn, writeSn);
        }
    }

    // ==================== 同步刷盘 (供 MTR commit 调用) ====================

    /**
     * 同步等待指定 SN 被持久化 (Phase 1-2 模式)
     *
     * <p>当 flushLogAtTrxCommit=1 时，MTR commit 会调用此方法。
     * 此方法会通知后台 flusher 线程并等待 flushedSn 达到目标值，
     * 而不是在用户线程中直接执行 fsync。</p>
     *
     * <h3>关键设计</h3>
     * <ul>
     *   <li><b>不在用户线程执行 fsync</b>: 避免破坏 Group Commit</li>
     *   <li><b>通知后台线程</b>: 唤醒 flusher 加速 fsync</li>
     *   <li><b>等待水位推进</b>: 阻塞直到 flushedSn >= targetSn</li>
     * </ul>
     *
     * @param targetSn 目标 SN
     * @throws InterruptedException 如果等待被中断
     */
    public void syncFlush(long targetSn) throws InterruptedException, IOException {
        if (flushLogAtTrxCommit != RedoLogConfig.FLUSH_AT_TRX_COMMIT_SYNC) {
            // 非同步模式，不需要等待
            return;
        }

        // 如果已经 flush 过了，直接返回
        if (buffer.getFlushedSn() >= targetSn) {
            return;
        }

        // 通知后台 flusher 线程有新数据需要 flush
        notifyNewWrite();

        // 等待 flushedSn 被后台线程推进到目标值
        // 使用 buffer 的 waitForFlush 方法，这会在 flushedSn 推进时被唤醒
        buffer.waitForFlush(targetSn);
    }

    /**
     * Group Commit Leader 执行 fsync (Phase 5 模式)
     *
     * <p>此方法供 Group Commit 的 Leader 线程调用。
     * Leader 直接在当前线程执行 fsync，一次 fsync 覆盖多个事务。</p>
     *
     * <h3>与 syncFlush 的区别</h3>
     * <ul>
     *   <li><b>syncFlush</b>: 等待后台线程完成 fsync（被动）</li>
     *   <li><b>leaderFlush</b>: Leader 主动执行 fsync（主动）</li>
     * </ul>
     *
     * <h3>Group Commit 工作流程</h3>
     * <pre>
     * T1 (Leader): leaderFlush(300) → 执行 fsync → 推进 flushedSn → 唤醒 T2, T3
     * T2 (Follower): 等待被唤醒
     * T3 (Follower): 等待被唤醒
     * </pre>
     *
     * @param targetSn 目标 SN (本批次的最大 SN)
     * @throws IOException 如果 fsync 失败
     */
    public void leaderFlush(long targetSn) throws IOException {
        long flushedSn = buffer.getFlushedSn();

        // 如果已经 flush 过了，直接返回
        if (flushedSn >= targetSn) {
            return;
        }

        // 等待 writer 完成写入
        long writeSn = buffer.getWriteSn();
        int waitCount = 0;
        while (writeSn < targetSn && waitCount < 1000) {
            // 短暂等待 writer 追上
            LockSupport.parkNanos(10_000); // 10μs
            writeSn = buffer.getWriteSn();
            waitCount++;
        }

        // 确定实际可以 flush 的范围
        long flushUpTo = Math.min(targetSn, writeSn);
        if (flushUpTo <= flushedSn) {
            return;
        }

        // Leader 直接执行 fsync
        doFsync(flushedSn, flushUpTo);
    }

    // ==================== 通知方法 ====================

    /**
     * 通知有新的写入完成
     *
     * <p>由 LogWriter 调用，唤醒等待的 flusher 线程。</p>
     */
    public void notifyNewWrite() {
        lock.lock();
        try {
            newWriteAvailable.signal();
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
     * 获取总 fsync 次数
     */
    public long getTotalFsyncCount() {
        return totalFsyncCount;
    }

    /**
     * 获取平均 fsync 耗时 (微秒)
     */
    public long getAverageFsyncTimeUs() {
        return totalFsyncCount > 0 ? totalFsyncTimeNanos / totalFsyncCount / 1000 : 0;
    }
}
