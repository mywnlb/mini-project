package cn.zhangyis.minidb.sql.catalog;

/**
 * 列级统计信息（ANALYZE TABLE 时采集）
 */
public record ColumnStatistics(
    String columnName,
    long ndv,
    Comparable<?> minValue,
    Comparable<?> maxValue,
    long nullCount,
    long totalRows,
    Histogram histogram
) {
    /** 兼容旧代码的构造函数 */
    public ColumnStatistics(String columnName, long ndv, Comparable<?> minValue,
                          Comparable<?> maxValue, long nullCount, long totalRows) {
        this(columnName, ndv, minValue, maxValue, nullCount, totalRows, null);
    }

    /** 等值选择率 = 1/NDV，无统计回退 0.1。优先使用Histogram */
    public double selectivityEq() {
        if (histogram != null) {
            // Histogram.selectivityEq 需要value，这里返回平均值作为近似
            return ndv > 0 ? 1.0 / ndv : 0.1;
        }
        return ndv > 0 ? 1.0 / ndv : 0.1;
    }

    /** 等值选择率（带具体值）- 为Histogram优化准备 */
    public double selectivityEq(Comparable<?> value) {
        if (histogram != null && value != null) {
            return histogram.selectivityEq(value);
        }
        return selectivityEq();
    }

    /** 范围选择率，基于 min/max 线性插值。优先使用Histogram */
    @SuppressWarnings("unchecked")
    public double selectivityRange(Comparable<?> value, boolean isLessThan) {
        if (histogram != null && value != null) {
            return histogram.selectivityRange(value, isLessThan);
        }
        if (minValue == null || maxValue == null || value == null) return 0.3;
        try {
            double min = toDouble(minValue);
            double max = toDouble(maxValue);
            double val = toDouble(value);
            if (max <= min) return 0.3;
            double fraction = (val - min) / (max - min);
            fraction = Math.max(0.0, Math.min(1.0, fraction));
            return isLessThan ? fraction : (1.0 - fraction);
        } catch (Exception e) {
            return 0.3;
        }
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return 0;
    }
}