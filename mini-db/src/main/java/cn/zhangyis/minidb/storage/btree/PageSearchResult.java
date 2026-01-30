package cn.zhangyis.minidb.storage.btree;

/**
 * 页内搜索结果
 *
 * <p>表示在单个 IndexPage 内搜索的结果。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class PageSearchResult {

    /** 找到的记录偏移（如果精确匹配）或应插入位置的前一条记录偏移 */
    private final int recordOffset;

    /** 是否精确匹配 */
    private final boolean exactMatch;

    /** 记录所在的 slot 号 */
    private final int slotNo;

    /**
     * 构造搜索结果
     *
     * @param recordOffset 记录偏移
     * @param exactMatch   是否精确匹配
     * @param slotNo       slot 号
     */
    public PageSearchResult(int recordOffset, boolean exactMatch, int slotNo) {
        this.recordOffset = recordOffset;
        this.exactMatch = exactMatch;
        this.slotNo = slotNo;
    }

    /**
     * 获取记录偏移
     *
     * <p>如果 exactMatch=true，这是匹配记录的偏移。
     * 如果 exactMatch=false，这是应插入位置的前一条记录偏移（新记录应插入在此记录之后）。</p>
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
     * 获取 slot 号
     *
     * <p>记录所在或应插入的 slot 范围。</p>
     *
     * @return slot 号
     */
    public int getSlotNo() {
        return slotNo;
    }

    @Override
    public String toString() {
        return String.format("PageSearchResult{offset=%d, exactMatch=%s, slot=%d}",
                recordOffset, exactMatch, slotNo);
    }
}
