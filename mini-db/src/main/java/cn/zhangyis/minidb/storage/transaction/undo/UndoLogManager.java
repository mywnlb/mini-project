package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.transaction.core.Transaction;
import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Undo Log 管理器
 *
 * <p>管理所有 Rollback Segment，为事务分配 Undo Segment，
 * 提供 Undo 记录的读写操作。</p>
 *
 * <h2>InnoDB Undo 架构</h2>
 * <pre>
 * UndoLogManager
 *     │
 *     ├─ RollbackSegment[0]
 *     │      ├─ UndoSegment (Trx 100, INSERT)
 *     │      ├─ UndoSegment (Trx 100, UPDATE)
 *     │      └─ UndoSegment (Trx 105, INSERT)
 *     │
 *     ├─ RollbackSegment[1]
 *     │      └─ ...
 *     │
 *     └─ RollbackSegment[127]
 *            └─ ...
 * </pre>
 *
 * <h2>Rollback Segment 分配策略</h2>
 * <p>使用轮询方式分配，减少锁竞争：</p>
 * <pre>
 * rseg_id = (trx_id % num_rollback_segments)
 * </pre>
 *
 * <h2>设计约束 (Invariants)</h2>
 * <ul>
 *   <li><b>U5</b>: Rollback Segment 分配需要并发安全</li>
 *   <li><b>U7</b>: 每个事务的 INSERT/UPDATE Undo 分开管理</li>
 *   <li><b>U8</b>: Undo 记录写入必须在 MTR 提交前完成</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * UndoLogManager undoMgr = new UndoLogManager(bufferPool, spaceId, 128);
 *
 * // 事务开始时分配 Undo Segment
 * Transaction trx = transactionManager.begin();
 * UndoSegment insertSeg = undoMgr.assignInsertSegment(trx);
 * UndoSegment updateSeg = undoMgr.assignUpdateSegment(trx);
 *
 * // 写入 Undo
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     RollbackPointer rollPtr = undoMgr.writeInsertUndo(mtr, trx, tableId, pk);
 *     // 使用 rollPtr 设置记录的 ROLL_PTR
 *     mtr.commit();
 * }
 *
 * // 事务提交时释放 Undo Segment
 * undoMgr.commitTransaction(trx);
 * }</pre>
 *
 * @author MiniDB
 * @version 1.0
 * @see UndoSegment
 * @see RollbackPointer
 */
public class UndoLogManager {

    private static final Logger logger = LoggerFactory.getLogger(UndoLogManager.class);

    // ==================== 常量 ====================

    /**
     * 默认 Rollback Segment 数量
     */
    public static final int DEFAULT_ROLLBACK_SEGMENTS = 128;

    /**
     * 最大 Rollback Segment 数量
     */
    public static final int MAX_ROLLBACK_SEGMENTS = 128;

    // ==================== 字段 ====================

    /**
     * Buffer Pool 引用
     */
    private final BufferPool bufferPool;

    /**
     * Undo 表空间 ID
     */
    private final int spaceId;

    /**
     * Rollback Segment 数量
     */
    private final int numRollbackSegments;

    /**
     * Rollback Segment 锁 (每个段一把锁)
     */
    private final ReentrantLock[] rsegLocks;

    /**
     * 事务到 INSERT UndoSegment 的映射
     */
    private final Map<TransactionId, UndoSegment> insertSegments;

    /**
     * 事务到 UPDATE UndoSegment 的映射
     */
    private final Map<TransactionId, UndoSegment> updateSegments;

    /**
     * Rollback Segment 轮询计数器
     */
    private final AtomicInteger rsegRoundRobin;

    /**
     * 下一个可分配的 Undo Page 号
     *
     * <p>简化实现：直接递增分配。生产环境应该使用 Segment 管理。</p>
     */
    private final AtomicInteger nextPageNo;

    /**
     * Undo Page 起始页号
     */
    private final int undoPageStart;

    // ==================== 构造函数 ====================

    /**
     * 创建 Undo Log Manager
     *
     * @param bufferPool Buffer Pool 实例
     * @param spaceId    Undo 表空间 ID
     */
    public UndoLogManager(BufferPool bufferPool, int spaceId) {
        this(bufferPool, spaceId, DEFAULT_ROLLBACK_SEGMENTS, 64);
    }

