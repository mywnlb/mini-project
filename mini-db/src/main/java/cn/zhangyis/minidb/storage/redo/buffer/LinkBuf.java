package cn.zhangyis.minidb.storage.redo.buffer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Link Buffer - 无锁并发写入连续性追踪器
 *
 * <p>LinkBuf 是 MySQL 8.0 无锁 redo log 的核心数据结构，用于追踪并发写入的连续边界。
 * 当多个 MTR 并发写入 buffer 时，可能出现乱序完成的情况（空洞）。
 * LinkBuf 通过环形数组记录每个位置的完成状态，并维护连续完成的边界 (tail)。</p>
 *
 * <h2>工作原理</h2>
 * <pre>
 * 场景：3 个 MTR 并发写入
 *   MTR-1: [0, 100)   先预留，后完成
 *   MTR-2: [100, 180) 先预留，先完成
 *   MTR-3: [180, 250) 先预留，中间完成
 *
 * 时间线：
 *   t1: MTR-2 完成 → slots[1]=180  → tail=0 (slot[0]还是0，有空洞)
 *   t2: MTR-3 完成 → slots[2]=250  → tail=0 (仍有空洞)
 *   t3: MTR-1 完成 → slots[0]=100  → tail 可推进到 250
 *
 * slots 状态变化 (存储 endSn):
 *   初始:    [0  ][0  ][0  ]...   tail=0
 *   t1 后:   [0  ][180][0  ]...   tail=0
 *   t2 后:   [0  ][180][250]...   tail=0
 *   t3 后:   [100][180][250]...   tail=250 (连续了)
 * </pre>
 *
 * <h2>设计要点</h2>
 * <ul>
 *   <li><b>粒度 (granularity)</b>: SN 按粒度映射到槽位，减少数组大小</li>
 *   <li><b>环形复用</b>: 使用 mask 实现环形索引，tail 推进时清零槽位</li>
 *   <li><b>Slot 存储 endSn</b>: 防止 ABA 问题，推进时验证 slot 值与预期一致</li>
 *   <li><b>VarHandle</b>: 使用 release/acquire 语义保证可见性</li>
 *   <li><b>后台推进</b>: tail 由单一后台线程 (LogWriter/LogCloser) 推进</li>
 * </ul>
 *
 * <h2>内存语义</h2>
 * <ul>
 *   <li>{@link #addLink}: setRelease - 确保写入 buffer 的数据对其他线程可见</li>
 *   <li>{@link #advanceTail}: getAcquire - 确保看到最新的槽位值</li>
 * </ul>
 *
 * <h2>安全约束</h2>
 * <ul>
 *   <li><b>对齐约束</b>: startSn 必须对齐到 granularity 边界，否则抛出异常</li>
 *   <li><b>唯一性约束</b>: 同一 startSn 不能重复调用 addLink，通过 slot != 0 检测</li>
 *   <li><b>单线程推进</b>: advanceTail 必须由单一线程调用，通过 owner 校验 (debug 模式)</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.1 (slot 存储 endSn，增加安全校验)
 */
public class LinkBuf {

    /**
     * 默认粒度 (8 字节)
     */
    public static final int DEFAULT_GRANULARITY = 8;

    /**
     * 是否启用断言检查 (可通过 -ea JVM 参数启用)
     */
    private static final boolean ASSERTIONS_ENABLED;

    static {
        boolean ea = false;
        assert ea = true; // 仅在启用断言时执行
        ASSERTIONS_ENABLED = ea;
    }

    /**
     * VarHandle 用于 slots 数组的原子操作
     */
    private static final VarHandle SLOTS_HANDLE;

    static {
        try {
            SLOTS_HANDLE = MethodHandles.arrayElementVarHandle(long[].class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * 容量 (槽位数量，必须是 2 的幂)
     */
    private final int capacity;

    /**
     * 容量掩码 (capacity - 1，用于快速取模)
     */
    private final int mask;

    /**
     * 粒度 (每个槽位覆盖的 SN 范围)
     */
    private final int granularity;

    /**
     * 环形数组 (每个槽位存储对应记录的 endSn)
     *
     * <p><b>关键变更</b>: 从存储 length 改为存储 endSn (= startSn + length)，
     * 解决环形复用时的 ABA 问题。推进时可验证 slot 值是否属于当前 tail 对应的记录。</p>
     */
    private final long[] slots;

    /**
     * 连续完成的边界 (下一个未完成的 SN)
     *
     * <p>tail 之前的所有数据都已连续完成，可以安全地写入文件或用于 checkpoint。</p>
     */
    private final AtomicLong tail;

    /**
     * 推进线程 ID (用于单线程约束校验，仅 debug 模式)
     */
    private volatile long advanceOwnerThreadId = -1;

    /**
     * 创建 LinkBuf
     *
     * @param capacity    容量 (槽位数量，必须是 2 的幂)
     * @param granularity 粒度 (每个槽位覆盖的 SN 范围)
     */
    public LinkBuf(int capacity, int granularity) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException(
                "capacity must be positive power of 2: " + capacity);
        }
        if (granularity <= 0) {
            throw new IllegalArgumentException(
                "granularity must be positive: " + granularity);
        }

        this.capacity = capacity;
        this.mask = capacity - 1;
        this.granularity = granularity;
        this.slots = new long[capacity];
        this.tail = new AtomicLong(0);
    }

    /**
     * 创建 LinkBuf (使用默认粒度)
     *
     * @param capacity 容量
     */
    public LinkBuf(int capacity) {
        this(capacity, DEFAULT_GRANULARITY);
    }

    /**
     * 计算 SN 对应的槽位索引
     *
     * <p><b>重要</b>: 调用者必须确保 sn 对齐到 granularity 边界，
     * 否则不同的 sn 可能映射到同一个 slot 导致覆盖。</p>
     *
     * @param sn Sequence Number (必须对齐到 granularity)
     * @return 槽位索引
     */
    private int slotIndex(long sn) {
        return (int) ((sn / granularity) & mask);
    }

    /**
     * 标记 [startSn, startSn+length) 区间写入完成
     *
     * <p>MTR 写完数据后调用此方法。使用 setRelease 语义确保
     * 写入 buffer 的数据对读取线程可见。</p>
     *
     * <h3>安全约束 (违反时抛出异常)</h3>
     * <ul>
     *   <li>startSn 必须对齐到 granularity 边界</li>
     *   <li>同一 startSn 不能重复调用 (slot 非零则为重复)</li>
     * </ul>
     *
     * <h3>调用者职责</h3>
     * <ul>
     *   <li>length 必须与 reserveSpace 返回的预留大小一致</li>
     *   <li>调用前必须完成 buffer 数据写入</li>
     * </ul>
     *
     * @param startSn 起始 SN (由 reserveSpace 返回，必须对齐到 granularity)
     * @param length  数据长度 (字节)
     * @throws IllegalArgumentException 如果 startSn 未对齐
     * @throws IllegalStateException    如果检测到重复调用
     */
    public void addLink(long startSn, int length) {
        if (length <= 0) {
            return;
        }

        // 对齐检查
        if (startSn % granularity != 0) {
            throw new IllegalArgumentException(
                "startSn must be aligned to granularity: startSn=" + startSn +
                ", granularity=" + granularity);
        }

        int slot = slotIndex(startSn);
        long endSn = startSn + length;

        // 重复检测 (断言模式)
        if (ASSERTIONS_ENABLED) {
            long existing = (long) SLOTS_HANDLE.getAcquire(slots, slot);
            if (existing != 0) {
                throw new IllegalStateException(
                    "Duplicate addLink detected: startSn=" + startSn +
                    ", slot=" + slot + ", existing endSn=" + existing);
            }
        }

        // 使用 release 语义写入 endSn
        // 确保 MTR 写入 buffer 的数据在此之前对其他线程可见
        SLOTS_HANDLE.setRelease(slots, slot, endSn);
    }

    /**
     * 尝试推进 tail 指针
     *
     * <p>由后台线程 (LogWriter 或 LogCloser) 调用。
     * 从当前 tail 开始遍历，遇到空洞 (endSn=0) 或不匹配的 endSn 时停止。</p>
     *
     * <h3>工作流程</h3>
     * <pre>
     * 1. 读取当前 tail
     * 2. 检查 tail 对应的槽位
     *    - 如果 endSn == 0: 遇到空洞，停止
     *    - 如果 endSn > currentTail: 有效，推进 tail = endSn，清零槽位，继续
     *    - 如果 endSn <= currentTail: 无效/陈旧槽位，停止 (不应发生)
     * 3. 更新 tail
     * </pre>
     *
     * <h3>单线程约束</h3>
     * <p>此方法必须由单一线程调用。在启用断言时会验证调用线程一致性。
     * 如果需要多线程推进，应改用 CAS 循环。</p>
     *
     * @return 推进后的 tail 值
     */
    public long advanceTail() {
        // 单线程约束校验 (debug 模式)
        checkAdvanceOwner();

        long currentTail = tail.get();

        while (true) {
            int slot = slotIndex(currentTail);

            // 使用 acquire 语义读取 endSn
            // 确保看到 addLink 写入的最新值，以及之前写入 buffer 的数据
            long endSn = (long) SLOTS_HANDLE.getAcquire(slots, slot);

            if (endSn == 0) {
                // 遇到空洞，停止推进
                break;
            }

            // 验证 endSn 有效性：必须大于 currentTail
            // 这是防止 ABA 问题的关键：如果 slot 被新一代写入复用，
            // 其 endSn 会远大于 currentTail + 合理长度，直接停止
            if (endSn <= currentTail) {
                // 陈旧或无效的 slot 值，不应发生
                // 可能是：1) 被错误清零 2) 环形复用错乱
                break;
            }

            // 计算 length 用于合理性检查
            long length = endSn - currentTail;

            // 合理性检查：单条记录不应超过 coverageRange
            // 这可以捕获 ABA 问题（新一代 endSn 远大于 currentTail）
            if (length > getCoverageRange()) {
                // endSn 跳跃过大，可能是 ABA 问题
                break;
            }

            // 清零槽位 (为下一轮循环复用)
            SLOTS_HANDLE.setRelease(slots, slot, 0L);

            // 推进 tail
            currentTail = endSn;
        }

        // 更新 tail (使用 set，因为只有单线程推进)
        tail.set(currentTail);

        return currentTail;
    }

    /**
     * 尝试推进 tail 到指定上界
     *
     * <p>与 {@link #advanceTail()} 类似，但在达到 upTo 时停止。
     * 只处理完全落在 upTo 范围内的记录（即 endSn <= upTo）。</p>
     *
     * <h3>单线程约束</h3>
     * <p>此方法必须由与 {@link #advanceTail()} 相同的线程调用。</p>
     *
     * @param upTo 推进上界
     * @return 实际推进到的位置
     */
    public long advanceTailTo(long upTo) {
        // 单线程约束校验 (debug 模式)
        checkAdvanceOwner();

        long currentTail = tail.get();

        if (currentTail >= upTo) {
            return currentTail;
        }

        while (currentTail < upTo) {
            int slot = slotIndex(currentTail);
            long endSn = (long) SLOTS_HANDLE.getAcquire(slots, slot);

            if (endSn == 0) {
                break;
            }

            // 验证 endSn 有效性
            if (endSn <= currentTail) {
                break;
            }

            // 检查是否会超出上界
            if (endSn > upTo) {
                // 记录结束位置超过 upTo，停止推进
                break;
            }

            // 合理性检查
            long length = endSn - currentTail;
            if (length > getCoverageRange()) {
                break;
            }

            SLOTS_HANDLE.setRelease(slots, slot, 0L);
            currentTail = endSn;
        }

        tail.set(currentTail);
        return currentTail;
    }

    /**
     * 校验推进线程一致性 (仅 debug 模式)
     *
     * <p>确保 advanceTail/advanceTailTo 只由单一线程调用。
     * 首次调用时记录线程 ID，后续调用时验证。</p>
     */
    private void checkAdvanceOwner() {
        if (!ASSERTIONS_ENABLED) {
            return;
        }

        long currentThreadId = Thread.currentThread().threadId();

        if (advanceOwnerThreadId == -1) {
            // 首次调用，记录 owner
            advanceOwnerThreadId = currentThreadId;
        } else if (advanceOwnerThreadId != currentThreadId) {
            throw new IllegalStateException(
                "advanceTail called from different thread: owner=" +
                advanceOwnerThreadId + ", current=" + currentThreadId);
        }
    }

    /**
     * 获取当前 tail 值
     *
     * <p>tail 表示连续完成的边界。tail 之前的所有数据
     * 都已写入完成，可以安全地处理。</p>
     *
     * @return 当前 tail 值
     */
    public long getTail() {
        return tail.get();
    }

    /**
     * 检查指定 SN 是否已连续完成
     *
     * @param sn 要检查的 SN
     * @return true 如果 sn <= tail
     */
    public boolean isComplete(long sn) {
        return sn <= tail.get();
    }

    /**
     * 获取容量
     *
     * @return 槽位数量
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * 获取粒度
     *
     * @return 每个槽位覆盖的 SN 范围
     */
    public int getGranularity() {
        return granularity;
    }

    /**
     * 获取 LinkBuf 覆盖的 SN 范围
     *
     * <p>用于计算 checkpoint 时的安全边界。</p>
     *
     * @return capacity * granularity
     */
    public long getCoverageRange() {
        return (long) capacity * granularity;
    }

    /**
     * 获取指定槽位的当前值 (仅用于调试和测试)
     *
     * <p>返回槽位存储的 endSn 值，0 表示空槽位。</p>
     *
     * @param slot 槽位索引
     * @return 槽位值 (endSn 或 0)
     */
    long getSlotValue(int slot) {
        if (slot < 0 || slot >= capacity) {
            throw new IndexOutOfBoundsException("slot: " + slot);
        }
        return (long) SLOTS_HANDLE.getAcquire(slots, slot);
    }

    /**
     * 重置 LinkBuf (仅用于测试)
     *
     * <p>清零所有槽位，重置 tail 到 0，清除 owner 线程记录。</p>
     */
    void reset() {
        for (int i = 0; i < capacity; i++) {
            SLOTS_HANDLE.setRelease(slots, i, 0L);
        }
        tail.set(0);
        advanceOwnerThreadId = -1;
    }

    /**
     * 重置 tail 到指定值 (仅用于测试)
     *
     * <p>用于测试环形复用场景，直接设置 tail 值。</p>
     *
     * @param newTail 新的 tail 值
     */
    void resetTail(long newTail) {
        tail.set(newTail);
        advanceOwnerThreadId = -1;
    }

    /**
     * 清除 owner 线程记录 (仅用于测试)
     *
     * <p>允许测试中从不同线程调用 advanceTail。</p>
     */
    void clearAdvanceOwner() {
        advanceOwnerThreadId = -1;
    }

    @Override
    public String toString() {
        return String.format("LinkBuf{capacity=%d, granularity=%d, tail=%d, coverage=%d, slotEncoding=endSn}",
            capacity, granularity, tail.get(), getCoverageRange());
    }
}
