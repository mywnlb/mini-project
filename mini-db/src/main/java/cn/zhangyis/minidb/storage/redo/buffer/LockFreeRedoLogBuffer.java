package cn.zhangyis.minidb.storage.redo.buffer;

import cn.zhangyis.minidb.storage.redo.lifecycle.BackgroundService;
import cn.zhangyis.minidb.storage.redo.wait.WaitSlots;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 无锁 Redo Log Buffer 实现 (MySQL 8.0 风格)
 *
 * <p>使用 CAS 原子操作实现并发写入，配合 LinkBuf 追踪连续性。
 * 多个 MTR 可以同时预留空间并并发写入各自的区域。</p>
 *
 * <h2>核心设计</h2>
 * <ul>
 *   <li><b>无锁空间预留</b>: 使用 AtomicLong.getAndAdd() 原子递增 currentSn</li>
 *   <li><b>并发写入</b>: 多个 MTR 并行写入各自预留的区域</li>
 *   <li><b>LinkBuf 追踪</b>: recentWritten 追踪写入连续性，recentClosed 追踪脏页注册</li>
 *   <li><b>分片等待</b>: WaitSlots 实现精准唤醒</li>
 *   <li><b>三重背压</b>: 防止环形缓冲区覆盖和 LinkBuf 槽位覆盖</li>
 *   <li><b>主动唤醒</b>: 等待时主动唤醒后台服务加速处理</li>
 * </ul>
 *
 * <h2>背压机制 (MySQL 8.0 风格)</h2>
 * <pre>
 * reserveSpace() 中的三重检查:
 *
 * 1. log_buffer_full: currentSn - flushedSn > capacity
 *    → 主动唤醒 LogWriter + LogFlusher，等待 flushedSn 推进
 *
 * 2. log_recent_written_wait: currentSn - recentWritten.tail > linkBufCoverage
 *    → 主动唤醒 LogWriter，等待 recentWritten.tail 推进
 *
 * 3. log_recent_closed_wait: currentSn - recentClosed.tail > linkBufCoverage
 *    → 主动唤醒 LogCloser，等待 recentClosed.tail 推进
 * </pre>
 *
 * <h2>并发模型</h2>
 * <pre>
 * MTR-1: [reserve:0-100]──[write]──[markComplete]──[wait]
 * MTR-2:    [reserve:100-180]──[write]──[markComplete]──[wait]
 * MTR-3:       [reserve:180-250]──[write]──[markComplete]──[wait]
 *                              ↓ 并发执行
 *
 * recentWritten 状态:
 *   MTR-2 先完成: slots[1]=80, tail=0 (有空洞)
 *   MTR-1 后完成: slots[0]=100, tail 推进到连续边界
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 * @see RedoLogBufferApi
 * @see LinkBuf
 * @see WaitSlots
 */
public class LockFreeRedoLogBuffer implements RedoLogBufferApi {

    private static final Logger logger = LoggerFactory.getLogger(LockFreeRedoLogBuffer.class);

    /** 默认等待超时时间 (100ms) */
    private static final long DEFAULT_WAIT_TIMEOUT_NANOS = 100_000_000L;

    // ==================== 核心字段 ====================

    /** 环形缓冲区 */
    private final byte[] buffer;

    /** Buffer 容量 */
    private final int capacity;

    // ==================== SN 指针 ====================

    /** 下一个要分配的 SN (CAS 原子递增) */
    private final AtomicLong currentSn = new AtomicLong(0);

    /** 已写入文件的 SN (由 LogWriter 更新) */
    private volatile long writeSn = 0;

    /** 已 fsync 的 SN (由 LogFlusher 更新) */
    private volatile long flushedSn = 0;

    // ==================== LinkBuf ====================

    /** 追踪 buffer 写入连续性 */
    private final LinkBuf recentWritten;

    /** 追踪脏页注册连续性 */
    private final LinkBuf recentClosed;

    /** LinkBuf 覆盖范围 (用于背压检查) */
    private final long linkBufCoverage;

    // ==================== WaitSlots ====================

    /** write 等待槽位 (等待 writeSn 推进) */
    private final WaitSlots writeWaitSlots;

    /** flush 等待槽位 (等待 flushedSn 推进) */
    private final WaitSlots flushWaitSlots;

    /** recentWritten 等待槽位 (等待 recentWritten.tail 推进) */
    private final WaitSlots recentWrittenWaitSlots;

    /** recentClosed 等待槽位 (等待 recentClosed.tail 推进) */
    private final WaitSlots recentClosedWaitSlots;

    // ==================== 后台服务引用 (用于主动唤醒) ====================

