package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;

import java.util.*;

/**
 * 部分聚合执行器：按 group keys 分组，输出中间结果列。
 *
 * 输出列 = group key columns + partial result columns (_partial_sum_0, _partial_count_0, etc.)
 */
public class PartialAggregateExec implements ExecNode {
    private final ExecNode input;
    private final SqlNodeList groupBy;
    private final List<PartialAggCall> partialCalls;

    private Iterator<Row> resultIterator;

    public PartialAggregateExec(ExecNode input, SqlNodeList groupBy, List<PartialAggCall> partialCalls) {
        this.input = input;
        this.groupBy = groupBy;
        this.partialCalls = partialCalls;
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

        // 计算 partial 聚合
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

            // 计算每个 partial 聚合
            for (PartialAggCall call : partialCalls) {
                Object val = computePartial(call, groupRows);
                resultRow.put(call.outputAlias(), val);
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

    private Object computePartial(PartialAggCall call, List<Row> rows) {
        String func = call.funcName().toUpperCase();
        return switch (func) {
            case "COUNT" -> {
                if (call.arg().kind() == SqlKind.STAR) {
                    yield (long) rows.size();
                }
                long count = 0;
                for (Row r : rows) {
                    if (resolveArg(call.arg(), r) != null) count++;
                }
                yield count;
            }
            case "SUM" -> {
                double sum = 0;
                boolean hasNonNull = false;
                for (Row r : rows) {
                    Object val = resolveArg(call.arg(), r);
                    if (val instanceof Number n) { sum += n.doubleValue(); hasNonNull = true; }
                }
                yield hasNonNull ? sum : null;
            }
            case "MAX" -> {
                Object max = null;
                for (Row r : rows) {
                    Object val = resolveArg(call.arg(), r);
                    if (val == null) continue;
                    if (max == null || FilterExec.compareValues(val, max) > 0) max = val;
                }
                yield max;
            }
            case "MIN" -> {
                Object min = null;
                for (Row r : rows) {
                    Object val = resolveArg(call.arg(), r);
                    if (val == null) continue;
                    if (min == null || FilterExec.compareValues(val, min) < 0) min = val;
                }
                yield min;
            }
            default -> null;
        };
    }

    private Object resolveArg(SqlNode arg, Row row) {
        if (arg.kind() == SqlKind.STAR) return 1;
        if (arg instanceof SqlIdentifier id) return row.get(id.name());
        return null;
    }
}
