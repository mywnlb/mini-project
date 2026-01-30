package cn.zhangyis.minidb.storage.btree;

/**
 * 批量加载结果
 *
 * <p>记录批量加载操作的结果信息。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class BulkLoadResult {

    /** 是否成功 */
    private final boolean success;

    /** 加载的记录数 */
    private final long recordCount;

    /** 创建的叶子页面数 */
    private final int leafPageCount;

    /** 创建的非叶子页面数 */
    private final int internalPageCount;

    /** 树高度 */
    private final int treeHeight;

    /** 耗时（毫秒） */
    private final long durationMs;

    /** 错误信息（如果失败） */
    private final String errorMessage;

    /** 统计信息（可选） */
    private BTreeStats stats;

    /**
     * 创建成功结果
     */
    public static BulkLoadResult success(long recordCount, int leafPageCount,
                                         int internalPageCount, int treeHeight, long durationMs) {
        return new BulkLoadResult(true, recordCount, leafPageCount, internalPageCount,
                treeHeight, durationMs, null);
    }

    /**
     * 创建失败结果
     */
    public static BulkLoadResult failure(String errorMessage, long durationMs) {
        return new BulkLoadResult(false, 0, 0, 0, 0, durationMs, errorMessage);
    }

    private BulkLoadResult(boolean success, long recordCount, int leafPageCount,
                           int internalPageCount, int treeHeight, long durationMs,
                           String errorMessage) {
        this.success = success;
        this.recordCount = recordCount;
        this.leafPageCount = leafPageCount;
        this.internalPageCount = internalPageCount;
        this.treeHeight = treeHeight;
        this.durationMs = durationMs;
        this.errorMessage = errorMessage;
    }

    // ==================== Getters ====================

    public boolean isSuccess() {
        return success;
    }

    public long getRecordCount() {
        return recordCount;
    }

    public int getLeafPageCount() {
        return leafPageCount;
    }

    public int getInternalPageCount() {
        return internalPageCount;
    }

    public int getTotalPageCount() {
        return leafPageCount + internalPageCount;
    }

    public int getTreeHeight() {
        return treeHeight;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public BTreeStats getStats() {
        return stats;
    }

    public void setStats(BTreeStats stats) {
        this.stats = stats;
    }

    /**
     * 获取加载速率（记录/秒）
     */
    public double getRecordsPerSecond() {
        if (durationMs == 0) {
            return 0;
        }
        return (double) recordCount / durationMs * 1000;
    }

    @Override
    public String toString() {
        if (success) {
            return String.format("BulkLoadResult{success=true, records=%d, pages=%d (leaf=%d, internal=%d), " +
                            "height=%d, duration=%dms, rate=%.0f rec/s}",
                    recordCount, getTotalPageCount(), leafPageCount, internalPageCount,
                    treeHeight, durationMs, getRecordsPerSecond());
        } else {
            return String.format("BulkLoadResult{success=false, error='%s', duration=%dms}",
                    errorMessage, durationMs);
        }
    }
}
