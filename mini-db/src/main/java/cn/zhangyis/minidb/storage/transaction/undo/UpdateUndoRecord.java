package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * UPDATE 操作的 Undo 记录
 *
 * <p>当执行 UPDATE 操作时，生成此类型的 Undo 记录。
 * 存储被修改列的旧值，用于回滚和 MVCC 历史版本读取。</p>
 *
 * <h2>Payload 格式</h2>
 * <pre>
 * ┌────────────┬─────────────────────────────────────────────────────┐
 * │ pk_len     │ primary_key_data                                    │
 * │ (2B)       │ (variable)                                          │
 * ├────────────┼─────────────────────────────────────────────────────┤
 * │ n_cols     │ 被修改的列数量                                        │
 * │ (1B)       │                                                     │
 * ├────────────┼──────────┬───────────┬──────────┬───────────┬───────┤
 * │ col_id_1   │ len_1    │ old_val_1 │ col_id_2 │ len_2     │ ...   │
 * │ (2B)       │ (2B)     │ (var)     │ (2B)     │ (2B)      │       │
 * └────────────┴──────────┴───────────┴──────────┴───────────┴───────┘
 * </pre>
 *
 * <h2>完整记录格式</h2>
 * <pre>
 * ┌──────┬──────┬─────────┬──────────┬───────────┬─────────────────────┐
 * │ type │ len  │ trx_id  │ table_id │ prev_undo │ pk + old_columns    │
 * │ 0x0C │ (2B) │ (6B)    │ (4B)     │ (7B)      │ (variable)          │
 * └──────┴──────┴─────────┴──────────┴───────────┴─────────────────────┘
 * </pre>
 *
 * <h2>回滚操作</h2>
 * <p>UPDATE table SET col1=old_val1, col2=old_val2 WHERE pk=...</p>
 *
 * <h2>MVCC 语义</h2>
 * <p>UPDATE Undo 用于重建历史版本：当前行数据 + 旧值 = 历史版本</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class UpdateUndoRecord extends UndoRecord {

    // ==================== Payload 常量 ====================

    /** pk_len 字段大小 */
    private static final int PK_LEN_SIZE = 2;

    /** n_cols 字段大小 */
    private static final int N_COLS_SIZE = 1;

    /** col_id 字段大小 */
    private static final int COL_ID_SIZE = 2;

    /** col_len 字段大小 */
    private static final int COL_LEN_SIZE = 2;

    // ==================== 字段 ====================

    /**
     * 主键数据
     */
    private final byte[] primaryKeyData;

    /**
     * 被修改列的旧值列表
     */
    private final List<OldColumnValue> oldColumns;

    // ==================== 构造函数 ====================

    /**
     * 创建 UPDATE Undo 记录
     *
     * @param trxId          事务 ID
     * @param tableId        表 ID
     * @param prevUndoPtr    上一个 Undo 指针 (指向本行的上一个版本)
     * @param primaryKeyData 主键数据
     * @param oldColumns     被修改列的旧值
     */
    public UpdateUndoRecord(TransactionId trxId, int tableId,
                            RollbackPointer prevUndoPtr,
                            byte[] primaryKeyData,
                            List<OldColumnValue> oldColumns) {
        super(UndoRecordType.UPDATE, trxId, tableId, prevUndoPtr);
        this.primaryKeyData = primaryKeyData != null ? primaryKeyData.clone() : new byte[0];
        this.oldColumns = oldColumns != null ? new ArrayList<>(oldColumns) : new ArrayList<>();
    }

    // ==================== 访问方法 ====================

    /**
     * 获取主键数据
     *
     * @return 主键数据的副本
     */
    public byte[] getPrimaryKeyData() {
        return primaryKeyData.clone();
    }

    /**
     * 获取被修改列的旧值列表
     *
     * @return 旧值列表的副本
     */
    public List<OldColumnValue> getOldColumns() {
        return new ArrayList<>(oldColumns);
    }

    /**
     * 获取被修改的列数量
     *
     * @return 列数量
     */
    public int getColumnCount() {
        return oldColumns.size();
    }

    /**
     * 根据列 ID 获取旧值
     *
     * @param columnId 列 ID
     * @return 旧值，如果不存在返回 null
     */
    public OldColumnValue getOldValue(int columnId) {
        for (OldColumnValue col : oldColumns) {
            if (col.columnId == columnId) {
                return col;
            }
        }
        return null;
    }

    // ==================== 实现抽象方法 ====================

    @Override
    protected int getPayloadSize() {
        int size = PK_LEN_SIZE + primaryKeyData.length + N_COLS_SIZE;
        for (OldColumnValue col : oldColumns) {
            size += COL_ID_SIZE + COL_LEN_SIZE + col.value.length;
        }
        return size;
    }

    @Override
    protected void writePayload(ByteBuffer buf, int offset) {
        int pos = offset;

        // pk_len (2 bytes)
        buf.putShort(pos, (short) primaryKeyData.length);
        pos += PK_LEN_SIZE;

        // pk_data
        for (byte b : primaryKeyData) {
            buf.put(pos++, b);
        }

        // n_cols (1 byte)
        buf.put(pos++, (byte) oldColumns.size());

        // columns
        for (OldColumnValue col : oldColumns) {
            // col_id (2 bytes)
            buf.putShort(pos, (short) col.columnId);
            pos += COL_ID_SIZE;

            // col_len (2 bytes)
            buf.putShort(pos, (short) col.value.length);
            pos += COL_LEN_SIZE;

            // col_value
            for (byte b : col.value) {
                buf.put(pos++, b);
            }
        }
    }

    @Override
    public String getRollbackDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("UPDATE table_%d SET ", tableId));
        for (int i = 0; i < oldColumns.size(); i++) {
            if (i > 0) sb.append(", ");
            OldColumnValue col = oldColumns.get(i);
            sb.append(String.format("col_%d=[%d bytes]", col.columnId, col.value.length));
        }
        sb.append(String.format(" WHERE pk=[%d bytes]", primaryKeyData.length));
        return sb.toString();
    }

    // ==================== 反序列化 ====================

    /**
     * 从 ByteBuffer 读取 payload
     *
     * @param buf    源缓冲区
     * @param offset payload 起始偏移
     * @param header 记录头部
     * @return UPDATE Undo 记录
     */
    static UpdateUndoRecord readPayload(ByteBuffer buf, int offset, UndoRecordHeader header) {
        int pos = offset;

        // pk_len
        int pkLen = buf.getShort(pos) & 0xFFFF;
        pos += PK_LEN_SIZE;

        // pk_data
        byte[] pkData = new byte[pkLen];
        for (int i = 0; i < pkLen; i++) {
            pkData[i] = buf.get(pos++);
        }

        // n_cols
        int nCols = buf.get(pos++) & 0xFF;

        // columns
        List<OldColumnValue> oldColumns = new ArrayList<>(nCols);
        for (int i = 0; i < nCols; i++) {
            // col_id
            int colId = buf.getShort(pos) & 0xFFFF;
            pos += COL_ID_SIZE;

            // col_len
            int colLen = buf.getShort(pos) & 0xFFFF;
            pos += COL_LEN_SIZE;

            // col_value
            byte[] colValue = new byte[colLen];
            for (int j = 0; j < colLen; j++) {
                colValue[j] = buf.get(pos++);
            }

            oldColumns.add(new OldColumnValue(colId, colValue));
        }

        return new UpdateUndoRecord(
                header.trxId(),
                header.tableId(),
                header.prevUndoPtr(),
                pkData,
                oldColumns
        );
    }

    // ==================== Object 方法 ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UpdateUndoRecord that = (UpdateUndoRecord) o;
        return tableId == that.tableId
                && trxId.equals(that.trxId)
                && prevUndoPtr.equals(that.prevUndoPtr)
                && Arrays.equals(primaryKeyData, that.primaryKeyData)
                && oldColumns.equals(that.oldColumns);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(trxId, tableId, prevUndoPtr, oldColumns);
        result = 31 * result + Arrays.hashCode(primaryKeyData);
        return result;
    }

    @Override
    public String toString() {
        return String.format("UpdateUndo{trxId=%s, tableId=%d, pkLen=%d, cols=%d}",
                trxId, tableId, primaryKeyData.length, oldColumns.size());
    }

    // ==================== 内部类：旧列值 ====================

    /**
     * 被修改列的旧值
     */
    public static class OldColumnValue {
        /**
         * 列 ID
         */
        public final int columnId;

        /**
         * 列的旧值 (序列化后)
         */
        public final byte[] value;

        /**
         * 创建旧列值
         *
         * @param columnId 列 ID
         * @param value    旧值
         */
        public OldColumnValue(int columnId, byte[] value) {
            this.columnId = columnId;
            this.value = value != null ? value.clone() : new byte[0];
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OldColumnValue that = (OldColumnValue) o;
            return columnId == that.columnId && Arrays.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            int result = columnId;
            result = 31 * result + Arrays.hashCode(value);
            return result;
        }

        @Override
        public String toString() {
            return String.format("OldCol{id=%d, len=%d}", columnId, value.length);
        }
    }
}
