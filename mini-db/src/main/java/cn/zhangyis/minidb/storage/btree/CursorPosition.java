package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.storage.page.PageId;

/**
 * B+Tree 游标位置
 *
 * <p>表示游标在 B+Tree 中的当前位置。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class CursorPosition {

    /** 当前页面 ID */
    private final PageId pageId;

    /** 当前记录偏移 */
    private final int recordOffset;

    /** 是否有效 */
    private final boolean valid;

    /**
     * 构造有效位置
     *
     * @param pageId       页面 ID
     * @param recordOffset 记录偏移
     */
    public CursorPosition(PageId pageId, int recordOffset) {
        this.pageId = pageId;
        this.recordOffset = recordOffset;
        this.valid = true;
    }

    /**
     * 构造无效位置
     */
    private CursorPosition() {
        this.pageId = null;
        this.recordOffset = -1;
        this.valid = false;
    }

    /**
     * 创建无效位置
     *
     * @return 无效位置
     */
    public static CursorPosition invalid() {
        return new CursorPosition();
    }

    /**
     * 获取页面 ID
     *
     * @return 页面 ID
     */
    public PageId getPageId() {
        return pageId;
    }

    /**
     * 获取记录偏移
     *
     * @return 记录偏移
     */
    public int getRecordOffset() {
        return recordOffset;
    }

    /**
     * 是否有效
     *
     * @return 如果位置有效返回 true
     */
    public boolean isValid() {
        return valid;
    }

    @Override
    public String toString() {
        if (!valid) {
            return "CursorPosition{INVALID}";
        }
        return String.format("CursorPosition{pageId=%s, offset=%d}", pageId, recordOffset);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CursorPosition that = (CursorPosition) o;
        if (!valid && !that.valid) return true;
        if (valid != that.valid) return false;
        return recordOffset == that.recordOffset &&
                pageId != null && pageId.equals(that.pageId);
    }

    @Override
    public int hashCode() {
        if (!valid) return 0;
        int result = pageId != null ? pageId.hashCode() : 0;
        result = 31 * result + recordOffset;
        return result;
    }
}
