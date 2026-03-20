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
    private final Integer offset;

    private Iterator<Row> iterator;

    public SortExec(ExecNode input, SqlNodeList orderBy, Integer limit, Integer offset) {
        this.input = input;
        this.orderBy = orderBy;
        this.limit = limit;
        this.offset = offset != null && offset > 0 ? offset : 0;
    }

    public SortExec(ExecNode input, SqlNodeList orderBy, Integer limit) {
        this(input, orderBy, limit, 0);
    }

    @Override
    public void open() {
        input.open();

        Comparator<Row> comparator = buildComparator();

        List<Row> result;
        if (comparator == null && limit != null && limit > 0) {
            // 无 ORDER BY + 有 LIMIT：只读 offset+limit 行
            result = readLimited();
        } else if (limit != null && limit > 0 && comparator != null) {
            // TopN 优化：用最大堆维护 top-K 最小元素（支持 OFFSET）
            result = topNWithOffset(comparator);
        } else {
            // MemSort：全量物化 + 排序（支持 OFFSET + LIMIT）
            result = memSort(comparator);
        }

        iterator = result.iterator();
    }

    /**
     * 无 ORDER BY 短路：只读 offset+limit 行，不做排序
     */
    private List<Row> readLimited() {
        int skip = offset != null ? offset : 0;
        int toRead = skip + limit;
        List<Row> rows = new ArrayList<>(toRead);
        Row row;
        while (rows.size() < toRead && (row = input.next()) != null) {
            rows.add(row);
        }
        if (skip > 0 && skip < rows.size()) {
            return new ArrayList<>(rows.subList(skip, rows.size()));
        } else if (skip >= rows.size()) {
            return new ArrayList<>();
        }
        return rows;
    }

    /**
     * TopN + OFFSET：维护大小为 K+offset 的最大堆
     * 遍历所有行，堆满后只替换堆顶（最大元素），最终堆中就是最小的 K+offset 个
     * 排序后跳过前 offset 行
     */
    private List<Row> topNWithOffset(Comparator<Row> comparator) {
        int heapSize = limit + (offset != null ? offset : 0);
        PriorityQueue<Row> heap = new PriorityQueue<>(heapSize + 1, comparator.reversed());

        Row row;
        while ((row = input.next()) != null) {
            heap.offer(row);
            if (heap.size() > heapSize) {
                heap.poll();
            }
        }

        List<Row> result = new ArrayList<>(heap);
        result.sort(comparator);

        int skip = offset != null ? offset : 0;
        if (skip > 0 && skip < result.size()) {
            return new ArrayList<>(result.subList(skip, result.size()));
        } else if (skip >= result.size()) {
            return new ArrayList<>();
        }
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

        // 应用 OFFSET 和 LIMIT
        int start = offset != null ? offset : 0;
        int end = rows.size();
        if (limit != null && limit > 0) {
            end = Math.min(start + limit, rows.size());
        }
        if (start > 0 || end < rows.size()) {
            end = Math.min(end, rows.size());
            if (start < end) {
                rows = new ArrayList<>(rows.subList(start, end));
            } else {
                rows = new ArrayList<>();
            }
        }

        return rows;
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

                int cmp = FilterExec.compareValues(resolveOrderValue(a, expression), resolveOrderValue(b, expression));
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
