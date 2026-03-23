package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.SqlAggCall;
import cn.zhangyis.minidb.sql.ast.SqlKind;

import java.util.*;
import java.util.concurrent.*;

/**
 * 并行聚合执行器（线程安全修复版）
 *
 * 执行策略：
 * 1. 单线程物化所有输入行（ExecNode 非线程安全）
 * 2. 按 hash(groupKey) 分区到独立 partition
 * 3. 各 partition 并行独立聚合
 * 4. 合并结果
 *
 * 不变式：
 * 1. 结果等价：与串行 AggregateExec 结果一致
 * 2. 线程安全：各线程只访问独立 partition，无共享可变状态
 * 3. 资源回收：close() 清理所有状态
 */
public class ParallelAggregateExec implements ExecNode {

    private final ExecNode input;
    private final List<SqlAggCall> aggregates;
    private final List<String> groupByColumns;
    private final QueryThreadPool threadPool;
    private final int parallelism;

    private Iterator<Row> resultIterator;

    public ParallelAggregateExec(ExecNode input, List<SqlAggCall> aggregates,
                                 List<String> groupByColumns, QueryThreadPool threadPool) {
        this.input = input;
        this.aggregates = aggregates;
        this.groupByColumns = groupByColumns != null ? groupByColumns : List.of();
        this.threadPool = threadPool;
        this.parallelism = threadPool.parallelism();
    }

    @Override
    public void open() {
        input.open();

        // 1. 单线程物化所有行
        List<Row> allRows = new ArrayList<>();
        Row row;
        while ((row = input.next()) != null) {
            allRows.add(row);
        }

        // 2. 按 hash(groupKey) 分区
        List<List<Row>> partitions = new ArrayList<>(parallelism);
        for (int i = 0; i < parallelism; i++) {
            partitions.add(new ArrayList<>());
        }
        for (Row r : allRows) {
            Object groupKey = ExecUtils.extractGroupKey(r, groupByColumns);
            int bucket = Math.abs(groupKey.hashCode() % parallelism);
            partitions.get(bucket).add(r);
        }

        // 3. 并行聚合各分区
        List<Future<Map<Object, AggregateState>>> futures = new ArrayList<>(parallelism);
        for (int i = 0; i < parallelism; i++) {
            final List<Row> partition = partitions.get(i);
            futures.add(threadPool.submit(() -> aggregatePartition(partition)));
        }

        // 4. 收集结果
        Map<Object, AggregateState> mergedStates = new LinkedHashMap<>();
        for (Future<Map<Object, AggregateState>> f : futures) {
            try {
                mergedStates.putAll(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                throw new RuntimeException("Parallel aggregate failed", e.getCause());
            }
        }

        // 5. 生成结果行
        List<Row> results = new ArrayList<>();
        for (Map.Entry<Object, AggregateState> entry : mergedStates.entrySet()) {
            results.add(computeFinalRow(entry.getKey(), entry.getValue()));
        }

        resultIterator = results.iterator();
    }

    private Map<Object, AggregateState> aggregatePartition(List<Row> partition) {
        Map<Object, AggregateState> states = new LinkedHashMap<>();
        for (Row row : partition) {
            Object groupKey = ExecUtils.extractGroupKey(row, groupByColumns);
            AggregateState state = states.computeIfAbsent(groupKey,
                    k -> new AggregateState(aggregates.size()));
            updateState(state, row);
        }
        return states;
    }

    private void updateState(AggregateState state, Row row) {
        for (int i = 0; i < aggregates.size(); i++) {
            SqlAggCall agg = aggregates.get(i);
            Object value;
            if (agg.arg() != null && agg.arg().kind() == SqlKind.STAR) {
                value = 1; // COUNT(*)
            } else if (agg.arg() != null) {
                value = row.get(agg.arg().toString());
            } else {
                value = row.columns().values().iterator().next();
            }
            state.update(i, value, agg.funcName());
        }
    }

    private Row computeFinalRow(Object groupKey, AggregateState state) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 添加 group key 列
        if (!groupByColumns.isEmpty()) {
            if (groupByColumns.size() == 1) {
                result.put(groupByColumns.get(0), groupKey);
            } else if (groupKey instanceof List<?> keys) {
                for (int i = 0; i < groupByColumns.size(); i++) {
                    result.put(groupByColumns.get(i), keys.get(i));
                }
            }
        }

        // 添加聚合列
        for (int i = 0; i < aggregates.size(); i++) {
            SqlAggCall agg = aggregates.get(i);
            String key;
            if (agg.arg().kind() == SqlKind.STAR) {
                key = agg.funcName().toUpperCase() + "(*)";
            } else {
                key = agg.funcName().toUpperCase() + "(" + agg.arg().toString().replaceAll("\\s+", "") + ")";
            }
            result.put(key, state.getFinal(i, agg.funcName()));
        }
        return new Row(result);
    }

    @Override
    public Row next() {
        return resultIterator != null && resultIterator.hasNext() ? resultIterator.next() : null;
    }

    @Override
    public void close() {
        input.close();
        resultIterator = null;
    }

    /** 聚合状态容器（支持 COUNT, SUM, AVG, MIN, MAX） */
    private static class AggregateState {
        private final long[] counts;
        private final double[] sums;
        private final Comparable<?>[] mins;
        private final Comparable<?>[] maxs;

        AggregateState(int aggCount) {
            this.counts = new long[aggCount];
            this.sums = new double[aggCount];
            this.mins = new Comparable[aggCount];
            this.maxs = new Comparable[aggCount];
        }

        @SuppressWarnings("unchecked")
        void update(int idx, Object value, String funcName) {
            if (value == null) return;

            counts[idx]++;
            if (value instanceof Number n) {
                double d = n.doubleValue();
                sums[idx] += d;
                if (mins[idx] == null || d < ((Number) mins[idx]).doubleValue()) mins[idx] = (Comparable<?>) value;
                if (maxs[idx] == null || d > ((Number) maxs[idx]).doubleValue()) maxs[idx] = (Comparable<?>) value;
            } else if (value instanceof Comparable c) {
                if (mins[idx] == null || c.compareTo(mins[idx]) < 0) mins[idx] = c;
                if (maxs[idx] == null || c.compareTo(maxs[idx]) > 0) maxs[idx] = c;
            }
        }

        Object getFinal(int idx, String funcName) {
            return switch (funcName.toUpperCase()) {
                case "COUNT" -> counts[idx];
                case "SUM" -> counts[idx] == 0 ? null : sums[idx];
                case "AVG" -> counts[idx] == 0 ? null : sums[idx] / counts[idx];
                case "MIN" -> mins[idx];
                case "MAX" -> maxs[idx];
                default -> counts[idx] == 0 ? null : sums[idx] / counts[idx];
            };
        }
    }
}
