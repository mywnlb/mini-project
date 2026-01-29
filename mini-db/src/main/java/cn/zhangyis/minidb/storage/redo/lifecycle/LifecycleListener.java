package cn.zhangyis.minidb.storage.redo.lifecycle;

/**
 * 生命周期监听器接口
 *
 * <p>监听组件生命周期事件，用于实现日志记录、监控、依赖管理等功能。</p>
 *
 * <h2>使用示例</h2>
 * <pre>
 * public class MetricsListener implements LifecycleListener {
 *     &#64;Override
 *     public void onStarted(LifecycleEvent event) {
 *         metrics.recordComponentStart(event.getSourceName());
 *     }
 *
 *     &#64;Override
 *     public void onFailed(LifecycleEvent event) {
 *         alertService.sendAlert("Component failed: " + event.getSourceName());
 *     }
 * }
 * </pre>
 *
 * <h2>线程安全</h2>
 * <p>监听器方法可能在不同线程中被调用，实现类需要保证线程安全。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public interface LifecycleListener {

    /**
     * 组件初始化完成时调用
     *
     * @param event 生命周期事件
     */
    default void onInitialized(LifecycleEvent event) {
        // 默认空实现
    }

    /**
     * 组件启动完成时调用
     *
     * @param event 生命周期事件
     */
    default void onStarted(LifecycleEvent event) {
        // 默认空实现
    }

    /**
     * 组件停止完成时调用
     *
     * @param event 生命周期事件
     */
    default void onStopped(LifecycleEvent event) {
        // 默认空实现
    }

    /**
     * 组件销毁完成时调用
     *
     * @param event 生命周期事件
     */
    default void onDestroyed(LifecycleEvent event) {
        // 默认空实现
    }

    /**
     * 组件发生错误时调用
     *
     * @param event 生命周期事件（包含错误信息）
     */
    default void onFailed(LifecycleEvent event) {
        // 默认空实现
    }

    /**
     * 任意状态转换时调用
     *
     * <p>在具体的状态回调之前调用，用于通用的状态追踪。</p>
     *
     * @param event 生命周期事件
     */
    default void onStateChanged(LifecycleEvent event) {
        // 默认空实现
    }
}
