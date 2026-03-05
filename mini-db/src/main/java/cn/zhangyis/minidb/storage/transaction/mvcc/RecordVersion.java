package cn.zhangyis.minidb.storage.transaction.mvcc;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import cn.zhangyis.minidb.storage.transaction.undo.UndoRecord;
import cn.zhangyis.minidb.storage.transaction.undo.UpdateUndoRecord;

import java.util.List;
import java.util.Map;

/**
 * 记录版本数据
 *
 * <p>表示记录的一个历史版本，包含版本元数据和列值。</p>
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li>MVCC 版本链读取时返回历史版本</li>
 *   <li>用于构建满足 ReadView 的可见版本</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class RecordVersion {

    // ==================== 版本元数据 ====================

    /**
     * 产生此版本的事务 ID
     */
    private final TransactionId trxId;

    /**
     * 表 ID
     */
    private final int tableId;

    /**
     * 指向更早版本的回滚指针
     */
    private final RollbackPointer prevVersionPtr;

    /**
     * 主键数据
     */
    private final byte[] primaryKey;

    /**
     * 列值映射 (columnId -> value)
     *
     * <p>只包含与当前版本不同的列值（差异存储）</p>
     */
    private final Map<Integer, byte[]> columnValues;

    /**
     * 是否是版本链的起点 (INSERT 操作创建的)
     */
    private final boolean isInsertVersion;

    /**
     * 是否是删除标记版本
     */
    private final boolean isDeleteMarked;

    // ==================== 构造函数 ====================

    /**
     * 创建记录版本
     *
     * @param trxId          事务 ID
     * @param tableId        表 ID
     * @param prevVersionPtr 上一版本指针
     * @param primaryKey     主键数据
     * @param columnValues   列值映射
     * @param isInsertVersion 是否是 INSERT 版本
     * @param isDeleteMarked 是否是删除标记
     */
    public RecordVersion(TransactionId trxId,
                         int tableId,
                         RollbackPointer prevVersionPtr,
                         byte[] primaryKey,
                         Map<Integer, byte[]> columnValues,
                         boolean isInsertVersion,
                         boolean isDeleteMarked) {
        this.trxId = trxId;
        this.tableId = tableId;
        this.prevVersionPtr = prevVersionPtr;
        this.primaryKey = primaryKey != null ? primaryKey.clone() : new byte[0];
        this.columnValues = columnValues != null ? Map.copyOf(columnValues) : Map.of();
        this.isInsertVersion = isInsertVersion;
        this.isDeleteMarked = isDeleteMarked;
    }

    // ==================== 工厂方法 ====================

    /**
     * 从 UPDATE Undo 记录创建版本
     *
     * @param undoRecord UPDATE Undo 记录
     * @return 记录版本
     */
    public static RecordVersion fromUpdateUndo(UpdateUndoRecord undoRecord) {
        Map<Integer, byte[]> values = new java.util.HashMap<>();
        for (UpdateUndoRecord.OldColumnValue col : undoRecord.getOldColumns()) {
            values.put(col.columnId, col.value.clone());
        }

        return new RecordVersion(
                undoRecord.getTrxId(),
                undoRecord.getTableId(),
                undoRecord.getPrevUndoPtr(),
                undoRecord.getPrimaryKeyData(),
                values,
                false,  // UPDATE 不是 INSERT 版本
                false   // UPDATE Undo 不是删除标记
        );
    }

    /**
     * 创建表示 INSERT 起点的版本 (记录之前不存在)
     *
     * @param trxId      事务 ID
     * @param tableId    表 ID
     * @param primaryKey 主键数据
     * @return 记录版本
     */
    public static RecordVersion createInsertOrigin(TransactionId trxId,
                                                   int tableId,
                                                   byte[] primaryKey) {
        return new RecordVersion(
                trxId,
                tableId,
                RollbackPointer.NULL,
                primaryKey,
                Map.of(),
                true,   // 是 INSERT 版本
                false   // 不是删除标记
        );
    }

    /**
     * 创建表示删除标记的版本
     *
     * @param trxId          事务 ID
     * @param tableId        表 ID
     * @param prevVersionPtr 上一版本指针
     * @param primaryKey     主键数据
     * @return 记录版本
     */
    public static RecordVersion createDeleteMarked(TransactionId trxId,
                                                   int tableId,
                                                   RollbackPointer prevVersionPtr,
                                                   byte[] primaryKey) {
        return new RecordVersion(
                trxId,
                tableId,
                prevVersionPtr,
                primaryKey,
                Map.of(),
                false,  // 不是 INSERT 版本
                true    // 是删除标记
        );
    }

    /**
     * 简化构造函数（用于从页面读取记录版本）
     *
     * <p>当只需要版本元数据（trxId、tableId、rollPtr、deleteMarked）时使用。</p>
     *
     * @param trxIdValue     事务 ID（long 值）
     * @param tableId        表 ID
     * @param prevVersionPtr 上一版本指针
     * @param isDeleteMarked 是否是删除标记
     */
    public RecordVersion(long trxIdValue, int tableId,
                         RollbackPointer prevVersionPtr,
                         boolean isDeleteMarked) {
        this(new TransactionId(trxIdValue), tableId, prevVersionPtr,
                null, null, false, isDeleteMarked);
    }

    // ==================== 转换方法 ====================

    /**
     * 转换为 DataTuple
     *
     * <p>将版本的列值映射转换为 DataTuple 对象。
     * 如果没有列值信息，返回一个空的 DataTuple。</p>
     *
     * @return DataTuple 对象
     */
    public cn.zhangyis.minidb.storage.record.logical.DataTuple toDataTuple() {
        if (columnValues.isEmpty()) {
            return cn.zhangyis.minidb.storage.record.logical.DataTuple.create(0);
        }
        int maxColId = columnValues.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        cn.zhangyis.minidb.storage.record.logical.DataTuple tuple =
                cn.zhangyis.minidb.storage.record.logical.DataTuple.create(maxColId + 1);
        for (var entry : columnValues.entrySet()) {
            tuple.setField(entry.getKey(),
                    cn.zhangyis.minidb.storage.record.logical.DataField.varbinaryField(entry.getValue()));
        }
        return tuple;
    }

    // ==================== 访问方法 ====================

    /**
     * 获取事务 ID
     *
     * @return 事务 ID
     */
    public TransactionId getTrxId() {
        return trxId;
    }

    /**
     * 获取表 ID
     *
     * @return 表 ID
     */
    public int getTableId() {
        return tableId;
    }

    /**
     * 获取上一版本指针
     *
     * @return 回滚指针
     */
    public RollbackPointer getPrevVersionPtr() {
        return prevVersionPtr;
    }

    /**
     * 获取回滚指针（getPrevVersionPtr 的别名）
     *
     * <p>为调用方提供语义更直接的 API。</p>
     *
     * @return 回滚指针
     */
    public RollbackPointer getRollPtr() {
        return prevVersionPtr;
    }

    /**
     * 获取主键数据
     *
     * @return 主键数据副本
     */
    public byte[] getPrimaryKey() {
        return primaryKey.clone();
    }

    /**
     * 获取列值
     *
     * @param columnId 列 ID
     * @return 列值，如果不存在返回 null
     */
    public byte[] getColumnValue(int columnId) {
        byte[] value = columnValues.get(columnId);
        return value != null ? value.clone() : null;
    }

    /**
     * 获取所有列值
     *
     * @return 列值映射（不可变）
     */
    public Map<Integer, byte[]> getColumnValues() {
        return columnValues;
    }

    /**
     * 是否是 INSERT 版本（版本链起点）
     *
     * @return true 如果是 INSERT 版本
     */
    public boolean isInsertVersion() {
        return isInsertVersion;
    }

    /**
     * 是否是删除标记版本
     *
     * @return true 如果是删除标记
     */
    public boolean isDeleteMarked() {
        return isDeleteMarked;
    }

    /**
     * 是否有更早的版本
     *
     * @return true 如果存在更早版本
     */
    public boolean hasPreviousVersion() {
        return !prevVersionPtr.isNull() && !isInsertVersion;
    }

    // ==================== Object 方法 ====================

    @Override
    public String toString() {
        return String.format(
                "RecordVersion{trxId=%s, tableId=%d, cols=%d, insert=%s, delete=%s, prev=%s}",
                trxId, tableId, columnValues.size(), isInsertVersion, isDeleteMarked, prevVersionPtr);
    }
}
