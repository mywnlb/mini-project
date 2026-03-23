package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.exec.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * K路归并执行器测试
 */
class MergeSortTest {

    @Test
    void twoWayMerge() {
        List<Row> left = List.of(row("id", 1), row("id", 3), row("id", 5));
        List<Row> right = List.of(row("id", 2), row("id", 4), row("id", 6));

        MergeSortExec exec = new MergeSortExec(
                List.of(new ListExecNode(left), new ListExecNode(right)),
                Comparator.comparingInt(r -> (int) r.get("id"))
        );

        List<Row> result = collectRows(exec);
        assertEquals(6, result.size());
        for (int i = 0; i < 6; i++) {
            assertEquals(i + 1, result.get(i).get("id"));
        }
    }

    @Test
    void fourWayMerge() {
        List<List<Row>> inputs = List.of(
                List.of(row("id", 1), row("id", 5), row("id", 9)),
                List.of(row("id", 2), row("id", 6), row("id", 10)),
                List.of(row("id", 3), row("id", 7), row("id", 11)),
                List.of(row("id", 4), row("id", 8), row("id", 12))
        );
        List<ExecNode> execs = new ArrayList<>();
        for (var input : inputs) execs.add(new ListExecNode(input));

        MergeSortExec exec = new MergeSortExec(
                execs,
                Comparator.comparingInt(r -> (int) r.get("id"))
        );

        List<Row> result = collectRows(exec);
        assertEquals(12, result.size());
        for (int i = 0; i < 12; i++) {
            assertEquals(i + 1, result.get(i).get("id"));
        }
    }

    @Test
    void eightWayMerge() {
        List<ExecNode> execs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            List<Row> partition = new ArrayList<>();
            for (int j = 0; j < 5; j++) {
                partition.add(row("id", i * 5 + j));
            }
            // 已排序
            execs.add(new ListExecNode(partition));
        }

        MergeSortExec exec = new MergeSortExec(
                execs,
                Comparator.comparingInt(r -> (int) r.get("id"))
        );

        List<Row> result = collectRows(exec);
        assertEquals(40, result.size());
        for (int i = 0; i < 40; i++) {
            assertEquals(i, result.get(i).get("id"));
        }
    }

    @Test
    void emptyInputs() {
        MergeSortExec exec = new MergeSortExec(
                List.of(new ListExecNode(List.of()), new ListExecNode(List.of())),
                Comparator.comparingInt(r -> (int) r.get("id"))
        );
        List<Row> result = collectRows(exec);
        assertTrue(result.isEmpty());
    }

    @Test
    void oneEmptyOneNonEmpty() {
        List<Row> data = List.of(row("id", 1), row("id", 2));
        MergeSortExec exec = new MergeSortExec(
                List.of(new ListExecNode(List.of()), new ListExecNode(data)),
                Comparator.comparingInt(r -> (int) r.get("id"))
        );
        List<Row> result = collectRows(exec);
        assertEquals(2, result.size());
        assertEquals(1, result.get(0).get("id"));
        assertEquals(2, result.get(1).get("id"));
    }

    @Test
    void descOrder() {
        List<Row> left = List.of(row("id", 6), row("id", 4), row("id", 2));
        List<Row> right = List.of(row("id", 5), row("id", 3), row("id", 1));

        MergeSortExec exec = new MergeSortExec(
                List.of(new ListExecNode(left), new ListExecNode(right)),
                Comparator.comparingInt((Row r) -> (int) r.get("id")).reversed()
        );

        List<Row> result = collectRows(exec);
        assertEquals(6, result.size());
        for (int i = 0; i < 6; i++) {
            assertEquals(6 - i, result.get(i).get("id"));
        }
    }

    // ==================== helpers ====================

    private static Row row(String k, Object v) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k, v);
        return new Row(map);
    }

    private static List<Row> collectRows(ExecNode exec) {
        List<Row> rows = new ArrayList<>();
        exec.open();
        try {
            Row row;
            while ((row = exec.next()) != null) rows.add(row);
        } finally {
            exec.close();
        }
        return rows;
    }

    static class ListExecNode implements ExecNode {
        private final List<Row> rows;
        private Iterator<Row> iter;

        ListExecNode(List<Row> rows) {
            this.rows = rows;
        }

        @Override
        public void open() {
            iter = rows.iterator();
        }

        @Override
        public Row next() {
            return iter.hasNext() ? iter.next() : null;
        }

        @Override
        public void close() {
            iter = null;
        }
    }
}
