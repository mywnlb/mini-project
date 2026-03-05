package cn.zhangyis.minidb.storage.transaction.dml;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.btree.BTree;
import cn.zhangyis.minidb.storage.btree.BTreeRangeScanner;
import cn.zhangyis.minidb.storage.btree.BTreeSearchResult;
import cn.zhangyis.minidb.storage.btree.MvccBTreeRangeScanner;
import cn.zhangyis.minidb.storage.btree.RangeBound;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.record.RecordHeader;
import cn.zhangyis.minidb.storage.record.format.CompactRecordFormat;
import cn.zhangyis.minidb.storage.record.logical.DataTuple;
import cn.zhangyis.minidb.storage.record.physical.SystemLayout;
import cn.zhangyis.minidb.storage.record.schema.RecordSchema;
import cn.zhangyis.minidb.storage.transaction.core.Transaction;
import cn.zhangyis.minidb.storage.transaction.mvcc.ReadView;
import cn.zhangyis.minidb.storage.transaction.mvcc.RecordVersion;
import cn.zhangyis.minidb.storage.transaction.mvcc.VersionChainReader;
import cn.zhangyis.minidb.storage.transaction.mvcc.VisibilityChecker;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import cn.zhangyis.minidb.storage.transaction.core.Transaction.IsolationLevel;
import cn.zhangyis.minidb.storage.transaction.lock.LockManager;
import cn.zhangyis.minidb.storage.transaction.lock.LockMode;
import cn.zhangyis.minidb.storage.transaction.undo.UndoLogManager;
import cn.zhangyis.minidb.storage.transaction.undo.UpdateUndoRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * 事务性 DML 操作
 *
 * <p>协调 B+Tree DML 操作与事务子系统（Undo Log、TRX_ID、ROLL_PTR）。</p>
 *
 * <h2>职责</h2>
 * <ul>
 *   <li><b>INSERT</b>: 写入 INSERT Undo，设置 TRX_ID/ROLL_PTR，插入 B+Tree</li>
 *   <li><b>UPDATE</b>: 写入 UPDATE Undo，更新 TRX_ID/ROLL_PTR</li>
 *   <li><b>DELETE</b>: 写入 DELETE Undo，标记删除</li>
 * </ul>
 *
 * <h2>设计约束 (Invariants)</h2>
 * <ul>
 *   <li><b>T3</b>: Undo 必须先于数据修改持久化</li>
 *   <li><b>U8</b>: Undo 记录写入必须在 MTR 提交前完成</li>
 *   <li><b>DML1</b>: 记录修改前必须写入 Undo</li>
 *   <li><b>DML2</b>: TRX_ID 和 ROLL_PTR 必须在记录中正确设置</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * TransactionalDml dml = new TransactionalDml(btree, undoLogManager, bufferPool, schema, layout);
 *
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     // 插入
 *     dml.insert(mtr, trx, tuple, primaryKey);
 *
 *     // 更新
 *     dml.update(mtr, trx, primaryKey, newTuple, oldColumns);
 *
 *     // 删除
 *     dml.delete(mtr, trx, primaryKey);
 *
 *     mtr.commit();
 * }
 * }</pre>
 *
 * @author MiniDB
 * @version 1.0
 * @see UndoLogManager
 * @see RollbackPointer
 */
public class TransactionalDml {

    private static final Logger logger = LoggerFactory.getLogger(TransactionalDml.class);

    // ==================== 字段 ====================

    /**
     * B+Tree 索引
     */
    private final BTree btree;

    /**
     * Undo Log 管理器
     */
    private final UndoLogManager undoLogManager;

    /**
     * Buffer Pool
     */
    private final BufferPool bufferPool;

    /**
     * 记录 Schema
     */
    private final RecordSchema schema;

    /**
     * 系统列布局
     */
    private final SystemLayout layout;

    /**
     * 记录格式
     */
    private final CompactRecordFormat recordFormat;

    /**
     * 表 ID
     */
    private final int tableId;

    /**
     * Lock Manager (可选)
     *
     * <p>DML-L1: 设置后，DML 操作在 B+Tree 物理修改前获取锁。
     * 未设置时退化为纯 MVCC 无锁模式（仅适用于单线程或测试场景）。</p>
     */
    private final LockManager lockManager;

    /**
     * 版本链读取器（用于 MVCC）
     */
    private final VersionChainReader versionChainReader;

    // ==================== 构造函数 ====================

    /**
     * 创建事务性 DML 处理器（无锁模式，向后兼容）
     *
     * @param btree          B+Tree 索引
     * @param undoLogManager Undo Log 管理器
     * @param bufferPool     Buffer Pool
     * @param schema         记录 Schema
     * @param layout         系统列布局
     * @param tableId        表 ID
     */
    public TransactionalDml(BTree btree, UndoLogManager undoLogManager,
                            BufferPool bufferPool, RecordSchema schema,
                            SystemLayout layout, int tableId) {
        this(btree, undoLogManager, bufferPool, schema, layout, tableId, null);
    }

