package cn.zhangyis.minidb.storage.record;

import java.nio.ByteBuffer;

/**
 * 记录头 (5 字节)
 *
 * <p>对应 InnoDB Compact 格式的记录头，存储记录的元信息。</p>
 *
 * <h2>布局 (40 bits = 5 bytes)</h2>
 * <pre>
 * ┌─────────┬─────────┬──────────────────┬───────────────┬────────────────┐
 * │ 4 bits  │ 4 bits  │     13 bits      │    3 bits     │    16 bits     │
 * │ info_   │ n_owned │     heap_no      │   rec_type    │  next_record   │
 * │ bits    │         │                  │               │  (相对偏移)     │
 * └─────────┴─────────┴──────────────────┴───────────────┴────────────────┘
 * </pre>
 *
 * <h2>info_bits 含义</h2>
 * <ul>
 *   <li>Bit 0: REC_INFO_DELETED_FLAG - 删除标记（MVCC 软删除）</li>
 *   <li>Bit 1: REC_INFO_MIN_REC_FLAG - B+Tree 非叶节点最小记录</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class RecordHeader {

    /** 记录头大小 (5 bytes) */
    public static final int SIZE = 5;

    // ==================== info_bits 标志 ====================

    /** 删除标记 */
    public static final int REC_INFO_DELETED_FLAG = 0x01;

    /** B+Tree 非叶节点最小记录标记 */
    public static final int REC_INFO_MIN_REC_FLAG = 0x02;

    // ==================== rec_type 类型 ====================

    /** 普通用户记录 */
    public static final int REC_ORDINARY = 0;

    /** B+Tree 非叶节点指针 */
    public static final int REC_NODE_PTR = 1;

    /** 虚拟最小记录 (Infimum) */
    public static final int REC_INFIMUM = 2;

    /** 虚拟最大记录 (Supremum) */
    public static final int REC_SUPREMUM = 3;

    // ==================== 字段 ====================

    private int infoBits;      // 4 bits
    private int nOwned;        // 4 bits
    private int heapNo;        // 13 bits (max 8191)
    private int recType;       // 3 bits
    private int nextRecord;    // 16 bits (相对偏移，有符号)

    /**
     * 默认构造函数
     */
    public RecordHeader() {
        this.infoBits = 0;
        this.nOwned = 0;
        this.heapNo = 0;
        this.recType = REC_ORDINARY;
        this.nextRecord = 0;
    }

    /**
     * 完整构造函数
     */
    public RecordHeader(int infoBits, int nOwned, int heapNo, int recType, int nextRecord) {
        this.infoBits = infoBits & 0x0F;
        this.nOwned = nOwned & 0x0F;
        this.heapNo = heapNo & 0x1FFF;
        this.recType = recType & 0x07;
        this.nextRecord = (short) nextRecord; // 有符号 16 位
    }

    // ==================== 从页面读取 ====================

    /**
     * 从页面缓冲区读取记录头
     *
     * @param buffer   页面缓冲区
     * @param recStart 记录起始偏移 (recStart 锚点)
     * @return RecordHeader 实例
     */
    public static RecordHeader readFrom(ByteBuffer buffer, int recStart) {
        RecordHeader header = new RecordHeader();

        // 读取 5 字节
        int b0 = buffer.get(recStart) & 0xFF;
        int b1 = buffer.get(recStart + 1) & 0xFF;
        int b2 = buffer.get(recStart + 2) & 0xFF;
        int b3 = buffer.get(recStart + 3) & 0xFF;
        int b4 = buffer.get(recStart + 4) & 0xFF;

        // 解析各字段
        // Byte 0: [info_bits(4) | n_owned(4)]
        header.infoBits = (b0 >> 4) & 0x0F;
        header.nOwned = b0 & 0x0F;

        // Byte 1-2: [heap_no(13) | rec_type(3)]
        // heap_no 占 13 bits，rec_type 占 3 bits
        int combined = (b1 << 8) | b2;
        header.heapNo = (combined >> 3) & 0x1FFF;
        header.recType = combined & 0x07;

        // Byte 3-4: next_record (有符号 16 位，大端序)
        header.nextRecord = (short) ((b3 << 8) | b4);

        return header;
    }

    /**
     * 写入到页面缓冲区
     *
     * @param buffer   页面缓冲区
     * @param recStart 记录起始偏移 (recStart 锚点)
     */
    public void writeTo(ByteBuffer buffer, int recStart) {
        // Byte 0: [info_bits(4) | n_owned(4)]
        buffer.put(recStart, (byte) ((infoBits << 4) | (nOwned & 0x0F)));

        // Byte 1-2: [heap_no(13) | rec_type(3)]
        int combined = ((heapNo & 0x1FFF) << 3) | (recType & 0x07);
        buffer.put(recStart + 1, (byte) ((combined >> 8) & 0xFF));
        buffer.put(recStart + 2, (byte) (combined & 0xFF));

        // Byte 3-4: next_record (大端序)
        buffer.put(recStart + 3, (byte) ((nextRecord >> 8) & 0xFF));
        buffer.put(recStart + 4, (byte) (nextRecord & 0xFF));
    }

    // ==================== 静态辅助方法 ====================

    /**
     * 计算 dataStart 位置
     *
     * @param recStart 记录起始偏移
     * @return dataStart = recStart + SIZE
     */
    public static int dataStart(int recStart) {
        return recStart + SIZE;
    }

    /**
     * 快速读取 next_record
     *
     * @param buffer   页面缓冲区
     * @param recStart 记录起始偏移
     * @return next_record 相对偏移
     */
    public static int peekNextRecord(ByteBuffer buffer, int recStart) {
        return buffer.getShort(recStart + 3);
    }

    /**
     * 快速写入 next_record
     *
     * @param buffer     页面缓冲区
     * @param recStart   记录起始偏移
     * @param nextRecord 相对偏移
     */
    public static void pokeNextRecord(ByteBuffer buffer, int recStart, int nextRecord) {
        buffer.putShort(recStart + 3, (short) nextRecord);
    }

    /**
     * 快速读取 n_owned
     *
     * @param buffer   页面缓冲区
     * @param recStart 记录起始偏移
     * @return n_owned 值
     */
    public static int peekNOwned(ByteBuffer buffer, int recStart) {
        return buffer.get(recStart) & 0x0F;
    }

    /**
     * 快速写入 n_owned
     *
     * @param buffer   页面缓冲区
     * @param recStart 记录起始偏移
     * @param nOwned   n_owned 值
     */
    public static void pokeNOwned(ByteBuffer buffer, int recStart, int nOwned) {
        int b0 = buffer.get(recStart) & 0xFF;
        buffer.put(recStart, (byte) ((b0 & 0xF0) | (nOwned & 0x0F)));
    }

    /**
     * 快速读取 info_bits
     *
     * @param buffer   页面缓冲区
     * @param recStart 记录起始偏移
     * @return info_bits 值
     */
    public static int peekInfoBits(ByteBuffer buffer, int recStart) {
        return (buffer.get(recStart) >> 4) & 0x0F;
    }

    /**
     * 快速设置删除标记
     *
     * @param buffer   页面缓冲区
     * @param recStart 记录起始偏移
     * @param deleted  是否删除
     */
    public static void pokeDeletedFlag(ByteBuffer buffer, int recStart, boolean deleted) {
        int b0 = buffer.get(recStart) & 0xFF;
        if (deleted) {
            b0 |= (REC_INFO_DELETED_FLAG << 4);
        } else {
            b0 &= ~(REC_INFO_DELETED_FLAG << 4);
        }
        buffer.put(recStart, (byte) b0);
    }

    /**
     * 快速检查删除标记
     *
     * @param buffer   页面缓冲区
     * @param recStart 记录起始偏移
     * @return 是否已删除
     */
    public static boolean isDeleted(ByteBuffer buffer, int recStart) {
        int infoBits = peekInfoBits(buffer, recStart);
        return (infoBits & REC_INFO_DELETED_FLAG) != 0;
    }

    // ==================== Getters & Setters ====================

    public int getInfoBits() {
        return infoBits;
    }

    public void setInfoBits(int infoBits) {
        this.infoBits = infoBits & 0x0F;
    }

    public int getNOwned() {
        return nOwned;
    }

    public void setNOwned(int nOwned) {
        this.nOwned = nOwned & 0x0F;
    }

    public int getHeapNo() {
        return heapNo;
    }

    public void setHeapNo(int heapNo) {
        this.heapNo = heapNo & 0x1FFF;
    }

    public int getRecType() {
        return recType;
    }

    public void setRecType(int recType) {
        this.recType = recType & 0x07;
    }

    public int getNextRecord() {
        return nextRecord;
    }

    public void setNextRecord(int nextRecord) {
        this.nextRecord = (short) nextRecord;
    }

    // ==================== 便捷方法 ====================

    public boolean isDeleted() {
        return (infoBits & REC_INFO_DELETED_FLAG) != 0;
    }

    public void setDeleted(boolean deleted) {
        if (deleted) {
            infoBits |= REC_INFO_DELETED_FLAG;
        } else {
            infoBits &= ~REC_INFO_DELETED_FLAG;
        }
    }

    public boolean isMinRec() {
        return (infoBits & REC_INFO_MIN_REC_FLAG) != 0;
    }

    public void setMinRec(boolean minRec) {
        if (minRec) {
            infoBits |= REC_INFO_MIN_REC_FLAG;
        } else {
            infoBits &= ~REC_INFO_MIN_REC_FLAG;
        }
    }

    public boolean isOrdinary() {
        return recType == REC_ORDINARY;
    }

    public boolean isNodePtr() {
        return recType == REC_NODE_PTR;
    }

    public boolean isInfimum() {
        return recType == REC_INFIMUM;
    }

    public boolean isSupremum() {
        return recType == REC_SUPREMUM;
    }

    @Override
    public String toString() {
        return String.format("RecordHeader{info=%d, owned=%d, heapNo=%d, type=%d, next=%d}",
            infoBits, nOwned, heapNo, recType, nextRecord);
    }
}
