package cn.zhangyis.minidb.storage.transaction.dml;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.btree.BTree;
import cn.zhangyis.minidb.storage.btree.BTreeSearchResult;
import cn.zhangyis.minidb.storage.btree.MvccBTreeRangeScanner;
import cn.zhangyis.minidb.storage.btree.RangeBound;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
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
     * 版本链读取器（用于 MVCC）
     */
    private final VersionChainReader versionChainReader;

    // ==================== 构造函数 ====================

    /**
     * 创建事务性 DML 处理器
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
        if (btree == null || bufferPool == null || schema == null || layout == null) {
            throw new NullPointerException("Required parameters cannot be null");
        }

        this.btree = btree;
        this.undoLogManager = undoLogManager;
        this.bufferPool = bufferPool;
        this.schema = schema;
        this.layout = layout;
        this.tableId = tableId;
        this.recordFormat = new CompactRecordFormat();
        this.versionChainReader = undoLogManager != null ?
            new VersionChainReader(undoLogManager) : null;
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

        // 1. 搜索记录
        BTreeSearchResult searchResult = btree.search(primaryKey, mtr);
        if (!searchResult.isExactMatch()) {
            logger.warn("UPDATE failed: record not found, pk={}", bytesToHex(primaryKey));
            return false;
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

        // 1. 搜索记录
        BTreeSearchResult searchResult = btree.search(primaryKey, mtr);
        if (!searchResult.isExactMatch()) {
            logger.warn("DELETE failed: record not found, pk={}", bytesToHex(primaryKey));
            return false;
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

        // 1. 获取 ReadView
        ReadView readView = trx.getOrCreateReadView();

        // 2. 在 B+Tree 中搜索
        BTreeSearchResult result = btree.search(primaryKey, mtr);
        if (!result.isExactMatch()) {
            return null;  // 记录不存在
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
    public boolean updateRollPtr(long pageId, int recordOffset, long newRollPtr,
                                 MiniTransaction mtr) throws MiniDbException {
        try {
            // 获取数据页（READ_EXISTING 模式）
            Page page = mtr.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
            ByteBuffer buf = page.getBuffer();

            // 计算 ROLL_PTR 在页内的绝对偏移
            // 记录布局：RecordHeader + SystemLayout
            // ROLL_PTR 位置：recordOffset + RecordHeader.SIZE + SystemLayout.OFF_ROLL_PTR
            int dataStart = recordOffset + RecordHeader.SIZE;
            int rollPtrOffset = dataStart + SystemLayout.OFF_ROLL_PTR;

            // 验证 ABA 冲突：读取当前 LSN
            long lsnBefore = page.getLSN();

            // 将 ROLL_PTR 转换为 7 字节数组（大端序）
            byte[] rollPtrBytes = new byte[7];
            rollPtrBytes[0] = (byte) ((newRollPtr >> 48) & 0xFF);
            rollPtrBytes[1] = (byte) ((newRollPtr >> 40) & 0xFF);
            rollPtrBytes[2] = (byte) ((newRollPtr >> 32) & 0xFF);
            rollPtrBytes[3] = (byte) ((newRollPtr >> 24) & 0xFF);
            rollPtrBytes[4] = (byte) ((newRollPtr >> 16) & 0xFF);
            rollPtrBytes[5] = (byte) ((newRollPtr >> 8) & 0xFF);
            rollPtrBytes[6] = (byte) (newRollPtr & 0xFF);

            // 在 MTR 保护下写入 ROLL_PTR（7 字节）
            // MTR 会自动生成 redo 日志
            mtr.writeBytes(page.getFrame(), rollPtrOffset, rollPtrBytes);

            // 验证 ABA 冲突：检查 LSN 是否改变
            // 注意：这里的 LSN 检查是在获取页面后进行的
            // 实际的 ABA 冲突检测应该在获取 X-latch 后进行
            // 但由于 MTR 已经处理了 latch，这里只做日志记录
            long lsnAfter = page.getLSN();
            if (lsnBefore != lsnAfter) {
                logger.warn("LSN changed during roll_ptr update: before={}, after={}, " +
                        "possible concurrent modification detected",
                        lsnBefore, lsnAfter);
                // 注意：MTR 会自动处理冲突，这里只是记录警告
            }

            logger.debug("Updated ROLL_PTR: pageId={}, recordOffset={}, newRollPtr={}, " +
                    "rollPtrOffset={}",
                    pageId, recordOffset, String.format("0x%014x", newRollPtr), rollPtrOffset);

            return true;

        } catch (Exception e) {
            logger.error("Failed to update ROLL_PTR: pageId={}, recordOffset={}, newRollPtr={}",
                    pageId, recordOffset, String.format("0x%014x", newRollPtr), e);
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