    /**
     * 创建事务性 DML 处理器
     *
     * @param btree          B+Tree 索引
     * @param undoLogManager Undo Log 管理器
     * @param bufferPool     Buffer Pool
     * @param schema         记录 Schema
     * @param layout         系统列布局
     * @param tableId        表 ID
     * @param lockManager    Lock Manager (可为 null，null 时退化为无锁模式)
     */
    public TransactionalDml(BTree btree, UndoLogManager undoLogManager,
                            BufferPool bufferPool, RecordSchema schema,
                            SystemLayout layout, int tableId,
                            LockManager lockManager) {
        if (btree == null || bufferPool == null || schema == null || layout == null) {
            throw new NullPointerException("Required parameters cannot be null");
        }

        this.btree = btree;
        this.undoLogManager = undoLogManager;
        this.bufferPool = bufferPool;
        this.schema = schema;
        this.layout = layout;
        this.tableId = tableId;
        this.lockManager = lockManager;
        this.recordFormat = new CompactRecordFormat();
        this.versionChainReader = undoLogManager != null ?
            new VersionChainReader(undoLogManager.createUndoRecordReader()) : null;
    }

    // ==================== INSERT 操作 ====================

    /**
     * 插入记录
     *
     * <p>执行步骤：</p>
     * <ol>
     *   <li>写入 INSERT Undo 记录（获取 ROLL_PTR）</li>
     *   <li>编码记录（包含 TRX_ID、ROLL_PTR）</li>
     *   <li>插入 B+Tree</li>
     *   <li>更新事务统计</li>
     * </ol>
     *
     * @param mtr        Mini-Transaction
     * @param trx        事务
     * @param tuple      数据元组
     * @param primaryKey 主键字节
     * @return 如果插入成功返回 true
     * @throws MiniDbException 如果操作失败
     */
    public boolean insert(MiniTransaction mtr, Transaction trx,
                          DataTuple tuple, byte[] primaryKey) throws MiniDbException {
        trx.checkActive();

        // DML-L1: 锁获取在 B+Tree 物理修改之前
        if (lockManager != null) {
            // DML-L2: Table IX lock
            lockManager.lockTable(trx, tableId, LockMode.INTENTION_EXCLUSIVE);

            // DML-L5: InsertIntention lock on the gap
            acquireInsertLocks(trx, primaryKey, mtr);
        }

        // 1. 写入 INSERT Undo
        RollbackPointer rollPtr = RollbackPointer.NULL;
        if (undoLogManager != null) {
            rollPtr = undoLogManager.writeInsertUndo(mtr, trx, tableId, primaryKey);
        }

        // 2. 编码记录
        long trxId = trx.getId().getValue();
        long rollPtrValue = rollPtr.encode();
        int rowVersion = 0; // 初始版本
        long rowId = 0;     // 如果使用隐式 ROW_ID，需要从其他地方获取

        byte[] recordData = encodeRecord(tuple, trxId, rollPtrValue, rowVersion, rowId);

        // 3. 插入 B+Tree
        boolean success = btree.insert(recordData, primaryKey, mtr);

        if (success) {
            // 4. 更新事务统计
            trx.incrementInsertCount();
            trx.setLastInsertUndoPtr(rollPtr);

            logger.trace("INSERT: trxId={}, pk={}, rollPtr={}",
                    trxId, bytesToHex(primaryKey), rollPtr);
        }

        return success;
    }

    // ==================== UPDATE 操作 ====================

