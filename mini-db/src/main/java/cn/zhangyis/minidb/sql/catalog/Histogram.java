package cn.zhangyis.minidb.sql.catalog;

import java.util.List;

/**
 * 等高直方图（equi-height histogram）
 * 不可变对象，线程安全
 *
 * 核心不变式（CLAUDE.md要求映射）：
 * 1. 不可变性：buckets使用List.copyOf
 * 2. 单调性：桶边界严格递增，selectivity结果∈[0.0,1.0]
 * 3. NDV有效性：每个桶ndv>0时selectivityEq返回合理值
 */
public record Histogram(List<Bucket> buckets) {

    public Histogram {
        buckets = List.copyOf(buckets); // 不可变
    }

    /**
     * 直方图桶
     */
    public record Bucket(Comparable<?> lowerBound, Comparable<?> upperBound, long rowCount, long ndv) {}

    /**
     * 等值选择率：查找 value 所在桶，返回 1/桶内NDV
     */
    public double selectivityEq(Comparable<?> value) {
        if (buckets.isEmpty() || value == null) return 0.1;
        for (Bucket b : buckets) {
            if (inBucket(value, b)) {
                return b.ndv > 0 ? 1.0 / b.ndv : 0.1;
            }
        }
        // value 不在任何桶内 → 选择率极低
        return 0.0;
    }

    /**
     * 范围选择率：估算 value < X 或 value > X 的比例
     */
    public double selectivityRange(Comparable<?> value, boolean isLessThan) {
        if (buckets.isEmpty() || value == null) return 0.3;
        long totalRows = buckets.stream().mapToLong(Bucket::rowCount).sum();
        if (totalRows <= 0) return 0.3;

        long accumulatedRows = 0;
        for (Bucket b : buckets) {
            double lo = toDouble(b.lowerBound);
            double hi = toDouble(b.upperBound);
            double val = toDouble(value);

            if (val <= lo) {
                // value 在此桶之前
                break;
            } else if (val >= hi) {
                // value 在此桶之后，累计整桶
                accumulatedRows += b.rowCount;
            } else {
                // value 在桶内，线性插值
                double fraction = (hi > lo) ? (val - lo) / (hi - lo) : 0.5;
                accumulatedRows += (long) (b.rowCount * fraction);
                break;
            }
        }

        double sel = (double) accumulatedRows / totalRows;
        sel = Math.max(0.0, Math.min(1.0, sel));
        return isLessThan ? sel : (1.0 - sel);
    }

    @SuppressWarnings("unchecked")
    private boolean inBucket(Comparable<?> value, Bucket b) {
        try {
            Comparable<Object> v = (Comparable<Object>) value;
            return v.compareTo(b.lowerBound) >= 0 && v.compareTo(b.upperBound) <= 0;
        } catch (ClassCastException e) {
            return false;
        }
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return 0;
    }
}
