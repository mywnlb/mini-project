package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * UPDATE 操作的 Undo 记录（支持增量格式）
 *
 * <p>支持两种格式：
 * <ul>
 *   <li><b>V1 格式</b>：存储所有列的旧值（原始格式）</li>
 *   <li><b>V2 格式</b>：只存储修改的列（增量格式，减少空间占用 30-70%）</li>
 * </ul>
 * </p>
 *
 * <h2>V1 格式（原始）</h2>
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
 * <h2>V2 格式（增量）</h2>
 * <pre>
 * ┌──────────┬──────────┬────────────┬─────────────────────────────┐
 * │ fmt_ver  │ sch_ver  │ pk_len     │ primary_key_data            │
 * │ (1B)     │ (1B)     │ (2B)       │ (variable)                  │
 * ├──────────┼──────────┼────────────┼─────────────────────────────┤
 * │ n_cols   │ 被修改的列数量                                        │
 * │ (1B)     │                                                     │
 * ├──────────┼──────────┬───────────┬──────────┬───────────┬───────┤
 * │ col_id_1 │ len_1    │ old_val_1 │ col_id_2 │ len_2     │ ...   │
 * │ (2B)     │ (2B)     │ (var)     │ (2B)     │ (2B)      │       │
 * └──────────┴──────────┴───────────┴──────────┴───────────┴───────┘
 * </pre>
 *
 * <h2>设计约束</h2>
 * <ul>
 *   <li><b>U1</b>：Undo 记录不可修改</li>
 *   <li><b>U2</b>：版本链完整性 - 需要确保列继承逻辑正确</li>
 *   <li><b>U8</b>：Undo 读取安全 - 仍然无需锁</li>
 * </ul>
 *
 * @author MiniDB
 * @version 2.0
 */
public class UpdateUndoRecord extends UndoRecord {

    // ==================== Payload 常量 ====================

    /** V1 格式：pk_len 字段大小 */
    private static final int PK_LEN_SIZE = 2;

    /** V1 格式：n_cols 字段大小 */
    private static final int N_COLS_SIZE = 1;

    /** V2 格式：format_version 字段大小 */
    private static final int FORMAT_VERSION_SIZE = 1;

    /** V2 格式：schema_version 字段大小 */
    private static final int SCHEMA_VERSION_SIZE = 1;

    /** col_id 字段大小 */
    private static final int COL_ID_SIZE = 2;

    /** col_len 字段大小 */
    private static final int COL_LEN_SIZE = 2;

    // ==================== 字段 ====================

    /**
     * 格式版本（V1 或 V2）
     */
    private final byte formatVersion;