    /**
     * 更新记录
     *
     * <p>执行步骤：</p>
     * <ol>
     *   <li>搜索记录位置</li>
     *   <li>读取旧记录的 ROLL_PTR（用于版本链）</li>
     *   <li>写入 UPDATE Undo 记录</li>
     *   <li>原地更新或 delete + insert</li>
     * </ol>
     *
     * @param mtr        Mini-Transaction
     * @param trx        事务
     * @param primaryKey 主键
     * @param newTuple   新数据元组
     * @param oldColumns 旧列值列表
     * @return 如果更新成功返回 true
     * @throws MiniDbException 如果操作失败
     */
    public boolean update(MiniTransaction mtr, Transaction trx,
                          byte[] primaryKey, DataTuple newTuple,
                          List<UpdateUndoRecord.OldColumnValue> oldColumns) throws MiniDbException {
        trx.checkActive();

        // DML-L2: Table IX lock
        if (lockManager != null) {
            lockManager.lockTable(trx, tableId, LockMode.INTENTION_EXCLUSIVE);
        }

        // 1. 搜索记录
        BTreeSearchResult searchResult = btree.search(primaryKey, mtr);
        if (!searchResult.isExactMatch()) {
            logger.warn("UPDATE failed: record not found, pk={}", bytesToHex(primaryKey));
            return false;
        }

        // DML-L1: 搜索后、修改前加锁
        // DML-L4: RC = Record X; RR/SERIALIZABLE = NextKey X
        if (lockManager != null) {
            acquireRecordLockForWrite(trx, searchResult, mtr);
        }

        // 2. 读取旧记录的 ROLL_PTR
        RollbackPointer prevRollPtr = readRollPtr(searchResult, mtr);

        // 3. 写入 UPDATE Undo
        RollbackPointer newRollPtr = RollbackPointer.NULL;
        if (undoLogManager != null) {
            newRollPtr = undoLogManager.writeUpdateUndo(
                    mtr, trx, tableId, prevRollPtr, primaryKey, oldColumns);
        }

        // 4. 编码新记录
        long trxId = trx.getId().getValue();
        long rollPtrValue = newRollPtr.encode();
        int rowVersion = readRowVersion(searchResult, mtr) + 1;
        long rowId = 0;

        byte[] newRecordData = encodeRecord(newTuple, trxId, rollPtrValue, rowVersion, rowId);

        // 5. 更新记录
        // 简化实现：删除旧记录，插入新记录
        // 生产环境应该支持原地更新
        btree.delete(primaryKey, getOldRecordSize(searchResult, mtr), mtr);
        boolean success = btree.insert(newRecordData, primaryKey, mtr);

        if (success) {
            trx.incrementUpdateCount();
            trx.setLastUpdateUndoPtr(newRollPtr);

            logger.trace("UPDATE: trxId={}, pk={}, prevRollPtr={}, newRollPtr={}",
                    trxId, bytesToHex(primaryKey), prevRollPtr, newRollPtr);
        }

        return success;
    }

    // ==================== DELETE 操作 ====================

    /**
     * 删除记录
     *
     * <p>执行步骤：</p>
     * <ol>
     *   <li>搜索记录位置</li>
     *   <li>读取旧记录数据</li>
     *   <li>写入 DELETE Undo 记录</li>
     *   <li>标记删除（设置 delete_flag）</li>
     * </ol>
     *
     * <p>注意：MVCC 删除不是物理删除，而是设置 delete_flag。
     * 物理删除由 Purge 线程稍后执行。</p>
     *
     * @param mtr        Mini-Transaction
     * @param trx        事务
     * @param primaryKey 主键
     * @return 如果删除成功返回 true
     * @throws MiniDbException 如果操作失败
     */
    public boolean delete(MiniTransaction mtr, Transaction trx,
                          byte[] primaryKey) throws MiniDbException {
        trx.checkActive();

        // DML-L2: Table IX lock
        if (lockManager != null) {
            lockManager.lockTable(trx, tableId, LockMode.INTENTION_EXCLUSIVE);
        }

        // 1. 搜索记录
        BTreeSearchResult searchResult = btree.search(primaryKey, mtr);
        if (!searchResult.isExactMatch()) {
            logger.warn("DELETE failed: record not found, pk={}", bytesToHex(primaryKey));
            return false;
        }

        // DML-L1: 搜索后、修改前加锁
        // DML-L4: RC = Record X; RR/SERIALIZABLE = NextKey X
        if (lockManager != null) {
            acquireRecordLockForWrite(trx, searchResult, mtr);
        }

        // 2. 读取旧记录信息
        RollbackPointer prevRollPtr = readRollPtr(searchResult, mtr);
        byte[] oldRowData = readOldRowData(searchResult, mtr);

        // 3. 写入 DELETE Undo
        RollbackPointer newRollPtr = RollbackPointer.NULL;
        if (undoLogManager != null) {
            newRollPtr = undoLogManager.writeDeleteUndo(
                    mtr, trx, tableId, prevRollPtr, primaryKey, oldRowData);
        }

        // 4. 标记删除
        // 设置 delete_flag 并更新 TRX_ID/ROLL_PTR
        markDeleted(searchResult, trx.getId().getValue(), newRollPtr.encode(), mtr);

        trx.incrementDeleteCount();
        trx.setLastUpdateUndoPtr(newRollPtr);

        logger.trace("DELETE: trxId={}, pk={}, prevRollPtr={}, newRollPtr={}",
                trx.getId().getValue(), bytesToHex(primaryKey), prevRollPtr, newRollPtr);

        return true;
    }

    // ==================== READ 操作 ====================

