package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;

import java.util.*;

/**
 * 排序执行器：支持两种策略
 * 1. MemSort：全量物化 + 排序（通用）
 * 2. TopN：ORDER BY + LIMIT 时用 PriorityQueue 维护堆，O(N*logK) 代替 O(N*logN)
 */
public class SortExec implements ExecNode {
    private final ExecNode input;
    private final SqlNodeList orderBy;
    private final Integer limit;

    private Iterator<Row> iterator;

    public SortExec(ExecNode input, SqlNodeList orderBy, Integer limit) {
        this.input = input;
        this.orderBy = orderBy;
        this.limit = limit;
    }

    @Override
    public void open() {
        input.open();

        Comparator<Row> comparator = buildComparator();

        List<Row> result;
        if (limit != null && limit > 0 && comparator != null) {
            // TopN 优化：用最大堆维护 top-K 最小元素
            result = topN(comparator);
        } else {
            // MemSort：全量物化 + 排序
            result = memSort(comparator);
        }

        iterator = result.iterator();
    }

    /**
     * TopN：维护大小为 K 的最大堆
     * 遍历所有行，堆满后只替换堆顶（最大元素），最终堆中就是最小的 K 个
     * 时间 O(N*logK)，空间 O(K)
     */
    private List<Row> topN(Comparator<Row> comparator) {
        // 最大堆：堆顶是当前 top-K 中最大的
        PriorityQueue<Row> heap = new PriorityQueue<>(limit + 1, comparator.reversed());

        Row row;
        while ((row = input.next()) != null) {
            heap.offer(row);
            if (heap.size() > limit) {
                heap.poll(); // 弹出最大的，保留最小的 K 个
            }
        }

        // 堆中元素按正序排列输出
        List<Row> result = new ArrayList<>(heap);
        result.sort(comparator);
        return result;
    }

    /**
     * MemSort：全量物化 + 排序 + 可选 LIMIT 截断
     */
    private List<Row> memSort(Comparator<Row> comparator) {
        List<Row> rows = new ArrayList<>();
        Row row;
        while ((row = input.next()) != null) {
            rows.add(row);
        }

        if (comparator != null) {
            rows.sort(comparator);
        }

        if (limit != null && limit < rows.size()) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }

        return rows;
    }

    private Comparator<Row> buildComparator() {
        if (orderBy == null || orderBy.size() == 0) return null;

        return (a, b) -> {
            for (SqlNode node : orderBy.nodes()) {
                String colName;
                boolean ascending = true;

                if (node instanceof SqlOrderByItem item) {
                    colName = ((SqlIdentifier) item.column()).name();
                    ascending = item.ascending();
                } else if (node instanceof SqlIdentifier id) {
                    colName = id.name();
                } else {
                    continue;
                }

                int cmp = FilterExec.compareValues(a.get(colName), b.get(colName));
                if (cmp != 0) return ascending ? cmp : -cmp;
            }
            return 0;
        };
    }

    @Override
    public Row next() {
        return iterator.hasNext() ? iterator.next() : null;
    }

    @Override
    public void close() {
        input.close();
        iterator = null;
    }
}
