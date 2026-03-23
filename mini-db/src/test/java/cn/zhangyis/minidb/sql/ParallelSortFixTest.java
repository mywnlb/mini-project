package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.exec.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证修复后 ParallelSortExec 正确性
 */
class ParallelSortFixTest {

    @Test
    void sortByFirstColumnAsc() {
        List<Row> data = List.of(
                row("id", 3, "name", "c"),
                row("id", 1, "name", "a"),
                row("id", 2, "name", "b")
        );
        ListExecNode input = new ListExecNode(data);

        // 构建 ORDER BY id ASC
        var orderBy = orderByList(orderByItem("id", true));

        ParallelSortExec exec = new ParallelSortExec(input, orderBy, new QueryThreadPool(2));
        List<Row> result = collectRows(exec);
        assertEquals(3, result.size());
        assertEquals(1, result.get(0).get("id"));
        assertEquals(2, result.get(1).get("id"));
        assertEquals(3, result.get(2).get("id"));
    }

    @Test
    void sortByFirstColumnDesc() {
        List<Row> data = List.of(
                row("id", 1, "name", "a"),
                row("id", 3, "name", "c"),
                row("id", 2, "name", "b")
        );
        ListExecNode input = new ListExecNode(data);
        var orderBy = orderByList(orderByItem("id", false));

        ParallelSortExec exec = new ParallelSortExec(input, orderBy, new QueryThreadPool(2));
        List<Row> result = collectRows(exec);
        assertEquals(3, result.size());
        assertEquals(3, result.get(0).get("id"));
        assertEquals(2, result.get(1).get("id"));
        assertEquals(1, result.get(2).get("id"));
    }

    @Test
    void sortMultiColumnMixed() {
        List<Row> data = List.of(
                row("dept", "A", "salary", 300),
                row("dept", "B", "salary", 100),
                row("dept", "A", "salary", 100),
                row("dept", "B", "salary", 300)
        );
        ListExecNode input = new ListExecNode(data);
        // ORDER BY dept ASC, salary DESC
        var orderBy = orderByList(orderByItem("dept", true), orderByItem("salary", false));

        ParallelSortExec exec = new ParallelSortExec(input, orderBy, new QueryThreadPool(3));
        List<Row> result = collectRows(exec);
        assertEquals(4, result.size());
        // A dept first (ASC), within A: 300 before 100 (DESC)
        assertEquals("A", result.get(0).get("dept"));
        assertEquals(300, result.get(0).get("salary"));
        assertEquals("A", result.get(1).get("dept"));
        assertEquals(100, result.get(1).get("salary"));
        assertEquals("B", result.get(2).get("dept"));
        assertEquals(300, result.get(2).get("salary"));
        assertEquals("B", result.get(3).get("dept"));
        assertEquals(100, result.get(3).get("salary"));
    }

    @Test
    void emptyInput() {
        ListExecNode input = new ListExecNode(List.of());
        var orderBy = orderByList(orderByItem("id", true));
        ParallelSortExec exec = new ParallelSortExec(input, orderBy, new QueryThreadPool(2));
        List<Row> result = collectRows(exec);
        assertTrue(result.isEmpty());
    }

    @Test
    void nullValuesHandled() {
        List<Row> data = List.of(
                row("id", null, "name", "x"),
                row("id", 1, "name", "a"),
                row("id", 3, "name", "c")
        );
        ListExecNode input = new ListExecNode(data);
        var orderBy = orderByList(orderByItem("id", true));

        ParallelSortExec exec = new ParallelSortExec(input, orderBy, new QueryThreadPool(2));
        List<Row> result = collectRows(exec);
        assertEquals(3, result.size());
        // 不崩溃即可；NULL 排序位置取决于 compareValues 实现
    }

    // ==================== helpers ====================

    private static Row row(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return new Row(map);
    }

    private static cn.zhangyis.minidb.sql.ast.SqlNodeList orderByList(cn.zhangyis.minidb.sql.ast.SqlNode... items) {
        cn.zhangyis.minidb.sql.ast.SqlNodeList list = new cn.zhangyis.minidb.sql.ast.SqlNodeFactory().nodeList();
        for (var item : items) list.add(item);
        return list;
    }

    private static cn.zhangyis.minidb.sql.ast.SqlOrderByItem orderByItem(String column, boolean asc) {
        return new cn.zhangyis.minidb.sql.ast.SqlOrderByItem(
                new cn.zhangyis.minidb.sql.ast.SqlIdentifier(column), asc);
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

    /**
     * 简单的列表输入执行器
     */
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