    /**
     * 单行读取（支持 MVCC）
     *
     * <p>执行步骤：</p>
     * <ol>
     *   <li>获取 ReadView</li>
     *   <li>在 B+Tree 中搜索记录</li>
     *   <li>检查当前版本可见性</li>
     *   <li>如果不可见，遍历版本链查找可见版本</li>
     *   <li>检查删除标记</li>
     * </ol>
     *
     * @param mtr        Mini-Transaction
     * @param trx        事务
     * @param primaryKey 主键
     * @return 可见的数据元组，如果不存在返回 null
     * @throws MiniDbException 如果操作失败
     */
    public DataTuple read(MiniTransaction mtr, Transaction trx,
                         byte[] primaryKey) throws MiniDbException {
        trx.checkActive();

        // SERIALIZABLE: 普通 SELECT 自动转为锁定读 (DML-L4)
        if (lockManager != null && trx.getIsolationLevel() == IsolationLevel.SERIALIZABLE) {
            lockManager.lockTable(trx, tableId, LockMode.INTENTION_SHARED);
        }

        // 1. 获取 ReadView
        ReadView readView = trx.getOrCreateReadView();

        // 2. 在 B+Tree 中搜索
        BTreeSearchResult result = btree.search(primaryKey, mtr);
        if (!result.isExactMatch()) {
            return null;  // 记录不存在
        }

        // SERIALIZABLE: 搜索后对记录加 NextKey S 锁 (DML-L4)
        if (lockManager != null && trx.getIsolationLevel() == IsolationLevel.SERIALIZABLE) {
            acquireRecordLockForRead(trx, result, mtr);
        }

        // 3. 从页面读取记录
        Page page = mtr.getPage(result.getPageId(), BufferPool.FetchMode.READ_EXISTING);
        RecordVersion currentRecord = readRecordVersion(page, result.getRecordOffset());

        // 4. 检查当前版本是否可见
        if (readView == null || VisibilityChecker.isVisible(currentRecord.getTrxId(), readView)) {
            // 当前版本可见，检查删除标记
            if (currentRecord.isDeleteMarked()) {
                return null;  // 记录已被删除
            }
            return currentRecord.toDataTuple();
        }

        // 5. 当前版本不可见，遍历版本链查找可见版本
        if (versionChainReader == null) {
            return null;  // 无法遍历版本链
        }

        Optional<RecordVersion> visibleVersion = versionChainReader.findVisibleVersion(
            currentRecord.getRollPtr(), readView);

        if (visibleVersion.isPresent()) {
            RecordVersion version = visibleVersion.get();
            // 检查是否是删除标记
            if (version.isDeleteMarked()) {
                return null;  // 记录已被删除
            }
            return version.toDataTuple();
        }

        // 6. 没有找到可见版本，记录对当前事务不存在
        return null;
    }

    /**
     * 范围扫描（支持 MVCC）
     *
     * <p>返回一个迭代器，自动过滤不可见的记录。</p>
     *
     * @param mtr        Mini-Transaction
     * @param trx        事务
     * @param lowerBound 下界（可选）
     * @param upperBound 上界（可选）
     * @return MVCC 感知的迭代器
     * @throws MiniDbException 如果操作失败
     */
    public Iterator<DataTuple> scan(MiniTransaction mtr, Transaction trx,
                                     byte[] lowerBound, byte[] upperBound) throws MiniDbException {
        trx.checkActive();

        // 1. 获取 ReadView
        ReadView readView = trx.getOrCreateReadView();

        // 2. 创建范围边界
        RangeBound lower = lowerBound != null ? RangeBound.inclusive(lowerBound) : RangeBound.unbounded();
        RangeBound upper = upperBound != null ? RangeBound.inclusive(upperBound) : RangeBound.unbounded();

        // 3. 创建 MVCC 感知的 B+Tree 范围扫描器
        MvccBTreeRangeScanner mvccScanner = MvccBTreeRangeScanner.range(
            btree, bufferPool, null, mtr, lower, upper,
            readView, versionChainReader,
            new RecordVersionReaderImpl()
        );

        // 4. 包装为 DataTuple 迭代器
        return new DataTupleIteratorAdapter(mvccScanner);
    }

    // ==================== 锁辅助方法 ====================

    /**
     * INSERT 加锁逻辑 (DML-L5)
     *
     * <p>搜索 B+Tree 确定插入位置：</p>
     * <ul>
     *   <li>key 不存在: 在 next record 上获取 InsertIntention lock</li>
     *   <li>key 已存在: 在现有记录上获取 Record X lock（等待未提交的 INSERT 或检测 duplicate key）</li>
     * </ul>
     */
    private void acquireInsertLocks(Transaction trx, byte[] primaryKey,
                                    MiniTransaction mtr) throws MiniDbException {
        BTreeSearchResult pos = btree.search(primaryKey, mtr);
        Page page = mtr.getPage(pos.getPageId(), BufferPool.FetchMode.READ_EXISTING);
        int spaceId = pos.getPageId().getSpaceId();
        int pageNo = pos.getPageId().getPageNo();

        if (pos.isExactMatch()) {
            // Key 已存在 — 对已有记录加 X lock
            // 如果该记录由另一个活跃事务插入（未提交），lock 会等待
            int heapNo = readHeapNo(page, pos.getRecordOffset());
            lockManager.lockRecord(trx, spaceId, pageNo, heapNo, LockMode.EXCLUSIVE);
        } else {
            // Key 不存在 — InsertIntention lock on the gap (next record)
            int nextHeapNo = readNextRecordHeapNo(page, pos.getRecordOffset());
            lockManager.lockInsertIntention(trx, spaceId, pageNo, nextHeapNo);
        }
    }

