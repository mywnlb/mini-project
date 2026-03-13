package cn.zhangyis.minidb.sql.exec;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 去重执行器：保留输入流中第一次出现的结果行。
 */
public class DistinctExec implements ExecNode {
    private final ExecNode input;

    private Iterator<Row> iterator;

    public DistinctExec(ExecNode input) {
        this.input = input;
    }

    @Override
    public void open() {
        input.open();

        Set<RowKey> seen = new LinkedHashSet<>();
        List<Row> distinctRows = new ArrayList<>();
        Row row;
        while ((row = input.next()) != null) {
            RowKey key = RowKey.from(row);
            if (seen.add(key)) {
                distinctRows.add(row);
            }
        }

        iterator = distinctRows.iterator();
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

    private record RowKey(List<Object> values) {
        private static RowKey from(Row row) {
            return new RowKey(new ArrayList<>(row.columns().values()));
        }
    }
}
