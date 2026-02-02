package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * DELETE 操作的 Undo 记录
 *
 * <p>当执行 DELETE 操作时，生成此类型的 Undo 记录。
 * 存储完整的旧行数据，用于回滚和 MVCC 历史版本读取。</p>
 *
 * <h2>InnoDB 的删除机制</h2>
 * <p>InnoDB 采用标记删除 (delete-mark) 机制：
 * <ol>
 *   <li>DELETE 操作只设置记录的 delete_flag，不立即物理删除</li>
 *   <li>Undo 记录存储完整旧行，用于 MVCC 读取和回滚</li>
 *   <li>Purge 线程在所有活跃事务都不需要该版本时执行物理删除</li>
 * </ol>
 * </p>
 *
 * <h2>Payload 格式</h2>
 * <pre>
 * ┌────────────┬─────────────────────┐
 * │ row_len    │ full_row_data       │
 * │ (2B)       │ (variable)          │
 * └────────────┴─────────────────────┘
 * </pre>
 *
 * <h2>完整记录格式</h2>
 * <pre>
 * ┌──────┬──────┬─────────┬──────────┬───────────┬─────────┬───────────┐
 * │ type │ len  │ trx_id  │ table_id │ prev_undo │ row_len │ row_data  │
 * │ 0x0D │ (2B) │ (6B)    │ (4B)     │ (7B)      │ (2B)    │ (var)     │
 * └──────┴──────┴─────────┴──────────┴───────────┴─────────┴───────────┘
 * </pre>
 *
 * <h2>回滚操作</h2>
 * <p>清除 delete_flag，恢复行的可见性（无需插入，行仍在原位置）</p>
 *
 * <h2>MVCC 语义</h2>
 * <p>DELETE Undo 包含删除前的完整行数据，用于重建历史版本</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class DeleteUndoRecord extends UndoRecord {

    // ==================== Payload 常量 ====================

    /** row_len 字段大小 */
    private static final int ROW_LEN_SIZE = 2;

    // ==================== 字段 ====================

    /**
     * 完整的旧行数据 (序列化后)
     *
     * <p>包含用户列数据，不含系统列 (TRX_ID, ROLL_PTR)。
     * 系统列由记录头部提供。</p>
     */
    private final byte[] oldRowData;

    /**
     * 主键数据 (可选，用于快速定位)
     */
    private final byte[] primaryKeyData;

    // ==================== 构造函数 ====================

    /**
     * 创建 DELETE Undo 记录
     *
     * @param trxId          事务 ID
     * @param tableId        表 ID
     * @param prevUndoPtr    上一个 Undo 指针
     * @param primaryKeyData 主键数据
     * @param oldRowData     完整的旧行数据
     */
    public DeleteUndoRecord(TransactionId trxId, int tableId,
                            RollbackPointer prevUndoPtr,
                            byte[] primaryKeyData,
                            byte[] oldRowData) {
        super(UndoRecordType.DELETE_MARK, trxId, tableId, prevUndoPtr);
        this.primaryKeyData = primaryKeyData != null ? primaryKeyData.clone() : new byte[0];
        this.oldRowData = oldRowData != null ? oldRowData.clone() : new byte[0];
    }

    /**
     * 简化构造函数（只有行数据）
     *
     * @param trxId       事务 ID
     * @param tableId     表 ID
     * @param prevUndoPtr 上一个 Undo 指针
     * @param oldRowData  完整的旧行数据 (包含主键)
     */
    public DeleteUndoRecord(TransactionId trxId, int tableId,
                            RollbackPointer prevUndoPtr,
                            byte[] oldRowData) {
        this(trxId, tableId, prevUndoPtr, null, oldRowData);
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
     * 获取完整的旧行数据
     *
     * @return 旧行数据的副本
     */
    public byte[] getOldRowData() {
        return oldRowData.clone();
    }

    /**
     * 获取旧行数据长度
     *
     * @return 数据长度
     */
    public int getOldRowDataLength() {
        return oldRowData.length;
    }

    // ==================== 实现抽象方法 ====================

    @Override
    protected int getPayloadSize() {
        // pk_len(2) + pk_data + row_len(2) + row_data
        return ROW_LEN_SIZE + primaryKeyData.length + ROW_LEN_SIZE + oldRowData.length;
    }

    @Override
    protected void writePayload(ByteBuffer buf, int offset) {
        int pos = offset;

        // pk_len (2 bytes)
        buf.putShort(pos, (short) primaryKeyData.length);
        pos += ROW_LEN_SIZE;

        // pk_data
        for (byte b : primaryKeyData) {
            buf.put(pos++, b);
        }

        // row_len (2 bytes)
        buf.putShort(pos, (short) oldRowData.length);
        pos += ROW_LEN_SIZE;

        // row_data
        for (byte b : oldRowData) {
            buf.put(pos++, b);
        }
    }

    @Override
    public String getRollbackDescription() {
        return String.format("UNDELETE table_%d: clear delete_flag, " +
                        "pk=[%d bytes], row=[%d bytes]",
                tableId, primaryKeyData.length, oldRowData.length);
    }

    // ==================== 反序列化 ====================

    /**
     * 从 ByteBuffer 读取 payload
     *
     * @param buf    源缓冲区
     * @param offset payload 起始偏移
     * @param header 记录头部
     * @return DELETE Undo 记录
     */
    static DeleteUndoRecord readPayload(ByteBuffer buf, int offset, UndoRecordHeader header) {
        int pos = offset;

        // pk_len
        int pkLen = buf.getShort(pos) & 0xFFFF;
        pos += ROW_LEN_SIZE;

        // pk_data
        byte[] pkData = new byte[pkLen];
        for (int i = 0; i < pkLen; i++) {
            pkData[i] = buf.get(pos++);
        }

        // row_len
        int rowLen = buf.getShort(pos) & 0xFFFF;
        pos += ROW_LEN_SIZE;

        // row_data
        byte[] rowData = new byte[rowLen];
        for (int i = 0; i < rowLen; i++) {
            rowData[i] = buf.get(pos++);
        }

        return new DeleteUndoRecord(
                header.trxId(),
                header.tableId(),
                header.prevUndoPtr(),
                pkData,
                rowData
        );
    }

    // ==================== Object 方法 ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeleteUndoRecord that = (DeleteUndoRecord) o;
        return tableId == that.tableId
                && trxId.equals(that.trxId)
                && prevUndoPtr.equals(that.prevUndoPtr)
                && Arrays.equals(primaryKeyData, that.primaryKeyData)
                && Arrays.equals(oldRowData, that.oldRowData);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(trxId, tableId, prevUndoPtr);
        result = 31 * result + Arrays.hashCode(primaryKeyData);
        result = 31 * result + Arrays.hashCode(oldRowData);
        return result;
    }

    @Override
    public String toString() {
        return String.format("DeleteUndo{trxId=%s, tableId=%d, pkLen=%d, rowLen=%d}",
                trxId, tableId, primaryKeyData.length, oldRowData.length);
    }
}