    /**
     * UPDATE/DELETE 加锁逻辑 (DML-L4)
     *
     * <p>根据隔离级别选择锁类型：</p>
     * <ul>
     *   <li>RC: Record X lock（仅锁记录，不锁间隙）</li>
     *   <li>RR/SERIALIZABLE: NextKey X lock（锁记录 + 间隙，防止幻读）</li>
     * </ul>
     */
    private void acquireRecordLockForWrite(Transaction trx, BTreeSearchResult searchResult,
                                           MiniTransaction mtr) throws MiniDbException {
        Page page = mtr.getPage(searchResult.getPageId(), BufferPool.FetchMode.READ_EXISTING);
        int heapNo = readHeapNo(page, searchResult.getRecordOffset());
        int spaceId = searchResult.getPageId().getSpaceId();
        int pageNo = searchResult.getPageId().getPageNo();

        if (useNextKeyLock(trx)) {
            lockManager.lockNextKey(trx, spaceId, pageNo, heapNo, LockMode.EXCLUSIVE);
        } else {
            lockManager.lockRecord(trx, spaceId, pageNo, heapNo, LockMode.EXCLUSIVE);
        }
    }

    /**
     * SERIALIZABLE SELECT 加锁逻辑 (DML-L4)
     *
     * <p>SERIALIZABLE 隔离级别下，普通 SELECT 自动转为 NextKey S lock，
     * 等效于 SELECT ... LOCK IN SHARE MODE。</p>
     */
    private void acquireRecordLockForRead(Transaction trx, BTreeSearchResult searchResult,
                                          MiniTransaction mtr) throws MiniDbException {
        Page page = mtr.getPage(searchResult.getPageId(), BufferPool.FetchMode.READ_EXISTING);
        int heapNo = readHeapNo(page, searchResult.getRecordOffset());
        int spaceId = searchResult.getPageId().getSpaceId();
        int pageNo = searchResult.getPageId().getPageNo();

        lockManager.lockNextKey(trx, spaceId, pageNo, heapNo, LockMode.SHARED);
    }

    /**
     * 从 RecordHeader 读取 heapNo
     *
     * @param page         页面
     * @param recordOffset 记录在页面中的偏移量
     * @return heapNo (13 bits, 唯一标识页内记录)
     */
    private int readHeapNo(Page page, int recordOffset) {
        ByteBuffer buf = page.getBuffer();
        RecordHeader header = RecordHeader.readFrom(buf, recordOffset);
        return header.getHeapNo();
    }

    /**
     * 读取 next record 的 heapNo（用于 InsertIntention lock）
     *
     * <p>通过 RecordHeader.nextRecord 相对偏移找到下一条记录，
     * 读取其 heapNo。如果 nextRecord=0（无后继），使用 Supremum 的 heapNo=1。</p>
     *
     * @param page         页面
     * @param recordOffset 当前记录（predecessor）的偏移量
     * @return next record 的 heapNo
     */
    private int readNextRecordHeapNo(Page page, int recordOffset) {
        ByteBuffer buf = page.getBuffer();
        int nextRecordRelative = RecordHeader.peekNextRecord(buf, recordOffset);
        if (nextRecordRelative == 0) {
            // 无后继记录 — 使用 Supremum 约定 (heapNo=1)
            return 1;
        }
        int nextRecordOffset = recordOffset + nextRecordRelative;
        RecordHeader nextHeader = RecordHeader.readFrom(buf, nextRecordOffset);
        return nextHeader.getHeapNo();
    }

    /**
     * 是否使用 NextKey lock（根据隔离级别判断）
     *
     * <p>RC 使用 Record lock（不锁间隙），RR/SERIALIZABLE 使用 NextKey lock。</p>
     */
    private boolean useNextKeyLock(Transaction trx) {
        IsolationLevel level = trx.getIsolationLevel();
        return level == IsolationLevel.REPEATABLE_READ || level == IsolationLevel.SERIALIZABLE;
    }

    // ==================== 辅助方法 ====================

