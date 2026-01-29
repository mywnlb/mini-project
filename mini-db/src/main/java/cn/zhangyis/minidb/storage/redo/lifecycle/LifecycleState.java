package cn.zhangyis.minidb.storage.redo.lifecycle;

/**
 * 生命周期状态枚举
 *
 * <p>定义组件在生命周期中的各个状态，状态转换遵循以下流程：</p>
 * <pre>
 * NEW → INITIALIZING → INITIALIZED → STARTING → RUNNING
 *                                                  ↓
 *                    DESTROYED ← DESTROYING ← STOPPED ← STOPPING
 *
 * 任意状态 → FAILED (发生错误时)
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public enum LifecycleState {

    /**
     * 新建状态，组件刚创建
     */
    NEW,

    /**
     * 初始化中，正在分配资源
     */
    INITIALIZING,

    /**
     * 已初始化，资源已分配但未开始工作
     */
    INITIALIZED,

    /**
     * 启动中，正在启动工作线程
     */
    STARTING,

    /**
     * 运行中，正常工作状态
     */
    RUNNING,

    /**
     * 停止中，正在停止工作
     */
    STOPPING,

    /**
     * 已停止，工作已停止但资源未释放
     */
    STOPPED,

    /**
     * 销毁中，正在释放资源
     */
    DESTROYING,

    /**
     * 已销毁，资源已释放
     */
    DESTROYED,

    /**
     * 失败状态，发生不可恢复的错误
     */
    FAILED;

    /**
     * 检查是否可以转换到目标状态
     *
     * @param target 目标状态
     * @return true 如果转换合法
     */
    public boolean canTransitionTo(LifecycleState target) {
        // FAILED 状态可以从任何状态转换
        if (target == FAILED) {
            return true;
        }

        return switch (this) {
            case NEW -> target == INITIALIZING;
            case INITIALIZING -> target == INITIALIZED;
            case INITIALIZED -> target == STARTING;
            case STARTING -> target == RUNNING;
            case RUNNING -> target == STOPPING;
            case STOPPING -> target == STOPPED;
            case STOPPED -> target == DESTROYING;
            case DESTROYING -> target == DESTROYED;
            case DESTROYED, FAILED -> false;
        };
    }

    /**
     * 检查组件是否处于活跃状态（可以执行工作）
     *
     * @return true 如果组件处于活跃状态
     */
    public boolean isActive() {
        return this == RUNNING;
    }

    /**
     * 检查组件是否已终止（不再工作）
     *
     * @return true 如果组件已终止
     */
    public boolean isTerminated() {
        return this == STOPPED || this == DESTROYED || this == FAILED;
    }

    /**
     * 检查组件是否正在转换状态
     *
     * @return true 如果组件正在转换
     */
    public boolean isTransitioning() {
        return this == INITIALIZING || this == STARTING ||
               this == STOPPING || this == DESTROYING;
    }
}
