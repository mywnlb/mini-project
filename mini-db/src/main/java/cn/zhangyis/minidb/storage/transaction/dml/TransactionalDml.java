package cn.zhangyis.minidb.storage.transaction.dml;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.btree.BTree;
import cn.zhangyis.minidb.storage.btree.BTreeSearchResult;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.record.RecordHeader;
import cn.zhangyis.minidb.storage.record.format.CompactRecordFormat;
import cn.zhangyis.minidb.storage.record.logical.DataTuple;
import cn.zhangyis.minidb.storage.record.physical.SystemLayout;
import cn.zhangyis.minidb.storage.record.schema.RecordSchema;
import cn.zhangyis.minidb.storage.transaction.core.Transaction;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import cn.zhangyis.minidb.storage.transaction.undo.UndoLogManager;
import cn.zhangyis.minidb.storage.transaction.undo.UpdateUndoRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;

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
        header.setRecType(RecordHeader.REC_TYPE_ORDINARY);
        header.writeTo(buffer, recStart);

        // 编码记录数据
        recordFormat.encodeTo(buffer, recStart, tuple, schema, layout,
                trxId, rollPtr, rowVersion, rowId);

        return buffer.array();
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
}
