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
    long totalRows
) {
    /** 等值选择率 = 1/NDV，无统计回退 0.1 */
    public double selectivityEq() {
        return ndv > 0 ? 1.0 / ndv : 0.1;
    }

    /** 范围选择率，基于 min/max 线性插值 */
    @SuppressWarnings("unchecked")
    public double selectivityRange(Comparable<?> value, boolean isLessThan) {
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
