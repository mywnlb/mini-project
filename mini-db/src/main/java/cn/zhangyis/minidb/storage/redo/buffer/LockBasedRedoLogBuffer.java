package cn.zhangyis.minidb.storage.redo.buffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 有锁 Redo Log Buffer 实现 (Phase 1-2)
 *
 * <p>使用 ReentrantLock 实现串行化的 redo log 缓冲区。
 * 这是原始的实现方式，简单可靠，适合作为对比基准。</p>
 *
 * <h2>并发控制</h2>
 * <ul>
 *   <li>单把锁: ReentrantLock 保护所有状态</li>
 *   <li>串行提交: MTR 顺序获取锁，依次预留空间</li>
 *   <li>等待空间: 如果 buffer 满，阻塞直到有空间</li>
 *   <li>等待 fsync: waitForFlush() 等待 flushedSn >= commitSn</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 * @see RedoLogBufferApi
 */
public class LockBasedRedoLogBuffer implements RedoLogBufferApi {

    private static final Logger logger = LoggerFactory.getLogger(LockBasedRedoLogBuffer.class);

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

    /** 下一个要分配的 SN */
    private long currentSn;

    /** 数据已 ready 的 SN (可被 LogWriter 读取) */
    private long writeReadySn;

    /** 已写入文件的 SN (尚未 fsync) */
    private long writeSn;

    /** 已 fsync 的 SN (持久化完成) */
    private long flushedSn;

    // ==================== 构造函数 ====================

    /**
     * 创建 LockBasedRedoLogBuffer
     *
     * @param capacity Buffer 容量 (bytes)
     */
    public LockBasedRedoLogBuffer(int capacity) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("capacity must be power of 2: " + capacity);
        }

        this.buffer = new byte[capacity];
        this.capacity = capacity;
        this.lock = new ReentrantLock();
        this.spaceAvailable = lock.newCondition();
        this.dataFlushed = lock.newCondition();

        this.currentSn = 0;
        this.writeReadySn = 0;
        this.writeSn = 0;
        this.flushedSn = 0;

        logger.info("LockBasedRedoLogBuffer created: capacity={}MB", capacity / (1024 * 1024));
    }

    /**
     * 创建默认大小的 LockBasedRedoLogBuffer (16MB)
     */
    public LockBasedRedoLogBuffer() {
        this(DEFAULT_BUFFER_SIZE);
    }

    // ==================== RedoLogBufferApi 实现 ====================

    @Override
    public long reserveSpace(int size) throws InterruptedException {
        if (size <= 0 || size > capacity / 2) {
            throw new IllegalArgumentException("Invalid size: " + size + " (capacity=" + capacity + ")");
        }

        lock.lock();
        try {
            while (currentSn - flushedSn + size > capacity) {
                logger.debug("Buffer full, waiting for space: currentSn={}, flushedSn={}, need={}",
                        currentSn, flushedSn, size);
                spaceAvailable.await();
            }

            long startSn = currentSn;
            currentSn += size;

            logger.trace("Reserved space: startSn={}, size={}, newCurrentSn={}", startSn, size, currentSn);
            return startSn;

        } finally {
            lock.unlock();
        }
    }

    @Override
    public void writeRecord(long startSn, byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }

        lock.lock();
        try {
            if (startSn < writeReadySn || startSn >= currentSn) {
                throw new IllegalStateException(
                        String.format("Invalid startSn: %d (writeReadySn=%d, currentSn=%d)",
                                startSn, writeReadySn, currentSn));
            }

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

    @Override
    public void markWriteComplete(long startSn, int length) {
        // 有锁模式下，直接推进 writeReadySn
        advanceWriteReadySn(startSn + length);
    }

    /**
     * 推进 writeReadySn (兼容旧代码)
     *
     * @param endSn 数据结束位置
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

    @Override
    public long getCurrentSn() {
        lock.lock();
        try {
            return currentSn;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long getBufReadyForWriteSn() {
        return getWriteReadySn();
    }

    /**
     * 获取 writeReadySn (兼容旧代码)
     */
    public long getWriteReadySn() {
        lock.lock();
        try {
            return writeReadySn;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long getWriteSn() {
        lock.lock();
        try {
            return writeSn;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long getFlushedSn() {
        lock.lock();
        try {
            return flushedSn;
        } finally {
            lock.unlock();
        }
    }

    @Override
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

    @Override
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

            dataFlushed.signalAll();
            spaceAvailable.signalAll();

        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean waitForFlush(long targetSn, long timeoutNanos) throws InterruptedException {
        lock.lock();
        try {
            if (flushedSn >= targetSn) {
                return true;
            }

            if (timeoutNanos <= 0) {
                // 无超时
                while (flushedSn < targetSn) {
                    dataFlushed.await();
                }
                return true;
            } else {
                // 有超时
                long deadline = System.nanoTime() + timeoutNanos;
                while (flushedSn < targetSn) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        return false;
                    }
                    dataFlushed.awaitNanos(remaining);
                }
                return true;
            }

        } finally {
            lock.unlock();
        }
    }

    /**
     * 等待 fsync 完成 (兼容旧代码，无超时)
     *
     * @param commitSn 目标 SN
     * @throws InterruptedException 如果被中断
     */
    public void waitForFlush(long commitSn) throws InterruptedException {
        waitForFlush(commitSn, 0);
    }

    @Override
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

    @Override
    public byte[] getData(long sn, int length) {
        if (length <= 0) {
            return new byte[0];
        }

        lock.lock();
        try {
            // 检查数据是否还在环形缓冲区中
            // 数据有效条件:
            // 1. sn + length <= currentSn (数据已写入)
            // 2. currentSn - sn <= capacity (数据未被覆盖)
            // 注意: 不使用 flushedSn 检查，因为即使数据已刷盘，只要未被覆盖就仍可读取
            // 这对于 partial block 处理很重要 (LogWriter 需要读取前缀数据)
            if (sn + length > currentSn || currentSn - sn > capacity) {
                logger.warn("getData out of range: sn={}, length={}, currentSn={}, capacity={}",
                        sn, length, currentSn, capacity);
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

        } finally {
            lock.unlock();
        }
    }

    @Override
    public int getCapacity() {
        return capacity;
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取待 flush 的数据量 (bytes)
     */
    public long getPendingFlushSize() {
        lock.lock();
        try {
            return writeReadySn - flushedSn;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 检查数据是否可用 (在环形缓冲区中)
     */
    public boolean isDataAvailable(long sn, int length) {
        lock.lock();
        try {
            // 数据有效条件: 已写入且未被覆盖
            return sn + length <= currentSn && currentSn - sn <= capacity;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        lock.lock();
        try {
            return String.format("LockBasedRedoLogBuffer{capacity=%dMB, currentSn=%d, " +
                            "writeReadySn=%d, writeSn=%d, flushedSn=%d, occupied=%d}",
                    capacity / (1024 * 1024), currentSn, writeReadySn, writeSn, flushedSn,
                    currentSn - flushedSn);
        } finally {
            lock.unlock();
        }
    }
}
