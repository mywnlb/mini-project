package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;

import java.util.*;

/**
 * 最终聚合执行器：合并 PartialAggregateExec 的中间结果。
 *
 * 按 group keys 再次分组，对 partial 列做最终聚合：
 *   COUNT → SUM(partial_count)
 *   SUM   → SUM(partial_sum)
 *   AVG   → SUM(partial_sum) / SUM(partial_count)
 *   MAX   → MAX(partial_max)
 *   MIN   → MIN(partial_min)
 */
public class FinalAggregateExec implements ExecNode {
    private final ExecNode input;
    private final SqlNodeList groupBy;
    private final List<SqlAggCall> originalCalls;
    private final List<PartialAggCall> partialCalls;

    private Iterator<Row> resultIterator;

    public FinalAggregateExec(ExecNode input, SqlNodeList groupBy,
                              List<SqlAggCall> originalCalls, List<PartialAggCall> partialCalls) {
        this.input = input;
        this.groupBy = groupBy;
        this.originalCalls = originalCalls;
        this.partialCalls = partialCalls;
    }

    @Override
    public void open() {
        input.open();

        // 按 group keys 分组 partial 结果
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        Row row;
        while ((row = input.next()) != null) {
            String groupKey = buildGroupKey(row);
            groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(row);
        }

        // 最终聚合
        List<Row> results = new ArrayList<>();
        for (Map.Entry<String, List<Row>> entry : groups.entrySet()) {
            List<Row> groupRows = entry.getValue();
            Map<String, Object> resultRow = new LinkedHashMap<>();

            // 添加 group key 列
            if (groupBy != null) {
                Row sample = groupRows.get(0);
                for (SqlNode node : groupBy.nodes()) {
                    if (node instanceof SqlIdentifier id) {
                        resultRow.put(id.name(), sample.get(id.name()));
                    }
                }
            }

            // 合并每个原始聚合
            int partialIdx = 0;
            for (SqlAggCall original : originalCalls) {
                String func = original.funcName().toUpperCase();
                String key = buildAggKey(original);

                switch (func) {
                    case "COUNT" -> {
                        PartialAggCall pc = partialCalls.get(partialIdx++);
                        resultRow.put(key, sumPartialColumn(groupRows, pc.outputAlias()));
                    }
                    case "SUM" -> {
                        PartialAggCall pc = partialCalls.get(partialIdx++);
                        resultRow.put(key, sumPartialColumn(groupRows, pc.outputAlias()));
                    }
                    case "AVG" -> {
                        PartialAggCall sumCall = partialCalls.get(partialIdx++);
                        PartialAggCall countCall = partialCalls.get(partialIdx++);
                        double totalSum = sumPartialColumnDouble(groupRows, sumCall.outputAlias());
                        long totalCount = sumPartialColumnLong(groupRows, countCall.outputAlias());
                        resultRow.put(key, totalCount > 0 ? totalSum / totalCount : null);
                    }
                    case "MAX" -> {
                        PartialAggCall pc = partialCalls.get(partialIdx++);
                        resultRow.put(key, maxPartialColumn(groupRows, pc.outputAlias()));
                    }
                    case "MIN" -> {
                        PartialAggCall pc = partialCalls.get(partialIdx++);
                        resultRow.put(key, minPartialColumn(groupRows, pc.outputAlias()));
                    }
                    default -> partialIdx++;
                }
            }

            results.add(new Row(resultRow));
        }

        resultIterator = results.iterator();
    }

    @Override
    public Row next() {
        return resultIterator.hasNext() ? resultIterator.next() : null;
    }

    @Override
    public void close() {
        input.close();
        resultIterator = null;
    }

    private String buildGroupKey(Row row) {
        if (groupBy == null || groupBy.size() == 0) return "__ALL__";
        StringBuilder sb = new StringBuilder();
        for (SqlNode node : groupBy.nodes()) {
            if (node instanceof SqlIdentifier id) {
                sb.append(row.get(id.name())).append("|");
            }
        }
        return sb.toString();
    }

    private String buildAggKey(SqlAggCall agg) {
        if (agg.arg().kind() == SqlKind.STAR) {
            return agg.funcName().toUpperCase() + "(*)";
        }
        String argStr = agg.arg().toString().replaceAll("\\s+", "");
        return agg.funcName().toUpperCase() + "(" + argStr + ")";
    }

    /** SUM a partial column, returning as Number (auto long/double) */
    private Object sumPartialColumn(List<Row> rows, String colName) {
        double sum = 0;
        boolean hasNonNull = false;
        boolean allLong = true;
        for (Row r : rows) {
            Object val = r.get(colName);
            if (val instanceof Number n) {
                sum += n.doubleValue();
                hasNonNull = true;
                if (!(val instanceof Long || val instanceof Integer)) allLong = false;
            }
        }
        if (!hasNonNull) return null;
        return allLong ? (long) sum : sum;
    }

    private double sumPartialColumnDouble(List<Row> rows, String colName) {
        double sum = 0;
        for (Row r : rows) {
            Object val = r.get(colName);
            if (val instanceof Number n) sum += n.doubleValue();
        }
        return sum;
    }

    private long sumPartialColumnLong(List<Row> rows, String colName) {
        long sum = 0;
        for (Row r : rows) {
            Object val = r.get(colName);
            if (val instanceof Number n) sum += n.longValue();
        }
        return sum;
    }

    private Object maxPartialColumn(List<Row> rows, String colName) {
        Object max = null;
        for (Row r : rows) {
            Object val = r.get(colName);
            if (val == null) continue;
            if (max == null || FilterExec.compareValues(val, max) > 0) max = val;
        }
        return max;
    }

    private Object minPartialColumn(List<Row> rows, String colName) {
        Object min = null;
        for (Row r : rows) {
            Object val = r.get(colName);
            if (val == null) continue;
            if (min == null || FilterExec.compareValues(val, min) < 0) min = val;
        }
        return min;
    }
}
