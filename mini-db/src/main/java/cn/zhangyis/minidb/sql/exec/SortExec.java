package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.*;

import java.util.*;

/**
 * 排序执行器：物化 + 排序 + 可选 LIMIT
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

        // 物化所有行
        List<Row> rows = new ArrayList<>();
        Row row;
        while ((row = input.next()) != null) {
            rows.add(row);
        }

        // 排序
        if (orderBy != null && orderBy.size() > 0) {
            rows.sort((a, b) -> {
                for (SqlNode node : orderBy.nodes()) {
                    if (node instanceof SqlIdentifier id) {
                        int cmp = FilterExec.compareValues(a.get(id.name()), b.get(id.name()));
                        if (cmp != 0) return cmp;
                    }
                }
                return 0;
            });
        }

        // LIMIT
        if (limit != null && limit < rows.size()) {
            rows = rows.subList(0, limit);
        }

        iterator = rows.iterator();
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
