package cn.zhangyis.minidb.storage.redo.buffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Redo Log Buffer (使用 SN，Phase 1-2)
 *
 * <p>内存中的环形缓冲区，用于暂存 redo records。这是 SN/LSN 分离的核心体现：
 * Buffer 内部使用 SN (纯 payload 偏移)，避免过早引入 log block 开销。</p>
 *
 * <h2>核心设计原则</h2>
 * <p><b>关键约束 4.0</b>: RedoLogBuffer 使用 SN，不使用 LSN。</p>
 * <ul>
 *   <li>Buffer 内部: currentSn, writeReadySn, writeSn, flushedSn (纯 SN)</li>
 *   <li>SN → LSN 转换: 仅在 LogBlockFormatter 中进行</li>
 *   <li>MTR 提交: 直接追加 payload，不关心 log block</li>
 * </ul>
 *
 * <h2>Buffer 状态 (4 个 SN 指针)</h2>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────┐
 * │         Redo Log Buffer (循环使用)                               │
 * ├──────────────────────────────────────────────────────────────────┤
 * │flushedSn    writeSn      writeReadySn      currentSn             │
 * │    ↓          ↓               ↓                ↓                 │
 * │    [已持久化] [已写文件]      [可写]         [预留]              │
 * │    ←─────────────────────────────────────────────→               │
 * │              可复用空间 ←                  → 占用空间             │
 * └──────────────────────────────────────────────────────────────────┘
 *
 * flushedSn: 已 fsync 到磁盘的 SN (txn 可以 commit)
 * writeSn: 已写入文件但未 fsync 的 SN (LogWriter 推进)
 * writeReadySn: 数据已 ready，可以被 LogWriter 读取
 * currentSn: 下一个要分配的 SN (MTR 预留空间)
 * </pre>
 *
 * <h2>操作流程 (Phase 1-2: 串行提交)</h2>
 * <pre>
 * 1. MTR Commit:
 *    - reserveSpace(size) → 分配 [startSn, endSn)
 *    - 将 redo records 写入 buffer[startSn % capacity]
 *    - writeRecord(startSn, data)
 *    - advanceWriteReadySn(endSn)
 *
 * 2. LogWriter Thread (后台):
 *    - 等待 writeReadySn > writeSn
 *    - data = getWriteReadyData(writeSn, writeReadySn)
 *    - LogBlockFormatter.format(data) → LSN 转换
 *    - FileChannel.write(blocks)
 *    - advanceWriteSn(newWriteSn)
 *
 * 3. LogFlusher Thread (后台):
 *    - 周期性或收到通知
 *    - FileChannel.force(true) → fsync
 *    - advanceFlushedSn(writeSn)
 *    - notifyAll() → 唤醒等待的 txn
 * </pre>
 *
 * <h2>并发控制 (Phase 1-2)</h2>
 * <ul>
 *   <li>单把锁: ReentrantLock 保护所有状态</li>
 *   <li>串行提交: MTR 顺序获取锁，依次预留空间</li>
 *   <li>等待空间: 如果 buffer 满，阻塞直到有空间</li>
 *   <li>等待 fsync: commitAndWait() 等待 flushedSn >= commitSn</li>
 * </ul>
 *
 * <h2>Phase 5 优化方向</h2>
 * <ul>
 *   <li>无锁预留: CAS 原子递增 currentSn</li>
 *   <li>并发写入: 多个 MTR 并行写入各自区域</li>
 *   <li>Group Commit: Leader/Follower 模式</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class RedoLogBuffer {

    private static final Logger logger = LoggerFactory.getLogger(RedoLogBuffer.class);

    // ==================== 配置常量 ====================

    /** Buffer 大小 (16MB，必须是 2 的幂次) */
    private static final int DEFAULT_BUFFER_SIZE = 16 * 1024 * 1024;

    // ==================== 核心字段 ====================

    /** 环形缓冲区 */
    private final byte[] buffer;

    /** Buffer 容量 */
    private final int capacity;

    /** 锁 (保护所有状态) */
    private final ReentrantLock lock;

    /** 条件变量: 有空间可用 */
    private final Condition spaceAvailable;

    /** 条件变量: 数据已 fsync */
    private final Condition dataFlushed;

    // ==================== 状态指针 (SN) ====================

    /**
     * 下一个要分配的 SN (MTR 预留起点)
     * <p>线程安全: 受 lock 保护 (Phase 1-2) 或 CAS (Phase 5)</p>
     */
    private long currentSn;

    /**
     * 数据已 ready 的 SN (可被 LogWriter 读取)
     * <p>推进: MTR 写完数据后调用 advanceWriteReadySn()</p>
     */
    private long writeReadySn;

    /**
     * 已写入文件的 SN (尚未 fsync)
     * <p>推进: LogWriter 调用 advanceWriteSn()</p>
     */
    private long writeSn;

    /**
     * 已 fsync 的 SN (持久化完成)
     * <p>推进: LogFlusher 调用 advanceFlushedSn()</p>
     */
    private long flushedSn;

    // ==================== 构造函数 ====================

    /**
     * 创建 RedoLogBuffer
     *
     * @param capacity Buffer 容量 (bytes)
     */
    public RedoLogBuffer(int capacity) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("capacity must be power of 2: " + capacity);
        }

        this.buffer = new byte[capacity];
        this.capacity = capacity;
        this.lock = new ReentrantLock();
        this.spaceAvailable = lock.newCondition();
        this.dataFlushed = lock.newCondition();

        // 初始状态: 所有指针为 0
        this.currentSn = 0;
        this.writeReadySn = 0;
        this.writeSn = 0;
        this.flushedSn = 0;

        logger.info("RedoLogBuffer created: capacity={}MB", capacity / (1024 * 1024));
    }

    /**
     * 创建默认大小的 RedoLogBuffer (16MB)
     */
    public RedoLogBuffer() {
        this(DEFAULT_BUFFER_SIZE);
    }

    // ==================== 核心方法: 预留空间 ====================

    /**
     * 预留缓冲区空间 (Phase 1-2: 串行)
     *
     * <p>MTR 在提交时调用，分配 [startSn, startSn+size) 范围。
     * 如果空间不足，阻塞等待。</p>
     *
     * <h3>空间计算</h3>
     * <pre>
     * 已占用 = currentSn - flushedSn
     * 可用 = capacity - 已占用
     * 如果 size > 可用 → 等待 LogFlusher 推进 flushedSn
     * </pre>
     *
     * @param size 需要的字节数
     * @return 起始 SN
     * @throws InterruptedException 如果等待被中断
     */
    public long reserveSpace(int size) throws InterruptedException {
        if (size <= 0 || size > capacity / 2) {
            throw new IllegalArgumentException("Invalid size: " + size + " (capacity=" + capacity + ")");
        }

        lock.lock();
        try {
            // 等待空间可用
            while (currentSn - flushedSn + size > capacity) {
                logger.debug("Buffer full, waiting for space: currentSn={}, flushedSn={}, need={}",
                        currentSn, flushedSn, size);
                spaceAvailable.await();
            }

            // 分配空间
            long startSn = currentSn;
            currentSn += size;

            logger.trace("Reserved space: startSn={}, size={}, newCurrentSn={}", startSn, size, currentSn);
            return startSn;

        } finally {
            lock.unlock();
        }
    }

    // ==================== 核心方法: 写入数据 ====================

    /**
     * 写入 redo record 数据到 buffer
     *
     * <p>将 data 复制到 buffer[startSn % capacity] 位置。
     * 支持环形缓冲区的自动回绕。</p>
     *
     * @param startSn 起始 SN (由 reserveSpace 返回)
     * @param data 要写入的数据
     */
    public void writeRecord(long startSn, byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }

        lock.lock();
        try {
            // 验证: startSn 应该在 [writeReadySn, currentSn) 范围内
            if (startSn < writeReadySn || startSn >= currentSn) {
                throw new IllegalStateException(
                        String.format("Invalid startSn: %d (writeReadySn=%d, currentSn=%d)",
                                startSn, writeReadySn, currentSn));
            }

            // 写入数据 (支持环形回绕)
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

        } finally {
            lock.unlock();
        }
    }

    // ==================== 核心方法: 推进 writeReadySn ====================

    /**
     * 标记数据已 ready (可被 LogWriter 读取)
     *
     * <p>MTR 写完数据后调用，推进 writeReadySn。
     * Phase 1-2: 串行提交，endSn == writeReadySn + written_size。</p>
     *
     * @param endSn 数据结束位置 (exclusive)
     */
    public void advanceWriteReadySn(long endSn) {
        lock.lock();
        try {
            if (endSn <= writeReadySn) {
                logger.warn("Attempt to advance writeReadySn backwards: current={}, new={}",
                        writeReadySn, endSn);
                return;
            }

            if (endSn > currentSn) {
                throw new IllegalStateException(
                        String.format("endSn exceeds currentSn: %d > %d", endSn, currentSn));
            }

            writeReadySn = endSn;
            logger.trace("Advanced writeReadySn: {}", writeReadySn);

        } finally {
            lock.unlock();
        }
    }

    // ==================== 核心方法: 获取待写入数据 ====================

    /**
     * 获取待写入的数据 (供 LogWriter 使用)
     *
     * <p>返回 [writeSn, writeReadySn) 范围的数据副本。
     * LogWriter 将此数据交给 LogBlockFormatter 转换为 log blocks。</p>
     *
     * @return 待写入的数据 (可能为空)
     */
    public ByteBuffer getWriteReadyData() {
        lock.lock();
        try {
            long availableSize = writeReadySn - writeSn;
            if (availableSize <= 0) {
                return ByteBuffer.allocate(0);
            }

            if (availableSize > Integer.MAX_VALUE) {
                throw new IllegalStateException("Available size too large: " + availableSize);
            }

            int size = (int) availableSize;
            byte[] data = new byte[size];

            // 复制数据 (支持环形回绕)
            int offset = (int) (writeSn % capacity);
            int remaining = size;
            int destPos = 0;

            while (remaining > 0) {
                int chunkSize = Math.min(remaining, capacity - offset);
                System.arraycopy(buffer, offset, data, destPos, chunkSize);

                destPos += chunkSize;
                remaining -= chunkSize;
                offset = (offset + chunkSize) % capacity;
            }

            logger.trace("Got write-ready data: startSn={}, size={}", writeSn, size);
            return ByteBuffer.wrap(data);

        } finally {
            lock.unlock();
        }
    }

    // ==================== 核心方法: 推进 writeSn ====================

    /**
     * 推进 writeSn (LogWriter 写文件后调用)
     *
     * @param newWriteSn 新的 writeSn
     */
    public void advanceWriteSn(long newWriteSn) {
        lock.lock();
        try {
            if (newWriteSn <= writeSn) {
                logger.warn("Attempt to advance writeSn backwards: current={}, new={}",
                        writeSn, newWriteSn);
                return;
            }

            if (newWriteSn > writeReadySn) {
                throw new IllegalStateException(
                        String.format("newWriteSn exceeds writeReadySn: %d > %d",
                                newWriteSn, writeReadySn));
            }

            writeSn = newWriteSn;
            logger.trace("Advanced writeSn: {}", writeSn);

        } finally {
            lock.unlock();
        }
    }

    // ==================== 核心方法: 推进 flushedSn ====================

    /**
     * 推进 flushedSn (LogFlusher fsync 后调用)
     *
     * <p>更新 flushedSn，唤醒所有等待 commit 的线程和等待空间的线程。</p>
     *
     * <h3>Phase 1-2 的问题 (Thundering Herd)</h3>
     * <p>使用 signalAll() 会唤醒所有等待线程，即使大部分线程的 commit_lsn 尚未 flush。
     * Phase 5 优化: 精确唤醒 (commitSn <= flushedSn 的线程)。</p>
     *
     * @param newFlushedSn 新的 flushedSn
     */
    public void advanceFlushedSn(long newFlushedSn) {
        lock.lock();
        try {
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

            flushedSn = newFlushedSn;
            logger.debug("Advanced flushedSn: {}", flushedSn);

            // 唤醒所有等待线程 (Phase 1-2: 简单但低效)
            dataFlushed.signalAll();
            spaceAvailable.signalAll();

        } finally {
            lock.unlock();
        }
    }

    // ==================== 等待持久化 ====================

    /**
     * 等待指定 SN 被 fsync (用于 innodb_flush_log_at_trx_commit=1)
     *
     * <p>阻塞直到 flushedSn >= commitSn，即 redo 已持久化。</p>
     *
     * @param commitSn 要等待的 SN
     * @throws InterruptedException 如果等待被中断
     */
    public void waitForFlush(long commitSn) throws InterruptedException {
        lock.lock();
        try {
            while (flushedSn < commitSn) {
                logger.trace("Waiting for flush: commitSn={}, flushedSn={}", commitSn, flushedSn);
                dataFlushed.await();
            }
            logger.trace("Flush completed: commitSn={}, flushedSn={}", commitSn, flushedSn);

        } finally {
            lock.unlock();
        }
    }

    // ==================== 查询方法 ====================

    /**
     * 获取当前 SN (下一个要分配的位置)
     *
     * @return currentSn
     */
    public long getCurrentSn() {
        lock.lock();
        try {
            return currentSn;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取 flushedSn (已持久化的位置)
     *
     * @return flushedSn
     */
    public long getFlushedSn() {
        lock.lock();
        try {
            return flushedSn;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取待 flush 的数据量 (bytes)
     *
     * @return writeReadySn - flushedSn
     */
    public long getPendingFlushSize() {
        lock.lock();
        try {
            return writeReadySn - flushedSn;
        } finally {
            lock.unlock();
        }
    }

    // ==================== 调试方法 ====================

    @Override
    public String toString() {
        lock.lock();
        try {
            return String.format("RedoLogBuffer{capacity=%dMB, currentSn=%d, writeReadySn=%d, writeSn=%d, flushedSn=%d, occupied=%d}",
                    capacity / (1024 * 1024), currentSn, writeReadySn, writeSn, flushedSn,
                    currentSn - flushedSn);
        } finally {
            lock.unlock();
        }
    }
}
