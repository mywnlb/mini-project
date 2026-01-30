package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.storage.page.CompactRecordUtil;

import java.nio.ByteBuffer;

/**
 * 简单记录构建器
 *
 * <p>用于测试的简单记录格式。记录布局：</p>
 * <pre>
 * +------------------+------------------+------------------+
 * | Record Header    | Key (4 bytes)    | Value (变长)     |
 * | (5 bytes)        | int, big-endian  |                  |
 * +------------------+------------------+------------------+
 * </pre>
 *
 * <h2>Record Header 布局 (5 bytes)</h2>
 * <pre>
 * Byte 0: info_bits (n_owned=4bits, delete=1bit, min_rec=1bit, reserved=2bits)
 * Byte 1: heap_no 高位
 * Byte 2: heap_no 低位
 * Byte 3: rec_type (3 bits) + reserved
 * Byte 4-5: next_record (2 bytes, 相对偏移)
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class SimpleRecordBuilder {

    /** 记录头大小 */
    public static final int RECORD_HEADER_SIZE = 5;

    /** 键大小（4 字节整数） */
    public static final int KEY_SIZE = 4;

    /**
     * 构建普通用户记录
     *
     * @param key     键（整数）
     * @param value   值（字节数组）
     * @param heapNo  堆号
     * @return 完整的记录字节数组
     */
    public static byte[] buildRecord(int key, byte[] value, int heapNo) {
        int totalSize = RECORD_HEADER_SIZE + KEY_SIZE + (value != null ? value.length : 0);
        byte[] record = new byte[totalSize];

        // 写入记录头
        writeRecordHeader(record, 0, heapNo, CompactRecordUtil.REC_TYPE_ORDINARY, 0, false);

        // 写入键（大端序）
        record[RECORD_HEADER_SIZE] = (byte) (key >> 24);
        record[RECORD_HEADER_SIZE + 1] = (byte) (key >> 16);
        record[RECORD_HEADER_SIZE + 2] = (byte) (key >> 8);
        record[RECORD_HEADER_SIZE + 3] = (byte) key;

        // 写入值
        if (value != null && value.length > 0) {
            System.arraycopy(value, 0, record, RECORD_HEADER_SIZE + KEY_SIZE, value.length);
        }

        return record;
    }

    /**
     * 构建只有键的记录（用于非叶子节点）
     *
     * @param key        键（整数）
     * @param childPageNo 子页号
     * @param heapNo     堆号
     * @return 完整的记录字节数组
     */
    public static byte[] buildNodePtrRecord(int key, int childPageNo, int heapNo) {
        int totalSize = RECORD_HEADER_SIZE + KEY_SIZE + 4; // key + child_page_no
        byte[] record = new byte[totalSize];

        // 写入记录头
        writeRecordHeader(record, 0, heapNo, CompactRecordUtil.REC_TYPE_NODE_PTR, 0, false);

        // 写入键
        record[RECORD_HEADER_SIZE] = (byte) (key >> 24);
        record[RECORD_HEADER_SIZE + 1] = (byte) (key >> 16);
        record[RECORD_HEADER_SIZE + 2] = (byte) (key >> 8);
        record[RECORD_HEADER_SIZE + 3] = (byte) key;

        // 写入子页号
        int offset = RECORD_HEADER_SIZE + KEY_SIZE;
        record[offset] = (byte) (childPageNo >> 24);
        record[offset + 1] = (byte) (childPageNo >> 16);
        record[offset + 2] = (byte) (childPageNo >> 8);
        record[offset + 3] = (byte) childPageNo;

        return record;
    }

    /**
     * 写入记录头
     *
     * @param record    记录字节数组
     * @param offset    写入偏移
     * @param heapNo    堆号
     * @param recType   记录类型
     * @param nOwned    拥有的记录数
     * @param deleted   是否删除标记
     */
    private static void writeRecordHeader(byte[] record, int offset, int heapNo,
                                          int recType, int nOwned, boolean deleted) {
        // Byte 0: n_owned (高4位) + delete_mask (bit 5) + min_rec (bit 4)
        int infoBits = (nOwned & 0x0F) << 4;
        if (deleted) {
            infoBits |= 0x20; // delete_mask
        }
        record[offset] = (byte) infoBits;

        // Byte 1-2: heap_no (13 bits，但我们简化为 16 bits)
        record[offset + 1] = (byte) ((heapNo >> 8) & 0xFF);
        record[offset + 2] = (byte) (heapNo & 0xFF);

        // Byte 3: rec_type (低 3 位)
        record[offset + 3] = (byte) (recType & 0x07);

        // Byte 4-5: next_record (初始为 0)
        record[offset + 4] = 0;
        // 注意：实际的 next_record 在 offset+3 处开始（2字节），这里简化处理
    }

    /**
     * 计算记录大小
     *
     * @param valueLength 值的长度
     * @return 总记录大小
     */
    public static int calculateRecordSize(int valueLength) {
        return RECORD_HEADER_SIZE + KEY_SIZE + valueLength;
    }

    /**
     * 从记录中读取键
     *
     * @param pageBuffer   页面 ByteBuffer
     * @param recordOffset 记录偏移
     * @return 键值
     */
    public static int readKey(ByteBuffer pageBuffer, int recordOffset) {
        return pageBuffer.getInt(recordOffset + RECORD_HEADER_SIZE);
    }

    /**
     * 从记录中读取值
     *
     * @param pageBuffer   页面 ByteBuffer
     * @param recordOffset 记录偏移
     * @param valueLength  值长度
     * @return 值字节数组
     */
    public static byte[] readValue(ByteBuffer pageBuffer, int recordOffset, int valueLength) {
        byte[] value = new byte[valueLength];
        int valueOffset = recordOffset + RECORD_HEADER_SIZE + KEY_SIZE;
        for (int i = 0; i < valueLength; i++) {
            value[i] = pageBuffer.get(valueOffset + i);
        }
        return value;
    }

    // 禁止实例化
    private SimpleRecordBuilder() {
        throw new UnsupportedOperationException("SimpleRecordBuilder is a utility class");
    }
}
