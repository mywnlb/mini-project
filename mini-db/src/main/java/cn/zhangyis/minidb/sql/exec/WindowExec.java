package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;

import java.util.*;

/**
 * 窗口函数执行器：支持排名函数和聚合窗口函数
 *
 * <p>排名函数: ROW_NUMBER / RANK / DENSE_RANK
 * <p>聚合窗口: SUM / COUNT / AVG / MIN / MAX
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
                             SqlNodeList partitionBy, SqlNodeList orderBy,
                             SqlNode arg, SqlNodeList extraArgs) {
        /** 兼容旧构造：排名函数无 arg 和 extraArgs */
        public WindowSpec(String funcName, String outputLabel,
                          SqlNodeList partitionBy, SqlNodeList orderBy) {
            this(funcName, outputLabel, partitionBy, orderBy, null, null);
        }
        /** 兼容构造：有 arg 无 extraArgs */
        public WindowSpec(String funcName, String outputLabel,
                          SqlNodeList partitionBy, SqlNodeList orderBy, SqlNode arg) {
            this(funcName, outputLabel, partitionBy, orderBy, arg, null);
        }
    }

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

        // 记录排序后的行顺序
        List<Integer> sortedOrder = new ArrayList<>();

        for (List<Integer> partition : partitions.values()) {
            if (comp != null) {
                partition.sort(comp);
            }

            String funcName = spec.funcName.toUpperCase();
            switch (funcName) {
                case "ROW_NUMBER" -> computeRowNumber(rows, partition, spec.outputLabel);
                case "RANK" -> computeRank(rows, partition, spec);
                case "DENSE_RANK" -> computeDenseRank(rows, partition, spec);
                case "SUM" -> computeRunningSum(rows, partition, spec);
                case "COUNT" -> computeRunningCount(rows, partition, spec);
                case "AVG" -> computeRunningAvg(rows, partition, spec);
                case "MIN" -> computeRunningMin(rows, partition, spec);
                case "MAX" -> computeRunningMax(rows, partition, spec);
                case "LAG" -> computeLag(rows, partition, spec);
                case "LEAD" -> computeLead(rows, partition, spec);
                case "NTILE" -> computeNtile(rows, partition, spec);
                case "PERCENT_RANK" -> computePercentRank(rows, partition, spec);
                case "CUME_DIST" -> computeCumeDist(rows, partition, spec);
            }

            sortedOrder.addAll(partition);
        }

        // 按分区+排序顺序重排行
        List<Row> reordered = new ArrayList<>(rows.size());
        for (int idx : sortedOrder) {
            reordered.add(rows.get(idx));
        }
        rows.clear();
        rows.addAll(reordered);
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

    // ==================== 聚合窗口函数 ====================

    private void computeRunningSum(List<Row> rows, List<Integer> partition, WindowSpec spec) {
        boolean hasOrderBy = spec.orderBy != null && spec.orderBy.size() > 0;
        if (!hasOrderBy) {
            // 无 ORDER BY：整个分区的聚合值
            double total = 0;
            for (int idx : partition) {
                Object val = resolveArgValue(rows.get(idx), spec.arg);
                if (val instanceof Number n) total += n.doubleValue();
            }
            Object result = toNumericResult(total);
            for (int idx : partition) rows.get(idx).put(spec.outputLabel, result);
        } else {
            // 有 ORDER BY：running sum
            double running = 0;
            for (int idx : partition) {
                Object val = resolveArgValue(rows.get(idx), spec.arg);
                if (val instanceof Number n) running += n.doubleValue();
                rows.get(idx).put(spec.outputLabel, toNumericResult(running));
            }
        }
    }

    private void computeRunningCount(List<Row> rows, List<Integer> partition, WindowSpec spec) {
        boolean hasOrderBy = spec.orderBy != null && spec.orderBy.size() > 0;
        boolean isCountStar = spec.arg != null && spec.arg.kind() == SqlKind.STAR;
        if (!hasOrderBy) {
            long total = 0;
            for (int idx : partition) {
                if (isCountStar) {
                    total++;
                } else {
                    Object val = resolveArgValue(rows.get(idx), spec.arg);
                    if (val != null) total++;
                }
            }
            for (int idx : partition) rows.get(idx).put(spec.outputLabel, total);
        } else {
            long running = 0;
            for (int idx : partition) {
                if (isCountStar) {
                    running++;
                } else {
                    Object val = resolveArgValue(rows.get(idx), spec.arg);
                    if (val != null) running++;
                }
                rows.get(idx).put(spec.outputLabel, running);
            }
        }
    }

    private void computeRunningAvg(List<Row> rows, List<Integer> partition, WindowSpec spec) {
        boolean hasOrderBy = spec.orderBy != null && spec.orderBy.size() > 0;
        if (!hasOrderBy) {
            double total = 0;
            int count = 0;
            for (int idx : partition) {
                Object val = resolveArgValue(rows.get(idx), spec.arg);
                if (val instanceof Number n) { total += n.doubleValue(); count++; }
            }
            double avg = count > 0 ? total / count : 0;
            for (int idx : partition) rows.get(idx).put(spec.outputLabel, avg);
        } else {
            double running = 0;
            int count = 0;
            for (int idx : partition) {
                Object val = resolveArgValue(rows.get(idx), spec.arg);
                if (val instanceof Number n) { running += n.doubleValue(); count++; }
                double avg = count > 0 ? running / count : 0;
                rows.get(idx).put(spec.outputLabel, avg);
            }
        }
    }

    private void computeRunningMin(List<Row> rows, List<Integer> partition, WindowSpec spec) {
        boolean hasOrderBy = spec.orderBy != null && spec.orderBy.size() > 0;
        if (!hasOrderBy) {
            Object min = null;
            for (int idx : partition) {
                Object val = resolveArgValue(rows.get(idx), spec.arg);
                if (val != null && (min == null || FilterExec.compareValues(val, min) < 0)) min = val;
            }
            for (int idx : partition) rows.get(idx).put(spec.outputLabel, min);
        } else {
            Object min = null;
            for (int idx : partition) {
                Object val = resolveArgValue(rows.get(idx), spec.arg);
                if (val != null && (min == null || FilterExec.compareValues(val, min) < 0)) min = val;
                rows.get(idx).put(spec.outputLabel, min);
            }
        }
    }

    private void computeRunningMax(List<Row> rows, List<Integer> partition, WindowSpec spec) {
        boolean hasOrderBy = spec.orderBy != null && spec.orderBy.size() > 0;
        if (!hasOrderBy) {
            Object max = null;
            for (int idx : partition) {
                Object val = resolveArgValue(rows.get(idx), spec.arg);
                if (val != null && (max == null || FilterExec.compareValues(val, max) > 0)) max = val;
            }
            for (int idx : partition) rows.get(idx).put(spec.outputLabel, max);
        } else {
            Object max = null;
            for (int idx : partition) {
                Object val = resolveArgValue(rows.get(idx), spec.arg);
                if (val != null && (max == null || FilterExec.compareValues(val, max) > 0)) max = val;
                rows.get(idx).put(spec.outputLabel, max);
            }
        }
    }

    private Object resolveArgValue(Row row, SqlNode arg) {
        if (arg == null || arg.kind() == SqlKind.STAR) return null;
        return resolveValue(row, arg);
    }

    private Object toNumericResult(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            long lv = (long) value;
            if (lv >= Integer.MIN_VALUE && lv <= Integer.MAX_VALUE) return (int) lv;
            return lv;
        }
        return value;
    }

    // ==================== LAG/LEAD/NTILE/PERCENT_RANK/CUME_DIST ====================

    private void computeLag(List<Row> rows, List<Integer> partition, WindowSpec spec) {
        int offset = 1;
        Object defaultVal = null;
        if (spec.extraArgs() != null) {
            if (spec.extraArgs().size() >= 2) {
                Object offVal = resolveValue(rows.get(partition.get(0)), spec.extraArgs().get(1));
                if (offVal instanceof Number n) offset = n.intValue();
            }
            if (spec.extraArgs().size() >= 3) {
                defaultVal = resolveValue(rows.get(partition.get(0)), spec.extraArgs().get(2));
            }
        }
        for (int i = 0; i < partition.size(); i++) {
            int idx = partition.get(i);
            if (i - offset >= 0) {
                int lagIdx = partition.get(i - offset);
                rows.get(idx).put(spec.outputLabel(), resolveArgValue(rows.get(lagIdx), spec.arg()));
            } else {
                rows.get(idx).put(spec.outputLabel(), defaultVal);
            }
        }
    }

    private void computeLead(List<Row> rows, List<Integer> partition, WindowSpec spec) {
        int offset = 1;
        Object defaultVal = null;
        if (spec.extraArgs() != null) {
            if (spec.extraArgs().size() >= 2) {
                Object offVal = resolveValue(rows.get(partition.get(0)), spec.extraArgs().get(1));
                if (offVal instanceof Number n) offset = n.intValue();
            }
            if (spec.extraArgs().size() >= 3) {
                defaultVal = resolveValue(rows.get(partition.get(0)), spec.extraArgs().get(2));
            }
        }
        for (int i = 0; i < partition.size(); i++) {
            int idx = partition.get(i);
            if (i + offset < partition.size()) {
                int leadIdx = partition.get(i + offset);
                rows.get(idx).put(spec.outputLabel(), resolveArgValue(rows.get(leadIdx), spec.arg()));
            } else {
                rows.get(idx).put(spec.outputLabel(), defaultVal);
            }
        }
    }

    private void computeNtile(List<Row> rows, List<Integer> partition, WindowSpec spec) {
        int n = 1;
        if (spec.arg() != null) {
            Object nVal = resolveValue(rows.get(partition.get(0)), spec.arg());
            if (nVal instanceof Number num) n = num.intValue();
        }
        if (n <= 0) n = 1;
        int size = partition.size();
        int baseSize = size / n;
        int remainder = size % n;
        int bucket = 1;
        int count = 0;
        int currentBucketSize = baseSize + (bucket <= remainder ? 1 : 0);
        for (int i = 0; i < size; i++) {
            rows.get(partition.get(i)).put(spec.outputLabel(), (long) bucket);
            count++;
            if (count >= currentBucketSize && bucket < n) {
                bucket++;
                count = 0;
                currentBucketSize = baseSize + (bucket <= remainder ? 1 : 0);
            }
        }
    }

    private void computePercentRank(List<Row> rows, List<Integer> partition, WindowSpec spec) {
        int size = partition.size();
        if (size <= 1) {
            for (int idx : partition) {
                rows.get(idx).put(spec.outputLabel(), 0.0);
            }
            return;
        }
        // 先计算 RANK
        long[] ranks = new long[size];
        ranks[0] = 1;
        for (int i = 1; i < size; i++) {
            if (orderByEquals(rows.get(partition.get(i)), rows.get(partition.get(i - 1)), spec.orderBy())) {
                ranks[i] = ranks[i - 1];
            } else {
                ranks[i] = i + 1;
            }
        }
        for (int i = 0; i < size; i++) {
            double percentRank = (double) (ranks[i] - 1) / (size - 1);
            rows.get(partition.get(i)).put(spec.outputLabel(), percentRank);
        }
    }

    private void computeCumeDist(List<Row> rows, List<Integer> partition, WindowSpec spec) {
        int size = partition.size();
        for (int i = 0; i < size; i++) {
            // CUME_DIST = (行的位置，即等于或小于当前值的行数) / 分区总行数
            // 找到最后一个与当前行 ORDER BY 值相同的位置
            int lastEqual = i;
            while (lastEqual + 1 < size &&
                    orderByEquals(rows.get(partition.get(i)), rows.get(partition.get(lastEqual + 1)), spec.orderBy())) {
                lastEqual++;
            }
            double cumeDist = (double) (lastEqual + 1) / size;
            rows.get(partition.get(i)).put(spec.outputLabel(), cumeDist);
        }
    }

    // ==================== 通用辅助方法 ====================

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
