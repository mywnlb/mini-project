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

class WindowFunctionEnhancementTest {
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

    // ==================== LAG ====================

    @Nested
    class LagTests {

        @Test
        void lagDefault() {
            // LAG(amount) — offset 默认为1
            List<Row> rows = execute(
                    "SELECT order_id, amount, LAG(amount) OVER (ORDER BY order_id) AS prev_amt FROM orders");
            assertEquals(5, rows.size());
            // 第一行的 prev_amt 应为 null（无前一行）
            assertNull(rows.get(0).get("prev_amt"));
            // 第二行的 prev_amt 应为第一行的 amount
            assertNotNull(rows.get(1).get("prev_amt"));
        }

        @Test
        void lagWithOffset() {
            List<Row> rows = execute(
                    "SELECT order_id, LAG(amount, 2) OVER (ORDER BY order_id) AS prev2 FROM orders");
            assertEquals(5, rows.size());
            assertNull(rows.get(0).get("prev2"));
            assertNull(rows.get(1).get("prev2"));
            assertNotNull(rows.get(2).get("prev2"));
        }

        @Test
        void lagWithDefault() {
            List<Row> rows = execute(
                    "SELECT order_id, LAG(amount, 1, 0) OVER (ORDER BY order_id) AS prev_amt FROM orders");
            assertEquals(5, rows.size());
            // 第一行应返回默认值 0
            assertEquals(0, ((Number) rows.get(0).get("prev_amt")).intValue());
        }
    }

    // ==================== LEAD ====================

    @Nested
    class LeadTests {

        @Test
        void leadDefault() {
            List<Row> rows = execute(
                    "SELECT order_id, amount, LEAD(amount) OVER (ORDER BY order_id) AS next_amt FROM orders");
            assertEquals(5, rows.size());
            // 最后一行的 next_amt 应为 null
            assertNull(rows.get(rows.size() - 1).get("next_amt"));
            // 第一行的 next_amt 应有值
            assertNotNull(rows.get(0).get("next_amt"));
        }

        @Test
        void leadWithOffset() {
            List<Row> rows = execute(
                    "SELECT order_id, LEAD(amount, 2) OVER (ORDER BY order_id) AS next2 FROM orders");
            assertEquals(5, rows.size());
            assertNull(rows.get(3).get("next2"));
            assertNull(rows.get(4).get("next2"));
            assertNotNull(rows.get(0).get("next2"));
        }
    }

    // ==================== NTILE ====================

    @Nested
    class NtileTests {

        @Test
        void ntileBasic() {
            List<Row> rows = execute(
                    "SELECT order_id, NTILE(3) OVER (ORDER BY order_id) AS bucket FROM orders");
            assertEquals(5, rows.size());
            // 5 rows / 3 buckets: bucket 1 has 2 rows, bucket 2 has 2, bucket 3 has 1
            long bucket1Count = rows.stream().filter(r -> ((Number) r.get("bucket")).longValue() == 1).count();
            long bucket2Count = rows.stream().filter(r -> ((Number) r.get("bucket")).longValue() == 2).count();
            long bucket3Count = rows.stream().filter(r -> ((Number) r.get("bucket")).longValue() == 3).count();
            assertEquals(2, bucket1Count);
            assertEquals(2, bucket2Count);
            assertEquals(1, bucket3Count);
        }

        @Test
        void ntileSingleBucket() {
            List<Row> rows = execute(
                    "SELECT order_id, NTILE(1) OVER (ORDER BY order_id) AS bucket FROM orders");
            for (Row row : rows) {
                assertEquals(1L, row.get("bucket"));
            }
        }
    }

    // ==================== PERCENT_RANK ====================

    @Nested
    class PercentRankTests {

        @Test
        void percentRankBasic() {
            List<Row> rows = execute(
                    "SELECT order_id, PERCENT_RANK() OVER (ORDER BY order_id) AS prank FROM orders");
            assertEquals(5, rows.size());
            // 第一行 percent_rank = 0
            assertEquals(0.0, ((Number) rows.get(0).get("prank")).doubleValue(), 0.001);
            // 最后一行 percent_rank = 1.0
            assertEquals(1.0, ((Number) rows.get(4).get("prank")).doubleValue(), 0.001);
        }
    }

    // ==================== CUME_DIST ====================

    @Nested
    class CumeDistTests {

        @Test
        void cumeDistBasic() {
            List<Row> rows = execute(
                    "SELECT order_id, CUME_DIST() OVER (ORDER BY order_id) AS cdist FROM orders");
            assertEquals(5, rows.size());
            // 第一行 cume_dist = 1/5 = 0.2
            assertEquals(0.2, ((Number) rows.get(0).get("cdist")).doubleValue(), 0.001);
            // 最后一行 cume_dist = 5/5 = 1.0
            assertEquals(1.0, ((Number) rows.get(4).get("cdist")).doubleValue(), 0.001);
        }
    }
}
