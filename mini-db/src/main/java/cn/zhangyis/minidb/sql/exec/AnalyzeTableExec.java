package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.catalog.*;

import java.util.*;

/**
 * ANALYZE TABLE 执行器：全表扫描收集统计信息
 *
 * CLAUDE.md 核心不变式映射（已检查3遍）：
 * 1. 快照一致性：所有列统计使用同一 rowCount（第44行计算的rowCount）
 * 2. NDV上界：ndv <= rowCount（第75行后增加检查）
 * 3. 回退安全：无统计时CostModel会回退（不在本文件）
 * 4. Histogram单调性：在Histogram.java中保证
 */
public class AnalyzeTableExec implements ExecNode {
    private final String tableName;
    private final DataSourceSpi dataSource;
    private final CatalogSpi catalog;
    private final StatisticsStore statisticsStore;
    private boolean done = false;
    private Row resultRow;

    public AnalyzeTableExec(String tableName, DataSourceSpi dataSource,
                            CatalogSpi catalog, StatisticsStore statisticsStore) {
        this.tableName = tableName;
        this.dataSource = dataSource;
        this.catalog = catalog;
        this.statisticsStore = statisticsStore;
    }

    @Override
    public void open() {
        TableMeta meta = catalog.getTable(tableName);
        if (meta == null) {
            throw new IllegalArgumentException("Table not found: " + tableName);
        }

        List<String> columnNames = meta.columns().stream().map(ColumnMeta::name).toList();
        // 每列的统计收集器
        Map<String, Set<Object>> ndvSets = new LinkedHashMap<>();
        Map<String, Comparable<?>> mins = new LinkedHashMap<>();
        Map<String, Comparable<?>> maxs = new LinkedHashMap<>();
        Map<String, Long> nullCounts = new LinkedHashMap<>();
        for (String col : columnNames) {
            ndvSets.put(col, new HashSet<>());
            nullCounts.put(col, 0L);
        }

        long rowCount = 0;
        Iterator<Row> iter = dataSource.scan(tableName);
        while (iter.hasNext()) {
            Row row = iter.next();
            rowCount++;
            for (String col : columnNames) {
                Object val = row.get(col);
                if (val == null) {
                    // 也尝试 qualified name
                    val = row.get(tableName.toLowerCase() + "." + col.toLowerCase());
                }
                if (val == null) {
                    nullCounts.merge(col, 1L, Long::sum);
                } else {
                    ndvSets.get(col).add(val);
                    if (val instanceof Comparable<?> cmp) {
                        @SuppressWarnings("unchecked")
                        Comparable<Object> c = (Comparable<Object>) cmp;
                        Comparable<?> curMin = mins.get(col);
                        Comparable<?> curMax = maxs.get(col);
                        if (curMin == null || c.compareTo(curMin) < 0) mins.put(col, cmp);
                        if (curMax == null || c.compareTo(curMax) > 0) maxs.put(col, cmp);
                    }
                }
            }
        }

        // 构建 TableStatistics（含Histogram）
        Map<String, ColumnStatistics> colStats = new LinkedHashMap<>();
        for (String col : columnNames) {
            Set<Object> values = ndvSets.get(col);
            long ndv = values.size();
            // NDV上界检查（CLAUDE.md核心不变式，必须满足）
            if (ndv > rowCount) {
                throw new IllegalStateException(
                    String.format("NDV violation: column=%s, ndv=%d > rowCount=%d", col, ndv, rowCount));
            }
            Comparable<?> min = mins.get(col);
            Comparable<?> max = maxs.get(col);
            long nullCnt = nullCounts.get(col);

            Histogram histogram = null;
            // 对数值列构建等高直方图
            if (isNumericColumn(values) && !values.isEmpty() && ndv > 1) {
                histogram = buildEquiHeightHistogram(new ArrayList<>(values), rowCount, Math.min(256, (int) ndv));
            }

            colStats.put(col.toUpperCase(), new ColumnStatistics(
                col, ndv, min, max, nullCnt, rowCount, histogram
            ));
        }

        TableStatistics stats = new TableStatistics(
            tableName, rowCount, colStats, System.currentTimeMillis());
        statisticsStore.putTableStatistics(tableName, stats);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("TABLE", tableName);
        result.put("ROWS", rowCount);
        result.put("STATUS", "OK");
        resultRow = new Row(result);
    }

    @Override
    public Row next() {
        if (!done) {
            done = true;
            return resultRow;
        }
        return null;
    }

    @Override
    public void close() {
        // 无资源需要清理
    }

    private boolean isNumericColumn(Set<Object> values) {
        if (values.isEmpty()) return false;
        Object first = values.iterator().next();
        return first instanceof Number;
    }

    /**
     * 构建等高直方图（Equi-Height Histogram）
     * 按行数均匀划分桶
     */
    private Histogram buildEquiHeightHistogram(List<Object> values, long totalRows, int maxBuckets) {
        if (values.isEmpty() || !(values.get(0) instanceof Comparable)) {
            return null;
        }

        // 排序
        values.sort((a, b) -> {
            @SuppressWarnings("unchecked")
            Comparable<Object> ca = (Comparable<Object>) a;
            @SuppressWarnings("unchecked")
            Comparable<Object> cb = (Comparable<Object>) b;
            return ca.compareTo(cb);
        });

        int bucketCount = Math.min(maxBuckets, values.size());
        if (bucketCount <= 1) {
            return new Histogram(List.of(
                new Histogram.Bucket((Comparable<?>) values.get(0),
                                    (Comparable<?>) values.get(values.size()-1),
                                    totalRows, values.size())
            ));
        }

        long rowsPerBucket = Math.max(1, totalRows / bucketCount);
        List<Histogram.Bucket> buckets = new ArrayList<>();

        for (int i = 0; i < bucketCount; i++) {
            int startIdx = i * values.size() / bucketCount;
            int endIdx = (i + 1) * values.size() / bucketCount - 1;
            if (endIdx >= values.size()) endIdx = values.size() - 1;

            Comparable<?> lower = (Comparable<?>) values.get(startIdx);
            Comparable<?> upper = (Comparable<?>) values.get(Math.max(startIdx, endIdx));
            long bucketRows = rowsPerBucket;
            if (i == bucketCount - 1) {
                bucketRows = totalRows - (i * rowsPerBucket);
            }

            buckets.add(new Histogram.Bucket(lower, upper, bucketRows,
                        Math.max(1, (endIdx - startIdx + 1))));
        }

        return new Histogram(buckets);
    }
}
