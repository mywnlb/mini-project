package cn.zhangyis.minidb.storage.btree;

import java.nio.ByteBuffer;

/**
 * 记录比较器接口
 *
 * <p>用于 B+Tree 中记录的比较操作。比较器需要能够：</p>
 * <ul>
 *   <li>比较两条页内记录</li>
 *   <li>比较搜索键与页内记录</li>
 * </ul>
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li>比较器由索引元数据决定，不同索引可能有不同的比较逻辑</li>
 *   <li>支持前缀匹配（用于范围查询）</li>
 *   <li>比较结果遵循标准约定：负数表示小于，0 表示等于，正数表示大于</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public interface RecordComparator {

    /**
     * 比较搜索键与页内记录
     *
     * <p>用于在页内查找时，将搜索键与现有记录进行比较。</p>
     *
     * @param searchKey    搜索键（字节数组形式）
     * @param pageBuffer   页面 ByteBuffer
     * @param recordOffset 记录在页面中的偏移（origin 位置）
     * @return 负数表示 searchKey < record，0 表示相等，正数表示 searchKey > record
     */
    int compareKeyToRecord(byte[] searchKey, ByteBuffer pageBuffer, int recordOffset);

    /**
     * 比较两条页内记录
     *
     * <p>用于验证记录顺序或合并操作。</p>
     *
     * @param pageBuffer1   第一条记录所在页面
     * @param recordOffset1 第一条记录偏移
     * @param pageBuffer2   第二条记录所在页面
     * @param recordOffset2 第二条记录偏移
     * @return 负数表示 record1 < record2，0 表示相等，正数表示 record1 > record2
     */
    int compareRecords(ByteBuffer pageBuffer1, int recordOffset1,
                       ByteBuffer pageBuffer2, int recordOffset2);

    /**
     * 从记录中提取键
     *
     * <p>用于分裂时获取分隔键。</p>
     *
     * @param pageBuffer   页面 ByteBuffer
     * @param recordOffset 记录偏移
     * @return 键的字节数组表示
     */
    byte[] extractKey(ByteBuffer pageBuffer, int recordOffset);

    /**
     * 获取键的长度
     *
     * <p>用于计算记录大小。对于变长键，返回实际长度。</p>
     *
     * @param key 键字节数组
     * @return 键长度
     */
    int getKeyLength(byte[] key);

    /**
     * 从页面读取完整记录字节（含 extraBytes 前缀）。
     *
     * <p>Compact 行格式的记录在 recStart 之前有 varlen list + NULL bitmap，
     * 本方法将这些前缀字节一并读取，返回的 {@link RecordBytes} 中
     * recordHeaderOffset 指示 RecordHeader 在字节数组中的起始位置。</p>
     *
     * <p>默认实现假设 SimpleRecordBuilder 的固定 10 字节记录（header 5B + key 4B + value 1B）。</p>
     *
     * @param buf      页面 ByteBuffer
     * @param recStart 记录头起始偏移（页面内）
     * @return 完整记录字节封装
     */
    default RecordBytes readFullRecord(ByteBuffer buf, int recStart) {
        int size = SimpleRecordBuilder.RECORD_HEADER_SIZE + SimpleRecordBuilder.KEY_SIZE + 1;
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = buf.get(recStart + i);
        }
        return new RecordBytes(data, 0);
    }

    /**
     * 比较两个已提取的 key 字节数组。
     *
     * <p>用于页分裂时比较 searchKey 与 splitKey。
     * 默认实现假设 4 字节整数键（兼容 {@link IntKeyComparator}）。</p>
     *
     * @param key1 键 1
     * @param key2 键 2
     * @return 负数表示 key1 < key2，0 表示相等，正数表示 key1 > key2
     */
    default int compareExtractedKeys(byte[] key1, byte[] key2) {
        int k1 = IntKeyComparator.bytesToInt(key1);
        int k2 = IntKeyComparator.bytesToInt(key2);
        return Integer.compare(k1, k2);
    }
}
