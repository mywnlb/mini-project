package cn.zhangyis.minidb.storage.redo.writer;

import cn.zhangyis.minidb.storage.redo.RedoLogConfig;
import cn.zhangyis.minidb.storage.redo.buffer.LockBasedRedoLogBuffer;
import cn.zhangyis.minidb.storage.redo.buffer.LockFreeRedoLogBuffer;
import cn.zhangyis.minidb.storage.redo.buffer.RedoLogBufferApi;
import cn.zhangyis.minidb.storage.redo.fileset.RedoLogFileSet;
import cn.zhangyis.minidb.storage.redo.lifecycle.Lifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Redo Log 后台服务工厂
 *
 * <p>根据配置创建对应的后台服务集合。</p>
 *
 * <h2>有锁模式 (LOCK_BASED)</h2>
 * <ul>
 *   <li>LogWriter (现有实现)</li>
 *   <li>LogFlusher (现有实现)</li>
 * </ul>
 *
 * <h2>无锁模式 (LOCK_FREE)</h2>
 * <ul>
 *   <li>LogCloser</li>
 *   <li>LockFreeLogWriter</li>
 *   <li>LockFreeLogFlusher</li>
 *   <li>LogWriteNotifier</li>
 *   <li>LogFlushNotifier</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class RedoLogServiceFactory {

    private static final Logger logger = LoggerFactory.getLogger(RedoLogServiceFactory.class);

    private RedoLogServiceFactory() {
        // 工厂类，禁止实例化
    }

    /**
     * 服务创建结果
     *
     * <p>包含创建的服务列表和关键服务引用。</p>
     */
    public static class ServiceBundle {
        private final List<Lifecycle> services;
        private final LogWriteNotifier writeNotifier;
        private final LogFlushNotifier flushNotifier;

        ServiceBundle(List<Lifecycle> services,
                     LogWriteNotifier writeNotifier,
                     LogFlushNotifier flushNotifier) {
            this.services = services;
            this.writeNotifier = writeNotifier;
            this.flushNotifier = flushNotifier;
        }

        public List<Lifecycle> getServices() {
            return services;
        }

        public LogWriteNotifier getWriteNotifier() {
            return writeNotifier;
        }

        public LogFlushNotifier getFlushNotifier() {
            return flushNotifier;
        }
    }

    /**
     * 根据配置创建后台服务集合
     *
     * @param config  配置
     * @param buffer  RedoLogBuffer
     * @param fileSet RedoLogFileSet
     * @return 服务创建结果
     */
    public static ServiceBundle createServices(RedoLogConfig config,
                                               RedoLogBufferApi buffer,
                                               RedoLogFileSet fileSet) {
        return switch (config.getBufferMode()) {
            case LOCK_BASED -> createLockBasedServices(config, buffer, fileSet);
            case LOCK_FREE -> createLockFreeServices(config, buffer, fileSet);
        };
    }

    /**
     * 创建有锁模式的服务
     */
    private static ServiceBundle createLockBasedServices(RedoLogConfig config,
                                                         RedoLogBufferApi buffer,
                                                         RedoLogFileSet fileSet) {
        logger.info("Creating lock-based services");

        // 有锁模式使用现有的 LogWriter 和 LogFlusher
        // 由于它们实现了 Runnable 而非 Lifecycle，需要适配
        // 这里暂时返回空列表，现有代码通过 RedoLogManager 直接管理

        List<Lifecycle> services = new ArrayList<>();

        // TODO: 将现有的 LogWriter/LogFlusher 适配为 Lifecycle
        // 目前保持向后兼容，由 RedoLogManager 直接管理

        return new ServiceBundle(services, null, null);
    }

    /**
     * 创建无锁模式的服务
     */
    private static ServiceBundle createLockFreeServices(RedoLogConfig config,
                                                        RedoLogBufferApi buffer,
                                                        RedoLogFileSet fileSet) {
        if (!(buffer instanceof LockFreeRedoLogBuffer lfBuffer)) {
            throw new IllegalArgumentException(
                    "LOCK_FREE mode requires LockFreeRedoLogBuffer");
        }

        logger.info("Creating lock-free services");

        List<Lifecycle> services = new ArrayList<>();

        // 1. LogCloser (推进 recentClosed.tail)
        LogCloser closer = new LogCloser(lfBuffer);
        services.add(closer);

        // 2. LogWriteNotifier
        LogWriteNotifier writeNotifier = new LogWriteNotifier(lfBuffer.getWriteWaitSlots());
        services.add(writeNotifier);

        // 3. LogFlushNotifier
        LogFlushNotifier flushNotifier = new LogFlushNotifier(lfBuffer.getFlushWaitSlots());
        services.add(flushNotifier);

        // 4. LockFreeLogWriter
        LockFreeLogWriter writer = new LockFreeLogWriter(lfBuffer, fileSet, writeNotifier);
        services.add(writer);

        // 5. LockFreeLogFlusher
        LockFreeLogFlusher flusher = new LockFreeLogFlusher(lfBuffer, fileSet, flushNotifier);
        services.add(flusher);

        // 6. 注册服务到 Buffer (用于主动唤醒)
        lfBuffer.setLogWriter(writer);
        lfBuffer.setLogFlusher(flusher);
        lfBuffer.setLogCloser(closer);

        logger.info("Created {} lock-free services with proactive wakeup", services.size());

        return new ServiceBundle(services, writeNotifier, flushNotifier);
    }
}