    /**
     * 创建 Undo Log Manager
     *
     * @param bufferPool          Buffer Pool 实例
     * @param spaceId             Undo 表空间 ID
     * @param numRollbackSegments Rollback Segment 数量
     * @param undoPageStart       Undo Page 起始页号
     */
    public UndoLogManager(BufferPool bufferPool, int spaceId,
                          int numRollbackSegments, int undoPageStart) {
        if (bufferPool == null) {
            throw new NullPointerException("bufferPool cannot be null");
        }
        if (numRollbackSegments <= 0 || numRollbackSegments > MAX_ROLLBACK_SEGMENTS) {
            throw new IllegalArgumentException("Invalid numRollbackSegments: " + numRollbackSegments);
        }

        this.bufferPool = bufferPool;
        this.spaceId = spaceId;
        this.numRollbackSegments = numRollbackSegments;
        this.undoPageStart = undoPageStart;

        // 初始化 Rollback Segment 锁
        this.rsegLocks = new ReentrantLock[numRollbackSegments];
        for (int i = 0; i < numRollbackSegments; i++) {
            rsegLocks[i] = new ReentrantLock();
        }

        this.insertSegments = new ConcurrentHashMap<>();
        this.updateSegments = new ConcurrentHashMap<>();
        this.rsegRoundRobin = new AtomicInteger(0);
        this.nextPageNo = new AtomicInteger(undoPageStart);

        logger.info("UndoLogManager initialized: spaceId={}, rsegCount={}, undoPageStart={}",
                spaceId, numRollbackSegments, undoPageStart);
    }

    // ==================== Segment 分配方法 ====================

    /**
     * 为事务分配 INSERT Undo Segment
     *
     * @param trx 事务
     * @return INSERT Undo Segment
     */
    public UndoSegment assignInsertSegment(Transaction trx) {
        return assignSegment(trx, true);
    }

    /**
     * 为事务分配 UPDATE Undo Segment
     *
     * @param trx 事务
     * @return UPDATE Undo Segment
     */
    public UndoSegment assignUpdateSegment(Transaction trx) {
        return assignSegment(trx, false);
    }

    /**
     * 分配 Undo Segment
     *
     * @param trx      事务
     * @param isInsert 是否是 INSERT Undo
     * @return Undo Segment
     */
    private UndoSegment assignSegment(Transaction trx, boolean isInsert) {
        TransactionId trxId = trx.getId();
        Map<TransactionId, UndoSegment> segmentMap = isInsert ? insertSegments : updateSegments;

        // 检查是否已分配
        UndoSegment existing = segmentMap.get(trxId);
        if (existing != null) {
            return existing;
        }

        // 分配 Rollback Segment ID (轮询方式)
        int rsegId = selectRollbackSegment(trxId);

        // 创建新的 Undo Segment
        UndoSegment segment = new UndoSegment(trxId, rsegId, isInsert);
        segmentMap.put(trxId, segment);

        logger.debug("Assigned {} UndoSegment: trxId={}, rsegId={}",
                isInsert ? "INSERT" : "UPDATE", trxId, rsegId);

        return segment;
    }

    /**
     * 选择 Rollback Segment
     *
     * @param trxId 事务 ID
     * @return Rollback Segment ID
     */
    private int selectRollbackSegment(TransactionId trxId) {
        // 使用事务 ID 的哈希值选择，保证同一事务的分配稳定
        // 同时使用轮询避免热点
        int hash = (int) (trxId.getValue() % numRollbackSegments);
        return hash >= 0 ? hash : hash + numRollbackSegments;
    }

    // ==================== Undo 写入方法 ====================

    /**
     * 写入 INSERT Undo 记录
     *
     * @param mtr     Mini-Transaction
     * @param trx     事务
     * @param tableId 表 ID
     * @param pk      主键数据
     * @return RollbackPointer 指向写入的记录
     * @throws MiniDbException 如果写入失败
     */
    public RollbackPointer writeInsertUndo(MiniTransaction mtr, Transaction trx,
                                           int tableId, byte[] pk) throws MiniDbException {
        UndoSegment segment = getOrAssignInsertSegment(trx);
        InsertUndoRecord undoRecord = new InsertUndoRecord(trx.getId(), tableId, pk);

        return writeUndoRecord(mtr, segment, undoRecord);
    }

    /**
     * 写入 UPDATE Undo 记录
     *
     * @param mtr         Mini-Transaction
     * @param trx         事务
     * @param tableId     表 ID
     * @param prevRollPtr 上一版本的 ROLL_PTR
     * @param pk          主键数据
     * @param oldColumns  旧列值
     * @return RollbackPointer 指向写入的记录
     * @throws MiniDbException 如果写入失败
     */
    public RollbackPointer writeUpdateUndo(MiniTransaction mtr, Transaction trx,
                                           int tableId, RollbackPointer prevRollPtr,
                                           byte[] pk,
                                           java.util.List<UpdateUndoRecord.OldColumnValue> oldColumns)
            throws MiniDbException {
        UndoSegment segment = getOrAssignUpdateSegment(trx);
        UpdateUndoRecord undoRecord = new UpdateUndoRecord(
                trx.getId(), tableId, prevRollPtr, pk, oldColumns);

        return writeUndoRecord(mtr, segment, undoRecord);
    }

