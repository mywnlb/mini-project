package cn.zhangyis.minidb.storage.redo.buffer;

/**
 * RedoLogBuffer - 向后兼容的别名类
 *
 * <p><b>注意</b>: 此类已重命名为 {@link LockBasedRedoLogBuffer}。
 * 此类保留用于向后兼容，新代码应直接使用 {@link RedoLogBufferApi} 接口
 * 或通过 {@link RedoLogBufferFactory} 创建实例。</p>
 *
 * @author MiniDB
 * @version 1.0
 * @deprecated 使用 {@link LockBasedRedoLogBuffer} 或 {@link RedoLogBufferApi} 接口
 */
@Deprecated
public class RedoLogBuffer extends LockBasedRedoLogBuffer {

    /**
     * 创建 RedoLogBuffer
     *
     * @param capacity Buffer 容量 (bytes)
     * @deprecated 使用 {@link LockBasedRedoLogBuffer} 或 {@link RedoLogBufferFactory}
     */
    @Deprecated
    public RedoLogBuffer(int capacity) {
        super(capacity);
    }

    /**
     * 创建默认大小的 RedoLogBuffer (16MB)
     *
     * @deprecated 使用 {@link LockBasedRedoLogBuffer} 或 {@link RedoLogBufferFactory}
     */
    @Deprecated
    public RedoLogBuffer() {
        super();
    }
}
