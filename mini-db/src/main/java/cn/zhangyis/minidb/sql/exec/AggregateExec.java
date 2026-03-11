package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;

import java.util.*;

/**
 * 聚合执行器：Hash Aggregate
 * 按 groupBy 列分组，计算聚合函数
 */
public class AggregateExec implements ExecNode {
    private final ExecNode input;
    private final SqlNodeList groupBy;
    private final List<SqlAggCall> aggCalls;

    private Iterator<Row> resultIterator;

    public AggregateExec(ExecNode input, SqlNodeList groupBy, List<SqlAggCall> aggCalls) {
        this.input = input;
        this.groupBy = groupBy;
        this.aggCalls = aggCalls;
    }

    @Override
    public void open() {
        input.open();

        // 按 groupBy 列分组
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        Row row;
        while ((row = input.next()) != null) {
            String groupKey = buildGroupKey(row);
            groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(row);
        }

        // 计算聚合
        List<Row> results = new ArrayList<>();
        for (Map.Entry<String, List<Row>> entry : groups.entrySet()) {
            List<Row> groupRows = entry.getValue();
            Map<String, Object> resultRow = new LinkedHashMap<>();

            // 添加 groupBy 列
            if (groupBy != null) {
                Row sample = groupRows.get(0);
                for (SqlNode node : groupBy.nodes()) {
                    if (node instanceof SqlIdentifier id) {
                        resultRow.put(id.name(), sample.get(id.name()));
                    }
                }
            }

            // 计算每个聚合函数
            for (SqlAggCall agg : aggCalls) {
                String key = agg.funcName() + "(" + agg.arg() + ")";
                resultRow.put(key, computeAgg(agg, groupRows));
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

    private Object computeAgg(SqlAggCall agg, List<Row> rows) {
        String func = agg.funcName().toUpperCase();
        return switch (func) {
            case "COUNT" -> (long) rows.size();
            case "SUM" -> {
                double sum = 0;
                for (Row r : rows) {
                    Object val = resolveAggArg(agg.arg(), r);
                    if (val instanceof Number n) sum += n.doubleValue();
                }
                yield sum;
            }
            case "AVG" -> {
                double sum = 0;
                int count = 0;
                for (Row r : rows) {
                    Object val = resolveAggArg(agg.arg(), r);
                    if (val instanceof Number n) { sum += n.doubleValue(); count++; }
                }
                yield count > 0 ? sum / count : 0.0;
            }
            case "MAX" -> {
                Object max = null;
                for (Row r : rows) {
                    Object val = resolveAggArg(agg.arg(), r);
                    if (max == null || FilterExec.compareValues(val, max) > 0) max = val;
                }
                yield max;
            }
            case "MIN" -> {
                Object min = null;
                for (Row r : rows) {
                    Object val = resolveAggArg(agg.arg(), r);
                    if (min == null || FilterExec.compareValues(val, min) < 0) min = val;
                }
                yield min;
            }
            default -> null;
        };
    }

    private Object resolveAggArg(SqlNode arg, Row row) {
        if (arg.kind() == SqlKind.STAR) return 1; // COUNT(*)
        if (arg instanceof SqlIdentifier id) return row.get(id.name());
        return null;
    }
}