    /** LogWriter 服务 (推进 recentWritten.tail 和 writeSn) */
    private volatile BackgroundService logWriter;

    /** LogFlusher 服务 (推进 flushedSn) */
    private volatile BackgroundService logFlusher;

    /** LogCloser 服务 (推进 recentClosed.tail) */
    private volatile BackgroundService logCloser;

    // ==================== 统计信息 ====================

    /** log_buffer_full 等待次数 */
    private volatile long bufferFullWaitCount = 0;

    /** log_recent_written_wait 等待次数 */
    private volatile long recentWrittenWaitCount = 0;

    /** log_recent_closed_wait 等待次数 */
    private volatile long recentClosedWaitCount = 0;

    // ==================== 构造函数 ====================

    /**
     * 创建 LockFreeRedoLogBuffer
     *
     * @param capacity            Buffer 容量 (bytes，必须是 2 的幂)
     * @param linkBufCapacity     LinkBuf 槽位数量 (必须是 2 的幂)
     * @param linkBufGranularity  LinkBuf 粒度
     * @param waitSlotCount       WaitSlots 槽位数量
     * @param waitSlotGranularity WaitSlots 粒度
     */
    public LockFreeRedoLogBuffer(int capacity, int linkBufCapacity, int linkBufGranularity,
                                  int waitSlotCount, long waitSlotGranularity) {
        // 验证参数
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("capacity must be power of 2: " + capacity);
        }

        this.capacity = capacity;
        this.buffer = new byte[capacity];

        // 创建 LinkBuf
        this.recentWritten = new LinkBuf(linkBufCapacity, linkBufGranularity);
        this.recentClosed = new LinkBuf(linkBufCapacity, linkBufGranularity);
        this.linkBufCoverage = recentWritten.getCoverageRange();

        // 创建 WaitSlots
        this.writeWaitSlots = new WaitSlots(waitSlotCount, waitSlotGranularity);
        this.flushWaitSlots = new WaitSlots(waitSlotCount, waitSlotGranularity);
        this.recentWrittenWaitSlots = new WaitSlots(waitSlotCount, waitSlotGranularity);
        this.recentClosedWaitSlots = new WaitSlots(waitSlotCount, waitSlotGranularity);

