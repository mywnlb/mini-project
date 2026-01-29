package cn.zhangyis.minidb.storage.redo.buffer;

import java.nio.ByteBuffer;

/**
 * Redo Log Buffer 接口
 *
 * <p>定义 redo log 内存缓冲区的标准操作接口。
 * 支持两种实现：有锁模式 (LockBasedRedoLogBuffer) 和无锁模式 (LockFreeRedoLogBuffer)。</p>
 *
 * <h2>核心概念</h2>
 * <ul>
 *   <li><b>SN (Sequence Number)</b>: 纯 payload 字节偏移，不包含 log block 开销</li>
 *   <li><b>环形缓冲区</b>: 数据在 buffer 中循环存储</li>
 *   <li><b>4 个水位指针</b>: currentSn, bufReadyForWriteSn, writeSn, flushedSn</li>
 * </ul>
 *
 * <h2>Buffer 状态</h2>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────┐
 * │         Redo Log Buffer (循环使用)                               │
 * ├──────────────────────────────────────────────────────────────────┤
 * │flushedSn    writeSn    bufReadyForWriteSn    currentSn           │
 * │    ↓          ↓               ↓                 ↓                │
 * │    [已持久化] [已写文件]      [可写]         [预留]               │
 * └──────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>使用流程</h2>
 * <pre>
 * // MTR commit 时:
 * 1. long startSn = buffer.reserveSpace(size);     // 预留空间
 * 2. buffer.writeRecord(startSn, data);            // 写入数据
 * 3. buffer.markWriteComplete(startSn, size);      // 标记完成
 * 4. buffer.waitForFlush(startSn + size, timeout); // 等待持久化
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public interface RedoLogBufferApi {

    // ==================== 核心操作 ====================

    /**
     * 预留缓冲区空间
     *
     * <p>为即将写入的 redo record 预留空间。返回的 startSn 用于后续的
     * writeRecord 和 markWriteComplete 调用。</p>
     *
     * <p>如果空间不足，会阻塞等待直到有足够空间。</p>
     *
     * @param size 需要的字节数
     * @return 起始 SN
     * @throws InterruptedException 如果等待被中断
     * @throws IllegalArgumentException 如果 size 无效
     */
    long reserveSpace(int size) throws InterruptedException;

    /**
     * 写入 redo record 数据到 buffer
     *
     * <p>将数据复制到 buffer 中 startSn 对应的位置。
     * 支持环形缓冲区的自动回绕。</p>
     *
     * <p>注意：写入后必须调用 {@link #markWriteComplete} 标记完成。</p>
     *
     * @param startSn 起始 SN (由 reserveSpace 返回)
     * @param data    要写入的数据
     */
    void writeRecord(long startSn, byte[] data);

    /**
     * 标记写入完成
     *
     * <p>在 writeRecord 之后调用，通知 buffer 该区域的数据已准备好。
     * 对于无锁模式，这会更新 LinkBuf。</p>
     *
     * @param startSn 起始 SN
     * @param length  数据长度
     */
    void markWriteComplete(long startSn, int length);

    // ==================== 状态查询 ====================

    /**
     * 获取当前 SN (下一个要分配的位置)
     *
     * @return currentSn
     */
    long getCurrentSn();

    /**
     * 获取 buffer 中已准备好可写入文件的 SN 边界
     *
     * <p>对于有锁模式，等同于 writeReadySn。
     * 对于无锁模式，等同于 recentWritten.tail。</p>
     *
     * @return 连续完成的 SN 边界
     */
    long getBufReadyForWriteSn();

    /**
     * 获取已写入文件的 SN (尚未 fsync)
     *
     * @return writeSn
     */
    long getWriteSn();

    /**
     * 获取已 fsync 的 SN (持久化完成)
     *
     * @return flushedSn
     */
    long getFlushedSn();

    // ==================== 水位推进 (供后台线程调用) ====================

    /**
     * 推进 writeSn
     *
     * <p>由 LogWriter 在写入文件后调用。</p>
     *
     * @param newWriteSn 新的 writeSn
     */
    void advanceWriteSn(long newWriteSn);

    /**
     * 推进 flushedSn
     *
     * <p>由 LogFlusher 在 fsync 后调用。</p>
     *
     * @param newFlushedSn 新的 flushedSn
     */
    void advanceFlushedSn(long newFlushedSn);

    // ==================== 等待操作 ====================

    /**
     * 等待指定 SN 被 fsync 到磁盘
     *
     * @param targetSn     目标 SN
     * @param timeoutNanos 超时时间 (纳秒)，0 或负数表示无限等待
     * @return true 如果目标达成，false 如果超时
     * @throws InterruptedException 如果等待被中断
     */
    boolean waitForFlush(long targetSn, long timeoutNanos) throws InterruptedException;

    /**
     * 等待指定 SN 被 fsync 到磁盘 (无超时)
     *
     * @param targetSn 目标 SN
     * @throws InterruptedException 如果等待被中断
     */
    default void waitForFlush(long targetSn) throws InterruptedException {
        waitForFlush(targetSn, 0);
    }

    // ==================== 脏页注册 (无锁模式) ====================

    /**
     * 标记脏页注册完成
     *
     * <p>MTR 在将脏页加入 BufferPool FlushList 后调用。
     * 对于无锁模式，这会更新 recentClosed。
     * 对于有锁模式，此方法为空操作。</p>
     *
     * @param startSn 起始 SN (由 reserveSpace 返回)
     * @param length  数据长度
     */
    default void markPageDirtyComplete(long startSn, int length) {
        // 默认空实现，有锁模式不需要
    }

    /**
     * 获取脏页注册的低水位
     *
     * <p>用于 Checkpoint 计算安全的 checkpoint LSN。
     * 对于无锁模式，返回 recentClosed.tail。
     * 对于有锁模式，返回 flushedSn。</p>
     *
     * @return 脏页注册的低水位 SN
     */
    default long getDirtyPageLwm() {
        return getFlushedSn();
    }

    // ==================== 数据读取 (供 LogWriter 使用) ====================

    /**
     * 获取待写入的数据
     *
     * <p>返回 [writeSn, bufReadyForWriteSn) 范围的数据副本。
     * LogWriter 使用此方法获取数据进行写入。</p>
     *
     * @return 待写入的数据 (可能为空)
     */
    ByteBuffer getWriteReadyData();

    /**
     * 从 buffer 中读取指定位置的数据
     *
     * <p>用于 Partial Block 处理。读取 [sn, sn+length) 范围的数据。</p>
     *
     * @param sn     起始 SN
     * @param length 要读取的长度
     * @return 数据副本，如果超出范围则返回 null
     */
    byte[] getData(long sn, int length);

    // ==================== 配置信息 ====================

    /**
     * 获取 buffer 容量 (字节)
     *
     * @return 容量
     */
    int getCapacity();
}
