package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.rel.RelUnion;
import java.util.*;

/**
 * UNION 执行器
 */
public class UnionExec implements ExecNode {
    private final ExecNode left;
    private final ExecNode right;
    private final boolean all;

    private Iterator<Row> iterator;

    public UnionExec(ExecNode left, ExecNode right, boolean all) {
        this.left = left;
        this.right = right;
        this.all = all;
    }

    @Override
    public void open() {
        left.open();
        right.open();

        List<Row> result = new ArrayList<>();
        Row row;
        while ((row = left.next()) != null) {
            result.add(row);
        }
        while ((row = right.next()) != null) {
            result.add(row);
        }

        if (!all) {
            // UNION 去重
            Set<Row> unique = new LinkedHashSet<>(result);
            result = new ArrayList<>(unique);
        }

        iterator = result.iterator();
    }

    @Override
    public Row next() {
        return iterator.hasNext() ? iterator.next() : null;
    }

    @Override
    public void close() {
        left.close();
        right.close();
        iterator = null;
    }
}