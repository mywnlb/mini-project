package cn.zhangyis.minidb.storage.btree;

/**
 * B+Tree 统计信息
 *
 * <p>存储 B+Tree 的各种统计数据，用于查询优化和诊断。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class BTreeStats {

    // ==================== 基本统计 ====================

    /** 索引 ID */
    private final long indexId;

    /** 树高度 */
    private int treeHeight;

    /** 总记录数 */
    private long recordCount;

    /** 叶子页面数 */
    private int leafPageCount;

    /** 非叶子页面数 */
    private int internalPageCount;

    /** 总页面数 */
    private int totalPageCount;

    // ==================== 空间统计 ====================

    /** 总使用空间（字节） */
    private long totalSpaceUsed;

    /** 总空闲空间（字节） */
    private long totalFreeSpace;

    /** 平均页面填充率 */
    private double avgFillFactor;

    /** 最小页面填充率 */
    private double minFillFactor;

    /** 最大页面填充率 */
    private double maxFillFactor;

    // ==================== 键统计 ====================

    /** 最小键 */
    private byte[] minKey;

    /** 最大键 */
    private byte[] maxKey;

    /** 不同键的数量（估计值） */
    private long distinctKeyCount;

    /** 平均键大小（字节） */
    private double avgKeySize;

    /** 平均值大小（字节） */
    private double avgValueSize;

    // ==================== 页面级统计 ====================

    /** 平均每页记录数 */
    private double avgRecordsPerPage;

    /** 最小每页记录数 */
    private int minRecordsPerPage;

    /** 最大每页记录数 */
    private int maxRecordsPerPage;

    // ==================== 收集时间 ====================

    /** 统计收集时间戳 */
    private long collectionTimestamp;

    /** 统计收集耗时（毫秒） */
    private long collectionDurationMs;

    /**
     * 构造统计信息
     *
     * @param indexId 索引 ID
     */
    public BTreeStats(long indexId) {
        this.indexId = indexId;
        this.collectionTimestamp = System.currentTimeMillis();
    }

    // ==================== Getters and Setters ====================

    public long getIndexId() {
        return indexId;
    }

    public int getTreeHeight() {
        return treeHeight;
    }

    public void setTreeHeight(int treeHeight) {
        this.treeHeight = treeHeight;
    }

    public long getRecordCount() {
        return recordCount;
    }

    public void setRecordCount(long recordCount) {
        this.recordCount = recordCount;
    }

    public int getLeafPageCount() {
        return leafPageCount;
    }

    public void setLeafPageCount(int leafPageCount) {
        this.leafPageCount = leafPageCount;
    }

    public int getInternalPageCount() {
        return internalPageCount;
    }

    public void setInternalPageCount(int internalPageCount) {
        this.internalPageCount = internalPageCount;
    }

    public int getTotalPageCount() {
        return totalPageCount;
    }

    public void setTotalPageCount(int totalPageCount) {
        this.totalPageCount = totalPageCount;
    }

    public long getTotalSpaceUsed() {
        return totalSpaceUsed;
    }

    public void setTotalSpaceUsed(long totalSpaceUsed) {
        this.totalSpaceUsed = totalSpaceUsed;
    }

    public long getTotalFreeSpace() {
        return totalFreeSpace;
    }

    public void setTotalFreeSpace(long totalFreeSpace) {
        this.totalFreeSpace = totalFreeSpace;
    }

    public double getAvgFillFactor() {
        return avgFillFactor;
    }

    public void setAvgFillFactor(double avgFillFactor) {
        this.avgFillFactor = avgFillFactor;
    }

    public double getMinFillFactor() {
        return minFillFactor;
    }

    public void setMinFillFactor(double minFillFactor) {
        this.minFillFactor = minFillFactor;
    }

    public double getMaxFillFactor() {
        return maxFillFactor;
    }

    public void setMaxFillFactor(double maxFillFactor) {
        this.maxFillFactor = maxFillFactor;
    }

    public byte[] getMinKey() {
        return minKey;
    }

    public void setMinKey(byte[] minKey) {
        this.minKey = minKey;
    }

    public byte[] getMaxKey() {
        return maxKey;
    }

    public void setMaxKey(byte[] maxKey) {
        this.maxKey = maxKey;
    }

    public long getDistinctKeyCount() {
        return distinctKeyCount;
    }

    public void setDistinctKeyCount(long distinctKeyCount) {
        this.distinctKeyCount = distinctKeyCount;
    }

    public double getAvgKeySize() {
        return avgKeySize;
    }

    public void setAvgKeySize(double avgKeySize) {
        this.avgKeySize = avgKeySize;
    }

    public double getAvgValueSize() {
        return avgValueSize;
    }

    public void setAvgValueSize(double avgValueSize) {
        this.avgValueSize = avgValueSize;
    }

    public double getAvgRecordsPerPage() {
        return avgRecordsPerPage;
    }

    public void setAvgRecordsPerPage(double avgRecordsPerPage) {
        this.avgRecordsPerPage = avgRecordsPerPage;
    }

    public int getMinRecordsPerPage() {
        return minRecordsPerPage;
    }

    public void setMinRecordsPerPage(int minRecordsPerPage) {
        this.minRecordsPerPage = minRecordsPerPage;
    }

    public int getMaxRecordsPerPage() {
        return maxRecordsPerPage;
    }

    public void setMaxRecordsPerPage(int maxRecordsPerPage) {
        this.maxRecordsPerPage = maxRecordsPerPage;
    }

    public long getCollectionTimestamp() {
        return collectionTimestamp;
    }

    public void setCollectionTimestamp(long collectionTimestamp) {
        this.collectionTimestamp = collectionTimestamp;
    }

    public long getCollectionDurationMs() {
        return collectionDurationMs;
    }

    public void setCollectionDurationMs(long collectionDurationMs) {
        this.collectionDurationMs = collectionDurationMs;
    }

    // ==================== 计算方法 ====================

    /**
     * 获取统计信息的年龄（毫秒）
     *
     * @return 自收集以来的毫秒数
     */
    public long getAgeMs() {
        return System.currentTimeMillis() - collectionTimestamp;
    }

    /**
     * 检查统计信息是否过时
     *
     * @param maxAgeMs 最大年龄（毫秒）
     * @return 如果过时返回 true
     */
    public boolean isStale(long maxAgeMs) {
        return getAgeMs() > maxAgeMs;
    }

    /**
     * 估算范围查询的选择性
     *
     * <p>返回范围内记录数占总记录数的比例估计。</p>
     *
     * @param lowerKey 下界键（null 表示无下界）
     * @param upperKey 上界键（null 表示无上界）
     * @return 选择性（0.0 到 1.0）
     */
    public double estimateSelectivity(byte[] lowerKey, byte[] upperKey) {
        if (recordCount == 0 || minKey == null || maxKey == null) {
            return 1.0;
        }

        // 简化实现：假设均匀分布
        int minKeyInt = minKey != null ? IntKeyComparator.bytesToInt(minKey) : Integer.MIN_VALUE;
        int maxKeyInt = maxKey != null ? IntKeyComparator.bytesToInt(maxKey) : Integer.MAX_VALUE;
        int lowerInt = lowerKey != null ? IntKeyComparator.bytesToInt(lowerKey) : minKeyInt;
        int upperInt = upperKey != null ? IntKeyComparator.bytesToInt(upperKey) : maxKeyInt;

        if (maxKeyInt == minKeyInt) {
            return 1.0;
        }

        double totalRange = maxKeyInt - minKeyInt;
        double queryRange = Math.min(upperInt, maxKeyInt) - Math.max(lowerInt, minKeyInt);

        return Math.max(0.0, Math.min(1.0, queryRange / totalRange));
    }

    /**
     * 估算范围查询返回的记录数
     *
     * @param lowerKey 下界键
     * @param upperKey 上界键
     * @return 估计的记录数
     */
    public long estimateRangeCount(byte[] lowerKey, byte[] upperKey) {
        double selectivity = estimateSelectivity(lowerKey, upperKey);
        return Math.round(recordCount * selectivity);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("BTreeStats {\n");
        sb.append(String.format("  indexId: %d\n", indexId));
        sb.append(String.format("  treeHeight: %d\n", treeHeight));
        sb.append(String.format("  recordCount: %d\n", recordCount));
        sb.append(String.format("  totalPageCount: %d (leaf=%d, internal=%d)\n",
                totalPageCount, leafPageCount, internalPageCount));
        sb.append(String.format("  avgFillFactor: %.2f%% (min=%.2f%%, max=%.2f%%)\n",
                avgFillFactor * 100, minFillFactor * 100, maxFillFactor * 100));
        sb.append(String.format("  avgRecordsPerPage: %.1f (min=%d, max=%d)\n",
                avgRecordsPerPage, minRecordsPerPage, maxRecordsPerPage));
        if (minKey != null) {
            sb.append(String.format("  keyRange: [%d, %d]\n",
                    IntKeyComparator.bytesToInt(minKey), IntKeyComparator.bytesToInt(maxKey)));
        }
        sb.append(String.format("  collectionDuration: %d ms\n", collectionDurationMs));
        sb.append("}");
        return sb.toString();
    }
}
