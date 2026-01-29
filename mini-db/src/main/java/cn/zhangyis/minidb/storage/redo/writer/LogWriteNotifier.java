package cn.zhangyis.minidb.storage.redo.writer;

import cn.zhangyis.minidb.storage.redo.lifecycle.BackgroundService;
import cn.zhangyis.minidb.storage.redo.lifecycle.RedoLogOrders;
import cn.zhangyis.minidb.storage.redo.wait.WaitSlots;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.LockSupport;

/**
 * Log Write Notifier 后台服务
 *
 * <p>负责唤醒等待 writeSn 推进的线程。当 LogWriter 写入文件后，
 * 调用 notifyProgress() 通知本服务，然后由本服务精准唤醒相关等待者。</p>
 *
 * <h2>设计目的</h2>
 * <p>将唤醒职责从 LogWriter 中分离出来，避免 LogWriter 在唤醒大量线程时
 * 产生延迟，影响写入吞吐量。</p>
 *
 * <h2>工作流程</h2>
 * <pre>
 * LogWriter:
 *   write(data)
 *   advanceWriteSn(newSn)
 *   writeNotifier.notifyProgress(newSn)  // 非阻塞通知
 *
 * LogWriteNotifier:
 *   park() 等待通知
 *   收到通知后：wakeupRange(oldSn, newSn)  // 精准唤醒
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class LogWriteNotifier extends BackgroundService {

    private static final Logger logger = LoggerFactory.getLogger(LogWriteNotifier.class);

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
     * 创建 LogWriteNotifier
     *
     * @param waitSlots write 等待槽位
     */
    public LogWriteNotifier(WaitSlots waitSlots) {
        super("redo-log-write-notifier", RedoLogOrders.LOG_WRITE_NOTIFIER);
        this.waitSlots = waitSlots;
    }

    /**
     * 通知 write 进度更新
     *
     * <p>由 LogWriter 在推进 writeSn 后调用。非阻塞。</p>
     *
     * @param newWriteSn 新的 writeSn
     */
    public void notifyProgress(long newWriteSn) {
        pendingSn = newWriteSn;
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

            logger.trace("LogWriteNotifier: wakeup range {} -> {}", lastNotifiedSn, targetSn);
            lastNotifiedSn = targetSn;
        }
    }

    @Override
    protected void doStop() throws Exception {
        // 最后一次唤醒所有
        waitSlots.wakeupAll();
        logger.info("LogWriteNotifier stopped: notifyCount={}, wakeupCount={}",
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
