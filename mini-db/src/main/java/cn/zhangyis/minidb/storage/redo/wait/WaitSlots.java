package cn.zhangyis.minidb.storage.redo.wait;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * 分片等待槽位 - 精准唤醒机制
 *
 * <p>将等待线程按 SN 分散到多个槽位，避免单一条件变量的"惊群效应"。
 * 当 flushedSn 推进时，只唤醒受影响槽位的线程。</p>
 *
 * <h2>设计原理</h2>
 * <pre>
 * 传统方式 (单一条件变量):
 *   flushedSn 推进 → signalAll() → 所有等待线程被唤醒
 *   问题: 大部分线程的 targetSn 尚未满足，白白唤醒
 *
 * 分片方式:
 *   flushedSn 推进 [100, 200] → 只唤醒 slot[100/G .. 200/G]
 *   其他槽位的线程继续等待，减少无效唤醒
 * </pre>
 *
 * <h2>槽位映射</h2>
 * <pre>
 * 示例: 8 个槽位，粒度 4096 (4KB)
 *
 * Thread-1 等待 sn=8192  → slot[(8192/4096) % 8] = slot[2]
 * Thread-2 等待 sn=16384 → slot[(16384/4096) % 8] = slot[4]
 * Thread-3 等待 sn=8200  → slot[(8200/4096) % 8] = slot[2] (同槽)
 *
 * flushedSn 从 0 推进到 10000:
 *   → 唤醒 slot[0], slot[1], slot[2] (覆盖 0-12287)
 *   → slot[3..7] 的线程继续等待
 * </pre>
 *
 * <h2>使用方式</h2>
 * <pre>
 * // 等待方 (MTR commit)
 * waitSlots.waitFor(targetSn, () -> buffer.getFlushedSn(), timeoutNanos);
 *
 * // 唤醒方 (LogFlushNotifier)
 * waitSlots.wakeupRange(oldFlushedSn, newFlushedSn);
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class WaitSlots {

    /**
     * 默认槽位数量
     */
    public static final int DEFAULT_SLOT_COUNT = 64;

    /**
     * 默认粒度 (4KB)
     */
    public static final long DEFAULT_GRANULARITY = 4096;

    /**
     * 槽位数量 (必须是 2 的幂)
     */
    private final int slotCount;

    /**
     * 槽位掩码 (slotCount - 1)
     */
    private final int slotMask;

    /**
     * 粒度 (每个槽位覆盖的 SN 范围)
     */
    private final long granularity;

    /**
     * 每个槽位的锁
     */
    private final ReentrantLock[] locks;

    /**
     * 每个槽位的条件变量
     */
    private final Condition[] conditions;

    /**
     * 每个槽位的等待线程计数 (用于优化：无等待者时跳过唤醒)
     */
    private final AtomicInteger[] waiterCounts;

    /**
     * 创建 WaitSlots
     *
     * @param slotCount   槽位数量 (必须是 2 的幂)
     * @param granularity 粒度 (每个槽位覆盖的 SN 范围)
     */
    public WaitSlots(int slotCount, long granularity) {
        if (slotCount <= 0 || (slotCount & (slotCount - 1)) != 0) {
            throw new IllegalArgumentException(
                "slotCount must be positive power of 2: " + slotCount);
        }
        if (granularity <= 0) {
            throw new IllegalArgumentException(
                "granularity must be positive: " + granularity);
        }

        this.slotCount = slotCount;
        this.slotMask = slotCount - 1;
        this.granularity = granularity;

        this.locks = new ReentrantLock[slotCount];
        this.conditions = new Condition[slotCount];
        this.waiterCounts = new AtomicInteger[slotCount];

        for (int i = 0; i < slotCount; i++) {
            locks[i] = new ReentrantLock();
            conditions[i] = locks[i].newCondition();
            waiterCounts[i] = new AtomicInteger(0);
        }
    }

    /**
     * 创建 WaitSlots (使用默认参数)
     */
    public WaitSlots() {
        this(DEFAULT_SLOT_COUNT, DEFAULT_GRANULARITY);
    }

    /**
     * 计算 SN 对应的槽位索引
     *
     * @param sn Sequence Number
     * @return 槽位索引
     */
    private int slotIndex(long sn) {
        return (int) ((sn / granularity) & slotMask);
    }

    /**
     * 等待直到目标 SN 完成
     *
     * <p>阻塞直到 currentValueSupplier 返回值 >= targetSn，或超时。</p>
     *
     * <h3>工作流程</h3>
     * <ol>
     *   <li>快速路径: 检查是否已完成</li>
     *   <li>计算目标槽位</li>
     *   <li>增加等待计数</li>
     *   <li>获取槽位锁，循环等待</li>
     *   <li>减少等待计数，返回</li>
     * </ol>
     *
     * @param targetSn             目标 SN
     * @param currentValueSupplier 获取当前进度的函数
     * @param timeoutNanos         超时时间 (纳秒)，0 或负数表示无限等待
     * @return true 如果目标达成，false 如果超时
     * @throws InterruptedException 如果等待被中断
     */
    public boolean waitFor(long targetSn, LongSupplier currentValueSupplier,
                          long timeoutNanos) throws InterruptedException {

        // 快速路径: 已经完成
        if (currentValueSupplier.getAsLong() >= targetSn) {
            return true;
        }

        int slot = slotIndex(targetSn);
        ReentrantLock lock = locks[slot];
        Condition condition = conditions[slot];

        // 增加等待计数
        waiterCounts[slot].incrementAndGet();

        lock.lock();
        try {
            long deadline = timeoutNanos > 0 ?
                System.nanoTime() + timeoutNanos : Long.MAX_VALUE;

            while (currentValueSupplier.getAsLong() < targetSn) {
                if (timeoutNanos > 0) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        return false;  // 超时
                    }
                    condition.awaitNanos(remaining);
                } else {
                    condition.await();
                }
            }
            return true;

        } finally {
            lock.unlock();
            waiterCounts[slot].decrementAndGet();
        }
    }

    /**
     * 等待直到目标 SN 完成 (无超时)
     *
     * @param targetSn             目标 SN
     * @param currentValueSupplier 获取当前进度的函数
     * @throws InterruptedException 如果等待被中断
     */
    public void waitFor(long targetSn, LongSupplier currentValueSupplier)
            throws InterruptedException {
        waitFor(targetSn, currentValueSupplier, 0);
    }

    /**
     * 唤醒指定范围内的所有等待线程
     *
     * <p>当进度从 fromSn 推进到 toSn 时调用，唤醒所有
     * targetSn 在 (fromSn, toSn] 范围内的线程。</p>
     *
     * <h3>优化</h3>
     * <ul>
     *   <li>跳过无等待者的槽位</li>
     *   <li>如果范围跨越所有槽位，使用 wakeupAll()</li>
     * </ul>
     *
     * @param fromSn 旧的进度值
     * @param toSn   新的进度值
     */
    public void wakeupRange(long fromSn, long toSn) {
        if (toSn <= fromSn) {
            return;
        }

        // 计算跨越的槽位数
        long slotSpan = (toSn - fromSn) / granularity + 1;

        if (slotSpan >= slotCount) {
            // 跨越所有槽位，全部唤醒
            wakeupAll();
            return;
        }

        // 精准唤醒受影响的槽位
        int fromSlot = slotIndex(fromSn);
        int toSlot = slotIndex(toSn);

        if (fromSlot <= toSlot) {
            // 不跨越边界
            for (int i = fromSlot; i <= toSlot; i++) {
                wakeupSlot(i);
            }
        } else {
            // 跨越边界 (环形)
            for (int i = fromSlot; i < slotCount; i++) {
                wakeupSlot(i);
            }
            for (int i = 0; i <= toSlot; i++) {
                wakeupSlot(i);
            }
        }
    }

    /**
     * 唤醒单个槽位的所有等待线程
     *
     * @param slot 槽位索引
     */
    private void wakeupSlot(int slot) {
        // 优化: 无等待者时跳过
        if (waiterCounts[slot].get() == 0) {
            return;
        }

        ReentrantLock lock = locks[slot];

        // 尝试获取锁，避免阻塞
        if (lock.tryLock()) {
            try {
                conditions[slot].signalAll();
            } finally {
                lock.unlock();
            }
        }
        // 如果获取锁失败，等待者会在下次条件检查时重新评估
    }

    /**
     * 唤醒所有槽位的等待线程
     */
    public void wakeupAll() {
        for (int i = 0; i < slotCount; i++) {
            wakeupSlot(i);
        }
    }

    /**
     * 获取当前等待线程总数
     *
     * @return 所有槽位的等待线程数之和
     */
    public int getTotalWaiters() {
        int total = 0;
        for (AtomicInteger count : waiterCounts) {
            total += count.get();
        }
        return total;
    }

    /**
     * 获取指定槽位的等待线程数
     *
     * @param slot 槽位索引
     * @return 等待线程数
     */
    public int getWaiterCount(int slot) {
        if (slot < 0 || slot >= slotCount) {
            throw new IndexOutOfBoundsException("slot: " + slot);
        }
        return waiterCounts[slot].get();
    }

    /**
     * 获取槽位数量
     *
     * @return 槽位数量
     */
    public int getSlotCount() {
        return slotCount;
    }

    /**
     * 获取粒度
     *
     * @return 每个槽位覆盖的 SN 范围
     */
    public long getGranularity() {
        return granularity;
    }

    @Override
    public String toString() {
        return String.format("WaitSlots{slotCount=%d, granularity=%d, totalWaiters=%d}",
            slotCount, granularity, getTotalWaiters());
    }
}
