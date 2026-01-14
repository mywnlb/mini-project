package cn.zhangyis.minidb.storage.redo.record;

import cn.zhangyis.minidb.storage.page.PageId;

import java.nio.ByteBuffer;

/**
 * 整页 Redo (兜底机制，不推荐)
 *
 * <p>记录整个页面的 16KB 数据。这是一种兜底机制，仅用于调试、
 * 特殊页面格式化等场景，不应作为常规 redo 类型。</p>
 *
 * <h2>格式</h2>
 * <pre>
 * ┌─────────────┬──────────┬──────────┬──────────┬───────────────────┐
 * │ type (1B)   │ space_id │ page_no  │ data_len │ page_data         │
 * │             │ (4B)     │ (4B)     │ (2B)     │ (16384 bytes)     │
 * └─────────────┴──────────┴──────────┴──────────┴───────────────────┘
 * 总大小: 11 + 16384 = 16395 bytes
 * </pre>
 *
 * <h2>缺点</h2>
 * <ul>
 *   <li><b>Redo 体积巨大</b>: 每条记录 16KB+，一个 MTR 修改多页则需要 N * 16KB</li>
 *   <li><b>恢复写放大严重</b>: 即使只修改 4 字节，也要写入整页</li>
 *   <li><b>无法达到性能目标</b>: Phase 1-2 目标是 page-patch，而非 full-page</li>
 * </ul>
 *
 * <h2>适用场景</h2>
 * <ul>
 *   <li>调试: 记录完整页面状态用于问题诊断</li>
 *   <li>页面初始化: 新页面格式化时记录初始状态</li>
 *   <li>特殊页面: 某些系统页面的特殊操作</li>
 * </ul>
 *
 * <h2>应用规则</h2>
 * <ul>
 *   <li>通过 page_lsn 判断幂等性</li>
 *   <li>直接覆盖整个页面 (16384 bytes)</li>
 *   <li>必须验证页面 checksum</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class FullPageRecord extends RedoRecord {

    // ==================== 字段 ====================

    /** 目标页面 ID */
    private final PageId pageId;

    /** 完整页面数据 (16384 bytes) */
    private final byte[] pageData;

    // ==================== 构造函数 ====================

    /**
     * 创建 FullPageRecord
     *
     * @param pageId 目标页面 ID
     * @param pageData 完整页面数据 (必须是 16384 bytes)
     * @throws IllegalArgumentException 如果参数非法
     */
    public FullPageRecord(PageId pageId, byte[] pageData) {
        super(RedoRecordType.MLOG_FULL_PAGE);

        // 参数校验
        if (pageId == null) {
            throw new IllegalArgumentException("pageId cannot be null");
        }
        if (pageData == null) {
            throw new IllegalArgumentException("pageData cannot be null");
        }
        if (pageData.length != 16384) {
            throw new IllegalArgumentException(
                    "pageData must be exactly 16384 bytes, got: " + pageData.length);
        }

        this.pageId = pageId;
        this.pageData = pageData;
    }

    // ==================== RedoRecord 实现 ====================

    /**
     * 序列化为字节数组
     *
     * <p>格式: type(1) | space_id(4) | page_no(4) | data_len(2) | page_data(16384)</p>
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

        // 4. data_len (2 bytes, little endian) = 16384
        buffer.putShort(Short.reverseBytes((short) 16384));

        // 5. page_data (16384 bytes)
        buffer.put(pageData);

        return buffer.array();
    }

    /**
     * 获取序列化后的大小
     *
     * @return 字节数: 1(type) + 4(space_id) + 4(page_no) + 2(data_len) + 16384(page_data) = 16395
     */
    @Override
    public int getSize() {
        return 1 + 4 + 4 + 2 + 16384;
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
     * 获取页面数据 (只读副本)
     *
     * @return 页面数据副本 (16384 bytes)
     */
    public byte[] getPageData() {
        return pageData.clone();
    }

    // ==================== 辅助方法 ====================

    @Override
    public String toString() {
        return String.format("FullPageRecord{lsn=%d, page=%s, size=%d}",
                lsn, pageId, getSize());
    }

    /**
     * 从字节数组反序列化 (用于恢复)
     *
     * <p>格式: type(1) | space_id(4) | page_no(4) | data_len(2) | page_data(16384)</p>
     *
     * @param buffer 字节缓冲区 (position 指向 type 字段)
     * @return FullPageRecord 实例
     * @throws IllegalArgumentException 如果格式非法
     */
    public static FullPageRecord deserialize(ByteBuffer buffer) {
        // 1. type (1 byte) - 已在外层读取
        byte typeValue = buffer.get();
        assert typeValue == RedoRecordType.MLOG_FULL_PAGE.getValue()
                : "Type mismatch: expected " + RedoRecordType.MLOG_FULL_PAGE.getValue() + ", got " + typeValue;

        // 2. space_id (4 bytes, little endian)
        int spaceId = Integer.reverseBytes(buffer.getInt());

        // 3. page_no (4 bytes, little endian)
        int pageNo = Integer.reverseBytes(buffer.getInt());

        // 4. data_len (2 bytes, little endian)
        int dataLen = Short.reverseBytes(buffer.getShort()) & 0xFFFF;
        if (dataLen != 16384) {
            throw new IllegalArgumentException(
                    "data_len must be 16384 for FullPageRecord, got: " + dataLen);
        }

        // 5. page_data (16384 bytes)
        byte[] pageData = new byte[16384];
        buffer.get(pageData);

        // 6. 创建 PageId 和 Record
        PageId pageId = new PageId(spaceId, pageNo);
        return new FullPageRecord(pageId, pageData);
    }
}
