package cn.zhangyis.minidb.storage.redo.buffer;

import cn.zhangyis.minidb.storage.redo.RedoLogConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RedoLogBuffer 工厂类
 *
 * <p>根据配置创建对应的 RedoLogBuffer 实现。</p>
 *
 * <h2>支持的模式</h2>
 * <ul>
 *   <li><b>LOCK_BASED</b>: 有锁实现 {@link LockBasedRedoLogBuffer}</li>
 *   <li><b>LOCK_FREE</b>: 无锁实现 (待实现)</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * RedoLogConfig config = RedoLogConfig.builder()
 *     .dataDir("/data/minidb")
 *     .lockFree()
 *     .build();
 *
 * RedoLogBufferApi buffer = RedoLogBufferFactory.create(config);
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class RedoLogBufferFactory {

    private static final Logger logger = LoggerFactory.getLogger(RedoLogBufferFactory.class);

    private RedoLogBufferFactory() {
        // 工厂类，禁止实例化
    }

    /**
     * 根据配置创建 RedoLogBuffer
     *
     * @param config 配置
     * @return RedoLogBuffer 实例
     */
    public static RedoLogBufferApi create(RedoLogConfig config) {
        return switch (config.getBufferMode()) {
            case LOCK_BASED -> createLockBased(config);
            case LOCK_FREE -> createLockFree(config);
        };
    }

    /**
     * 创建有锁模式的 Buffer
     */
    private static RedoLogBufferApi createLockBased(RedoLogConfig config) {
        logger.info("Creating LockBasedRedoLogBuffer: bufferSize={}MB",
                config.getLogBufferSize() / (1024 * 1024));

        return new LockBasedRedoLogBuffer(config.getLogBufferSize());
    }

    /**
     * 创建无锁模式的 Buffer
     */
    private static RedoLogBufferApi createLockFree(RedoLogConfig config) {
        int bufferSize = config.getLogBufferSize();
        int linkBufCapacity = config.getLinkBufCapacity();
        int linkBufGranularity = config.getLinkBufGranularity();
        int waitSlotCount = config.getWaitSlotCount();
        long waitSlotGranularity = config.getWaitSlotGranularity();

        logger.info("Creating LockFreeRedoLogBuffer: bufferSize={}MB, " +
                        "linkBufCapacity={}, linkBufGranularity={}, " +
                        "waitSlotCount={}, waitSlotGranularity={}",
                bufferSize / (1024 * 1024),
                linkBufCapacity, linkBufGranularity,
                waitSlotCount, waitSlotGranularity);

        return new LockFreeRedoLogBuffer(bufferSize, linkBufCapacity, linkBufGranularity,
                waitSlotCount, waitSlotGranularity);
    }
}
