package cn.zhangyis.minidb.common.exception;

/**
 * Buffer Pool 耗尽异常
 *
 * <p>表示 Buffer Pool 无法分配新的页面帧，所有页面都被固定或无法淘汰。</p>
 *
 * <h2>错误码</h2>
 * <ul>
 *   <li>200101 - 无空闲帧可用</li>
 *   <li>200102 - 所有页面都被固定</li>
 *   <li>200103 - LRU 淘汰失败</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * if (freeList.isEmpty() &amp;&amp; !canEvict()) {
 *     throw BufferExhaustedException.allPagesPinned(poolSize);
 * }
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class BufferExhaustedException extends BufferException {

    /** 错误码：无空闲帧可用 */
    public static final int ERR_NO_FREE_FRAMES = 200101;

    /** 错误码：所有页面都被固定 */
    public static final int ERR_ALL_PAGES_PINNED = 200102;

    /** 错误码：LRU 淘汰失败 */
    public static final int ERR_EVICTION_FAILED = 200103;

    public BufferExhaustedException(String message) {
        super(message);
    }

    public BufferExhaustedException(int errorCode, String message) {
        super(errorCode, message);
    }

    public BufferExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }

    public BufferExhaustedException(int errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /**
     * 创建所有页面都被固定异常
     *
     * @param poolSize Buffer Pool 大小
     * @return BufferExhaustedException
     */
    public static BufferExhaustedException allPagesPinned(int poolSize) {
        return new BufferExhaustedException(ERR_ALL_PAGES_PINNED,
            "Buffer pool exhausted: all " + poolSize + " pages are pinned");
    }

    /**
     * 创建无空闲帧异常
     *
     * @return BufferExhaustedException
     */
    public static BufferExhaustedException noFreeFrames() {
        return new BufferExhaustedException(ERR_NO_FREE_FRAMES,
            "Buffer pool exhausted: no free frames available");
    }

    /**
     * 创建淘汰失败异常
     *
     * @param reason 失败原因
     * @return BufferExhaustedException
     */
    public static BufferExhaustedException evictionFailed(String reason) {
        return new BufferExhaustedException(ERR_EVICTION_FAILED,
            "Failed to evict page from buffer pool: " + reason);
    }
}
