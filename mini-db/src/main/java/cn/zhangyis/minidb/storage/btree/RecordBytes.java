package cn.zhangyis.minidb.storage.btree;

/**
 * 从页面读取的完整记录字节封装。
 *
 * <p>Compact 行格式的记录在 recStart 之前有 extraBytes（varlen list + NULL bitmap），
 * 因此从页面复制记录时必须包含这些前缀字节。本类同时携带 recordHeaderOffset，
 * 以便 {@link cn.zhangyis.minidb.storage.page.IndexPageOps#insertRecord} 正确定位 header 位置。</p>
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>INV-S1: data 包含 [extraBytes | RecordHeader | Data] 的完整连续区间</li>
 *   <li>INV-S2: recordHeaderOffset == extraBytes 字节数，data[recordHeaderOffset] 是 RecordHeader 第一字节</li>
 * </ul>
 *
 * @param data                完整记录字节（含 extraBytes 前缀）
 * @param recordHeaderOffset  RecordHeader 在 data 中的偏移（== extraBytes 字节数）
 */
public record RecordBytes(byte[] data, int recordHeaderOffset) {

    public RecordBytes {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (recordHeaderOffset < 0 || recordHeaderOffset >= data.length) {
            throw new IllegalArgumentException(
                    "recordHeaderOffset out of range: " + recordHeaderOffset + ", data.length=" + data.length);
        }
    }
}
