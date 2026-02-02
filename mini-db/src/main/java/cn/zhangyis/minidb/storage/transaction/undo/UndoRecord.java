package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;

import java.nio.ByteBuffer;

/**
 * Undo 记录基类
 *
 * <p>Undo 记录存储在 Undo Page 中，用于事务回滚和 MVCC 版本链。</p>
 *
 * <h2>通用记录头格式</h2>
 * <pre>
 * ┌──────┬──────┬─────────┬──────────┬───────────┬──────────────┐
 * │ type │ len  │ trx_id  │ table_id │ prev_undo │ payload...   │
 * │ (1B) │ (2B) │ (6B)    │ (4B)     │ (7B)      │ (variable)   │
 * └──────┴──────┴─────────┴──────────┴───────────┴──────────────┘
 * </pre>
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li><b>type</b>: 记录类型 (INSERT=0x0B, UPDATE=0x0C, DELETE=0x0D)</li>
 *   <li><b>len</b>: 记录总长度 (包括 header)</li>
 *   <li><b>trx_id</b>: 产生此 Undo 的事务 ID</li>
 *   <li><b>table_id</b>: 被修改的表 ID</li>
 *   <li><b>prev_undo</b>: 指向本行上一个 Undo 记录 (版本链)</li>
 *   <li><b>payload</b>: 子类特定的数据</li>
 * </ul>
 *
 * <h2>设计约束 (Invariants)</h2>
 * <ul>
 *   <li><b>T3</b>: Undo 必须先于数据修改持久化</li>
 *   <li><b>T5</b>: ROLL_PTR.is_insert 必须正确标记</li>
 *   <li><b>T6</b>: 版本链终止于 INSERT Undo 或 NULL</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public abstract class UndoRecord {

    // ==================== 头部常量 ====================

    /** type 字段偏移 */
    public static final int OFF_TYPE = 0;

    /** len 字段偏移 */
    public static final int OFF_LEN = 1;

    /** trx_id 字段偏移 */
    public static final int OFF_TRX_ID = 3;

    /** table_id 字段偏移 */
    public static final int OFF_TABLE_ID = 9;

    /** prev_undo 字段偏移 */
    public static final int OFF_PREV_UNDO = 13;

    /** 头部大小 (1 + 2 + 6 + 4 + 7 = 20 bytes) */
    public static final int HEADER_SIZE = 20;

    // ==================== 公共字段 ====================

    /**
     * 记录类型
     */
    protected final UndoRecordType type;

    /**
     * 产生此 Undo 的事务 ID
     */
    protected final TransactionId trxId;

    /**
     * 被修改的表 ID
     */
    protected final int tableId;

    /**
     * 指向本行上一个 Undo 记录
     *
     * <p>用于构建版本链：
     * <ul>
     *   <li>INSERT Undo: 通常为 NULL (记录之前不存在)</li>
     *   <li>UPDATE/DELETE Undo: 指向本行的上一个版本</li>
     * </ul>
     * </p>
     */
    protected final RollbackPointer prevUndoPtr;

    // ==================== 构造函数 ====================

    /**
     * 创建 Undo 记录
     *
     * @param type        记录类型
     * @param trxId       事务 ID
     * @param tableId     表 ID
     * @param prevUndoPtr 上一个 Undo 指针
     */
    protected UndoRecord(UndoRecordType type, TransactionId trxId,
                         int tableId, RollbackPointer prevUndoPtr) {
        this.type = type;
        this.trxId = trxId;
        this.tableId = tableId;
        this.prevUndoPtr = prevUndoPtr != null ? prevUndoPtr : RollbackPointer.NULL;
    }

    // ==================== 访问方法 ====================

    /**
     * 获取记录类型
     *
     * @return 记录类型
     */
    public UndoRecordType getType() {
        return type;
    }

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
     * 获取上一个 Undo 指针
     *
     * @return 回滚指针
     */
    public RollbackPointer getPrevUndoPtr() {
        return prevUndoPtr;
    }

    // ==================== 抽象方法 ====================

    /**
     * 获取 payload 数据的大小
     *
     * @return payload 字节数
     */
    protected abstract int getPayloadSize();

    /**
     * 写入 payload 数据到 ByteBuffer
     *
     * @param buf    目标缓冲区
     * @param offset 写入偏移 (payload 起始位置)
     */
    protected abstract void writePayload(ByteBuffer buf, int offset);

    /**
     * 创建用于回滚的描述信息
     *
     * @return 回滚描述
     */
    public abstract String getRollbackDescription();

    // ==================== 序列化方法 ====================

    /**
     * 计算记录总大小
     *
     * @return 总字节数
     */
    public int calculateSize() {
        return HEADER_SIZE + getPayloadSize();
    }

    /**
     * 写入完整记录到 ByteBuffer
     *
     * @param buf    目标缓冲区
     * @param offset 写入偏移
     * @return 写入的字节数
     */
    public int writeTo(ByteBuffer buf, int offset) {
        int totalSize = calculateSize();

        // type (1 byte)
        buf.put(offset + OFF_TYPE, type.getCodeByte());

        // len (2 bytes, Big-Endian)
        buf.putShort(offset + OFF_LEN, (short) totalSize);

        // trx_id (6 bytes)
        trxId.writeTo(buf, offset + OFF_TRX_ID);

        // table_id (4 bytes, Big-Endian)
        buf.putInt(offset + OFF_TABLE_ID, tableId);

        // prev_undo (7 bytes)
        prevUndoPtr.writeTo(buf, offset + OFF_PREV_UNDO);

        // payload
        writePayload(buf, offset + HEADER_SIZE);

        return totalSize;
    }

    /**
     * 写入到字节数组
     *
     * @param bytes  目标数组
     * @param offset 写入偏移
     * @return 写入的字节数
     */
    public int writeTo(byte[] bytes, int offset) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        return writeTo(buf, offset);
    }

    // ==================== 静态方法 ====================

    /**
     * 从 ByteBuffer 读取记录头部
     *
     * @param buf    源缓冲区
     * @param offset 读取偏移
     * @return 头部信息
     */
    public static UndoRecordHeader readHeader(ByteBuffer buf, int offset) {
        byte typeCode = buf.get(offset + OFF_TYPE);
        UndoRecordType type = UndoRecordType.fromCode(typeCode);

        int len = buf.getShort(offset + OFF_LEN) & 0xFFFF;
        TransactionId trxId = TransactionId.readFrom(buf, offset + OFF_TRX_ID);
        int tableId = buf.getInt(offset + OFF_TABLE_ID);
        RollbackPointer prevUndoPtr = RollbackPointer.readFrom(buf, offset + OFF_PREV_UNDO);

        return new UndoRecordHeader(type, len, trxId, tableId, prevUndoPtr);
    }

    /**
     * 从 ByteBuffer 读取完整记录
     *
     * @param buf    源缓冲区
     * @param offset 读取偏移
     * @return Undo 记录
     */
    public static UndoRecord readFrom(ByteBuffer buf, int offset) {
        UndoRecordHeader header = readHeader(buf, offset);
        int payloadOffset = offset + HEADER_SIZE;

        return switch (header.type()) {
            case INSERT -> InsertUndoRecord.readPayload(buf, payloadOffset, header);
            case UPDATE -> UpdateUndoRecord.readPayload(buf, payloadOffset, header);
            case DELETE_MARK -> DeleteUndoRecord.readPayload(buf, payloadOffset, header);
        };
    }

    /**
     * 从字节数组读取完整记录
     *
     * @param bytes  源数组
     * @param offset 读取偏移
     * @return Undo 记录
     */
    public static UndoRecord readFrom(byte[] bytes, int offset) {
        return readFrom(ByteBuffer.wrap(bytes), offset);
    }

    /**
     * 仅读取记录长度 (快速跳过)
     *
     * @param buf    源缓冲区
     * @param offset 读取偏移
     * @return 记录长度
     */
    public static int peekLength(ByteBuffer buf, int offset) {
        return buf.getShort(offset + OFF_LEN) & 0xFFFF;
    }

    /**
     * 仅读取记录类型
     *
     * @param buf    源缓冲区
     * @param offset 读取偏移
     * @return 记录类型
     */
    public static UndoRecordType peekType(ByteBuffer buf, int offset) {
        return UndoRecordType.fromCode(buf.get(offset + OFF_TYPE));
    }

    // ==================== Object 方法 ====================

    @Override
    public String toString() {
        return String.format("%s{trxId=%s, tableId=%d, prevUndo=%s}",
                type, trxId, tableId, prevUndoPtr);
    }

    // ==================== 内部类：头部信息 ====================

    /**
     * Undo 记录头部信息
     *
     * @param type        记录类型
     * @param length      记录总长度
     * @param trxId       事务 ID
     * @param tableId     表 ID
     * @param prevUndoPtr 上一个 Undo 指针
     */
    public record UndoRecordHeader(
            UndoRecordType type,
            int length,
            TransactionId trxId,
            int tableId,
            RollbackPointer prevUndoPtr
    ) {
    }
}
