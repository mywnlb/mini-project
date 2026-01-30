package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.storage.page.PageId;

/**
 * B+Tree 搜索结果
 *
 * <p>表示 B+Tree 搜索操作的结果。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class BTreeSearchResult {

    /** 找到记录所在的页面 ID */
    private final PageId pageId;

    /** 记录偏移（如果精确匹配）或应插入位置的前一条记录偏移 */
    private final int recordOffset;

    /** 是否精确匹配 */
    private final boolean exactMatch;

    /** 搜索路径 */
    private final BTreePath path;

    /**
     * 构造搜索结果
     *
     * @param pageId       页面 ID
     * @param recordOffset 记录偏移
     * @param exactMatch   是否精确匹配
     * @param path         搜索路径
     */
    public BTreeSearchResult(PageId pageId, int recordOffset, boolean exactMatch, BTreePath path) {
        this.pageId = pageId;
        this.recordOffset = recordOffset;
        this.exactMatch = exactMatch;
        this.path = path;
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
     * 是否精确匹配
     *
     * @return 如果找到完全匹配的记录返回 true
     */
    public boolean isExactMatch() {
        return exactMatch;
    }

    /**
     * 获取搜索路径
     *
     * @return 搜索路径
     */
    public BTreePath getPath() {
        return path;
    }

    @Override
    public String toString() {
        return String.format("BTreeSearchResult{pageId=%s, offset=%d, exactMatch=%s}",
                pageId, recordOffset, exactMatch);
    }
}
