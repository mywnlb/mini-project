package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.MockCatalog;
import cn.zhangyis.minidb.sql.exec.*;
import cn.zhangyis.minidb.sql.lexer.SqlLexer;
import cn.zhangyis.minidb.sql.lexer.TokenStream;
import cn.zhangyis.minidb.sql.optimize.RuleOptimizer;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.RelNode;
import cn.zhangyis.minidb.sql.validation.SqlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScalarFunctionTest {
    private final MockCatalog catalog = new MockCatalog();

    @BeforeEach
    void resetData() {
        MockDataSource.reset();
    }

    private SqlNode parse(String sql) {
        return new SqlParser(new TokenStream(new SqlLexer(sql))).parseStatement();
    }

    private RelNode buildPlan(String sql) {
        SqlNode ast = parse(sql);
        SqlNode validated = new SqlValidator(catalog).validate(ast);
        RelNode logical = new SqlToRelConverter(catalog).convert(validated);
        return new RuleOptimizer(false, catalog).optimize(logical);
    }

    private List<Row> execute(String sql) {
        ExecNode exec = new PhysicalPlanner().plan(buildPlan(sql));
        return collectRows(exec);
    }

    private List<Row> collectRows(ExecNode exec) {
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

    private Object singleValue(List<Row> rows) {
        assertEquals(1, rows.size());
        return rows.get(0).columns().values().iterator().next();
    }

    // ==================== 字符串函数 ====================

    @Nested
    class StringFunctions {

        @Test
        void concat() {
            List<Row> rows = execute("SELECT CONCAT(name, '-suffix') FROM users WHERE id = 1");
            assertEquals("alice-suffix", singleValue(rows));
        }

        @Test
        void concatMultipleArgs() {
            List<Row> rows = execute("SELECT CONCAT(name, '-', name) FROM users WHERE id = 1");
            assertEquals("alice-alice", singleValue(rows));
        }

        @Test
        void substring() {
            List<Row> rows = execute("SELECT SUBSTRING(name, 1, 3) FROM users WHERE id = 1");
            assertEquals("ali", singleValue(rows));
        }

        @Test
        void substringNoLength() {
            List<Row> rows = execute("SELECT SUBSTRING(name, 2) FROM users WHERE id = 1");
            assertEquals("lice", singleValue(rows));
        }

        @Test
        void trim() {
            // TRIM on a normal string (no leading/trailing spaces in mock data)
            List<Row> rows = execute("SELECT TRIM(name) FROM users WHERE id = 1");
            assertEquals("alice", singleValue(rows));
        }

        @Test
        void length() {
            List<Row> rows = execute("SELECT LENGTH(name) FROM users WHERE id = 1");
            assertEquals(5, singleValue(rows));
        }

        @Test
        void replace() {
            List<Row> rows = execute("SELECT REPLACE(name, 'li', 'LI') FROM users WHERE id = 1");
            assertEquals("aLIce", singleValue(rows));
        }
    }

    // ==================== 数学函数 ====================

    @Nested
    class MathFunctions {

        @Test
        void abs() {
            List<Row> rows = execute("SELECT ABS(amount) FROM orders WHERE order_id = 101");
            Object val = singleValue(rows);
            assertTrue(val instanceof Number);
            assertEquals(250, ((Number) val).intValue());
        }

        @Test
        void ceil() {
            // amount is integer 250, ceil(250) = 250
            List<Row> rows = execute("SELECT CEIL(amount) FROM orders WHERE order_id = 101");
            Object val = singleValue(rows);
            assertEquals(250L, ((Number) val).longValue());
        }

        @Test
        void floor() {
            List<Row> rows = execute("SELECT FLOOR(amount) FROM orders WHERE order_id = 102");
            Object val = singleValue(rows);
            assertEquals(130L, ((Number) val).longValue());
        }

        @Test
        void round() {
            // ROUND(amount, 0) on integer value
            List<Row> rows = execute("SELECT ROUND(amount) FROM orders WHERE order_id = 101");
            Object val = singleValue(rows);
            assertEquals(250L, ((Number) val).longValue());
        }

        @Test
        void mod() {
            // MOD(250, 3) = 1
            List<Row> rows = execute("SELECT MOD(amount, 3) FROM orders WHERE order_id = 101");
            Object val = singleValue(rows);
            assertEquals(1, ((Number) val).intValue());
        }

        @Test
        void modZeroDivisor() {
            List<Row> rows = execute("SELECT MOD(amount, 0) FROM orders WHERE order_id = 101");
            assertNull(singleValue(rows));
        }
    }

    // ==================== 日期函数 ====================

    @Nested
    class DateFunctions {

        @Test
        void now() {
            List<Row> rows = execute("SELECT NOW() FROM users WHERE id = 1");
            Object val = singleValue(rows);
            assertNotNull(val);
            // 格式: yyyy-MM-dd HH:mm:ss
            String s = val.toString();
            assertTrue(s.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), "NOW() format: " + s);
        }

        @Test
        void dateDiff() {
            List<Row> rows = execute("SELECT DATEDIFF('2025-03-15', '2025-03-10') FROM users WHERE id = 1");
            assertEquals(5, singleValue(rows));
        }

        @Test
        void dateFormat() {
            List<Row> rows = execute("SELECT DATE_FORMAT('2025-03-15', '%Y/%m/%d') FROM users WHERE id = 1");
            assertEquals("2025/03/15", singleValue(rows));
        }
    }

    // ==================== 函数嵌套 ====================

    @Nested
    class FunctionNesting {

        @Test
        void upperConcat() {
            List<Row> rows = execute("SELECT UPPER(CONCAT(name, '_test')) FROM users WHERE id = 1");
            assertEquals("ALICE_TEST", singleValue(rows));
        }

        @Test
        void lengthOfUpper() {
            List<Row> rows = execute("SELECT LENGTH(UPPER(name)) FROM users WHERE id = 1");
            assertEquals(5, singleValue(rows));
        }
    }

    // ==================== WHERE 中使用函数 ====================

    @Nested
    class FunctionInWhere {

        @Test
        void filterByLength() {
            List<Row> rows = execute("SELECT name FROM users WHERE LENGTH(name) > 4");
            assertTrue(rows.size() >= 2); // alice(5), charlie(7)
        }

        @Test
        void filterByUpper() {
            List<Row> rows = execute("SELECT name FROM users WHERE UPPER(name) = 'ALICE'");
            assertEquals(2, rows.size()); // two alice rows
        }
    }
}
