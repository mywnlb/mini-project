package cn.zhangyis.minidb.storage.redo.writer;

import cn.zhangyis.minidb.storage.redo.lifecycle.BackgroundService;
import cn.zhangyis.minidb.storage.redo.lifecycle.RedoLogOrders;
import cn.zhangyis.minidb.storage.redo.wait.WaitSlots;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.LockSupport;

/**
 * Log Flush Notifier 后台服务
 *
 * <p>负责唤醒等待 flushedSn 推进的线程。当 LogFlusher 完成 fsync 后，
 * 调用 notifyProgress() 通知本服务，然后由本服务精准唤醒相关等待者。</p>
 *
 * <h2>设计目的</h2>
 * <p>将唤醒职责从 LogFlusher 中分离出来，避免 LogFlusher 在唤醒大量线程时
 * 产生延迟，影响 fsync 频率。</p>
 *
 * <h2>工作流程</h2>
 * <pre>
 * LogFlusher:
 *   fsync()
 *   advanceFlushedSn(newSn)
 *   flushNotifier.notifyProgress(newSn)  // 非阻塞通知
 *
 * LogFlushNotifier:
 *   park() 等待通知
 *   收到通知后：wakeupRange(oldSn, newSn)  // 精准唤醒
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class LogFlushNotifier extends BackgroundService {

    private static final Logger logger = LoggerFactory.getLogger(LogFlushNotifier.class);

    /** flush 等待槽位 */
    private final WaitSlots waitSlots;

    /** 待通知的目标 SN (volatile 保证可见性) */
    private volatile long pendingSn = 0;

    /** 上次通知的 SN */
    private long lastNotifiedSn = 0;

    /** 通知次数统计 */
    private long notifyCount = 0;

    /** 唤醒线程数统计 */
    private long wakeupCount = 0;

    /**
     * 创建 LogFlushNotifier
     *
     * @param waitSlots flush 等待槽位
     */
    public LogFlushNotifier(WaitSlots waitSlots) {
        super("redo-log-flush-notifier", RedoLogOrders.LOG_FLUSH_NOTIFIER);
        this.waitSlots = waitSlots;
    }

    /**
     * 通知 flush 进度更新
     *
     * <p>由 LogFlusher 在推进 flushedSn 后调用。非阻塞。</p>
     *
     * @param newFlushedSn 新的 flushedSn
     */
    public void notifyProgress(long newFlushedSn) {
        pendingSn = newFlushedSn;
        wakeup();  // 唤醒 notifier 线程
    }

    @Override
    protected void doWork() throws Exception {
        // 等待通知
        LockSupport.park();

        long targetSn = pendingSn;
        if (targetSn > lastNotifiedSn) {
            // 唤醒等待线程
            waitSlots.wakeupRange(lastNotifiedSn, targetSn);

            notifyCount++;
            wakeupCount += waitSlots.getTotalWaiters(); // 近似值

            logger.trace("LogFlushNotifier: wakeup range {} -> {}", lastNotifiedSn, targetSn);
            lastNotifiedSn = targetSn;
        }
    }

    @Override
    protected void doStop() throws Exception {
        // 最后一次唤醒所有
        waitSlots.wakeupAll();
        logger.info("LogFlushNotifier stopped: notifyCount={}, wakeupCount={}",
                notifyCount, wakeupCount);
    }

    /**
     * 获取通知次数
     */
    public long getNotifyCount() {
        return notifyCount;
    }

    /**
     * 获取唤醒次数
     */
    public long getWakeupCount() {
        return wakeupCount;
    }
}
