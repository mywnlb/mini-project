package cn.zhangyis.minidb.storage.redo.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * 后台服务抽象基类 (使用虚拟线程)
 *
 * <p>提供后台服务的通用实现框架，子类只需实现 {@link #doWork()} 方法。
 * 使用 Java 21 虚拟线程，轻量级且高效。</p>
 *
 * <h2>工作模式</h2>
 * <pre>
 * start() 调用后:
 *   1. 创建虚拟线程
 *   2. 进入 runLoop()
 *   3. 循环调用 doWork() 直到 stop()
 *
 * stop() 调用后:
 *   1. 设置状态为 STOPPING
 *   2. 中断虚拟线程
 *   3. 等待线程退出
 * </pre>
 *
 * <h2>错误处理</h2>
 * <p>doWork() 抛出的异常会被捕获并传递给 {@link #onError(Exception)}，
 * 默认实现记录日志并继续运行。子类可以覆盖以实现自定义错误处理。</p>
 *
 * <h2>使用示例</h2>
 * <pre>
 * public class LogWriter extends BackgroundService {
 *     public LogWriter() {
 *         super("redo-log-writer", RedoLogOrders.LOG_WRITER);
 *     }
 *
 *     &#64;Override
 *     protected void doWork() throws Exception {
 *         // 单次工作循环
 *         writeBatch();
 *     }
 * }
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public abstract class BackgroundService implements Lifecycle {

    private static final Logger logger = LoggerFactory.getLogger(BackgroundService.class);

    /**
     * 默认线程停止等待超时时间 (毫秒)
     */
    private static final long DEFAULT_STOP_TIMEOUT_MS = 5000;

    /**
     * 服务名称
     */
    private final String name;

    /**
     * 启动顺序
     */
    private final int order;

    /**
     * 当前状态 (原子引用保证可见性)
     */
    private final AtomicReference<LifecycleState> state =
        new AtomicReference<>(LifecycleState.NEW);

    /**
     * 虚拟线程
     */
    private volatile Thread virtualThread;

    /**
     * 停止等待超时时间 (毫秒)
     */
    private long stopTimeoutMs = DEFAULT_STOP_TIMEOUT_MS;

    /**
     * 创建后台服务
     *
     * @param name  服务名称
     * @param order 启动顺序
     */
    protected BackgroundService(String name, int order) {
        this.name = name;
        this.order = order;
    }

    // ==================== Lifecycle 实现 ====================

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public LifecycleState getState() {
        return state.get();
    }

    @Override
    public void initialize() throws Exception {
        if (!state.compareAndSet(LifecycleState.NEW, LifecycleState.INITIALIZING)) {
            throw new IllegalStateException(
                "Cannot initialize: current state is " + state.get());
        }

        try {
            doInitialize();
            state.set(LifecycleState.INITIALIZED);
            logger.debug("{} initialized", name);
        } catch (Exception e) {
            state.set(LifecycleState.FAILED);
            throw e;
        }
    }

    @Override
    public void start() throws Exception {
        if (!state.compareAndSet(LifecycleState.INITIALIZED, LifecycleState.STARTING)) {
            throw new IllegalStateException(
                "Cannot start: current state is " + state.get());
        }

        try {
            // 创建虚拟线程
            virtualThread = Thread.ofVirtual()
                .name(name)
                .uncaughtExceptionHandler(this::handleUncaughtException)
                .start(this::runLoop);

            state.set(LifecycleState.RUNNING);
            logger.info("{} started", name);

        } catch (Exception e) {
            state.set(LifecycleState.FAILED);
            throw e;
        }
    }

    @Override
    public void stop() throws Exception {
        LifecycleState current = state.get();
        if (current != LifecycleState.RUNNING) {
            if (current == LifecycleState.STOPPED || current == LifecycleState.DESTROYED) {
                return; // 已经停止
            }
            throw new IllegalStateException(
                "Cannot stop: current state is " + current);
        }

        state.set(LifecycleState.STOPPING);
        logger.debug("{} stopping...", name);

        try {
            // 中断线程
            if (virtualThread != null) {
                virtualThread.interrupt();

                // 等待线程退出
                virtualThread.join(stopTimeoutMs);

                if (virtualThread.isAlive()) {
                    logger.warn("{} did not stop gracefully within {}ms",
                        name, stopTimeoutMs);
                }
            }

            doStop();
            state.set(LifecycleState.STOPPED);
            logger.info("{} stopped", name);

        } catch (Exception e) {
            state.set(LifecycleState.FAILED);
            throw e;
        }
    }

    @Override
    public void destroy() throws Exception {
        LifecycleState current = state.get();
        if (current == LifecycleState.DESTROYED) {
            return;
        }

        // 如果还在运行，先停止
        if (current == LifecycleState.RUNNING) {
            stop();
        }

        state.set(LifecycleState.DESTROYING);

        try {
            doDestroy();
            state.set(LifecycleState.DESTROYED);
            logger.debug("{} destroyed", name);
        } catch (Exception e) {
            state.set(LifecycleState.FAILED);
            throw e;
        }
    }

    // ==================== 主循环 ====================

    /**
     * 主工作循环
     */
    private void runLoop() {
        logger.debug("{} run loop started", name);

        while (state.get() == LifecycleState.RUNNING) {
            try {
                doWork();
            } catch (InterruptedException e) {
                // 正常中断，退出循环
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                onError(e);
            }
        }

        logger.debug("{} run loop exited", name);
    }

    /**
     * 处理未捕获异常
     */
    private void handleUncaughtException(Thread t, Throwable e) {
        logger.error("Uncaught exception in {}", name, e);
        state.set(LifecycleState.FAILED);
        onFatalError(e);
    }

    // ==================== 模板方法 (子类可覆盖) ====================

    /**
     * 初始化钩子
     *
     * <p>子类可覆盖以执行自定义初始化逻辑。</p>
     *
     * @throws Exception 如果初始化失败
     */
    protected void doInitialize() throws Exception {
        // 默认空实现
    }

    /**
     * 单次工作循环
     *
     * <p>子类必须实现，定义每次循环的工作内容。
     * 如果没有工作要做，应该短暂休眠以避免 CPU 空转。</p>
     *
     * <p>示例：</p>
     * <pre>
     * protected void doWork() throws Exception {
     *     if (hasWork()) {
     *         processWork();
     *     } else {
     *         Thread.sleep(1);  // 或使用 LockSupport.parkNanos
     *     }
     * }
     * </pre>
     *
     * @throws Exception         如果工作执行失败
     * @throws InterruptedException 如果被中断
     */
    protected abstract void doWork() throws Exception;

    /**
     * 停止钩子
     *
     * <p>子类可覆盖以执行自定义停止逻辑（如刷新缓冲区）。</p>
     *
     * @throws Exception 如果停止失败
     */
    protected void doStop() throws Exception {
        // 默认空实现
    }

    /**
     * 销毁钩子
     *
     * <p>子类可覆盖以释放资源。</p>
     *
     * @throws Exception 如果销毁失败
     */
    protected void doDestroy() throws Exception {
        // 默认空实现
    }

    /**
     * 错误处理钩子
     *
     * <p>doWork() 抛出非中断异常时调用。
     * 默认实现记录日志并继续运行。子类可覆盖以实现自定义策略。</p>
     *
     * @param e 异常
     */
    protected void onError(Exception e) {
        logger.error("Error in {}", name, e);
        // 短暂休眠，避免错误风暴
        LockSupport.parkNanos(100_000_000L); // 100ms
    }

    /**
     * 致命错误处理钩子
     *
     * <p>未捕获异常导致服务失败时调用。默认空实现。</p>
     *
     * @param e 异常
     */
    protected void onFatalError(Throwable e) {
        // 默认空实现，子类可覆盖以执行告警等操作
    }

    // ==================== 辅助方法 ====================

    /**
     * 设置停止等待超时时间
     *
     * @param timeoutMs 超时时间 (毫秒)
     */
    public void setStopTimeoutMs(long timeoutMs) {
        this.stopTimeoutMs = timeoutMs;
    }

    /**
     * 唤醒服务线程
     *
     * <p>用于通知有新工作可做，避免不必要的等待。
     * 用户线程在等待时可以主动调用此方法唤醒后台服务加速处理。</p>
     */
    public void wakeup() {
        Thread t = virtualThread;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    /**
     * 短暂休眠
     *
     * <p>用于 doWork() 中无工作时的等待。</p>
     *
     * @param nanos 休眠时间 (纳秒)
     */
    protected void parkNanos(long nanos) {
        LockSupport.parkNanos(nanos);
    }

    @Override
    public String toString() {
        return String.format("%s{state=%s, order=%d}", name, state.get(), order);
    }
}
