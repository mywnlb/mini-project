package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Undo Segment (回滚段内的事务槽)
 *
 * <p>UndoSegment 管理单个事务的一条 Undo 链（INSERT 或 UPDATE）。
 * 每个事务最多有两个 UndoSegment：一个用于 INSERT，一个用于 UPDATE/DELETE。</p>
 *
 * <h2>InnoDB 的 Undo Segment 设计</h2>
 * <pre>
 * Transaction
 *     │
 *     ├─ INSERT UndoSegment ──► UndoPage1 ──► UndoPage2 ──► ...
 *     │      (is_insert=1)
 *     │
 *     └─ UPDATE UndoSegment ──► UndoPage1 ──► UndoPage2 ──► ...
 *            (is_insert=0)
 * </pre>
 *
 * <h2>为什么分离 INSERT 和 UPDATE Undo</h2>
 * <ul>
 *   <li><b>Purge 优化</b>: INSERT Undo 在事务提交后可立即清理（记录之前不存在）</li>
 *   <li><b>MVCC 需求</b>: UPDATE Undo 需要保留更长时间供其他事务读取历史版本</li>
 *   <li><b>空间管理</b>: 分开管理便于独立回收</li>
 * </ul>
 *
 * <h2>设计约束 (Invariants)</h2>
 * <ul>
 *   <li><b>U4</b>: INSERT 和 UPDATE Undo 必须分离管理</li>
 *   <li><b>U5</b>: 当前页面写满时自动分配新页</li>
 *   <li><b>U6</b>: lastUndoPtr 始终指向最后一条记录</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 创建 INSERT Undo Segment
 * UndoSegment insertSeg = new UndoSegment(trxId, rsegId, true);
 *
 * // 写入 INSERT Undo
 * InsertUndoRecord undoRec = new InsertUndoRecord(trxId, tableId, pk);
 * RollbackPointer rollPtr = insertSeg.writeUndoRecord(undoRec, undoPageSupplier);
 *
 * // 事务回滚时遍历
 * for (UndoRecord rec : insertSeg.reverseIterate(undoPageReader)) {
 *     // 执行回滚操作
 * }
 * }</pre>
 *
 * @author MiniDB
 * @version 1.0
 * @see UndoPage
 * @see RollbackPointer
 */
public class UndoSegment {

    // ==================== 字段 ====================

    /**
     * 事务 ID
     */
    private final TransactionId trxId;

    /**
     * Rollback Segment ID (0-127)
     */
    private final int rsegId;

    /**
     * 是否是 INSERT Undo Segment
     *
     * <p>true: INSERT Undo (提交后可立即清理)
     * false: UPDATE/DELETE Undo (需要保留供 MVCC)</p>
     */
    private final boolean isInsert;

    /**
     * 当前活跃的 Undo Page 号
     *
     * <p>新的 Undo 记录写入此页面。当页面满时分配新页。</p>
     */
    private int currentPageNo;

    /**
     * 当前页面的 ByteBuffer (缓存，避免重复获取)
     */
    private ByteBuffer currentPageBuffer;

    /**
     * 最后一条 Undo 记录的位置
     *
     * <p>用于：
     * <ul>
     *   <li>回滚时作为遍历起点</li>
     *   <li>写入下一条记录时作为 prev_undo 引用</li>
     * </ul>
     * </p>
     */
    private RollbackPointer lastUndoPtr;

    /**
     * 第一条 Undo 记录的位置
     *
     * <p>用于正向遍历（如 Purge）</p>
     */
    private RollbackPointer firstUndoPtr;

    /**
     * Undo Page 列表 (按分配顺序)
     *
     * <p>记录所有分配的页面号，用于释放空间</p>
     */
    private final List<Integer> pageList;

    /**
     * 写入的 Undo 记录计数
     */
    private int recordCount;

    /**
     * 段状态
     */
    private State state;

    // ==================== 状态枚举 ====================

    /**
     * Undo Segment 状态
     */
    public enum State {
        /** 活跃：正在被事务使用 */
        ACTIVE,

        /** 已提交：等待 Purge */
        COMMITTED,

        /** 已回滚：等待清理 */
        ROLLED_BACK,

        /** 已清理：可以重用 */
        PURGED
    }

    // ==================== 构造函数 ====================

    /**
     * 创建 Undo Segment
     *
     * @param trxId    事务 ID
     * @param rsegId   Rollback Segment ID
     * @param isInsert 是否是 INSERT Undo
     */
    public UndoSegment(TransactionId trxId, int rsegId, boolean isInsert) {
        if (trxId == null) {
            throw new NullPointerException("trxId cannot be null");
        }
        if (rsegId < 0 || rsegId > RollbackPointer.MAX_RSEG_ID) {
            throw new IllegalArgumentException("Invalid rsegId: " + rsegId);
        }

        this.trxId = trxId;
        this.rsegId = rsegId;
        this.isInsert = isInsert;
        this.currentPageNo = 0;  // 未分配
        this.currentPageBuffer = null;
        this.lastUndoPtr = RollbackPointer.NULL;
        this.firstUndoPtr = RollbackPointer.NULL;
        this.pageList = new ArrayList<>();
        this.recordCount = 0;
        this.state = State.ACTIVE;
    }