        logger.info("LockFreeRedoLogBuffer created: capacity={}MB, linkBufCapacity={}, " +
                        "linkBufGranularity={}, linkBufCoverage={}, waitSlotCount={}, waitSlotGranularity={}",
                capacity / (1024 * 1024), linkBufCapacity, linkBufGranularity,
                linkBufCoverage, waitSlotCount, waitSlotGranularity);
    }

    /**
     * 创建 LockFreeRedoLogBuffer (使用默认参数)
     *
     * @param capacity Buffer 容量
     */
    public LockFreeRedoLogBuffer(int capacity) {
        this(capacity,
                capacity / 8 / LinkBuf.DEFAULT_GRANULARITY,  // linkBufCapacity
                LinkBuf.DEFAULT_GRANULARITY,                  // linkBufGranularity
                WaitSlots.DEFAULT_SLOT_COUNT,                 // waitSlotCount
                WaitSlots.DEFAULT_GRANULARITY);               // waitSlotGranularity
    }

    // ==================== RedoLogBufferApi 实现 ====================

    @Override
    public long reserveSpace(int size) throws InterruptedException {
        if (size <= 0 || size > capacity / 2) {
            throw new IllegalArgumentException("Invalid size: " + size + " (capacity=" + capacity + ")");
        }

        // ===== Step 1: 无锁原子预留空间 =====
        long startSn = currentSn.getAndAdd(size);
        long endSn = startSn + size;

        // ===== Step 2: 检查 1 - Log Buffer 环形空间 (log_buffer_full) =====
        // 确保新写入不会覆盖未刷盘的旧数据
        while (endSn - flushedSn > capacity) {
            bufferFullWaitCount++;
            long targetFlushedSn = endSn - capacity;
            logger.debug("log_buffer_full: endSn={}, flushedSn={}, need flushedSn >= {}",
                    endSn, flushedSn, targetFlushedSn);

            // 主动唤醒 LogWriter 和 LogFlusher 加速处理
            wakeupLogWriter();
            wakeupLogFlusher();

            // 等待 flushedSn 推进
            flushWaitSlots.waitFor(targetFlushedSn, this::getFlushedSn, DEFAULT_WAIT_TIMEOUT_NANOS);
        }

        // ===== Step 3: 检查 2 - recentWritten LinkBuf 容量 (log_recent_written_wait) =====
        // 确保 LinkBuf 槽位不会被覆盖
        long recentWrittenTail = recentWritten.getTail();
        while (endSn - recentWrittenTail > linkBufCoverage) {
            recentWrittenWaitCount++;
            long targetTail = endSn - linkBufCoverage;
            logger.debug("log_recent_written_wait: endSn={}, recentWritten.tail={}, need tail >= {}",
                    endSn, recentWrittenTail, targetTail);

            // 主动唤醒 LogWriter 加速处理
            wakeupLogWriter();

            // 等待 recentWritten.tail 推进
            recentWrittenWaitSlots.waitFor(targetTail, recentWritten::getTail, DEFAULT_WAIT_TIMEOUT_NANOS);
            recentWrittenTail = recentWritten.getTail();
        }

        // ===== Step 4: 检查 3 - recentClosed LinkBuf 容量 (log_recent_closed_wait) =====
        // 确保脏页追踪的 LinkBuf 槽位不会被覆盖
        long recentClosedTail = recentClosed.getTail();
        while (endSn - recentClosedTail > linkBufCoverage) {
            recentClosedWaitCount++;
            long targetTail = endSn - linkBufCoverage;
            logger.debug("log_recent_closed_wait: endSn={}, recentClosed.tail={}, need tail >= {}",
                    endSn, recentClosedTail, targetTail);

            // 主动唤醒 LogCloser 加速处理
            wakeupLogCloser();

            // 等待 recentClosed.tail 推进
            recentClosedWaitSlots.waitFor(targetTail, recentClosed::getTail, DEFAULT_WAIT_TIMEOUT_NANOS);
            recentClosedTail = recentClosed.getTail();
        }

        logger.trace("Reserved space: startSn={}, size={}", startSn, size);
        return startSn;
    }

    @Override
    public void writeRecord(long startSn, byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }

        // 无锁并发写入 (多个 MTR 写入各自的区域)
        int offset = (int) (startSn % capacity);
        int remaining = data.length;
        int srcPos = 0;

        while (remaining > 0) {
            int chunkSize = Math.min(remaining, capacity - offset);
            System.arraycopy(data, srcPos, buffer, offset, chunkSize);

            srcPos += chunkSize;
            remaining -= chunkSize;
            offset = (offset + chunkSize) % capacity;
        }

        logger.trace("Wrote data: startSn={}, length={}", startSn, data.length);
    }

    @Override
    public void markWriteComplete(long startSn, int length) {
        // 在 recentWritten 中标记完成
        recentWritten.addLink(startSn, length);
        logger.trace("Marked write complete: startSn={}, length={}", startSn, length);
    }

    @Override
    public long getCurrentSn() {
        return currentSn.get();
    }

    @Override
    public long getBufReadyForWriteSn() {
        // 返回 recentWritten 的 tail (连续完成的边界)
        return recentWritten.getTail();
    }

    @Override
    public long getWriteSn() {
        return writeSn;
    }

    @Override
    public long getFlushedSn() {
        return flushedSn;
    }

    @Override
    public void advanceWriteSn(long newWriteSn) {
        if (newWriteSn <= writeSn) {
            logger.warn("Attempt to advance writeSn backwards: current={}, new={}",
                    writeSn, newWriteSn);
            return;
        }

        long bufReady = getBufReadyForWriteSn();
        if (newWriteSn > bufReady) {
            throw new IllegalStateException(
                    String.format("newWriteSn exceeds bufReadyForWriteSn: %d > %d",
                            newWriteSn, bufReady));
        }

        long oldWriteSn = writeSn;
        writeSn = newWriteSn;
        logger.trace("Advanced writeSn: {} -> {}", oldWriteSn, writeSn);
    }

    @Override
    public void advanceFlushedSn(long newFlushedSn) {
        if (newFlushedSn <= flushedSn) {
            logger.warn("Attempt to advance flushedSn backwards: current={}, new={}",
                    flushedSn, newFlushedSn);
            return;
        }

        if (newFlushedSn > writeSn) {
            throw new IllegalStateException(
                    String.format("newFlushedSn exceeds writeSn: %d > %d",
                            newFlushedSn, writeSn));
        }

        long oldFlushedSn = flushedSn;
        flushedSn = newFlushedSn;
        logger.debug("Advanced flushedSn: {} -> {}", oldFlushedSn, flushedSn);
    }

    @Override
    public boolean waitForFlush(long targetSn, long timeoutNanos) throws InterruptedException {
        // 快速路径
        if (flushedSn >= targetSn) {
            return true;
        }

        // 使用分片等待槽位
        return flushWaitSlots.waitFor(targetSn, this::getFlushedSn, timeoutNanos);
    }

    @Override
    public void markPageDirtyComplete(long startSn, int length) {
        // 在 recentClosed 中标记完成 (与 recentWritten 相同的方式)
        recentClosed.addLink(startSn, length);
        logger.trace("Marked page dirty complete: startSn={}, length={}", startSn, length);
    }

    @Override
    public long getDirtyPageLwm() {
        // 返回 recentClosed 的 tail
        return recentClosed.getTail();
    }

    @Override
    public ByteBuffer getWriteReadyData() {
        long bufReady = getBufReadyForWriteSn();
        long currentWriteSn = writeSn;

        long availableSize = bufReady - currentWriteSn;
        if (availableSize <= 0) {
            return ByteBuffer.allocate(0);
        }

        if (availableSize > Integer.MAX_VALUE) {
            throw new IllegalStateException("Available size too large: " + availableSize);
        }

        int size = (int) availableSize;
        byte[] data = new byte[size];

        int offset = (int) (currentWriteSn % capacity);
        int remaining = size;
        int destPos = 0;

        while (remaining > 0) {
            int chunkSize = Math.min(remaining, capacity - offset);
            System.arraycopy(buffer, offset, data, destPos, chunkSize);

            destPos += chunkSize;
            remaining -= chunkSize;
            offset = (offset + chunkSize) % capacity;
        }

        logger.trace("Got write-ready data: startSn={}, size={}", currentWriteSn, size);
        return ByteBuffer.wrap(data);
    }

    @Override
    public byte[] getData(long sn, int length) {
        if (length <= 0) {
            return new byte[0];
        }

        // 检查数据是否还在环形缓冲区中
        // 数据有效条件:
        // 1. sn + length <= currentSn (数据已写入)
        // 2. currentSn - sn <= capacity (数据未被覆盖)
        // 注意: 不使用 flushedSn 检查，因为即使数据已刷盘，只要未被覆盖就仍可读取
        // 这对于 partial block 处理很重要 (LogWriter 需要读取前缀数据)
        long currSn = currentSn.get();
        if (sn + length > currSn || currSn - sn > capacity) {
            logger.warn("getData out of range: sn={}, length={}, currentSn={}, capacity={}",
                    sn, length, currSn, capacity);
            return null;
        }

        byte[] data = new byte[length];

        int offset = (int) (sn % capacity);
        int remaining = length;
        int destPos = 0;

        while (remaining > 0) {
            int chunkSize = Math.min(remaining, capacity - offset);
            System.arraycopy(buffer, offset, data, destPos, chunkSize);

            destPos += chunkSize;
            remaining -= chunkSize;
            offset = (offset + chunkSize) % capacity;
        }

        logger.trace("getData: sn={}, length={}", sn, length);
        return data;
    }

    @Override
    public int getCapacity() {
        return capacity;
    }

    // ==================== LinkBuf 访问 (供后台线程使用) ====================

    /**
     * 获取 recentWritten LinkBuf
     *
     * <p>供 LogWriter 调用 advanceTail() 推进连续边界。</p>
     *
     * @return recentWritten
     */
    public LinkBuf getRecentWritten() {
        return recentWritten;
    }

    /**
     * 获取 recentClosed LinkBuf
     *
     * <p>供 LogCloser 调用 advanceTail() 推进脏页注册边界。</p>
     *
     * @return recentClosed
     */
    public LinkBuf getRecentClosed() {
        return recentClosed;
    }

    // ==================== WaitSlots 访问 (供 Notifier 使用) ====================

    /**
     * 获取 write 等待槽位
     *
     * <p>供 LogWriteNotifier 唤醒等待线程。</p>
     *
     * @return writeWaitSlots
     */
    public WaitSlots getWriteWaitSlots() {
        return writeWaitSlots;
    }

    /**
     * 获取 flush 等待槽位
     *
     * <p>供 LogFlushNotifier 唤醒等待线程。</p>
     *
     * @return flushWaitSlots
     */
    public WaitSlots getFlushWaitSlots() {
        return flushWaitSlots;
    }

    /**
     * 获取 recentWritten 等待槽位
     *
     * <p>供唤醒等待 recentWritten.tail 推进的线程。</p>
     *
     * @return recentWrittenWaitSlots
     */
    public WaitSlots getRecentWrittenWaitSlots() {
        return recentWrittenWaitSlots;
    }

    /**
     * 获取 recentClosed 等待槽位
     *
     * <p>供唤醒等待 recentClosed.tail 推进的线程。</p>
     *
     * @return recentClosedWaitSlots
     */
    public WaitSlots getRecentClosedWaitSlots() {
        return recentClosedWaitSlots;
    }

    // ==================== 辅助方法 ====================

    /**
     * 通知 write 等待者
     *
     * <p>由 LogWriteNotifier 调用。</p>
     *
     * @param fromSn 旧的 writeSn
     * @param toSn   新的 writeSn
     */
    public void notifyWriteProgress(long fromSn, long toSn) {
        writeWaitSlots.wakeupRange(fromSn, toSn);
    }

    /**
     * 通知 flush 等待者
     *
     * <p>由 LogFlushNotifier 调用。</p>
     *
     * @param fromSn 旧的 flushedSn
     * @param toSn   新的 flushedSn
     */
    public void notifyFlushProgress(long fromSn, long toSn) {
        flushWaitSlots.wakeupRange(fromSn, toSn);
    }

    /**
     * 通知 recentWritten 等待者
     *
     * <p>由 LogWriter 在推进 recentWritten.tail 后调用。</p>
     *
     * @param fromTail 旧的 tail
     * @param toTail   新的 tail
     */
    public void notifyRecentWrittenProgress(long fromTail, long toTail) {
        recentWrittenWaitSlots.wakeupRange(fromTail, toTail);
    }

    /**
     * 通知 recentClosed 等待者
     *
     * <p>由 LogCloser 在推进 recentClosed.tail 后调用。</p>
     *
     * @param fromTail 旧的 tail
     * @param toTail   新的 tail
     */
    public void notifyRecentClosedProgress(long fromTail, long toTail) {
        recentClosedWaitSlots.wakeupRange(fromTail, toTail);
    }

    // ==================== 统计信息 ====================

    /**
     * 获取 LinkBuf 覆盖范围
     */
    public long getLinkBufCoverage() {
        return linkBufCoverage;
    }

    /**
     * 获取 log_buffer_full 等待次数
     */
    public long getBufferFullWaitCount() {
        return bufferFullWaitCount;
    }

    /**
     * 获取 log_recent_written_wait 等待次数
     */
    public long getRecentWrittenWaitCount() {
        return recentWrittenWaitCount;
    }

    /**
     * 获取 log_recent_closed_wait 等待次数
     */
    public long getRecentClosedWaitCount() {
        return recentClosedWaitCount;
    }

    // ==================== 后台服务注册 (用于主动唤醒) ====================

    /**
     * 注册 LogWriter 服务
     *
     * <p>在 log_buffer_full 和 log_recent_written_wait 时主动唤醒。</p>
     *
     * @param logWriter LogWriter 服务
     */
    public void setLogWriter(BackgroundService logWriter) {
        this.logWriter = logWriter;
    }

    /**
     * 注册 LogFlusher 服务
     *
     * <p>在 log_buffer_full 时主动唤醒。</p>
     *
     * @param logFlusher LogFlusher 服务
     */
    public void setLogFlusher(BackgroundService logFlusher) {
        this.logFlusher = logFlusher;
    }

    /**
     * 注册 LogCloser 服务
     *
     * <p>在 log_recent_closed_wait 时主动唤醒。</p>
     *
     * @param logCloser LogCloser 服务
     */
    public void setLogCloser(BackgroundService logCloser) {
        this.logCloser = logCloser;
    }

    /**
     * 唤醒 LogWriter
     */
    private void wakeupLogWriter() {
        BackgroundService writer = this.logWriter;
        if (writer != null) {
            writer.wakeup();
        }
    }

    /**
     * 唤醒 LogFlusher
     */
    private void wakeupLogFlusher() {
        BackgroundService flusher = this.logFlusher;
        if (flusher != null) {
            flusher.wakeup();
        }
    }

    /**
     * 唤醒 LogCloser
     */
    private void wakeupLogCloser() {
        BackgroundService closer = this.logCloser;
        if (closer != null) {
            closer.wakeup();
        }
    }

    @Override
    public String toString() {
        return String.format("LockFreeRedoLogBuffer{capacity=%dMB, linkBufCoverage=%d, currentSn=%d, " +
                        "bufReadyForWriteSn=%d, writeSn=%d, flushedSn=%d, " +
                        "recentWrittenTail=%d, recentClosedTail=%d, " +
                        "bufferFullWaits=%d, recentWrittenWaits=%d, recentClosedWaits=%d}",
                capacity / (1024 * 1024), linkBufCoverage, currentSn.get(),
                getBufReadyForWriteSn(), writeSn, flushedSn,
                recentWritten.getTail(), recentClosed.getTail(),
                bufferFullWaitCount, recentWrittenWaitCount, recentClosedWaitCount);
    }
}