    /**
     * 编码记录
     */
    private byte[] encodeRecord(DataTuple tuple, long trxId, long rollPtr,
                                int rowVersion, long rowId) {
        int size = recordFormat.calculateSize(tuple, schema, layout);
        int extraBytes = recordFormat.calculateExtraBytes(tuple, schema);

        // 分配完整的记录空间
        ByteBuffer buffer = ByteBuffer.allocate(size);

        // 计算 recStart 位置（extraBytes 在 header 之前）
        int recStart = extraBytes;

        // 写入记录头
        // 简化：使用默认的记录头
        RecordHeader header = new RecordHeader();
        header.setRecType(RecordHeader.REC_ORDINARY);
        header.writeTo(buffer, recStart);

        // 编码记录数据
        recordFormat.encodeTo(buffer, recStart, tuple, schema, layout,
                trxId, rollPtr, rowVersion, rowId);

        return buffer.array();
    }

    /**
     * 从页面读取记录版本（用于 MVCC）
     *
     * @param page   页面
     * @param offset 记录在页面中的偏移量
     * @return RecordVersion 对象
     */
    private RecordVersion readRecordVersion(Page page, int offset) {
        ByteBuffer buf = page.getBuffer();

        // 读取记录头
        RecordHeader header = RecordHeader.readFrom(buf, offset);
        int dataStart = offset + RecordHeader.SIZE;

        // 读取 TRX_ID
        long trxId = CompactRecordFormat.readTrxId(buf, dataStart + SystemLayout.OFF_TRX_ID);

        // 读取 ROLL_PTR
        long rollPtrValue = CompactRecordFormat.readRollPtr(buf, dataStart + SystemLayout.OFF_ROLL_PTR);
        RollbackPointer rollPtr = RollbackPointer.decode(rollPtrValue);

        // 读取 DELETE_FLAG
        boolean deleteMarked = header.isDeleted();

        // 创建 RecordVersion
        RecordVersion version = new RecordVersion(
            trxId,
            tableId,
            rollPtr,
            deleteMarked
        );

        return version;
    }

    /**
     * 读取记录的 ROLL_PTR
     */
    private RollbackPointer readRollPtr(BTreeSearchResult searchResult,
                                         MiniTransaction mtr) throws MiniDbException {
        Page page = mtr.getPage(searchResult.getPageId(), BufferPool.FetchMode.READ_EXISTING);
        ByteBuffer buf = page.getBuffer();

        int recStart = searchResult.getRecordOffset();
        int dataStart = recStart + RecordHeader.SIZE;

        long rollPtrValue = CompactRecordFormat.readRollPtr(buf,
                dataStart + SystemLayout.OFF_ROLL_PTR);

        return RollbackPointer.decode(rollPtrValue);
    }

    /**
     * 读取记录的 ROW_VERSION
     */
    private int readRowVersion(BTreeSearchResult searchResult,
                               MiniTransaction mtr) throws MiniDbException {
        Page page = mtr.getPage(searchResult.getPageId(), BufferPool.FetchMode.READ_EXISTING);
        ByteBuffer buf = page.getBuffer();
        int recStart = searchResult.getRecordOffset();
        return recordFormat.peekRowVersion(buf, recStart);
    }

    /**
     * 读取旧记录的完整数据
     */
    private byte[] readOldRowData(BTreeSearchResult searchResult,
                                  MiniTransaction mtr) throws MiniDbException {
        Page page = mtr.getPage(searchResult.getPageId(), BufferPool.FetchMode.READ_EXISTING);
        ByteBuffer buf = page.getBuffer();
        int recStart = searchResult.getRecordOffset();

        // 简化实现：读取固定大小
        // 生产环境应该根据实际记录大小读取
        int recordSize = getOldRecordSize(searchResult, mtr);
        byte[] data = new byte[recordSize];

        for (int i = 0; i < recordSize; i++) {
            data[i] = buf.get(recStart + i);
        }

        return data;
    }

    /**
     * 获取旧记录大小
     */
    private int getOldRecordSize(BTreeSearchResult searchResult,
                                 MiniTransaction mtr) throws MiniDbException {
        // 简化实现：使用固定大小
        // 生产环境应该从记录元数据中获取
        return RecordHeader.SIZE + layout.fixedSysBytes() + 100; // 估算
    }

    /**
     * 标记记录为删除
     */
    private void markDeleted(BTreeSearchResult searchResult,
                             long trxId, long rollPtr,
                             MiniTransaction mtr) throws MiniDbException {
        Page page = mtr.getPage(searchResult.getPageId(), BufferPool.FetchMode.READ_EXISTING);
        ByteBuffer buf = page.getBuffer();
        int recStart = searchResult.getRecordOffset();

        // 1. 设置 delete_flag
        RecordHeader header = RecordHeader.readFrom(buf, recStart);
        header.setDeleted(true);
        header.writeTo(buf, recStart);

        // 2. 更新 TRX_ID
        int dataStart = recStart + RecordHeader.SIZE;
        writeTrxId(buf, dataStart + SystemLayout.OFF_TRX_ID, trxId);

        // 3. 更新 ROLL_PTR
        writeRollPtr(buf, dataStart + SystemLayout.OFF_ROLL_PTR, rollPtr);

        // 标记页面为脏
        mtr.markDirty(page);
    }

