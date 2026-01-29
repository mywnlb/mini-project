package cn.zhangyis.minidb.storage.redo.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 数据库生命周期管理器
 *
 * <p>管理多个组件的生命周期，类似于 Spring 的 ApplicationContext。
 * 提供统一的启动、停止、监听器机制。</p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li>组件注册与排序：按 order 管理组件启动顺序</li>
 *   <li>统一生命周期：一键启动/停止所有组件</li>
 *   <li>事件通知：通过监听器机制通知状态变化</li>
 *   <li>优雅关闭：按注册的逆序关闭组件</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * DatabaseLifecycleManager manager = new DatabaseLifecycleManager("redo-log");
 *
 * // 注册组件 (按 order 自动排序)
 * manager.register(logCloser);    // order=100
 * manager.register(logWriter);    // order=200
 * manager.register(logFlusher);   // order=300
 *
 * // 添加监听器
 * manager.addListener(new LoggingLifecycleListener());
 *
 * // 启动所有组件 (按 order 顺序)
 * manager.start();
 *
 * // 停止所有组件 (按 order 逆序)
 * manager.stop();
 * </pre>
 *
 * <h2>线程安全</h2>
 * <p>组件列表使用 CopyOnWriteArrayList，支持并发读取。
 * 状态转换使用原子操作保证一致性。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class DatabaseLifecycleManager implements Lifecycle {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseLifecycleManager.class);

    /**
     * 管理器名称
     */
    private final String name;

    /**
     * 管理的组件列表 (按 order 排序)
     */
    private final List<Lifecycle> components = new CopyOnWriteArrayList<>();

    /**
     * 监听器列表
     */
    private final List<LifecycleListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 当前状态
     */
    private final AtomicReference<LifecycleState> state =
        new AtomicReference<>(LifecycleState.NEW);

    /**
     * 是否异步通知监听器
     */
    private final boolean asyncNotify;

    /**
     * 创建生命周期管理器
     *
     * @param name 管理器名称
     */
    public DatabaseLifecycleManager(String name) {
        this(name, false);
    }

    /**
     * 创建生命周期管理器
     *
     * @param name        管理器名称
     * @param asyncNotify 是否异步通知监听器
     */
    public DatabaseLifecycleManager(String name, boolean asyncNotify) {
        this.name = name;
        this.asyncNotify = asyncNotify;
    }

    // ==================== 组件注册 ====================

    /**
     * 注册组件
     *
     * <p>组件会按 order 自动排序。相同 order 的组件按注册顺序排列。</p>
     *
     * @param component 要注册的组件
     * @throws IllegalStateException 如果管理器已经启动
     */
    public void register(Lifecycle component) {
        LifecycleState current = state.get();
        if (current != LifecycleState.NEW && current != LifecycleState.INITIALIZED) {
            throw new IllegalStateException(
                "Cannot register component after manager started: " + current);
        }

        components.add(component);

        // 按 order 排序
        components.sort(Comparator.comparingInt(Lifecycle::getOrder));

        logger.debug("Registered component: {} (order={})",
            component.getName(), component.getOrder());
    }

    /**
     * 批量注册组件
     *
     * @param componentList 组件列表
     */
    public void registerAll(List<? extends Lifecycle> componentList) {
        for (Lifecycle component : componentList) {
            register(component);
        }
    }

    /**
     * 获取已注册的组件列表 (只读)
     *
     * @return 组件列表的不可变视图
     */
    public List<Lifecycle> getComponents() {
        return Collections.unmodifiableList(components);
    }

    // ==================== 监听器管理 ====================

    /**
     * 添加监听器
     *
     * @param listener 监听器
     */
    public void addListener(LifecycleListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除监听器
     *
     * @param listener 监听器
     */
    public void removeListener(LifecycleListener listener) {
        listeners.remove(listener);
    }

    // ==================== Lifecycle 实现 ====================

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getOrder() {
        return 0; // 管理器本身优先级最高
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

        logger.info("{} initializing {} components...", name, components.size());

        try {
            // 按顺序初始化所有组件
            for (Lifecycle component : components) {
                initializeComponent(component);
            }

            state.set(LifecycleState.INITIALIZED);
            logger.info("{} initialized", name);

        } catch (Exception e) {
            state.set(LifecycleState.FAILED);
            throw e;
        }
    }

    @Override
    public void start() throws Exception {
        LifecycleState current = state.get();

        // 如果是 NEW 状态，先初始化
        if (current == LifecycleState.NEW) {
            initialize();
        }

        if (!state.compareAndSet(LifecycleState.INITIALIZED, LifecycleState.STARTING)) {
            throw new IllegalStateException(
                "Cannot start: current state is " + state.get());
        }

        logger.info("{} starting {} components...", name, components.size());

        try {
            // 按顺序启动所有组件
            for (Lifecycle component : components) {
                startComponent(component);
            }

            state.set(LifecycleState.RUNNING);
            logger.info("{} started", name);

        } catch (Exception e) {
            state.set(LifecycleState.FAILED);
            // 尝试停止已启动的组件
            stopStartedComponents();
            throw e;
        }
    }

    @Override
    public void stop() throws Exception {
        LifecycleState current = state.get();
        if (current != LifecycleState.RUNNING) {
            if (current == LifecycleState.STOPPED || current == LifecycleState.DESTROYED) {
                return;
            }
            throw new IllegalStateException(
                "Cannot stop: current state is " + current);
        }

        state.set(LifecycleState.STOPPING);
        logger.info("{} stopping {} components...", name, components.size());

        // 按逆序停止所有组件
        List<Lifecycle> reversed = new ArrayList<>(components);
        Collections.reverse(reversed);

        List<Exception> errors = new ArrayList<>();

        for (Lifecycle component : reversed) {
            try {
                stopComponent(component);
            } catch (Exception e) {
                logger.error("Failed to stop component: {}", component.getName(), e);
                errors.add(e);
            }
        }

        state.set(LifecycleState.STOPPED);
        logger.info("{} stopped", name);

        if (!errors.isEmpty()) {
            throw new LifecycleException(
                "Failed to stop " + errors.size() + " components", errors.get(0));
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
        logger.info("{} destroying {} components...", name, components.size());

        // 按逆序销毁所有组件
        List<Lifecycle> reversed = new ArrayList<>(components);
        Collections.reverse(reversed);

        for (Lifecycle component : reversed) {
            try {
                destroyComponent(component);
            } catch (Exception e) {
                logger.error("Failed to destroy component: {}", component.getName(), e);
            }
        }

        state.set(LifecycleState.DESTROYED);
        logger.info("{} destroyed", name);
    }

    // ==================== 组件操作 ====================

    private void initializeComponent(Lifecycle component) throws Exception {
        LifecycleState oldState = component.getState();

        try {
            component.initialize();
            fireEvent(component, oldState, LifecycleState.INITIALIZED, null);
        } catch (Exception e) {
            fireEvent(component, oldState, LifecycleState.FAILED, e);
            throw new LifecycleException(
                "Failed to initialize component: " + component.getName(), e);
        }
    }

    private void startComponent(Lifecycle component) throws Exception {
        LifecycleState oldState = component.getState();

        try {
            component.start();
            fireEvent(component, oldState, LifecycleState.RUNNING, null);
        } catch (Exception e) {
            fireEvent(component, oldState, LifecycleState.FAILED, e);
            throw new LifecycleException(
                "Failed to start component: " + component.getName(), e);
        }
    }

    private void stopComponent(Lifecycle component) throws Exception {
        LifecycleState oldState = component.getState();

        try {
            component.stop();
            fireEvent(component, oldState, LifecycleState.STOPPED, null);
        } catch (Exception e) {
            fireEvent(component, oldState, LifecycleState.FAILED, e);
            throw e;
        }
    }

    private void destroyComponent(Lifecycle component) throws Exception {
        LifecycleState oldState = component.getState();

        try {
            component.destroy();
            fireEvent(component, oldState, LifecycleState.DESTROYED, null);
        } catch (Exception e) {
            fireEvent(component, oldState, LifecycleState.FAILED, e);
            throw e;
        }
    }

    /**
     * 停止已启动的组件 (用于启动失败时的清理)
     */
    private void stopStartedComponents() {
        List<Lifecycle> reversed = new ArrayList<>(components);
        Collections.reverse(reversed);

        for (Lifecycle component : reversed) {
            if (component.getState() == LifecycleState.RUNNING) {
                try {
                    component.stop();
                } catch (Exception e) {
                    logger.error("Failed to stop component during cleanup: {}",
                        component.getName(), e);
                }
            }
        }
    }

    // ==================== 事件通知 ====================

    /**
     * 触发生命周期事件
     */
    private void fireEvent(Lifecycle source, LifecycleState oldState,
                          LifecycleState newState, Throwable error) {
        if (listeners.isEmpty()) {
            return;
        }

        LifecycleEvent event = new LifecycleEvent(source, oldState, newState, error);

        for (LifecycleListener listener : listeners) {
            if (asyncNotify) {
                Thread.startVirtualThread(() -> notifyListener(listener, event));
            } else {
                notifyListener(listener, event);
            }
        }
    }

    /**
     * 通知单个监听器
     */
    private void notifyListener(LifecycleListener listener, LifecycleEvent event) {
        try {
            // 先调用通用回调
            listener.onStateChanged(event);

            // 再调用具体状态回调
            switch (event.newState()) {
                case INITIALIZED -> listener.onInitialized(event);
                case RUNNING -> listener.onStarted(event);
                case STOPPED -> listener.onStopped(event);
                case DESTROYED -> listener.onDestroyed(event);
                case FAILED -> listener.onFailed(event);
                default -> { /* 其他状态不通知 */ }
            }
        } catch (Exception e) {
            logger.error("Error in lifecycle listener", e);
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取指定类型的组件
     *
     * @param type 组件类型
     * @param <T>  组件类型参数
     * @return 匹配的组件，如果不存在返回 null
     */
    @SuppressWarnings("unchecked")
    public <T extends Lifecycle> T getComponent(Class<T> type) {
        for (Lifecycle component : components) {
            if (type.isInstance(component)) {
                return (T) component;
            }
        }
        return null;
    }

    /**
     * 获取所有指定类型的组件
     *
     * @param type 组件类型
     * @param <T>  组件类型参数
     * @return 匹配的组件列表
     */
    @SuppressWarnings("unchecked")
    public <T extends Lifecycle> List<T> getComponents(Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Lifecycle component : components) {
            if (type.isInstance(component)) {
                result.add((T) component);
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return String.format("DatabaseLifecycleManager{name=%s, state=%s, components=%d}",
            name, state.get(), components.size());
    }

    // ==================== 异常类 ====================

    /**
     * 生命周期异常
     */
    public static class LifecycleException extends Exception {
        public LifecycleException(String message) {
            super(message);
        }

        public LifecycleException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