    // ==================== 写入方法 ====================

    /**
     * 写入 Undo 记录
     *
     * <p>将 Undo 记录写入当前页面。如果空间不足，通过 pageSupplier 分配新页。</p>
     *
     * @param undoRecord   Undo 记录
     * @param pageSupplier 页面提供器 (当需要新页时调用)
     * @return RollbackPointer 指向写入的记录
     * @throws IllegalStateException 如果 Segment 不在 ACTIVE 状态
     */
    public RollbackPointer writeUndoRecord(UndoRecord undoRecord,
                                           UndoPageSupplier pageSupplier) {
        checkActive();

        // 确保类型匹配
        boolean recordIsInsert = undoRecord.getType() == UndoRecordType.INSERT;
        if (recordIsInsert != isInsert) {
            throw new IllegalArgumentException(
                    "Record type mismatch: segment isInsert=" + isInsert +
                            ", record type=" + undoRecord.getType());
        }

        // 确保有可用页面
        if (currentPageBuffer == null || !UndoPage.hasSpace(currentPageBuffer, undoRecord.calculateSize())) {
            allocateNewPage(pageSupplier);
        }

        // 再次检查空间（新分配的页面应该足够）
        if (!UndoPage.hasSpace(currentPageBuffer, undoRecord.calculateSize())) {
            throw new IllegalStateException("Undo record too large for page: " + undoRecord.calculateSize());
        }

        // 写入记录
        RollbackPointer rollPtr = UndoPage.writeUndoRecordWithPointer(
                currentPageBuffer, undoRecord, rsegId, currentPageNo);

        if (rollPtr == null) {
            throw new IllegalStateException("Failed to write undo record");
        }

        // 更新指针
        if (firstUndoPtr.isNull()) {
            firstUndoPtr = rollPtr;
        }
        lastUndoPtr = rollPtr;
        recordCount++;

        return rollPtr;
    }

    /**
     * 分配新的 Undo Page
     *
     * @param pageSupplier 页面提供器
     */
    private void allocateNewPage(UndoPageSupplier pageSupplier) {
        UndoPageInfo newPage = pageSupplier.allocateUndoPage(
                isInsert ? UndoPageHeader.UNDO_INSERT : UndoPageHeader.UNDO_UPDATE,
                trxId,
                rsegId
        );

        if (newPage == null) {
            throw new IllegalStateException("Failed to allocate undo page");
        }

        // 如果有当前页，设置其 next_page（简化：不维护链表，通过 pageList 管理）
        currentPageNo = newPage.pageNo;
        currentPageBuffer = newPage.buffer;
        pageList.add(currentPageNo);
    }

    // ==================== 状态管理 ====================

    /**
     * 标记为已提交
     *
     * <p>事务提交时调用。INSERT Undo 可以被立即清理，
     * UPDATE Undo 需要等待所有依赖的 ReadView 关闭。</p>
     */
    public void markCommitted() {
        if (state != State.ACTIVE) {
            throw new IllegalStateException("Cannot commit non-active segment: " + state);
        }
        state = State.COMMITTED;
    }

    /**
     * 标记为已回滚
     */
    public void markRolledBack() {
        if (state != State.ACTIVE) {
            throw new IllegalStateException("Cannot rollback non-active segment: " + state);
        }
        state = State.ROLLED_BACK;
    }

    /**
     * 标记为已清理
     */
    public void markPurged() {
        state = State.PURGED;
    }

    /**
     * 检查是否处于活跃状态
     */
    private void checkActive() {
        if (state != State.ACTIVE) {
            throw new IllegalStateException("Segment is not active: " + state);
        }
    }

    // ==================== 遍历方法 ====================

    /**
     * 反向遍历 Undo 记录 (用于回滚)
     *
     * <p>从最后一条记录开始，沿着 prev_undo 指针遍历。</p>
     *
     * @param pageReader 页面读取器
     * @return Undo 记录迭代器
     */
    public Iterable<UndoRecord> reverseIterate(UndoPageReader pageReader) {
        return () -> new ReverseUndoIterator(lastUndoPtr, pageReader);
    }

    /**
     * 正向遍历 Undo 记录 (用于 Purge)
     *
     * @param pageReader 页面读取器
     * @return Undo 记录迭代器
     */
    public Iterable<UndoRecord> forwardIterate(UndoPageReader pageReader) {
        return () -> new ForwardUndoIterator(pageList, pageReader);
    }

    // ==================== 访问方法 ====================

