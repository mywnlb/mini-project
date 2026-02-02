package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * INSERT 操作的 Undo 记录
 *
 * <p>当执行 INSERT 操作时，生成此类型的 Undo 记录。
 * 回滚时通过主键删除插入的记录。</p>
 *
 * <h2>Payload 格式</h2>
 * <pre>
 * ┌────────────┬─────────────────────┐
 * │ pk_len     │ primary_key_data    │
 * │ (2B)       │ (variable)          │
 * └────────────┴─────────────────────┘
 * </pre>
 *
 * <h2>完整记录格式</h2>
 * <pre>
 * ┌──────┬──────┬─────────┬──────────┬───────────┬────────┬──────────┐
 * │ type │ len  │ trx_id  │ table_id │ prev_undo │ pk_len │ pk_data  │
 * │ 0x0B │ (2B) │ (6B)    │ (4B)     │ (7B)      │ (2B)   │ (var)    │
 * └──────┴──────┴─────────┴──────────┴───────────┴────────┴──────────┘
 * </pre>
 *
 * <h2>回滚操作</h2>
 * <p>DELETE FROM table WHERE pk = primary_key_data</p>
 *
 * <h2>MVCC 语义</h2>
 * <p>INSERT Undo 表示版本链的终点：记录在此事务之前不存在。
 * 当沿版本链遍历到 INSERT Undo 时，说明已到达记录的最早版本。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class InsertUndoRecord extends UndoRecord {

    // ==================== Payload 常量 ====================

    /** pk_len 字段大小 */
    private static final int PK_LEN_SIZE = 2;

    // ==================== 字段 ====================

    /**
     * 主键数据
     *
     * <p>存储序列化后的主键列数据，用于回滚时定位要删除的记录。</p>
     */
    private final byte[] primaryKeyData;

    // ==================== 构造函数 ====================

    /**
     * 创建 INSERT Undo 记录
     *
     * @param trxId          事务 ID
     * @param tableId        表 ID
     * @param primaryKeyData 主键数据
     */
    public InsertUndoRecord(TransactionId trxId, int tableId, byte[] primaryKeyData) {
        // INSERT Undo 的 prevUndoPtr 通常为 NULL（记录之前不存在）
        super(UndoRecordType.INSERT, trxId, tableId, RollbackPointer.NULL);
        this.primaryKeyData = primaryKeyData != null ? primaryKeyData.clone() : new byte[0];
    }

    /**
     * 内部构造函数（用于反序列化）
     *
     * @param trxId          事务 ID
     * @param tableId        表 ID
     * @param prevUndoPtr    上一个 Undo 指针
     * @param primaryKeyData 主键数据
     */
    private InsertUndoRecord(TransactionId trxId, int tableId,
                             RollbackPointer prevUndoPtr, byte[] primaryKeyData) {
        super(UndoRecordType.INSERT, trxId, tableId, prevUndoPtr);
        this.primaryKeyData = primaryKeyData;
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
     * 获取主键数据长度
     *
     * @return 主键数据长度
     */
    public int getPrimaryKeyLength() {
        return primaryKeyData.length;
    }

    // ==================== 实现抽象方法 ====================

    @Override
    protected int getPayloadSize() {
        return PK_LEN_SIZE + primaryKeyData.length;
    }

    @Override
    protected void writePayload(ByteBuffer buf, int offset) {
        // pk_len (2 bytes, Big-Endian)
        buf.putShort(offset, (short) primaryKeyData.length);

        // pk_data
        for (int i = 0; i < primaryKeyData.length; i++) {
            buf.put(offset + PK_LEN_SIZE + i, primaryKeyData[i]);
        }
    }

    @Override
    public String getRollbackDescription() {
        return String.format("DELETE FROM table_%d WHERE pk = [%d bytes]",
                tableId, primaryKeyData.length);
    }

    // ==================== 反序列化 ====================

    /**
     * 从 ByteBuffer 读取 payload
     *
     * @param buf    源缓冲区
     * @param offset payload 起始偏移
     * @param header 记录头部
     * @return INSERT Undo 记录
     */
    static InsertUndoRecord readPayload(ByteBuffer buf, int offset, UndoRecordHeader header) {
        // pk_len
        int pkLen = buf.getShort(offset) & 0xFFFF;

        // pk_data
        byte[] pkData = new byte[pkLen];
        for (int i = 0; i < pkLen; i++) {
            pkData[i] = buf.get(offset + PK_LEN_SIZE + i);
        }

        return new InsertUndoRecord(
                header.trxId(),
                header.tableId(),
                header.prevUndoPtr(),
                pkData
        );
    }

    // ==================== Object 方法 ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InsertUndoRecord that = (InsertUndoRecord) o;
        return tableId == that.tableId
                && trxId.equals(that.trxId)
                && prevUndoPtr.equals(that.prevUndoPtr)
                && Arrays.equals(primaryKeyData, that.primaryKeyData);
    }

    @Override
    public int hashCode() {
        int result = trxId.hashCode();
        result = 31 * result + tableId;
        result = 31 * result + Arrays.hashCode(primaryKeyData);
        return result;
    }

    @Override
    public String toString() {
        return String.format("InsertUndo{trxId=%s, tableId=%d, pkLen=%d}",
                trxId, tableId, primaryKeyData.length);
    }
}
