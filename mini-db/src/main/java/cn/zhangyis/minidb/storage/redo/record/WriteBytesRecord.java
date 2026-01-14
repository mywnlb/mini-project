package cn.zhangyis.minidb.storage.redo.record;

import cn.zhangyis.minidb.storage.page.PageId;

import java.nio.ByteBuffer;

/**
 * 页内字节修改 Redo Record (Phase 1-2 主要类型)
 *
 * <p>记录对页面内特定区域的修改，采用 page-patch 方式 (offset + length + data)。
 * 这是 Phase 1-2 的主要 redo 类型，相比整页 redo 大幅减少日志体积。</p>
 *
 * <h2>格式</h2>
 * <pre>
 * ┌─────────────┬──────────┬──────────┬──────────┬────────┬────────┬──────────┐
 * │ type (1B)   │ space_id │ page_no  │ data_len │ offset │ length │ data     │
 * │             │ (4B)     │ (4B)     │ (2B)     │ (2B)   │ (2B)   │ (N bytes)│
 * └─────────────┴──────────┴──────────┴──────────┴────────┴────────┴──────────┘
 * 总大小: 15 + N bytes (相比整页 16KB 的巨大优势)
 * </pre>
 *
 * <h2>示例</h2>
 * <p>修改 Page 100 的偏移 38 处的 4 字节 (例如更新 page_lsn):</p>
 * <pre>
 * type=1 | space_id=1 | page_no=100 | data_len=8
 *   | offset=38 | length=4 | data=[0x64,0x00,0x00,0x00]
 * 总大小: 15 + 4 = 19 bytes (而非整页 16KB)
 * </pre>
 *
 * <h2>应用规则</h2>
 * <ul>
 *   <li>通过 page_lsn 判断幂等性</li>
 *   <li>offset + length 必须在页面范围内</li>
 *   <li>直接覆盖目标区域，不需要撤销</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class WriteBytesRecord extends RedoRecord {

    // ==================== 字段 ====================

    /** 目标页面 ID */
    private final PageId pageId;

    /** 页内偏移 (0-16383) */
    private final int offset;

    /** 修改的数据 */
    private final byte[] data;

    // ==================== 构造函数 ====================

    /**
     * 创建 WriteBytesRecord
     *
     * @param pageId 目标页面 ID
     * @param offset 页内偏移 (0-16383)
     * @param data 修改的数据 (不可为 null，长度 > 0)
     * @throws IllegalArgumentException 如果参数非法
     */
    public WriteBytesRecord(PageId pageId, int offset, byte[] data) {
        super(RedoRecordType.MLOG_WRITE_BYTES);

        // 参数校验
        if (pageId == null) {
            throw new IllegalArgumentException("pageId cannot be null");
        }
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data cannot be null or empty");
        }
        if (offset < 0 || offset >= 16384) {
            throw new IllegalArgumentException("offset out of range: " + offset);
        }
        if (offset + data.length > 16384) {
            throw new IllegalArgumentException(
                    String.format("offset + length exceeds page size: %d + %d > 16384",
                            offset, data.length));
        }

        this.pageId = pageId;
        this.offset = offset;
        this.data = data;
    }

    // ==================== RedoRecord 实现 ====================

    /**
     * 序列化为字节数组
     *
     * <p>格式: type(1) | space_id(4) | page_no(4) | data_len(2) | offset(2) | length(2) | data(N)</p>
     *
     * @return 序列化后的字节数组
     */
    @Override
    public byte[] serialize() {
        int totalSize = getSize();
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);

        // 1. type (1 byte)
        buffer.put((byte) type.getValue());

        // 2. space_id (4 bytes, little endian)
        buffer.putInt(Integer.reverseBytes(pageId.getSpaceId()));

        // 3. page_no (4 bytes, little endian)
        buffer.putInt(Integer.reverseBytes(pageId.getPageNo()));

        // 4. data_len (2 bytes, little endian) = offset(2) + length(2) + data(N)
        int dataLen = 2 + 2 + data.length;
        buffer.putShort(Short.reverseBytes((short) dataLen));

        // 5. offset (2 bytes, little endian)
        buffer.putShort(Short.reverseBytes((short) offset));

        // 6. length (2 bytes, little endian)
        buffer.putShort(Short.reverseBytes((short) data.length));

        // 7. data (N bytes)
        buffer.put(data);

        return buffer.array();
    }

    /**
     * 获取序列化后的大小
     *
     * @return 字节数: 1(type) + 4(space_id) + 4(page_no) + 2(data_len) + 2(offset) + 2(length) + N(data)
     */
    @Override
    public int getSize() {
        return 1 + 4 + 4 + 2 + 2 + 2 + data.length;
    }

    /**
     * 获取关联的 PageId
     *
     * @return PageId
     */
    @Override
    public PageId getPageId() {
        return pageId;
    }

    // ==================== Getter ====================

    /**
     * 获取页内偏移
     *
     * @return 偏移 (0-16383)
     */
    public int getOffset() {
        return offset;
    }

    /**
     * 获取修改数据的长度
     *
     * @return 数据长度
     */
    public int getLength() {
        return data.length;
    }

    /**
     * 获取修改的数据 (只读副本)
     *
     * @return 数据副本
     */
    public byte[] getData() {
        return data.clone();
    }

    // ==================== 辅助方法 ====================

    @Override
    public String toString() {
        return String.format("WriteBytesRecord{lsn=%d, page=%s, offset=%d, length=%d, size=%d}",
                lsn, pageId, offset, data.length, getSize());
    }

    /**
     * 从字节数组反序列化 (用于恢复)
     *
     * <p>格式: type(1) | space_id(4) | page_no(4) | data_len(2) | offset(2) | length(2) | data(N)</p>
     *
     * @param buffer 字节缓冲区 (position 指向 type 字段)
     * @return WriteBytesRecord 实例
     * @throws IllegalArgumentException 如果格式非法
     */
    public static WriteBytesRecord deserialize(ByteBuffer buffer) {
        // 1. type (1 byte) - 已在外层读取
        byte typeValue = buffer.get();
        assert typeValue == RedoRecordType.MLOG_WRITE_BYTES.getValue()
                : "Type mismatch: expected " + RedoRecordType.MLOG_WRITE_BYTES.getValue() + ", got " + typeValue;

        // 2. space_id (4 bytes, little endian)
        int spaceId = Integer.reverseBytes(buffer.getInt());

        // 3. page_no (4 bytes, little endian)
        int pageNo = Integer.reverseBytes(buffer.getInt());

        // 4. data_len (2 bytes, little endian)
        int dataLen = Short.reverseBytes(buffer.getShort()) & 0xFFFF;

        // 5. offset (2 bytes, little endian)
        int offset = Short.reverseBytes(buffer.getShort()) & 0xFFFF;

        // 6. length (2 bytes, little endian)
        int length = Short.reverseBytes(buffer.getShort()) & 0xFFFF;

        // 7. 验证 data_len
        int expectedDataLen = 2 + 2 + length;
        if (dataLen != expectedDataLen) {
            throw new IllegalArgumentException(
                    String.format("data_len mismatch: expected %d, got %d", expectedDataLen, dataLen));
        }

        // 8. data (N bytes)
        byte[] data = new byte[length];
        buffer.get(data);

        // 9. 创建 PageId 和 Record
        PageId pageId = new PageId(spaceId, pageNo);
        return new WriteBytesRecord(pageId, offset, data);
    }
}
