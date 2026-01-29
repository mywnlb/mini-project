package cn.zhangyis.minidb.storage.redo.lifecycle;

/**
 * 数据库组件生命周期接口
 *
 * <p>定义组件的标准生命周期管理方法，类似于 Spring 的 Lifecycle 接口，
 * 但针对数据库内核组件进行了定制。</p>
 *
 * <h2>生命周期阶段</h2>
 * <pre>
 * 1. 构造: new Component()
 * 2. 初始化: initialize() - 分配资源，建立连接
 * 3. 启动: start() - 开始工作，启动线程
 * 4. 运行: 正常工作状态
 * 5. 停止: stop() - 停止工作，但保留资源
 * 6. 销毁: destroy() - 释放所有资源
 * </pre>
 *
 * <h2>启动顺序</h2>
 * <p>通过 {@link #getOrder()} 方法定义组件的启动优先级。
 * 数字越小优先级越高，越先启动。关闭时按相反顺序执行。</p>
 *
 * <h2>使用示例</h2>
 * <pre>
 * public class LogWriter implements Lifecycle {
 *     public int getOrder() { return 200; }
 *
 *     public void initialize() {
 *         // 分配 buffer
 *     }
 *
 *     public void start() {
 *         // 启动工作线程
 *     }
 *
 *     public void stop() {
 *         // 停止工作线程
 *     }
 *
 *     public void destroy() {
 *         // 释放 buffer
 *     }
 * }
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public interface Lifecycle {

    /**
     * 获取组件名称
     *
     * <p>用于日志输出和调试，应返回有意义的描述性名称。</p>
     *
     * @return 组件名称
     */
    String getName();

    /**
     * 获取启动顺序
     *
     * <p>数字越小，启动越早，关闭越晚。建议使用以下范围：</p>
     * <ul>
     *   <li>0-99: 基础设施组件</li>
     *   <li>100-199: 核心组件</li>
     *   <li>200-299: I/O 组件</li>
     *   <li>300-399: 后台任务</li>
     *   <li>400+: 辅助组件</li>
     * </ul>
     *
     * @return 启动顺序值
     */
    int getOrder();

    /**
     * 初始化组件
     *
     * <p>分配资源、建立连接，但不开始工作。
     * 此方法应该是幂等的，多次调用不应产生副作用。</p>
     *
     * <p>初始化完成后状态变为 {@link LifecycleState#INITIALIZED}。</p>
     *
     * @throws Exception 如果初始化失败
     */
    void initialize() throws Exception;

    /**
     * 启动组件
     *
     * <p>开始工作，启动后台线程等。
     * 必须在 {@link #initialize()} 之后调用。</p>
     *
     * <p>启动完成后状态变为 {@link LifecycleState#RUNNING}。</p>
     *
     * @throws Exception 如果启动失败
     */
    void start() throws Exception;

    /**
     * 停止组件
     *
     * <p>停止工作，但保留资源。停止后可以通过 {@link #start()} 重新启动。</p>
     *
     * <p>停止完成后状态变为 {@link LifecycleState#STOPPED}。</p>
     *
     * @throws Exception 如果停止失败
     */
    void stop() throws Exception;

    /**
     * 销毁组件
     *
     * <p>释放所有资源，组件不可再使用。
     * 必须在 {@link #stop()} 之后调用。</p>
     *
     * <p>销毁完成后状态变为 {@link LifecycleState#DESTROYED}。</p>
     *
     * @throws Exception 如果销毁失败
     */
    void destroy() throws Exception;

    /**
     * 获取当前状态
     *
     * @return 当前生命周期状态
     */
    LifecycleState getState();

    /**
     * 检查组件是否正在运行
     *
     * @return true 如果组件处于 RUNNING 状态
     */
    default boolean isRunning() {
        return getState() == LifecycleState.RUNNING;
    }

    /**
     * 检查组件是否已终止
     *
     * @return true 如果组件已停止、销毁或失败
     */
    default boolean isTerminated() {
        return getState().isTerminated();
    }
}
