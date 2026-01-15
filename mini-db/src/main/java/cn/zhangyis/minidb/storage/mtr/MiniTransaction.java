package cn.zhangyis.minidb.storage.mtr;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.common.exception.PageNotManagedByMtrException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.redo.RedoLogManager;
import cn.zhangyis.minidb.storage.redo.record.RedoRecord;

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
     * </ul>
     * </p>
     *
     * <p>按获取顺序存储，释放时按相反顺序（LIFO）。</p>
     */
    private final List<MemoSlot> memo;

    /**
     * Redo Log 缓冲区
     *
     * <p>存储本 MTR 生成的 redo log 记录。
     * 在 commit() 时写入全局 redo log。</p>
     *
     * <p>TODO: 当前为简化实现，后续需要实现完整的 redo log 系统。</p>
     */
    private final List<byte[]> redoLogBuffer;

    /**
     * MTR 开始时间戳
     * <p>用于统计和调试。</p>
     */
    private final long startTime;

    // ==================== 构造函数 ====================

    /**
     * 创建一个新的 Mini-Transaction
     *
     * @param bufferPool Buffer Pool 实例
     */
    public MiniTransaction(BufferPool bufferPool) {
        this.bufferPool = bufferPool;
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
        checkActive();

        // 检查是否已经在 memo 中
        for (MemoSlot slot : memo) {
            if (slot.pageId.equals(pageId)) {
                return slot.page;  // 直接返回已缓存的页面
            }
        }

        // 从 Buffer Pool 获取页面（会自动 pin）
        BufferFrame frame = bufferPool.getPage(pageId, mode);
        Page page = frame.getPage();

        // 记录到 memo（默认非脏页）
        memo.add(new MemoSlot(pageId, page, false));

        return page;
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
     * 分配新页面
     *
     * <p>从 Buffer Pool 分配一个新页面，自动加入 MTR 管理。</p>
     *
     * @param spaceId 表空间 ID
     * @return 新分配的 Page 对象
     * @throws MiniDbException 如果分配失败
     */
    public Page newPage(int spaceId) throws MiniDbException {
        checkActive();

        BufferFrame frame = bufferPool.newPage(spaceId);
        Page page = frame.getPage();

        // 新页面自动标记为脏页
        memo.add(new MemoSlot(page.getPageId(), page, true));

        return page;
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
                page.markDirty();
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

    // ==================== 提交和回滚 ====================

    /**
     * 提交 Mini-Transaction
     *
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>生成并写入 redo log 到全局 log buffer</li>
     *   <li>按相反顺序释放所有页面（LIFO）</li>
     *   <li>对脏页调用 unpinPage(dirty=true)，加入 FlushList</li>
     *   <li>对非脏页调用 unpinPage(dirty=false)</li>
     *   <li>清空 memo</li>
     *   <li>状态改为 COMMITTED</li>
     * </ol>
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

        try {
            // ===== Step 1: 写入 redo log (WAL) =====
            // TODO: 当前简化实现，仅打印日志
            // 完整实现需要：
            // 1. 序列化 redo log records
            // 2. 写入 RedoLogBuffer
            // 3. 可能触发 log buffer flush
            if (!redoLogBuffer.isEmpty()) {
                // logManager.write(redoLogBuffer);
                // System.out.println("MTR: wrote " + redoLogBuffer.size() + " redo log records");
            }

            // ===== Step 2: 释放所有页面（LIFO 顺序） =====
            // 从后往前遍历，保证后获取的页面先释放
            for (int i = memo.size() - 1; i >= 0; i--) {
                MemoSlot slot = memo.get(i);
                bufferPool.unpinPage(slot.pageId, slot.isDirty);
            }

            // ===== Step 3: 清理状态 =====
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
        }
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
     * <p>记录 MTR 中获取的每个页面的信息。</p>
     */
    private static class MemoSlot {
        /** 页面标识 */
        final PageId pageId;

        /** 页面对象引用 */
        final Page page;

        /** 是否为脏页 */
        boolean isDirty;

        MemoSlot(PageId pageId, Page page, boolean isDirty) {
            this.pageId = pageId;
            this.page = page;
            this.isDirty = isDirty;
        }

        @Override
        public String toString() {
            return String.format("MemoSlot{pageId=%s, dirty=%s}", pageId, isDirty);
        }
    }

    @Override
    public String toString() {
        return String.format("MTR{state=%s, pages=%d, duration=%dμs}",
            state, memo.size(), getDuration() / 1000);
    }
}