    /**
     * 写入 DELETE Undo 记录
     *
     * @param mtr         Mini-Transaction
     * @param trx         事务
     * @param tableId     表 ID
     * @param prevRollPtr 上一版本的 ROLL_PTR
     * @param pk          主键数据
     * @param oldRowData  完整旧行数据
     * @return RollbackPointer 指向写入的记录
     * @throws MiniDbException 如果写入失败
     */
    public RollbackPointer writeDeleteUndo(MiniTransaction mtr, Transaction trx,
                                           int tableId, RollbackPointer prevRollPtr,
                                           byte[] pk, byte[] oldRowData)
            throws MiniDbException {
        UndoSegment segment = getOrAssignUpdateSegment(trx);
        DeleteUndoRecord undoRecord = new DeleteUndoRecord(
                trx.getId(), tableId, prevRollPtr, pk, oldRowData);

        return writeUndoRecord(mtr, segment, undoRecord);
    }

    /**
     * 写入 Undo 记录到 Segment
     *
     * @param mtr        Mini-Transaction
     * @param segment    Undo Segment
     * @param undoRecord Undo 记录
     * @return RollbackPointer
     * @throws MiniDbException 如果写入失败
     */
    private RollbackPointer writeUndoRecord(MiniTransaction mtr, UndoSegment segment,
                                            UndoRecord undoRecord) throws MiniDbException {
        int rsegId = segment.getRsegId();

        // 获取 Rollback Segment 锁
        rsegLocks[rsegId].lock();
        try {
            // 使用 UndoSegment 的 writeUndoRecord 方法
            RollbackPointer rollPtr = segment.writeUndoRecord(
                    undoRecord,
                    (undoType, trxId, rsId) -> allocateUndoPage(mtr, undoType, trxId, rsId)
            );

            logger.trace("Wrote undo record: type={}, trxId={}, rollPtr={}",
                    undoRecord.getType(), undoRecord.getTrxId(), rollPtr);

            return rollPtr;

        } finally {
            rsegLocks[rsegId].unlock();
        }
    }

    /**
     * 分配 Undo Page
     *
     * @param mtr      Mini-Transaction
     * @param undoType Undo 类型
     * @param trxId    事务 ID
     * @param rsegId   Rollback Segment ID
     * @return 页面信息
     */
    private UndoSegment.UndoPageInfo allocateUndoPage(MiniTransaction mtr,
                                                       int undoType,
                                                       TransactionId trxId,
                                                       int rsegId) {
        try {
            // 分配新页号
            int pageNo = nextPageNo.getAndIncrement();
            PageId pageId = PageId.of(spaceId, pageNo);

            // 从 Buffer Pool 获取页面
            Page page = mtr.getPage(pageId, BufferPool.FetchMode.NEW_PAGE);
            ByteBuffer buf = page.getBuffer();
            buf.order(ByteOrder.LITTLE_ENDIAN);

            // 初始化 Undo Page
            UndoPage.init(buf, undoType, trxId, rsegId);

            // 标记脏页
            mtr.markDirty(page);

            logger.debug("Allocated undo page: pageNo={}, undoType={}, trxId={}",
                    pageNo, undoType, trxId);

            return new UndoSegment.UndoPageInfo(pageNo, buf);

        } catch (MiniDbException e) {
            logger.error("Failed to allocate undo page", e);
            throw new RuntimeException("Failed to allocate undo page", e);
        }
    }

    // ==================== Undo 读取方法 ====================

