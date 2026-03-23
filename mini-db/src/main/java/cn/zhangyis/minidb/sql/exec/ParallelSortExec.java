package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * 并行排序执行器（分区排序 + K路流式归并）
 *
 * 修复原版问题：
 * 1. compareRow() 改为使用 ORDER BY 表达式构建 Comparator
 * 2. 先物化所有行再按 hash 分区（避免多线程共享 input.next()）
 * 3. K路归并改为流式 PriorityQueue<IndexedRow>
 */
public class ParallelSortExec implements ExecNode {

    private final ExecNode input;
    private final SqlNodeList orderBy;
    private final QueryThreadPool threadPool;
    private final int parallelism;

    private List<List<Row>> sortedPartitions;
    private int[] partitionCursors;
    private PriorityQueue<IndexedRow> mergeHeap;
    private Comparator<Row> comparator;

    public ParallelSortExec(ExecNode input, SqlNodeList orderBy, QueryThreadPool threadPool) {
        this.input = input;
        this.orderBy = orderBy;
        this.threadPool = threadPool;
        this.parallelism = threadPool.parallelism();
    }

    @Override
    public void open() {
        input.open();
        comparator = buildComparator();

        // 1. 物化所有行
        List<Row> allRows = new ArrayList<>();
        Row row;
        while ((row = input.next()) != null) {
            allRows.add(row);
        }

        // 2. 按 hash 分区
        List<List<Row>> partitions = new ArrayList<>(parallelism);
        for (int i = 0; i < parallelism; i++) {
            partitions.add(new ArrayList<>());
        }
        for (Row r : allRows) {
            int hash = Math.abs(r.hashCode() % parallelism);
            partitions.get(hash).add(r);
        }

        // 3. 并行排序各分区
        CountDownLatch latch = new CountDownLatch(parallelism);
        for (int i = 0; i < parallelism; i++) {
            final List<Row> partition = partitions.get(i);
            threadPool.execute(() -> {
                try {
                    if (comparator != null) {
                        partition.sort(comparator);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 4. 初始化 K路归并堆
        sortedPartitions = partitions;
        partitionCursors = new int[parallelism];
        Comparator<IndexedRow> heapComparator = (a, b) -> {
            if (comparator == null) return 0;
            return comparator.compare(a.row, b.row);
        };
        mergeHeap = new PriorityQueue<>(Math.max(1, parallelism), heapComparator);
        for (int i = 0; i < parallelism; i++) {
            if (!sortedPartitions.get(i).isEmpty()) {
                mergeHeap.offer(new IndexedRow(i, sortedPartitions.get(i).get(0)));
                partitionCursors[i] = 1;
            }
        }
    }

    @Override
    public Row next() {
        if (mergeHeap == null || mergeHeap.isEmpty()) {
            return null;
        }
        IndexedRow top = mergeHeap.poll();
        int idx = top.partitionIndex;
        // 从同一分区补充下一行
        if (partitionCursors[idx] < sortedPartitions.get(idx).size()) {
            Row nextRow = sortedPartitions.get(idx).get(partitionCursors[idx]);
            partitionCursors[idx]++;
            mergeHeap.offer(new IndexedRow(idx, nextRow));
        }
        return top.row;
    }

    @Override
    public void close() {
        input.close();
        if (sortedPartitions != null) {
            sortedPartitions.clear();
        }
        if (mergeHeap != null) {
            mergeHeap.clear();
        }
    }

    private Comparator<Row> buildComparator() {
        if (orderBy == null || orderBy.size() == 0) return null;

        return (a, b) -> {
            for (SqlNode node : orderBy.nodes()) {
                SqlNode expression;
                boolean ascending = true;

                if (node instanceof SqlOrderByItem item) {
                    expression = item.column();
                    ascending = item.ascending();
                } else {
                    expression = node;
                }

                int cmp = FilterExec.compareValues(
                        resolveOrderValue(a, expression),
                        resolveOrderValue(b, expression));
                if (cmp != 0) return ascending ? cmp : -cmp;
            }
            return 0;
        };
    }

    private Object resolveOrderValue(Row row, SqlNode expression) {
        if (expression instanceof SqlIdentifier id) {
            return row.get(id.name());
        }
        Object value = FilterExec.resolveValue(expression, row);
        return value != null ? value : row.get(String.valueOf(expression));
    }

    private record IndexedRow(int partitionIndex, Row row) {}
}
