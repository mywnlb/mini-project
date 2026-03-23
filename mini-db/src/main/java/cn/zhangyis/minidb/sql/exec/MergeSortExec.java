package cn.zhangyis.minidb.sql.exec;

import java.util.*;

/**
 * K路归并排序执行器
 *
 * 接收多个已排序的输入，使用 PriorityQueue 做流式归并。
 * 空间复杂度 O(K)，其中 K 为输入数量。
 */
public class MergeSortExec implements ExecNode {

    private final List<ExecNode> sortedInputs;
    private final Comparator<Row> comparator;
    private PriorityQueue<IndexedRow> heap;
    private Row[] currentRows;

    public MergeSortExec(List<ExecNode> sortedInputs, Comparator<Row> comparator) {
        this.sortedInputs = sortedInputs;
        this.comparator = comparator;
    }

    @Override
    public void open() {
        int k = sortedInputs.size();
        currentRows = new Row[k];
        Comparator<IndexedRow> heapCmp = (a, b) -> comparator.compare(a.row, b.row);
        heap = new PriorityQueue<>(Math.max(1, k), heapCmp);

        for (int i = 0; i < k; i++) {
            sortedInputs.get(i).open();
            Row row = sortedInputs.get(i).next();
            if (row != null) {
                currentRows[i] = row;
                heap.offer(new IndexedRow(i, row));
            }
        }
    }

    @Override
    public Row next() {
        if (heap == null || heap.isEmpty()) {
            return null;
        }
        IndexedRow top = heap.poll();
        int idx = top.inputIndex;

        // 从同一输入补充下一行
        Row nextRow = sortedInputs.get(idx).next();
        if (nextRow != null) {
            heap.offer(new IndexedRow(idx, nextRow));
        }

        return top.row;
    }

    @Override
    public void close() {
        for (ExecNode input : sortedInputs) {
            input.close();
        }
        if (heap != null) {
            heap.clear();
        }
    }

    private record IndexedRow(int inputIndex, Row row) {}
}
