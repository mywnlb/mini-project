package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.storage.page.PageId;

/**
 * 页分裂结果
 *
 * <p>表示页分裂操作的结果，包含分裂键和新页面信息。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class SplitResult {

    /** 分裂键（新页面的最小键） */
    private final byte[] splitKey;

    /** 新页面的 PageId */
    private final PageId newPageId;

    /** 新页面中第一条记录的偏移 */
    private final int firstRecordOffset;

    /** 迁移到新页面的记录数 */
    private final int movedRecordCount;

    /**
     * 构造分裂结果
     *
     * @param splitKey          分裂键
     * @param newPageId         新页面 ID
     * @param firstRecordOffset 新页面第一条记录偏移
     * @param movedRecordCount  迁移的记录数
     */
    public SplitResult(byte[] splitKey, PageId newPageId, int firstRecordOffset, int movedRecordCount) {
        this.splitKey = splitKey;
        this.newPageId = newPageId;
        this.firstRecordOffset = firstRecordOffset;
        this.movedRecordCount = movedRecordCount;
    }

    /**
     * 获取分裂键
     *
     * <p>分裂键是新页面的最小键，需要插入到父节点中。</p>
     *
     * @return 分裂键字节数组
     */
    public byte[] getSplitKey() {
        return splitKey;
    }

    /**
     * 获取新页面 ID
     *
     * @return 新页面的 PageId
     */
    public PageId getNewPageId() {
        return newPageId;
    }

    /**
     * 获取新页面第一条记录偏移
     *
     * @return 记录偏移
     */
    public int getFirstRecordOffset() {
        return firstRecordOffset;
    }

    /**
     * 获取迁移的记录数
     *
     * @return 记录数
     */
    public int getMovedRecordCount() {
        return movedRecordCount;
    }

    @Override
    public String toString() {
        return String.format("SplitResult{newPageId=%s, movedRecords=%d}",
                newPageId, movedRecordCount);
    }
}