    /**
     * 读取指定位置的 Undo 记录
     *
     * @param rollPtr 回滚指针
     * @return Undo 记录，如果不存在返回 null
     * @throws MiniDbException 如果读取失败
     */
    public UndoRecord readUndoRecord(RollbackPointer rollPtr) throws MiniDbException {
        if (rollPtr == null || rollPtr.isNull()) {
            return null;
        }

        PageId pageId = PageId.of(spaceId, rollPtr.getPageNo());

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
            ByteBuffer buf = page.getBuffer();

            UndoRecord record = UndoPage.readUndoRecord(buf, rollPtr.getOffset());
            mtr.commit();

            return record;
        }
    }

    /**
     * 创建 Undo 记录读取器 (用于 VersionChainReader)
     *
     * @return Undo 记录读取器
     */
    public cn.zhangyis.minidb.storage.transaction.mvcc.VersionChainReader.UndoRecordReader
    createUndoRecordReader() {
        return rollPtr -> {
            try {
                return readUndoRecord(rollPtr);
            } catch (MiniDbException e) {
                logger.warn("Failed to read undo record: {}", rollPtr, e);
                return null;
            }
        };
    }

    // ==================== 事务生命周期方法 ====================

    /**
     * 事务提交时调用
     *
     * <p>标记 Undo Segment 为已提交状态。
     * INSERT Undo 可以被立即清理。</p>
     *
     * @param trx 事务
     */
    public void commitTransaction(Transaction trx) {
        TransactionId trxId = trx.getId();

        UndoSegment insertSeg = insertSegments.get(trxId);
        if (insertSeg != null) {
            insertSeg.markCommitted();
            // INSERT Undo 可以立即清理 (简化实现：直接移除)
            insertSegments.remove(trxId);
            logger.debug("Committed INSERT UndoSegment: trxId={}", trxId);
        }

        UndoSegment updateSeg = updateSegments.get(trxId);
        if (updateSeg != null) {
            updateSeg.markCommitted();
            // UPDATE Undo 需要保留供 MVCC (由 Purge 线程稍后清理)
            logger.debug("Committed UPDATE UndoSegment: trxId={}", trxId);
        }
    }

    /**
     * 事务回滚时调用
     *
     * <p>返回事务的 Undo 链用于执行回滚操作。</p>
     *
     * @param trx 事务
     * @return 需要回滚的 Undo 记录迭代器 (INSERT + UPDATE 混合)
     */
    public Iterable<UndoRecord> rollbackTransaction(Transaction trx) {
        TransactionId trxId = trx.getId();

        // 创建合并迭代器 (先回滚 UPDATE，再回滚 INSERT)
        java.util.List<UndoRecord> records = new java.util.ArrayList<>();

        UndoSegment updateSeg = updateSegments.get(trxId);
        if (updateSeg != null) {
            for (UndoRecord rec : updateSeg.reverseIterate(this::readPageBuffer)) {
                records.add(rec);
            }
            updateSeg.markRolledBack();
            updateSegments.remove(trxId);
        }

        UndoSegment insertSeg = insertSegments.get(trxId);
        if (insertSeg != null) {
            for (UndoRecord rec : insertSeg.reverseIterate(this::readPageBuffer)) {
                records.add(rec);
            }
            insertSeg.markRolledBack();
            insertSegments.remove(trxId);
        }

        return records;
    }

    /**
     * 读取页面缓冲区 (供 UndoSegment 遍历使用)
     *
     * @param pageNo 页号
     * @return 页面缓冲区
     */
    private ByteBuffer readPageBuffer(int pageNo) {
        try {
            PageId pageId = PageId.of(spaceId, pageNo);
            BufferFrame frame = bufferPool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
            return frame.buffer();
        } catch (MiniDbException e) {
            logger.error("Failed to read undo page: {}", pageNo, e);
            throw new RuntimeException("Failed to read undo page: " + pageNo, e);
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取或分配 INSERT Undo Segment
     */
    private UndoSegment getOrAssignInsertSegment(Transaction trx) {
        UndoSegment segment = insertSegments.get(trx.getId());
        if (segment == null) {
            segment = assignInsertSegment(trx);
        }
        return segment;
    }

    /**
     * 获取或分配 UPDATE Undo Segment
     */
    private UndoSegment getOrAssignUpdateSegment(Transaction trx) {
        UndoSegment segment = updateSegments.get(trx.getId());
        if (segment == null) {
            segment = assignUpdateSegment(trx);
        }
        return segment;
    }

    // ==================== 统计信息 ====================

    /**
     * 获取活跃 INSERT Segment 数量
     *
     * @return 数量
     */
    public int getActiveInsertSegmentCount() {
        return insertSegments.size();
    }

    /**
     * 获取活跃 UPDATE Segment 数量
     *
     * @return 数量
     */
    public int getActiveUpdateSegmentCount() {
        return updateSegments.size();
    }

    /**
     * 获取已分配的 Undo Page 数量
     *
     * @return 数量
     */
    public int getAllocatedPageCount() {
        return nextPageNo.get() - undoPageStart;
    }

    /**
     * 获取 Rollback Segment 数量
     *
     * @return 数量
     */
    public int getNumRollbackSegments() {
        return numRollbackSegments;
    }

    // ==================== 关闭方法 ====================

    /**
     * 关闭 Undo Log Manager
     *
     * <p>在数据库关闭时调用，确保所有活跃事务已处理。</p>
     */
    public void close() {
        int activeInsert = insertSegments.size();
        int activeUpdate = updateSegments.size();

        if (activeInsert > 0 || activeUpdate > 0) {
            logger.warn("UndoLogManager closing with active segments: insert={}, update={}",
                    activeInsert, activeUpdate);
        }

        insertSegments.clear();
        updateSegments.clear();

        logger.info("UndoLogManager closed: allocatedPages={}", getAllocatedPageCount());
    }

    @Override
    public String toString() {
        return String.format("UndoLogManager{spaceId=%d, rsegCount=%d, " +
                        "insertSegs=%d, updateSegs=%d, pages=%d}",
                spaceId, numRollbackSegments,
                insertSegments.size(), updateSegments.size(),
                getAllocatedPageCount());
    }
}
