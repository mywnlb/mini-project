package cn.zhangyis.minidb.storage.redo.lifecycle;

/**
 * 生命周期事件
 *
 * <p>记录组件状态转换的事件，包含源组件、状态变化和可能的异常信息。
 * 用于通知 {@link LifecycleListener} 组件状态变化。</p>
 *
 * @param source   事件源组件
 * @param oldState 转换前的状态
 * @param newState 转换后的状态
 * @param error    错误信息（状态为 FAILED 时携带）
 *
 * @author MiniDB
 * @version 1.0
 */
public record LifecycleEvent(
    Lifecycle source,
    LifecycleState oldState,
    LifecycleState newState,
    Throwable error
) {

    /**
     * 创建正常状态转换事件
     *
     * @param source   事件源
     * @param oldState 旧状态
     * @param newState 新状态
     * @return 生命周期事件
     */
    public static LifecycleEvent of(Lifecycle source, LifecycleState oldState,
                                    LifecycleState newState) {
        return new LifecycleEvent(source, oldState, newState, null);
    }

    /**
     * 创建失败事件
     *
     * @param source   事件源
     * @param oldState 失败前的状态
     * @param error    错误信息
     * @return 生命周期事件
     */
    public static LifecycleEvent failed(Lifecycle source, LifecycleState oldState,
                                        Throwable error) {
        return new LifecycleEvent(source, oldState, LifecycleState.FAILED, error);
    }

    /**
     * 检查是否为失败事件
     *
     * @return true 如果新状态为 FAILED
     */
    public boolean isFailed() {
        return newState == LifecycleState.FAILED;
    }

    /**
     * 检查是否为启动完成事件
     *
     * @return true 如果新状态为 RUNNING
     */
    public boolean isStarted() {
        return newState == LifecycleState.RUNNING;
    }

    /**
     * 检查是否为停止完成事件
     *
     * @return true 如果新状态为 STOPPED
     */
    public boolean isStopped() {
        return newState == LifecycleState.STOPPED;
    }

    /**
     * 获取组件名称
     *
     * @return 源组件的名称
     */
    public String getSourceName() {
        return source != null ? source.getName() : "unknown";
    }

    @Override
    public String toString() {
        String base = String.format("LifecycleEvent{source=%s, %s -> %s}",
            getSourceName(), oldState, newState);
        if (error != null) {
            return base + ", error=" + error.getMessage();
        }
        return base;
    }
}
