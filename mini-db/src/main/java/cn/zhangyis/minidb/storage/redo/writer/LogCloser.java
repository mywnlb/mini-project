package cn.zhangyis.minidb.storage.redo.writer;

import cn.zhangyis.minidb.storage.redo.buffer.LockFreeRedoLogBuffer;
import cn.zhangyis.minidb.storage.redo.lifecycle.BackgroundService;
import cn.zhangyis.minidb.storage.redo.lifecycle.RedoLogOrders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Log Closer 后台服务
 *
 * <p>负责推进 recentClosed 的 tail 指针。当 MTR 将脏页注册到 Buffer Pool
 * 的 Flush List 后，会调用 markPageDirtyComplete() 在 recentClosed 中标记。
 * LogCloser 定期遍历 recentClosed，推进 tail 到连续完成的边界。</p>
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>推进 recentClosed.tail (脏页注册连续边界)</li>
 *   <li>通知等待 recentClosed.tail 推进的线程</li>
 *   <li>为 Checkpoint 提供安全的 LSN 下界</li>
 * </ul>
 *
 * <h2>工作流程</h2>
 * <pre>
 * while (running) {
 *     1. 调用 recentClosed.advanceTail()
 *     2. 如果 tail 推进，通知等待者
 *     3. 短暂休眠 (避免空转)
 * }
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class LogCloser extends BackgroundService {

    private static final Logger logger = LoggerFactory.getLogger(LogCloser.class);

    /** 默认检查间隔 (微秒) */
    private static final long DEFAULT_INTERVAL_US = 100;

    /** Buffer (用于通知等待者) */
    private final LockFreeRedoLogBuffer buffer;

    /** 检查间隔 (纳秒) */
    private final long intervalNanos;

    /** 上次 tail 值 (用于日志和通知) */
    private long lastTail = 0;

    /** 推进次数统计 */
    private long advanceCount = 0;

    /**
     * 创建 LogCloser
     *
     * @param buffer LockFreeRedoLogBuffer
     */
    public LogCloser(LockFreeRedoLogBuffer buffer) {
        this(buffer, DEFAULT_INTERVAL_US);
    }

    /**
     * 创建 LogCloser
     *
     * @param buffer     LockFreeRedoLogBuffer
     * @param intervalUs 检查间隔 (微秒)
     */
    public LogCloser(LockFreeRedoLogBuffer buffer, long intervalUs) {
        super("redo-log-closer", RedoLogOrders.LOG_CLOSER);
        this.buffer = buffer;
        this.intervalNanos = intervalUs * 1000;
    }

    @Override
    protected void doWork() throws Exception {
        // 推进 tail
        long newTail = buffer.getRecentClosed().advanceTail();

        if (newTail > lastTail) {
            advanceCount++;
            logger.trace("LogCloser: advanced tail {} -> {}", lastTail, newTail);

            // 通知等待 recentClosed.tail 推进的线程
            buffer.notifyRecentClosedProgress(lastTail, newTail);

            lastTail = newTail;
        }

        // 短暂休眠
        parkNanos(intervalNanos);
    }

    @Override
    protected void doStop() throws Exception {
        // 最后一次推进
        long newTail = buffer.getRecentClosed().advanceTail();
        if (newTail > lastTail) {
            buffer.notifyRecentClosedProgress(lastTail, newTail);
        }
        logger.info("LogCloser stopped: finalTail={}, advanceCount={}", newTail, advanceCount);
    }

    /**
     * 获取当前 tail 值
     *
     * @return recentClosed.tail
     */
    public long getTail() {
        return buffer.getRecentClosed().getTail();
    }

    /**
     * 获取推进次数
     *
     * @return 推进次数
     */
    public long getAdvanceCount() {
        return advanceCount;
    }
}
