package cn.zhangyis.minidb.storage.redo.writer;

import cn.zhangyis.minidb.storage.redo.buffer.LockFreeRedoLogBuffer;
import cn.zhangyis.minidb.storage.redo.fileset.RedoLogFileSet;
import cn.zhangyis.minidb.storage.redo.lifecycle.BackgroundService;
import cn.zhangyis.minidb.storage.redo.lifecycle.RedoLogOrders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 无锁模式 Log Flusher 后台服务
 *
 * <p>负责将 redo log 从 OS cache fsync 到磁盘。
 * 这是持久化保证的关键组件，确保 WAL 原则。</p>
 *
 * <h2>工作流程</h2>
 * <pre>
 * while (running) {
 *     1. 检查是否有待 fsync 数据 (writeSn > flushedSn)
 *     2. 如果没有数据，短暂休眠
 *     3. 执行 fsync
 *     4. 推进 flushedSn
 *     5. 通知 LogFlushNotifier
 * }
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class LockFreeLogFlusher extends BackgroundService {

    private static final Logger logger = LoggerFactory.getLogger(LockFreeLogFlusher.class);

    /** 默认检查间隔 (毫秒) */
    private static final long DEFAULT_INTERVAL_MS = 1;

    /** Buffer */
    private final LockFreeRedoLogBuffer buffer;

    /** 文件集 */
    private final RedoLogFileSet fileSet;

    /** Flush Notifier */
    private final LogFlushNotifier flushNotifier;

    /** 检查间隔 (纳秒) */
    private final long intervalNanos;

    /** 总 fsync 次数 */
    private long totalFsyncCount = 0;

    /** 总 fsync 耗时 (纳秒) */
    private long totalFsyncTimeNanos = 0;

    /**
     * 创建 LockFreeLogFlusher
     *
     * @param buffer        LockFreeRedoLogBuffer
     * @param fileSet       RedoLogFileSet
     * @param flushNotifier LogFlushNotifier
     */
    public LockFreeLogFlusher(LockFreeRedoLogBuffer buffer, RedoLogFileSet fileSet,
                              LogFlushNotifier flushNotifier) {
        this(buffer, fileSet, flushNotifier, DEFAULT_INTERVAL_MS);
    }

    /**
     * 创建 LockFreeLogFlusher
     *
     * @param buffer        LockFreeRedoLogBuffer
     * @param fileSet       RedoLogFileSet
     * @param flushNotifier LogFlushNotifier
     * @param intervalMs    检查间隔 (毫秒)
     */
    public LockFreeLogFlusher(LockFreeRedoLogBuffer buffer, RedoLogFileSet fileSet,
                              LogFlushNotifier flushNotifier, long intervalMs) {
        super("redo-log-flusher", RedoLogOrders.LOG_FLUSHER);
        this.buffer = buffer;
        this.fileSet = fileSet;
        this.flushNotifier = flushNotifier;
        this.intervalNanos = intervalMs * 1_000_000;
    }

    @Override
    protected void doWork() throws Exception {
        long currentWriteSn = buffer.getWriteSn();
        long currentFlushedSn = buffer.getFlushedSn();
        long toFlush = currentWriteSn - currentFlushedSn;

        if (toFlush <= 0) {
            // 没有数据，短暂休眠
            parkNanos(intervalNanos);
            return;
        }

        // 执行 fsync
        doFsync(currentFlushedSn, currentWriteSn);
    }

    /**
     * 执行 fsync
     */
    private void doFsync(long startSn, long endSn) throws IOException {
        long fsyncStart = System.nanoTime();

        // 1. 调用 fsync
        fileSet.fsync();

        // 2. 推进 flushedSn
        buffer.advanceFlushedSn(endSn);

        // 3. 通知 FlushNotifier
        if (flushNotifier != null) {
            flushNotifier.notifyProgress(endSn);
        }

        // 4. 更新统计
        long elapsed = System.nanoTime() - fsyncStart;
        totalFsyncCount++;
        totalFsyncTimeNanos += elapsed;

        logger.debug("LockFreeLogFlusher: fsync completed in {}μs, sn: {} -> {}",
                elapsed / 1000, startSn, endSn);
    }

    @Override
    protected void doStop() throws Exception {
        // 最后一次 fsync
        long currentWriteSn = buffer.getWriteSn();
        long currentFlushedSn = buffer.getFlushedSn();

        if (currentWriteSn > currentFlushedSn) {
            logger.info("Final fsync: {} bytes pending", currentWriteSn - currentFlushedSn);
            doFsync(currentFlushedSn, currentWriteSn);
        }

        logger.info("LockFreeLogFlusher stopped: totalFsyncCount={}, avgFsyncTimeUs={}",
                totalFsyncCount,
                totalFsyncCount > 0 ? totalFsyncTimeNanos / totalFsyncCount / 1000 : 0);
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
