package cn.zhangyis.minidb.storage.redo.lifecycle;

/**
 * Redo Log 后台服务启动顺序常量
 *
 * <p>定义 Redo Log 子系统中各后台服务的启动优先级。
 * 数字越小越先启动，关闭时按相反顺序。</p>
 *
 * <h2>启动顺序说明</h2>
 * <pre>
 * 启动顺序:
 *   LOG_CLOSER (100)        → 先启动，推进 recentClosed
 *   LOG_WRITER (200)        → 写入文件
 *   LOG_FLUSHER (300)       → fsync
 *   LOG_WRITE_NOTIFIER (400) → 唤醒 write 等待者
 *   LOG_FLUSH_NOTIFIER (410) → 唤醒 flush 等待者
 *
 * 关闭顺序 (逆序):
 *   LOG_FLUSH_NOTIFIER → LOG_WRITE_NOTIFIER → LOG_FLUSHER
 *   → LOG_WRITER → LOG_CLOSER
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class RedoLogOrders {

    private RedoLogOrders() {
        // 工具类，禁止实例化
    }

    /**
     * LogCloser 启动顺序
     * <p>最先启动，负责推进 recentClosed.tail</p>
     */
    public static final int LOG_CLOSER = 100;

    /**
     * LogWriter 启动顺序
     * <p>在 Closer 之后启动，负责将 buffer 写入文件</p>
     */
    public static final int LOG_WRITER = 200;

    /**
     * LogFlusher 启动顺序
     * <p>在 Writer 之后启动，负责 fsync</p>
     */
    public static final int LOG_FLUSHER = 300;

    /**
     * LogWriteNotifier 启动顺序
     * <p>在 I/O 线程之后启动，负责唤醒等待 write 完成的线程</p>
     */
    public static final int LOG_WRITE_NOTIFIER = 400;

    /**
     * LogFlushNotifier 启动顺序
     * <p>最后启动，负责唤醒等待 flush 完成的线程</p>
     */
    public static final int LOG_FLUSH_NOTIFIER = 410;

    /**
     * CheckpointManager 启动顺序
     * <p>在 notifier 之后启动</p>
     */
    public static final int CHECKPOINT_MANAGER = 500;
}
