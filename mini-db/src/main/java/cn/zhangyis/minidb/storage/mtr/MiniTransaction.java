package cn.zhangyis.minidb.storage.mtr;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.common.exception.PageNotManagedByMtrException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.constants.StorageConstants;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.redo.RedoLogConfig;
import cn.zhangyis.minidb.storage.redo.RedoLogManager;
import cn.zhangyis.minidb.storage.redo.record.MultiRecEndRecord;
import cn.zhangyis.minidb.storage.redo.record.RedoRecord;
import cn.zhangyis.minidb.storage.redo.record.WriteBytesRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;

/**
 * Mini-Transaction (MTR) - 页面操作的原子单元
 *
 * <p>MTR 是 InnoDB 中管理页面访问和修改的核心机制。它提供了一组页面操作的原子性保证，
 * 并自动管理页面的固定(pin)和释放(unpin)，以及生成相应的 redo log 记录。</p>
 *
 * <h2>核心职责</h2>
 * <ul>
 *   <li><b>页面生命周期管理</b>: 自动 pin/unpin 页面，防止在使用期间被淘汰</li>
 *   <li><b>修改追踪</b>: 记录哪些页面被修改，用于 redo log 生成</li>
 *   <li><b>原子性保证</b>: 一组页面操作要么全部成功，要么全部失败</li>
 *   <li><b>Redo Log 生成</b>: 提交时生成 redo log 记录（后续实现）</li>
 *   <li><b>锁协调</b>: 持有期间保持页面锁（后续实现）</li>
 * </ul>
 *
 * <h2>使用模式</h2>
 * <pre>
 * // 推荐：使用 try-with-resources 自动管理生命周期
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     // 获取页面（自动 pin）
 *     Page page1 = mtr.getPage(pageId1, FetchMode.READ_EXISTING);
 *     Page page2 = mtr.getPage(pageId2, FetchMode.READ_EXISTING);
 *
 *     // 修改页面
 *     page1.putInt(offset, value);
 *     mtr.markDirty(page1);  // 标记为脏页
 *
 *     // 提交（生成 redo log，释放所有页面）
 *     mtr.commit();
 * } // 即使没有显式 commit，也会在 close() 时自动 rollback
 *
 * // 手动管理（不推荐）
 * MiniTransaction mtr = new MiniTransaction(bufferPool);
 * try {
 *     Page page = mtr.getPage(pageId, FetchMode.READ_EXISTING);
 *     // ... 操作页面 ...
 *     mtr.commit();
 * } finally {
 *     mtr.close();  // 确保资源释放
 * }
 * </pre>
 *
 * <h2>MTR 状态机</h2>
 * <pre>
 *     [CREATED]
 *         |
 *         | getPage() / markDirty()
 *         v
 *     [ACTIVE] ─────────┐
 *         |             │
 *         | commit()    | rollback() / close()
 *         v             v
 *     [COMMITTED]   [ABORTED]
 * </pre>
 *
 * <h2>与 Buffer Pool 的交互</h2>
 * <pre>
 * MTR                          Buffer Pool
 *  │                                │
 *  ├─ getPage(pageId) ─────────────>│
 *  │                                ├─ getPage() (pin++)
 *  │<──────── BufferFrame ──────────┤
 *  │                                │
 *  ├─ markDirty(page) ──────────────┤ (记录到 memo)
 *  │                                │
 *  ├─ commit() ────────────────────>│
 *  │                                ├─ 生成 redo log
 *  │                                ├─ unpinPage(dirty=true)
 *  │                                ├─ 加入 FlushList
 *  │<──────── OK ───────────────────┤
 * </pre>
 *
 * <h2>InnoDB 对应</h2>
 * <p>对应 InnoDB 的 mtr_t 结构和 mtr0mtr.cc 中的实现。</p>
 *
 * <h2>线程安全</h2>
 * <p><b>不是线程安全的</b>。一个 MTR 实例应该只在单个线程中使用。
 * 如果需要跨线程操作，应该在每个线程中创建独立的 MTR。</p>
 *
 * @author MiniDB
 * @version 1.0
 * @see BufferPool
 * @see Page
 */
