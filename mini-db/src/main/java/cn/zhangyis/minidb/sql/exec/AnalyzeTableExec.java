package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.catalog.*;

import java.util.*;

/**
 * ANALYZE TABLE 执行器：全表扫描收集统计信息
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

        // 构建 TableStatistics
        Map<String, ColumnStatistics> colStats = new LinkedHashMap<>();
        for (String col : columnNames) {
            colStats.put(col.toUpperCase(), new ColumnStatistics(
                col, ndvSets.get(col).size(),
                mins.get(col), maxs.get(col),
                nullCounts.get(col), rowCount
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
    public void close() {}
}
