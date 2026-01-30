package cn.zhangyis.minidb.storage.btree;

/**
 * 批量加载配置
 *
 * <p>控制批量加载的行为参数。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class BulkLoadConfig {

    /** 默认页面填充率 */
    public static final double DEFAULT_FILL_FACTOR = 0.9;

    /** 默认批次大小 */
    public static final int DEFAULT_BATCH_SIZE = 1000;

    /** 页面填充率（0.5 到 1.0） */
    private double fillFactor;

    /** 每批处理的记录数 */
    private int batchSize;

    /** 是否验证输入数据已排序 */
    private boolean validateSorted;

    /** 是否在加载后收集统计信息 */
    private boolean collectStats;

    /** 是否显示进度 */
    private boolean showProgress;

    /**
     * 创建默认配置
     */
    public BulkLoadConfig() {
        this.fillFactor = DEFAULT_FILL_FACTOR;
        this.batchSize = DEFAULT_BATCH_SIZE;
        this.validateSorted = true;
        this.collectStats = false;
        this.showProgress = false;
    }

    /**
     * 创建自定义配置
     *
     * @param fillFactor 填充率
     * @param batchSize  批次大小
     */
    public BulkLoadConfig(double fillFactor, int batchSize) {
        this.fillFactor = Math.max(0.5, Math.min(1.0, fillFactor));
        this.batchSize = Math.max(100, batchSize);
        this.validateSorted = true;
        this.collectStats = false;
        this.showProgress = false;
    }

    // ==================== Builder 方法 ====================

    public BulkLoadConfig fillFactor(double fillFactor) {
        this.fillFactor = Math.max(0.5, Math.min(1.0, fillFactor));
        return this;
    }

    public BulkLoadConfig batchSize(int batchSize) {
        this.batchSize = Math.max(100, batchSize);
        return this;
    }

    public BulkLoadConfig validateSorted(boolean validate) {
        this.validateSorted = validate;
        return this;
    }

    public BulkLoadConfig collectStats(boolean collect) {
        this.collectStats = collect;
        return this;
    }

    public BulkLoadConfig showProgress(boolean show) {
        this.showProgress = show;
        return this;
    }

    // ==================== Getters ====================

    public double getFillFactor() {
        return fillFactor;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public boolean isValidateSorted() {
        return validateSorted;
    }

    public boolean isCollectStats() {
        return collectStats;
    }

    public boolean isShowProgress() {
        return showProgress;
    }

    /**
     * 计算页面的目标使用空间
     *
     * @param pageSize 页面大小
     * @return 目标使用空间（字节）
     */
    public int getTargetUsedSpace(int pageSize) {
        return (int) (pageSize * fillFactor);
    }

    @Override
    public String toString() {
        return String.format("BulkLoadConfig{fillFactor=%.2f, batchSize=%d, validateSorted=%s}",
                fillFactor, batchSize, validateSorted);
    }
}