public class MiniTransaction implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(MiniTransaction.class);

    /**
     * MTR 状态枚举
     */
    public enum State {
        /** 已创建，可以获取和修改页面 */
        ACTIVE,

        /** 已提交，不能再操作 */
        COMMITTED,

        /** 已中止/回滚，不能再操作 */
        ABORTED
    }

    // ==================== 核心字段 ====================

    /**
     * Buffer Pool 引用
     * <p>用于获取和释放页面。</p>
     */
    private final BufferPool bufferPool;

    /**
     * Redo Log Manager 引用 (可选)
     * <p>用于写入 redo log。如果为 null，则不生成 redo log (向后兼容)。</p>
     */
    private final RedoLogManager redoLogManager;

    /**
     * MTR 当前状态
     */
    private State state;

    /**
     * MTR Memo - 记录所有获取的页面
     *
     * <p>每个 MemoSlot 记录一个页面的信息：
     * <ul>
     *   <li>PageId: 页面标识</li>
     *   <li>isDirty: 是否被修改</li>
     *   <li>page: Page 对象引用（用于快速访问）</li>
     *   <li>modifications: 页面修改记录列表</li>
     * </ul>
     * </p>
     *
     * <p>按获取顺序存储，释放时按相反顺序（LIFO）。</p>
     */
    private final List<MemoSlot> memo;

    /**
     * Redo Log 缓冲区 (旧版接口，保留向后兼容)
     *
     * <p>存储本 MTR 生成的 redo log 记录 (byte[] 格式)。
     * 新代码应使用 logModification() 方法，该字段仅保留兼容性。</p>
     */
    private final List<byte[]> redoLogBuffer;

    /**
     * MTR 开始时间戳
     * <p>用于统计和调试。</p>
     */
    private final long startTime;

    // ==================== 构造函数 ====================

    /**
     * 创建一个新的 Mini-Transaction (无 redo log 支持)
     *
     * <p>向后兼容的构造函数，不生成 redo log。</p>
     *
     * @param bufferPool Buffer Pool 实例
     */
    public MiniTransaction(BufferPool bufferPool) {
        this(bufferPool, null);
    }

    /**
     * 创建一个新的 Mini-Transaction (带 redo log 支持)
     *
     * @param bufferPool     Buffer Pool 实例
     * @param redoLogManager Redo Log Manager 实例 (可为 null)
     */
    public MiniTransaction(BufferPool bufferPool, RedoLogManager redoLogManager) {
        this.bufferPool = bufferPool;
        this.redoLogManager = redoLogManager;
        this.state = State.ACTIVE;
        this.memo = new ArrayList<>();
        this.redoLogBuffer = new ArrayList<>();
        this.startTime = System.nanoTime();
    }

    // ==================== 页面获取方法 ====================

    /**
     * 获取页面用于读取
     *
     * <p>页面会被自动 pin，并在 MTR 结束时自动 unpin。
     * 如果需要修改页面，必须调用 {@link #markDirty(Page)}。</p>
     *
     * @param pageId 页面标识
     * @param mode   获取模式
     * @return Page 对象
     * @throws MiniDbException 如果页面加载失败或 MTR 状态错误
     */
    public Page getPage(PageId pageId, BufferPool.FetchMode mode) throws MiniDbException {
        return getPageFrame(pageId, mode).getPage();
    }

    /**
     * 获取页面所属的 BufferFrame。
     *
     * <p>适用于需要显式持有 page latch 的页格式代码。返回的 frame 仍由 MTR
     * 负责 pin/unpin，重复获取同一页面不会重复 pin。</p>
     *
     * @param pageId 页面标识
     * @param mode   获取模式
     * @return BufferFrame
     * @throws MiniDbException 如果页面加载失败或 MTR 状态错误
     */
    public BufferFrame getPageFrame(PageId pageId, BufferPool.FetchMode mode) throws MiniDbException {
        checkActive();

        // 检查是否已经在 memo 中
        for (MemoSlot slot : memo) {
            if (slot.pageId.equals(pageId)) {
                return slot.frame;  // 直接返回已缓存的 frame
            }
        }

        // 从 Buffer Pool 获取页面（会自动 pin）
        BufferFrame frame = bufferPool.getPage(pageId, mode);
        Page page = frame.getPage();

        // 记录到 memo（默认非脏页）
        memo.add(new MemoSlot(pageId, page, frame, false));

        return frame;
    }

    /**
     * 获取页面用于读取（简化方法）
     *
     * @param pageId 页面标识
     * @return Page 对象
     * @throws MiniDbException 如果页面加载失败
     */
    public Page getPage(PageId pageId) throws MiniDbException {
        return getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
    }

    /**
     * 获取页面所属的 BufferFrame（简化方法）。
     *
     * @param pageId 页面标识
     * @return BufferFrame
     * @throws MiniDbException 如果页面加载失败
     */
    public BufferFrame getPageFrame(PageId pageId) throws MiniDbException {
        return getPageFrame(pageId, BufferPool.FetchMode.READ_EXISTING);
    }

    /**
     * 分配新页面
     *
     * <p>从 Buffer Pool 分配一个新页面，自动加入 MTR 管理。</p>
     *
     * @param spaceId 表空间 ID
     * @return 新分配的 Page 对象
     * @throws MiniDbException 如果分配失败
     */
    public Page newPage(int spaceId) throws MiniDbException {
        return newPageFrame(spaceId).getPage();
    }

    /**
     * 分配新页面并返回对应的 BufferFrame。
     *
     * @param spaceId 表空间 ID
     * @return 新分配的 BufferFrame
     * @throws MiniDbException 如果分配失败
     */
    public BufferFrame newPageFrame(int spaceId) throws MiniDbException {
        checkActive();

        BufferFrame frame = bufferPool.newPage(spaceId);
        Page page = frame.getPage();

        // 新页面自动标记为脏页
        memo.add(new MemoSlot(page.getPageId(), page, frame, true));

        return frame;
    }

    // ==================== 页面修改方法 ====================

    /**
     * 标记页面为脏页
     *
     * <p><b>重要</b>: 在修改页面内容后，必须调用此方法标记为脏页。
     * 否则修改不会被持久化到磁盘。</p>
     *
     * <h3>使用示例</h3>
     * <pre>
     * Page page = mtr.getPage(pageId);
     * page.putInt(offset, newValue);  // 修改页面
     * mtr.markDirty(page);            // 标记为脏页！
     * </pre>
     *
     * @param page 被修改的页面
     * @throws PageNotManagedByMtrException 如果页面不在 MTR 的 memo 中
     * @throws MtrStateException 如果 MTR 不在 ACTIVE 状态
     */
    public void markDirty(Page page) throws PageNotManagedByMtrException, MtrStateException {
        checkActive();

        PageId pageId = page.getPageId();

        // 在 memo 中查找并标记为脏
        for (MemoSlot slot : memo) {
            if (slot.pageId.equals(pageId)) {
                slot.isDirty = true;
                // Page.markDirty() 已废弃，脏页状态由 MemoSlot.isDirty 追踪，
                // 最终通过 unpinPage(pageId, isDirty) 传递给 BufferFrame
                return;
            }
        }

        throw PageNotManagedByMtrException.notInMemo(pageId);
    }

    /**
     * 写入 redo log 记录
     *
     * <p>将页面修改操作记录为 redo log。这些 log 会在 commit() 时
     * 批量写入全局 redo log 系统。</p>
     *
     * <p>TODO: 当前为占位实现，需要配合 Redo Log 系统完善。</p>
     *
     * @param logRecord redo log 记录的字节数组
     */
    public void writeRedoLog(byte[] logRecord) throws MtrStateException {
        checkActive();
        redoLogBuffer.add(logRecord);
    }

    /**
     * 记录页面修改 (用于生成 redo log)
     *
     * <p>在修改页面内容时调用此方法，记录修改的偏移和数据。
     * 这些修改记录会在 commit() 时生成 WriteBytesRecord 并写入 redo log。</p>
     *
     * <h3>使用示例</h3>
     * <pre>
     * Page page = mtr.getPage(pageId);
     * int newValue = 100;
     * page.putInt(offset, newValue);         // 修改页面
     * mtr.logModification(page, offset, 4);  // 记录修改 (4 bytes for int)
     * mtr.markDirty(page);                   // 标记为脏页
     * </pre>
     *
     * @param page   被修改的页面
     * @param offset 页内偏移 (0-16383)
     * @param length 修改的长度
     * @throws PageNotManagedByMtrException 如果页面不在 MTR 的 memo 中
     * @throws MtrStateException 如果 MTR 不在 ACTIVE 状态
     */
    public void logModification(Page page, int offset, int length)
            throws PageNotManagedByMtrException, MtrStateException {
        checkActive();

        if (redoLogManager == null) {
            // 无 redo log 支持，跳过记录
            return;
        }

        if (length <= 0 || offset < 0 || offset + length > StorageConstants.PAGE_SIZE) {
            throw new IllegalArgumentException(
                    String.format("Invalid modification: offset=%d, length=%d", offset, length));
        }

        PageId pageId = page.getPageId();

        // 在 memo 中查找并添加修改记录
        for (MemoSlot slot : memo) {
            if (slot.pageId.equals(pageId)) {
                // 从页面读取修改后的数据
                byte[] data = new byte[length];
                page.getBytes(offset, data);
                slot.addModification(offset, data);
                logger.trace("Logged modification: page={}, offset={}, length={}", pageId, offset, length);
                return;
            }
        }

        throw PageNotManagedByMtrException.notInMemo(pageId);
    }

    /**
     * 记录页面修改 (使用字节数组，用于生成 redo log)
     *
     * <p>与 {@link #logModification(Page, int, int)} 类似，但直接接受修改后的数据。
     * 当数据已经在手边时使用此方法可以避免再次从页面读取。</p>
     *
     * @param page   被修改的页面
     * @param offset 页内偏移 (0-16383)
     * @param data   修改后的数据
     * @throws PageNotManagedByMtrException 如果页面不在 MTR 的 memo 中
     * @throws MtrStateException 如果 MTR 不在 ACTIVE 状态
     */
    public void logModification(Page page, int offset, byte[] data)
            throws PageNotManagedByMtrException, MtrStateException {
        checkActive();

        if (redoLogManager == null) {
            // 无 redo log 支持，跳过记录
            return;
        }

        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data cannot be null or empty");
        }

        if (offset < 0 || offset + data.length > StorageConstants.PAGE_SIZE) {
            throw new IllegalArgumentException(
                    String.format("Invalid modification: offset=%d, length=%d", offset, data.length));
        }

        PageId pageId = page.getPageId();

        // 在 memo 中查找并添加修改记录
        for (MemoSlot slot : memo) {
            if (slot.pageId.equals(pageId)) {
                slot.addModification(offset, data.clone());
                logger.trace("Logged modification: page={}, offset={}, length={}", pageId, offset, data.length);
                return;
            }
        }

        throw PageNotManagedByMtrException.notInMemo(pageId);
    }

    // ==================== 直接写入方法 (BufferFrame-based) ====================

    /**
     * 写入单个字节到 BufferFrame
     *
     * <p>这是 IndexPageOps 等页内算法使用的底层写入方法。
     * 写入操作会自动记录到 redo log 并标记页面为脏。</p>
     *
     * @param frame  BufferFrame (必须持有 X-latch)
     * @param offset 页内偏移 (0-16383)
     * @param value  字节值
     * @throws IllegalStateException 如果未持有 X-latch
     * @throws MtrStateException     如果 MTR 不在 ACTIVE 状态
     */
    public void writeByte(BufferFrame frame, int offset, byte value) throws MtrStateException {
        checkActive();
        assertXLatched(frame);

        java.nio.ByteBuffer buf = frame.buffer();
        buf.put(offset, value);

        logFrameModification(frame, offset, new byte[]{value});
        markFrameDirty(frame);
    }

    /**
     * 写入 short (2字节) 到 BufferFrame
     *
     * @param frame  BufferFrame (必须持有 X-latch)
     * @param offset 页内偏移 (0-16383)
     * @param value  short 值
     * @throws IllegalStateException 如果未持有 X-latch
     * @throws MtrStateException     如果 MTR 不在 ACTIVE 状态
     */
    public void writeShort(BufferFrame frame, int offset, short value) throws MtrStateException {
        checkActive();
        assertXLatched(frame);

        java.nio.ByteBuffer buf = frame.buffer();
        buf.putShort(offset, value);

        // 记录修改数据
        byte[] data = new byte[2];
        data[0] = (byte) (value & 0xFF);
        data[1] = (byte) ((value >> 8) & 0xFF);
        logFrameModification(frame, offset, data);
        markFrameDirty(frame);
    }

    /**
     * 写入 int (4字节) 到 BufferFrame
     *
     * @param frame  BufferFrame (必须持有 X-latch)
     * @param offset 页内偏移 (0-16383)
     * @param value  int 值
     * @throws IllegalStateException 如果未持有 X-latch
     * @throws MtrStateException     如果 MTR 不在 ACTIVE 状态
     */
    public void writeInt(BufferFrame frame, int offset, int value) throws MtrStateException {
        checkActive();
        assertXLatched(frame);

        java.nio.ByteBuffer buf = frame.buffer();
        buf.putInt(offset, value);

        // 记录修改数据 (Little Endian)
        byte[] data = new byte[4];
        data[0] = (byte) (value & 0xFF);
        data[1] = (byte) ((value >> 8) & 0xFF);
        data[2] = (byte) ((value >> 16) & 0xFF);
        data[3] = (byte) ((value >> 24) & 0xFF);
        logFrameModification(frame, offset, data);
        markFrameDirty(frame);
    }

    /**
     * 写入 long (8字节) 到 BufferFrame
     *
     * @param frame  BufferFrame (必须持有 X-latch)
     * @param offset 页内偏移 (0-16383)
     * @param value  long 值
     * @throws IllegalStateException 如果未持有 X-latch
     * @throws MtrStateException     如果 MTR 不在 ACTIVE 状态
     */
    public void writeLong(BufferFrame frame, int offset, long value) throws MtrStateException {
        checkActive();
        assertXLatched(frame);

        java.nio.ByteBuffer buf = frame.buffer();
        buf.putLong(offset, value);

        // 记录修改数据 (Little Endian)
        byte[] data = new byte[8];
        for (int i = 0; i < 8; i++) {
            data[i] = (byte) ((value >> (i * 8)) & 0xFF);
        }
        logFrameModification(frame, offset, data);
        markFrameDirty(frame);
    }

    /**
     * 写入字节数组到 BufferFrame
     *
     * @param frame  BufferFrame (必须持有 X-latch)
     * @param offset 页内偏移 (0-16383)
     * @param data   要写入的数据
     * @throws IllegalStateException 如果未持有 X-latch
     * @throws MtrStateException     如果 MTR 不在 ACTIVE 状态
     */
    public void writeBytes(BufferFrame frame, int offset, byte[] data) throws MtrStateException {
        checkActive();
        assertXLatched(frame);

        if (data == null || data.length == 0) {
            return;
        }

        java.nio.ByteBuffer buf = frame.buffer();
        buf.position(offset);
        buf.put(data);

        logFrameModification(frame, offset, data.clone());
        markFrameDirty(frame);
    }

    /**
     * 内存移动 (处理重叠区间)
     *
     * <p>用于 Page Directory slot 移动等场景。
     * Redo 记录移动后的最终数据，而非移动操作本身（保证幂等性）。</p>
     *
     * @param frame BufferFrame (必须持有 X-latch)
     * @param dst   目标偏移
     * @param src   源偏移
     * @param len   移动长度
     * @throws IllegalStateException 如果未持有 X-latch
     * @throws MtrStateException     如果 MTR 不在 ACTIVE 状态
     */
    public void memmove(BufferFrame frame, int dst, int src, int len) throws MtrStateException {
        checkActive();
        assertXLatched(frame);

        if (len <= 0) {
            return;
        }

        java.nio.ByteBuffer buf = frame.buffer();

        // 读取源数据
        byte[] data = new byte[len];
        buf.position(src);
        buf.get(data);

        // 写入目标位置
        buf.position(dst);
        buf.put(data);

        // Redo 记录最终数据 (保证幂等性)
        logFrameModification(frame, dst, data);
        markFrameDirty(frame);
    }

    /**
     * 断言 BufferFrame 已持有 X-latch
     *
     * @param frame BufferFrame
     * @throws IllegalStateException 如果未持有 X-latch
     */
    private void assertXLatched(BufferFrame frame) {
        if (!frame.isWriteLatched()) {
            throw new IllegalStateException(
                    "Must hold X-latch on frame " + frame.getFrameId() +
                            " (pageId=" + frame.getPageId() + ")");
        }
    }

    /**
     * 记录 BufferFrame 的修改 (内部方法)
     *
     * @param frame  BufferFrame
     * @param offset 修改偏移
     * @param data   修改数据
     */
    private void logFrameModification(BufferFrame frame, int offset, byte[] data) {
        if (redoLogManager == null) {
            return;  // 无 redo log 支持
        }

        PageId pageId = frame.getPageId();
        for (MemoSlot slot : memo) {
            if (slot.pageId.equals(pageId)) {
                slot.addModification(offset, data);
                logger.trace("Logged frame modification: page={}, offset={}, length={}",
                        pageId, offset, data.length);
                return;
            }
        }

        // 如果页面不在 memo 中，先添加到 memo
        Page page = frame.getPage();
        MemoSlot newSlot = new MemoSlot(pageId, page, frame, true);
        newSlot.addModification(offset, data);
        memo.add(newSlot);
        logger.trace("Added frame to memo and logged modification: page={}, offset={}, length={}",
                pageId, offset, data.length);
    }

    /**
     * 标记 BufferFrame 为脏 (内部方法)
     *
     * @param frame BufferFrame
     */
    private void markFrameDirty(BufferFrame frame) {
        frame.setDirty(true);

        PageId pageId = frame.getPageId();
        for (MemoSlot slot : memo) {
            if (slot.pageId.equals(pageId)) {
                slot.isDirty = true;
                return;
            }
        }
    }

    // ==================== 提交和回滚 ====================

    /**
     * 提交 Mini-Transaction
     *
     * <h3>执行步骤 (Phase 1-2)</h3>
     * <ol>
     *   <li>获取 commitLock (保证串行提交)</li>
     *   <li>生成 redo records (WriteBytesRecord + MultiRecEndRecord)</li>
     *   <li>写入 RedoLogManager</li>
     *   <li>根据 flush 策略等待持久化</li>
     *   <li>更新 page LSN</li>
     *   <li>按相反顺序释放所有页面（LIFO）</li>
     *   <li>清空 memo，状态改为 COMMITTED</li>
     * </ol>
     *
     * <h3>Phase 5 Group Commit 优化</h3>
     * <p>当启用 Group Commit 时，在 waitForFlush 之前释放 commitLock，
     * 允许多个事务同时进入等待队列，实现批量 fsync。</p>
     *
     * <h3>Redo Log 顺序</h3>
     * <p>所有 redo log 必须在 unpin 页面之前写入，保证 WAL 规则：
     * <b>Write-Ahead Logging</b> - 日志先于数据落盘。</p>
     *
     * @throws MiniDbException 如果刷盘失败
     */
    public void commit() throws MiniDbException {
        if (state == State.COMMITTED) {
            return; // 已经提交过，幂等操作
        }

        checkActive();

        Lock commitLock = null;
        long commitSn = 0;
        boolean groupCommitEnabled = false;

        try {
            // ===== Step 1: 生成 redo group =====
            List<RedoRecord> redoGroup = buildRedoGroup();

            // ===== Step 2: 写入 redo log (WAL) =====
            if (redoLogManager != null && !redoGroup.isEmpty()) {
                // 检查是否启用 Group Commit
                groupCommitEnabled = redoLogManager.isGroupCommitEnabled();

                // 获取 commit lock (串行化写入 buffer)
                commitLock = redoLogManager.getCommitLock();
                commitLock.lock();

                try {
                    // 写入 redo log
                    commitSn = redoLogManager.write(redoGroup);
                    logger.debug("MTR: wrote {} redo records, commitSn={}", redoGroup.size(), commitSn);
                } finally {
                    // Phase 5 优化: 在 waitForFlush 之前释放 commitLock
                    // 这允许多个事务同时进入等待队列，实现 Group Commit
                    if (groupCommitEnabled) {
                        commitLock.unlock();
                        commitLock = null;  // 标记已释放，避免 finally 重复释放
                    }
                }

                // ===== Step 3: 等待 WAL (根据策略) =====
                // innodb_flush_log_at_trx_commit: 1=sync, 2=write, 0=none
                // 注意: Phase 5 模式下，此时 commitLock 已释放，多个事务可并发等待
                try {
                    redoLogManager.waitForFlush(commitSn);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MiniDbException("MTR commit interrupted", e);
                } catch (IOException e) {
                    throw new MiniDbException("MTR commit failed: redo log flush error", e);
                }
            }

            // ===== Step 4: 更新 page LSN =====
            if (commitSn > 0) {
                for (MemoSlot slot : memo) {
                    if (slot.isDirty) {
                        slot.page.setLsn(commitSn);
                    }
                }
            }

            // ===== Step 5: 释放所有页面（LIFO 顺序） =====
            // 从后往前遍历，保证后获取的页面先释放
            for (int i = memo.size() - 1; i >= 0; i--) {
                MemoSlot slot = memo.get(i);
                bufferPool.unpinPage(slot.pageId, slot.isDirty);
            }

            // ===== Step 6: 清理状态 =====
            memo.clear();
            redoLogBuffer.clear();
            state = State.COMMITTED;

        } catch (Exception e) {
            // 提交失败，尝试回滚
            rollback();
            if (e instanceof MiniDbException) {
                throw (MiniDbException) e;
            }
            throw new MiniDbException("MTR commit failed", e);
        } finally {
            // 释放 commit lock (Phase 1-2 模式，或 Phase 5 写入失败时)
            if (commitLock != null) {
                commitLock.unlock();
            }
        }
    }

    /**
     * 构建 redo group
     *
     * <p>从 memo 中的修改记录生成 redo records 列表，
     * 并添加 MLOG_MULTI_REC_END 标记。</p>
     *
     * @return redo records 列表 (可能为空)
     */
    private List<RedoRecord> buildRedoGroup() {
        List<RedoRecord> redoGroup = new ArrayList<>();

        for (MemoSlot slot : memo) {
            if (slot.isDirty && !slot.modifications.isEmpty()) {
                // 为每个修改生成 WriteBytesRecord
                for (PageModification mod : slot.modifications) {
                    WriteBytesRecord record = new WriteBytesRecord(
                            slot.pageId,
                            mod.offset,
                            mod.data
                    );
                    redoGroup.add(record);
                }
            }
        }

        // 添加 group end marker
        if (!redoGroup.isEmpty()) {
            redoGroup.add(new MultiRecEndRecord());
        }

        return redoGroup;
    }

    /**
     * 回滚 Mini-Transaction
     *
     * <p>释放所有页面，但<b>不生成 redo log</b>。
     * 由于页面还在 Buffer Pool 中，修改尚未持久化，
     * 简单释放即可实现回滚。</p>
     *
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>丢弃 redo log buffer</li>
     *   <li>按相反顺序 unpin 所有页面</li>
     *   <li>清空 memo</li>
     *   <li>状态改为 ABORTED</li>
     * </ol>
     *
     * <p><b>注意</b>: 回滚不会撤销已修改的页面内容。
     * 如果需要真正的撤销，需要配合 Undo Log 实现。</p>
     */
    public void rollback() {
        if (state == State.ABORTED) {
            return; // 已经回滚
        }

        try {
            // ===== Step 1: 丢弃 redo log =====
            redoLogBuffer.clear();

            // ===== Step 2: 释放所有页面（LIFO） =====
            // 注意：即使页面被标记为脏，这里也传 false
            // 因为我们不想让这些修改进入 FlushList
            for (int i = memo.size() - 1; i >= 0; i--) {
                MemoSlot slot = memo.get(i);
                // TODO: 理想情况下应该恢复页面的原始内容
                // 当前简化实现：直接 unpin，依赖页面未刷盘
                bufferPool.unpinPage(slot.pageId, false);
            }

            // ===== Step 3: 清理状态 =====
            memo.clear();
            state = State.ABORTED;

        } catch (Exception e) {
            // 回滚本身不应该失败，但仍需处理
            System.err.println("MTR rollback failed: " + e.getMessage());
        }
    }

    // ==================== AutoCloseable 实现 ====================

    /**
     * 自动关闭资源
     *
     * <p>在 try-with-resources 结束时自动调用。
     * 如果 MTR 还没有 commit，则自动 rollback。</p>
     */
    @Override
    public void close() {
        if (state == State.ACTIVE) {
            // 未显式 commit，自动回滚
            rollback();
        }
        // COMMITTED 或 ABORTED 状态：无需操作
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查 MTR 是否处于 ACTIVE 状态
     *
     * @throws MtrStateException 如果 MTR 已提交或中止
     */
    private void checkActive() throws MtrStateException {
        if (state != State.ACTIVE) {
            throw MtrStateException.notActive(state);
        }
    }

    /**
     * 获取 MTR 当前状态
     *
     * @return 状态枚举
     */
    public State getState() {
        return state;
    }

    /**
     * 获取 MTR 持有的页面数量
     *
     * @return 页面数
     */
    public int getPageCount() {
        return memo.size();
    }

    /**
     * 获取 MTR 运行时长（纳秒）
     *
     * @return 从创建到现在的时长
     */
    public long getDuration() {
        return System.nanoTime() - startTime;
    }

    /**
     * 检查某个页面是否在 MTR 管理中
     *
     * @param pageId 页面标识
     * @return 如果页面已被获取返回 true
     */
    public boolean hasPage(PageId pageId) {
        return memo.stream().anyMatch(slot -> slot.pageId.equals(pageId));
    }

    /**
     * 检查某个页面是否被标记为脏
     *
     * @param pageId 页面标识
     * @return 如果页面被标记为脏返回 true
     */
    public boolean isDirty(PageId pageId) {
        return memo.stream()
            .filter(slot -> slot.pageId.equals(pageId))
            .findFirst()
            .map(slot -> slot.isDirty)
            .orElse(false);
    }

    // ==================== 内部类：Memo Slot ====================

    /**
     * MTR Memo 槽位
     *
     * <p>记录 MTR 中获取的每个页面的信息，包括修改区域。</p>
     */
    private static class MemoSlot {
        /** 页面标识 */
        final PageId pageId;

        /** 页面对象引用 */
        final Page page;

        /** 页面所属的 BufferFrame */
        final BufferFrame frame;

        /** 是否为脏页 */
        boolean isDirty;

        /** 页面修改记录列表 (用于生成 redo log) */
        final List<PageModification> modifications;

        MemoSlot(PageId pageId, Page page, BufferFrame frame, boolean isDirty) {
            this.pageId = pageId;
            this.page = page;
            this.frame = frame;
            this.isDirty = isDirty;
            this.modifications = new ArrayList<>();
        }

        /**
         * 添加修改记录
         *
         * @param offset 页内偏移
         * @param data   修改后的数据
         */
        void addModification(int offset, byte[] data) {
            modifications.add(new PageModification(offset, data));
        }

        @Override
        public String toString() {
            return String.format("MemoSlot{pageId=%s, dirty=%s, modifications=%d}",
                    pageId, isDirty, modifications.size());
        }
    }

    // ==================== 内部类：Page Modification ====================

    /**
     * 页面修改记录
     *
     * <p>记录页面内某个区域的修改。</p>
     */
    private static class PageModification {
        /** 页内偏移 */
        final int offset;

        /** 修改后的数据 */
        final byte[] data;

        PageModification(int offset, byte[] data) {
            this.offset = offset;
            this.data = data;
        }

        @Override
        public String toString() {
            return String.format("PageModification{offset=%d, length=%d}", offset, data.length);
        }
    }

    @Override
    public String toString() {
        return String.format("MTR{state=%s, pages=%d, duration=%dμs}",
            state, memo.size(), getDuration() / 1000);
    }
}
