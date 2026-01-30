package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.storage.page.PageId;

/**
 * B+Tree 索引元数据
 *
 * <p>存储 B+Tree 索引的元信息，包括根节点位置、树高度等。</p>
 *
 * <h2>元数据内容</h2>
 * <ul>
 *   <li>索引 ID</li>
 *   <li>根节点 PageId</li>
 *   <li>树高度</li>
 *   <li>记录总数（可选，用于统计）</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class BTreeMetadata {

    /** 索引 ID */
    private final long indexId;

    /** 空间 ID */
    private final int spaceId;

    /** 根节点页号 */
    private volatile int rootPageNo;

    /** 树高度（叶子节点为 1，每增加一层 +1） */
    private volatile int treeHeight;

    /** 记录总数（近似值，用于统计） */
    private volatile long recordCount;

    /**
     * 构造索引元数据
     *
     * @param indexId    索引 ID
     * @param spaceId    空间 ID
     * @param rootPageNo 根节点页号
     */
    public BTreeMetadata(long indexId, int spaceId, int rootPageNo) {
        this.indexId = indexId;
        this.spaceId = spaceId;
        this.rootPageNo = rootPageNo;
        this.treeHeight = 1; // 初始只有根节点（也是叶子）
        this.recordCount = 0;
    }

    /**
     * 获取索引 ID
     *
     * @return 索引 ID
     */
    public long getIndexId() {
        return indexId;
    }

    /**
     * 获取空间 ID
     *
     * @return 空间 ID
     */
    public int getSpaceId() {
        return spaceId;
    }

    /**
     * 获取根节点页号
     *
     * @return 根节点页号
     */
    public int getRootPageNo() {
        return rootPageNo;
    }

    /**
     * 获取根节点 PageId
     *
     * @return 根节点 PageId
     */
    public PageId getRootPageId() {
        return new PageId(spaceId, rootPageNo);
    }

    /**
     * 设置根节点页号
     *
     * <p>当根节点分裂时调用。</p>
     *
     * @param rootPageNo 新的根节点页号
     */
    public void setRootPageNo(int rootPageNo) {
        this.rootPageNo = rootPageNo;
    }

    /**
     * 获取树高度
     *
     * @return 树高度
     */
    public int getTreeHeight() {
        return treeHeight;
    }

    /**
     * 设置树高度
     *
     * @param treeHeight 新的树高度
     */
    public void setTreeHeight(int treeHeight) {
        this.treeHeight = treeHeight;
    }

    /**
     * 增加树高度
     *
     * <p>当根节点分裂时调用。</p>
     */
    public void incrementTreeHeight() {
        this.treeHeight++;
    }

    /**
     * 获取记录总数
     *
     * @return 记录总数
     */
    public long getRecordCount() {
        return recordCount;
    }

    /**
     * 增加记录计数
     *
     * @param delta 增量（可以为负数）
     */
    public void addRecordCount(long delta) {
        this.recordCount += delta;
    }

    /**
     * 设置记录总数
     *
     * @param count 新的记录总数
     */
    public void setRecordCount(long count) {
        this.recordCount = count;
    }

    @Override
    public String toString() {
        return String.format("BTreeMetadata{indexId=%d, spaceId=%d, rootPageNo=%d, height=%d, records=%d}",
                indexId, spaceId, rootPageNo, treeHeight, recordCount);
    }
}
