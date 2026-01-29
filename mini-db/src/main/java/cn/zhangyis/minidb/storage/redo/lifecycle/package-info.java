/**
 * 数据库组件生命周期管理框架
 *
 * <p>本包提供类似 Spring 的生命周期管理功能，用于管理数据库内核组件的
 * 初始化、启动、停止和销毁。使用 Java 21 虚拟线程实现轻量级后台服务。</p>
 *
 * <h2>核心组件</h2>
 * <ul>
 *   <li>{@link cn.zhangyis.minidb.storage.redo.lifecycle.Lifecycle} - 生命周期接口</li>
 *   <li>{@link cn.zhangyis.minidb.storage.redo.lifecycle.LifecycleState} - 状态枚举</li>
 *   <li>{@link cn.zhangyis.minidb.storage.redo.lifecycle.LifecycleEvent} - 事件记录</li>
 *   <li>{@link cn.zhangyis.minidb.storage.redo.lifecycle.LifecycleListener} - 监听器接口</li>
 *   <li>{@link cn.zhangyis.minidb.storage.redo.lifecycle.BackgroundService} - 后台服务抽象</li>
 *   <li>{@link cn.zhangyis.minidb.storage.redo.lifecycle.DatabaseLifecycleManager} - 生命周期管理器</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 1. 定义组件
 * public class LogWriter extends BackgroundService {
 *     public LogWriter() {
 *         super("log-writer", 200);
 *     }
 *
 *     &#64;Override
 *     protected void doWork() throws Exception {
 *         // 工作逻辑
 *     }
 * }
 *
 * // 2. 创建管理器并注册组件
 * DatabaseLifecycleManager manager = new DatabaseLifecycleManager("redo-log");
 * manager.register(new LogWriter());
 * manager.register(new LogFlusher());
 *
 * // 3. 启动 (按 order 顺序)
 * manager.start();
 *
 * // 4. 停止 (按 order 逆序)
 * manager.stop();
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
package cn.zhangyis.minidb.storage.redo.lifecycle;