    /**
     * 写入 TRX_ID (6 bytes, 大端序)
     */
    private void writeTrxId(ByteBuffer buffer, int offset, long trxId) {
        buffer.put(offset, (byte) ((trxId >> 40) & 0xFF));
        buffer.put(offset + 1, (byte) ((trxId >> 32) & 0xFF));
        buffer.put(offset + 2, (byte) ((trxId >> 24) & 0xFF));
        buffer.put(offset + 3, (byte) ((trxId >> 16) & 0xFF));
        buffer.put(offset + 4, (byte) ((trxId >> 8) & 0xFF));
        buffer.put(offset + 5, (byte) (trxId & 0xFF));
    }

    /**
     * 写入 ROLL_PTR (7 bytes)
     */
    private void writeRollPtr(ByteBuffer buffer, int offset, long rollPtr) {
        buffer.put(offset, (byte) ((rollPtr >> 48) & 0xFF));
        buffer.put(offset + 1, (byte) ((rollPtr >> 40) & 0xFF));
        buffer.put(offset + 2, (byte) ((rollPtr >> 32) & 0xFF));
        buffer.put(offset + 3, (byte) ((rollPtr >> 24) & 0xFF));
        buffer.put(offset + 4, (byte) ((rollPtr >> 16) & 0xFF));
        buffer.put(offset + 5, (byte) ((rollPtr >> 8) & 0xFF));
        buffer.put(offset + 6, (byte) (rollPtr & 0xFF));
    }

    /**
     * 字节数组转十六进制字符串
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 更新记录的 ROLL_PTR（用于 Undo 压缩）
     *
     * <p>在 Undo 压缩后，需要更新记录的 roll_ptr 指向新的合并后的 Undo 记录。
     * 此方法在 MTR 保护下执行原子更新，并生成 redo 日志。</p>
     *
     * <p>设计约束：
     * <ul>
     *   <li><b>I3</b>：Roll_ptr 更新原子性 - MTR 保证 redo 日志生成</li>
     *   <li><b>I5</b>：ABA 冲突检测 - 使用 LSN 验证</li>
     *   <li><b>DML2</b>：TRX_ID 和 ROLL_PTR 必须在记录中正确设置</li>
     * </ul>
     * </p>
     *
     * @param pageId       数据页 ID
     * @param recordOffset 记录在页内的偏移
     * @param newRollPtr   新的 ROLL_PTR 值
     * @param mtr          迷你事务
     * @return 是否成功更新
     * @throws MiniDbException 如果更新失败
     */
    public boolean updateRollPtr(PageId pageId, int recordOffset, long newRollPtr,
                                 MiniTransaction mtr) throws MiniDbException {
        return updateRollPtr(bufferPool, pageId, recordOffset, newRollPtr, mtr);
    }

    /**
     * 在给定页面上更新 ROLL_PTR（静态工具方法）
     *
     * @param bufferPool   Buffer Pool
     * @param pageId       数据页 ID
     * @param recordOffset 记录在页内的偏移
     * @param newRollPtr   新的 ROLL_PTR 值
     * @param mtr          迷你事务
     * @return 是否成功更新
     * @throws MiniDbException 如果更新失败
     */
    public static boolean updateRollPtr(BufferPool bufferPool, PageId pageId,
                                        int recordOffset, long newRollPtr,
                                        MiniTransaction mtr) throws MiniDbException {
        try {
            if (bufferPool == null) {
                throw new NullPointerException("bufferPool cannot be null");
            }

            // 获取数据页（READ_EXISTING 模式）
            Page page = mtr.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
            ByteBuffer buf = page.getBuffer();

            // 计算 ROLL_PTR 在页内的绝对偏移
            // 记录布局：RecordHeader + SystemLayout
            // ROLL_PTR 位置：recordOffset + RecordHeader.SIZE + SystemLayout.OFF_ROLL_PTR
            int dataStart = recordOffset + RecordHeader.SIZE;
            int rollPtrOffset = dataStart + SystemLayout.OFF_ROLL_PTR;

            // 写入 ROLL_PTR（7 字节，大端序）
            buf.put(rollPtrOffset,     (byte) ((newRollPtr >> 48) & 0xFF));
            buf.put(rollPtrOffset + 1, (byte) ((newRollPtr >> 40) & 0xFF));
            buf.put(rollPtrOffset + 2, (byte) ((newRollPtr >> 32) & 0xFF));
            buf.put(rollPtrOffset + 3, (byte) ((newRollPtr >> 24) & 0xFF));
            buf.put(rollPtrOffset + 4, (byte) ((newRollPtr >> 16) & 0xFF));
            buf.put(rollPtrOffset + 5, (byte) ((newRollPtr >> 8) & 0xFF));
            buf.put(rollPtrOffset + 6, (byte) (newRollPtr & 0xFF));

            // 标记脏页，MTR 会自动生成 redo 日志
            mtr.markDirty(page);

            logger.debug("Updated ROLL_PTR: pageId={}, recordOffset={}, newRollPtr={}, " +
                    "rollPtrOffset={}",
                    pageId.getPageNo(), recordOffset, String.format("0x%014x", newRollPtr), rollPtrOffset);

            return true;

        } catch (Exception e) {
            logger.error("Failed to update ROLL_PTR: pageId={}, recordOffset={}, newRollPtr={}",
                    pageId.getPageNo(), recordOffset, String.format("0x%014x", newRollPtr), e);
            throw new MiniDbException("Failed to update ROLL_PTR: " + e.getMessage(), e);
        }
    }

