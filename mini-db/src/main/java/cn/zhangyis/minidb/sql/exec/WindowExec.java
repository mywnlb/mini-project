package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;

import java.util.*;

/**
 * 窗口函数执行器：支持 ROW_NUMBER / RANK / DENSE_RANK
 *
 * <p>执行策略：
 * 1. 物化所有输入行
 * 2. 按 PARTITION BY 分组
 * 3. 组内按 ORDER BY 排序
 * 4. 计算窗口函数值，添加到行中
 */
public class WindowExec implements ExecNode {
    private final ExecNode input;
    private final List<WindowSpec> windowSpecs;

    private Iterator<Row> iterator;

    public record WindowSpec(String funcName, String outputLabel,
                             SqlNodeList partitionBy, SqlNodeList orderBy) {}

    public WindowExec(ExecNode input, List<WindowSpec> windowSpecs) {
        this.input = input;
        this.windowSpecs = windowSpecs;
    }

    @Override
    public void open() {
        input.open();

        // 物化所有行
        List<Row> rows = new ArrayList<>();
        Row row;
        while ((row = input.next()) != null) rows.add(row);

        // 对每个窗口函数规格计算
        for (WindowSpec spec : windowSpecs) {
            computeWindowFunction(rows, spec);
        }

        iterator = rows.iterator();
    }

    private void computeWindowFunction(List<Row> rows, WindowSpec spec) {
        // 按 partitionBy 分组
        Map<String, List<Integer>> partitions = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            String key = partitionKey(rows.get(i), spec.partitionBy);
            partitions.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }

        // 对每个分区排序并计算
        Comparator<Integer> comp = buildComparator(rows, spec.orderBy);

        for (List<Integer> partition : partitions.values()) {
            if (comp != null) {
                partition.sort(comp);
            }

            String funcName = spec.funcName.toUpperCase();
            switch (funcName) {
                case "ROW_NUMBER" -> computeRowNumber(rows, partition, spec.outputLabel);
                case "RANK" -> computeRank(rows, partition, spec);
                case "DENSE_RANK" -> computeDenseRank(rows, partition, spec);
            }
        }
    }

    private void computeRowNumber(List<Row> rows, List<Integer> partition, String label) {
        for (int rank = 0; rank < partition.size(); rank++) {
            rows.get(partition.get(rank)).put(label, (long)(rank + 1));
        }
    }

    private void computeRank(List<Row> rows, List<Integer> partition, WindowSpec spec) {
        for (int i = 0; i < partition.size(); i++) {
            int idx = partition.get(i);
            if (i == 0) {
                rows.get(idx).put(spec.outputLabel, 1L);
            } else {
                int prevIdx = partition.get(i - 1);
                if (orderByEquals(rows.get(idx), rows.get(prevIdx), spec.orderBy)) {
                    rows.get(idx).put(spec.outputLabel, rows.get(prevIdx).get(spec.outputLabel));
                } else {
                    rows.get(idx).put(spec.outputLabel, (long)(i + 1));
                }
            }
        }
    }

    private void computeDenseRank(List<Row> rows, List<Integer> partition, WindowSpec spec) {
        long denseRank = 0;
        for (int i = 0; i < partition.size(); i++) {
            int idx = partition.get(i);
            if (i == 0) {
                denseRank = 1;
            } else {
                int prevIdx = partition.get(i - 1);
                if (!orderByEquals(rows.get(idx), rows.get(prevIdx), spec.orderBy)) {
                    denseRank++;
                }
            }
            rows.get(idx).put(spec.outputLabel, denseRank);
        }
    }

    private boolean orderByEquals(Row a, Row b, SqlNodeList orderBy) {
        if (orderBy == null || orderBy.size() == 0) return true;
        for (SqlNode node : orderBy.nodes()) {
            SqlNode expr = node instanceof SqlOrderByItem item ? item.column() : node;
            Object va = resolveValue(a, expr);
            Object vb = resolveValue(b, expr);
            if (FilterExec.compareValues(va, vb) != 0) return false;
        }
        return true;
    }

    private String partitionKey(Row row, SqlNodeList partitionBy) {
        if (partitionBy == null || partitionBy.size() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (SqlNode node : partitionBy.nodes()) {
            Object val = resolveValue(row, node);
            sb.append(val == null ? "\0" : val.toString()).append('\1');
        }
        return sb.toString();
    }

    private Comparator<Integer> buildComparator(List<Row> rows, SqlNodeList orderBy) {
        if (orderBy == null || orderBy.size() == 0) return null;
        return (a, b) -> {
            for (SqlNode node : orderBy.nodes()) {
                SqlNode expr;
                boolean ascending = true;
                if (node instanceof SqlOrderByItem item) {
                    expr = item.column();
                    ascending = item.ascending();
                } else {
                    expr = node;
                }
                int cmp = FilterExec.compareValues(resolveValue(rows.get(a), expr), resolveValue(rows.get(b), expr));
                if (cmp != 0) return ascending ? cmp : -cmp;
            }
            return 0;
        };
    }

    private Object resolveValue(Row row, SqlNode expr) {
        if (expr instanceof SqlIdentifier id) return row.get(id.name());
        return FilterExec.resolveValue(expr, row);
    }

    @Override
    public Row next() {
        return iterator != null && iterator.hasNext() ? iterator.next() : null;
    }

    @Override
    public void close() {
        input.close();
        iterator = null;
    }
}