    /**
     * Schema 版本
     */
    private final byte schemaVersion;

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
     * 创建 UPDATE Undo 记录（V1 格式）
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
        this(trxId, tableId, prevUndoPtr, primaryKeyData, oldColumns,
                UndoRecordVersion.FORMAT_V1, UndoRecordVersion.INITIAL_SCHEMA_VERSION);
    }

    /**
     * 创建 UPDATE Undo 记录（支持版本）
     *
     * @param trxId          事务 ID
     * @param tableId        表 ID
     * @param prevUndoPtr    上一个 Undo 指针
     * @param primaryKeyData 主键数据
     * @param oldColumns     被修改列的旧值
     * @param formatVersion  格式版本
     * @param schemaVersion  Schema 版本
     */
    public UpdateUndoRecord(TransactionId trxId, int tableId,
                            RollbackPointer prevUndoPtr,
                            byte[] primaryKeyData,
                            List<OldColumnValue> oldColumns,
                            byte formatVersion,
                            byte schemaVersion) {
        super(UndoRecordType.UPDATE, trxId, tableId, prevUndoPtr);

        if (!UndoRecordVersion.isValidFormatVersion(formatVersion)) {
            throw new IllegalArgumentException("Invalid format version: " + formatVersion);
        }
        if (!UndoRecordVersion.isValidSchemaVersion(schemaVersion)) {
            throw new IllegalArgumentException("Invalid schema version: " + schemaVersion);
        }

        this.formatVersion = formatVersion;
        this.schemaVersion = schemaVersion;
        this.primaryKeyData = primaryKeyData != null ? primaryKeyData.clone() : new byte[0];
        this.oldColumns = oldColumns != null ? new ArrayList<>(oldColumns) : new ArrayList<>();
    }

    // ==================== 访问方法 ====================

    /**
     * 获取格式版本
     *
     * @return 格式版本
     */
    public byte getFormatVersion() {
        return formatVersion;
    }

    /**
     * 获取 Schema 版本
     *
     * @return Schema 版本
     */
    public byte getSchemaVersion() {
        return schemaVersion;
    }

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

    /**
     * 是否为增量格式
     *
     * @return 如果是增量格式返回 true
     */
    public boolean isIncrementalFormat() {
        return UndoRecordVersion.isIncrementalFormat(formatVersion);
    }

    /**
     * 是否为原始格式
     *
     * @return 如果是原始格式返回 true
     */
    public boolean isOriginalFormat() {
        return UndoRecordVersion.isOriginalFormat(formatVersion);
    }

    // ==================== 实现抽象方法 ====================

    @Override
    protected int getPayloadSize() {
        int size = 0;

        // 版本字段（V2 格式）
        if (isIncrementalFormat()) {
            size += FORMAT_VERSION_SIZE + SCHEMA_VERSION_SIZE;
        }

        // 主键
        size += PK_LEN_SIZE + primaryKeyData.length;

        // 列数量
        size += N_COLS_SIZE;

        // 列数据
        for (OldColumnValue col : oldColumns) {
            size += COL_ID_SIZE + COL_LEN_SIZE + col.value.length;
        }

        return size;
    }

    @Override
    protected void writePayload(ByteBuffer buf, int offset) {
        int pos = offset;

        // 版本字段（V2 格式）
        if (isIncrementalFormat()) {
            buf.put(pos++, formatVersion);
            buf.put(pos++, schemaVersion);
        }

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
        sb.append(String.format(" WHERE pk=[%d bytes] (fmt=%s, sch=0x%02X)",
                primaryKeyData.length,
                UndoRecordVersion.getFormatVersionName(formatVersion),
                schemaVersion));
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
        byte formatVersion = UndoRecordVersion.FORMAT_V1;
        byte schemaVersion = UndoRecordVersion.INITIAL_SCHEMA_VERSION;

        // 尝试读取版本字段（V2 格式）
        // 启发式：如果第一个字节看起来像版本号（0x01 或 0x02），则为 V2 格式
        byte firstByte = buf.get(pos);
        if (UndoRecordVersion.isValidFormatVersion(firstByte)) {
            // 可能是 V2 格式，检查第二个字节是否为有效的 Schema 版本
            byte secondByte = buf.get(pos + 1);
            if (UndoRecordVersion.isValidSchemaVersion(secondByte)) {
                // 确认为 V2 格式
                formatVersion = firstByte;
                schemaVersion = secondByte;
                pos += FORMAT_VERSION_SIZE + SCHEMA_VERSION_SIZE;
            }
        }

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
                oldColumns,
                formatVersion,
                schemaVersion
        );
    }

    // ==================== Object 方法 ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UpdateUndoRecord that = (UpdateUndoRecord) o;
        return tableId == that.tableId
                && formatVersion == that.formatVersion
                && schemaVersion == that.schemaVersion
                && trxId.equals(that.trxId)
                && prevUndoPtr.equals(that.prevUndoPtr)
                && Arrays.equals(primaryKeyData, that.primaryKeyData)
                && oldColumns.equals(that.oldColumns);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(trxId, tableId, prevUndoPtr, formatVersion, schemaVersion, oldColumns);
        result = 31 * result + Arrays.hashCode(primaryKeyData);
        return result;
    }

    @Override
    public String toString() {
        return String.format("UpdateUndo{trxId=%s, tableId=%d, fmt=%s, sch=0x%02X, pkLen=%d, cols=%d}",
                trxId, tableId, UndoRecordVersion.getFormatVersionName(formatVersion),
                schemaVersion, primaryKeyData.length, oldColumns.size());
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