    // ==================== Getter 方法 ====================

    /**
     * 获取 B+Tree
     */
    public BTree getBTree() {
        return btree;
    }

    /**
     * 获取 Undo Log 管理器
     */
    public UndoLogManager getUndoLogManager() {
        return undoLogManager;
    }

    /**
     * 获取表 ID
     */
    public int getTableId() {
        return tableId;
    }

    /**
     * 获取版本链读取器
     */
    public VersionChainReader getVersionChainReader() {
        return versionChainReader;
    }

    /**
     * 获取 Lock Manager
     */
    public LockManager getLockManager() {
        return lockManager;
    }

    /**
     * 从页面读取记录版本（公开方法，用于 MVCC）
     *
     * @param page   页面
     * @param offset 记录在页面中的偏移量
     * @return RecordVersion 对象
     */
    public RecordVersion readRecordVersionPublic(Page page, int offset) {
        return readRecordVersion(page, offset);
    }

    // ==================== 内部类 ====================

    /**
     * 记录版本读取器实现
     */
    private class RecordVersionReaderImpl implements MvccBTreeRangeScanner.RecordVersionReader {
        @Override
        public RecordVersion readRecordVersion(byte[] key, byte[] value) throws MiniDbException {
            // 从 B+Tree 的键值对中读取记录版本信息
            // 这里假设 value 包含了 TRX_ID、ROLL_PTR 等系统列信息

            // 简化实现：从 value 中读取系统列信息
            // 实际实现需要根据具体的记录格式调整

            if (value == null || value.length < 13) {
                // 最少需要 6 字节 TRX_ID + 7 字节 ROLL_PTR
                throw new MiniDbException("Invalid record value length");
            }

            ByteBuffer buf = ByteBuffer.wrap(value);

            // 读取 TRX_ID (6 bytes)
            long trxId = 0;
            for (int i = 0; i < 6; i++) {
                trxId = (trxId << 8) | (buf.get(i) & 0xFF);
            }

            // 读取 ROLL_PTR (7 bytes)
            long rollPtrValue = 0;
            for (int i = 6; i < 13; i++) {
                rollPtrValue = (rollPtrValue << 8) | (buf.get(i) & 0xFF);
            }
            RollbackPointer rollPtr = RollbackPointer.decode(rollPtrValue);

            // 读取 DELETE_FLAG (1 byte)
            boolean deleteMarked = false;
            if (value.length > 13) {
                deleteMarked = (buf.get(13) & 0x01) != 0;
            }

            // 创建 RecordVersion
            return new RecordVersion(trxId, tableId, rollPtr, deleteMarked);
        }
    }

    /**
     * DataTuple 迭代器适配器
     *
     * <p>将 BTreeRangeScanner.ScanEntry 迭代器转换为 DataTuple 迭代器。</p>
     */
    private static class DataTupleIteratorAdapter implements Iterator<DataTuple> {
        private final Iterator<BTreeRangeScanner.ScanEntry> scanIterator;

        DataTupleIteratorAdapter(MvccBTreeRangeScanner mvccScanner) {
            this.scanIterator = mvccScanner.iterator();
        }

        @Override
        public boolean hasNext() {
            return scanIterator.hasNext();
        }

        @Override
        public DataTuple next() {
            BTreeRangeScanner.ScanEntry entry = scanIterator.next();
            // 这里需要将 ScanEntry 转换为 DataTuple
            // 简化实现：返回一个占位符
            // 实际实现需要根据具体的记录格式调整
            return null; // TODO: 实现转换逻辑
        }
    }
}