    public TransactionId getTrxId() {
        return trxId;
    }

    public int getRsegId() {
        return rsegId;
    }

    public boolean isInsert() {
        return isInsert;
    }

    public int getCurrentPageNo() {
        return currentPageNo;
    }

    public RollbackPointer getLastUndoPtr() {
        return lastUndoPtr;
    }

    public RollbackPointer getFirstUndoPtr() {
        return firstUndoPtr;
    }

    public List<Integer> getPageList() {
        return new ArrayList<>(pageList);
    }

    public int getRecordCount() {
        return recordCount;
    }

    public int getPageCount() {
        return pageList.size();
    }

    public State getState() {
        return state;
    }

    public boolean isEmpty() {
        return recordCount == 0;
    }

    // ==================== 接口定义 ====================

    /**
     * Undo Page 提供器接口
     *
     * <p>负责分配和初始化 Undo Page</p>
     */
    @FunctionalInterface
    public interface UndoPageSupplier {
        /**
         * 分配一个新的 Undo Page
         *
         * @param undoType Undo 类型
         * @param trxId    事务 ID
         * @param rsegId   Rollback Segment ID
         * @return 页面信息
         */
        UndoPageInfo allocateUndoPage(int undoType, TransactionId trxId, int rsegId);
    }

    /**
     * Undo Page 读取器接口
     *
     * <p>负责读取 Undo Page</p>
     */
    @FunctionalInterface
    public interface UndoPageReader {
        /**
         * 读取指定页面
         *
         * @param pageNo 页号
         * @return 页面缓冲区
         */
        ByteBuffer readPage(int pageNo);
    }

    /**
     * Undo Page 信息
     */
    public static class UndoPageInfo {
        public final int pageNo;
        public final ByteBuffer buffer;

        public UndoPageInfo(int pageNo, ByteBuffer buffer) {
            this.pageNo = pageNo;
            this.buffer = buffer;
        }
    }

    // ==================== 迭代器类 ====================

    /**
     * 反向 Undo 记录迭代器
     */
    private static class ReverseUndoIterator implements java.util.Iterator<UndoRecord> {
        private RollbackPointer currentPtr;
        private final UndoPageReader pageReader;
        private ByteBuffer currentPageBuffer;
        private int currentPageNo;

        ReverseUndoIterator(RollbackPointer startPtr, UndoPageReader pageReader) {
            this.currentPtr = startPtr;
            this.pageReader = pageReader;
            this.currentPageNo = -1;
            this.currentPageBuffer = null;
        }

        @Override
        public boolean hasNext() {
            return !currentPtr.isNull();
        }

        @Override
        public UndoRecord next() {
            if (!hasNext()) {
                throw new java.util.NoSuchElementException();
            }

            // 获取页面
            int pageNo = currentPtr.getPageNo();
            if (pageNo != currentPageNo) {
                currentPageBuffer = pageReader.readPage(pageNo);
                currentPageNo = pageNo;
            }

            // 读取记录
            UndoRecord record = UndoPage.readUndoRecord(currentPageBuffer, currentPtr.getOffset());

            // 移动到前一条记录
            currentPtr = record.getPrevUndoPtr();

            return record;
        }
    }

    /**
     * 正向 Undo 记录迭代器
     */
    private static class ForwardUndoIterator implements java.util.Iterator<UndoRecord> {
        private final List<Integer> pageList;
        private final UndoPageReader pageReader;
        private int pageIndex;
        private UndoPage.UndoRecordIterator pageIterator;

        ForwardUndoIterator(List<Integer> pageList, UndoPageReader pageReader) {
            this.pageList = pageList;
            this.pageReader = pageReader;
            this.pageIndex = 0;
            this.pageIterator = null;
            advanceToNextPage();
        }

        private void advanceToNextPage() {
            while (pageIndex < pageList.size()) {
                int pageNo = pageList.get(pageIndex);
                ByteBuffer buf = pageReader.readPage(pageNo);
                pageIterator = UndoPage.iterator(buf);

                if (pageIterator.hasNext()) {
                    return;
                }
                pageIndex++;
            }
            pageIterator = null;
        }

        @Override
        public boolean hasNext() {
            if (pageIterator != null && pageIterator.hasNext()) {
                return true;
            }
            pageIndex++;
            advanceToNextPage();
            return pageIterator != null && pageIterator.hasNext();
        }

        @Override
        public UndoRecord next() {
            if (!hasNext()) {
                throw new java.util.NoSuchElementException();
            }
            return pageIterator.next();
        }
    }

    // ==================== Object 方法 ====================

    @Override
    public String toString() {
        return String.format("UndoSegment{trxId=%s, rsegId=%d, isInsert=%s, " +
                        "pages=%d, records=%d, state=%s}",
                trxId, rsegId, isInsert, pageList.size(), recordCount, state);
    }
}
