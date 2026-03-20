package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.MockCatalog;
import cn.zhangyis.minidb.sql.exec.*;
import cn.zhangyis.minidb.sql.lexer.SqlLexer;
import cn.zhangyis.minidb.sql.lexer.TokenStream;
import cn.zhangyis.minidb.sql.optimize.RuleOptimizer;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.*;
import cn.zhangyis.minidb.sql.validation.SqlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 中优先级功能测试：
 * #4 Projection Pruning
 * #5 LIMIT Pushdown
 * #6 CAST 函数
 * #7 WINDOW 函数
 * #8 EXCEPT / INTERSECT
 * #9 表达式类型检查
 */
class MediumPriorityFeaturesTest {
    private final MockCatalog catalog = new MockCatalog();

    @BeforeEach
    void resetData() {
        MockDataSource.reset();
    }

    // ==================== helpers ====================

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

    // ==================== #6: CAST 函数 ====================

    @Nested
    class CastTests {
        @Test
        void castIntToVarchar() {
            List<Row> rows = execute("SELECT CAST(id AS VARCHAR) FROM users WHERE id = 1");
            assertEquals(1, rows.size());
            Object val = rows.get(0).columns().values().iterator().next();
            assertEquals("1", val);
        }

        @Test
        void castStringToInt() {
            // amount 是 decimal, cast 到 int
            List<Row> rows = execute("SELECT CAST(amount AS INT) FROM orders WHERE order_id = 101");
            assertEquals(1, rows.size());
            Object val = rows.get(0).columns().values().iterator().next();
            assertTrue(val instanceof Integer);
            assertEquals(250, (Integer) val);
        }

        @Test
        void castDecimalToBigint() {
            List<Row> rows = execute("SELECT CAST(amount AS BIGINT) FROM orders WHERE order_id = 102");
            assertEquals(1, rows.size());
            Object val = rows.get(0).columns().values().iterator().next();
            assertTrue(val instanceof Long);
            assertEquals(130L, (Long) val);
        }

        @Test
        void castWithAlias() {
            List<Row> rows = execute("SELECT CAST(id AS VARCHAR) AS id_str FROM users WHERE id = 1");
            assertEquals(1, rows.size());
            assertEquals("1", rows.get(0).get("id_str"));
        }

        @Test
        void castInWhereClause() {
            List<Row> rows = execute("SELECT * FROM users WHERE CAST(id AS VARCHAR) = '2'");
            assertEquals(1, rows.size());
            assertEquals(2, rows.get(0).get("users.id"));
        }
    }

    // ==================== #8: EXCEPT / INTERSECT ====================

    @Nested
    class SetOperationTests {
        @Test
        void exceptBasic() {
            // users 中 id=1,2,3,4,5. orders 中 user_id=1,2,3,5.
            // users.id EXCEPT orders.user_id → {4}
            List<Row> rows = execute(
                "SELECT id FROM users EXCEPT SELECT user_id FROM orders");
            // 结果应包含 id=4（唯一没有订单的用户）
            assertEquals(1, rows.size());
            assertEquals(4, rows.get(0).columns().values().iterator().next());
        }

        @Test
        void intersectBasic() {
            // users.id INTERSECT orders.user_id → {1, 2, 3, 5}
            List<Row> rows = execute(
                "SELECT id FROM users INTERSECT SELECT user_id FROM orders");
            assertEquals(4, rows.size());
            Set<Object> ids = rows.stream()
                .map(r -> r.columns().values().iterator().next())
                .collect(Collectors.toSet());
            assertTrue(ids.contains(1));
            assertTrue(ids.contains(2));
            assertTrue(ids.contains(3));
            assertTrue(ids.contains(5));
        }

        @Test
        void exceptAllBasic() {
            // alice 出现 2 次 (id=1,4), 如果 EXCEPT ALL 减去一个 alice
            List<Row> rows = execute(
                "SELECT name FROM users EXCEPT ALL SELECT name FROM users WHERE id = 1");
            // 移除一个 alice (id=1)，剩余: bob, charlie, alice(id=4), dave = 4行
            assertEquals(4, rows.size());
        }

        @Test
        void intersectAllBasic() {
            List<Row> rows = execute(
                "SELECT name FROM users INTERSECT ALL SELECT name FROM users WHERE id <= 3");
            // 左: alice, bob, charlie, alice, dave
            // 右: alice, bob, charlie
            // INTERSECT ALL: alice(1个), bob(1个), charlie(1个) = 3行
            assertEquals(3, rows.size());
        }

        @Test
        void unionStillWorks() {
            List<Row> rows = execute(
                "SELECT id FROM users WHERE id <= 2 UNION SELECT user_id FROM orders WHERE user_id = 1");
            // {1, 2} UNION {1} = {1, 2}
            assertEquals(2, rows.size());
        }
    }

    // ==================== #5: LIMIT Pushdown ====================

    @Nested
    class LimitPushdownTests {
        @Test
        void limitThroughProject() {
            // LIMIT 应该正确工作
            List<Row> rows = execute("SELECT id, name FROM users LIMIT 3");
            assertEquals(3, rows.size());
        }

        @Test
        void limitWithOffset() {
            List<Row> rows = execute("SELECT id FROM users LIMIT 2 OFFSET 2");
            assertEquals(2, rows.size());
        }

        @Test
        void limitPlanContainsSort() {
            // 验证计划结构：LIMIT 会生成 RelSort
            RelNode plan = buildPlan("SELECT id FROM users LIMIT 3");
            RelSort sort = findNode(plan, RelSort.class);
            assertNotNull(sort, "计划中应包含 RelSort（LIMIT 节点）");
        }
    }

    // ==================== #4: Projection Pruning ====================

    @Nested
    class ProjectionPruningTests {
        @Test
        void selectStarNotPruned() {
            List<Row> rows = execute("SELECT * FROM users");
            assertEquals(5, rows.size());
            // SELECT * 行应保留所有列
            assertTrue(rows.get(0).columns().size() >= 2);
        }

        @Test
        void selectSpecificColumns() {
            List<Row> rows = execute("SELECT name FROM users");
            assertEquals(5, rows.size());
        }

        @Test
        void selectWithFilter() {
            List<Row> rows = execute("SELECT name FROM users WHERE id = 1");
            assertEquals(1, rows.size());
            assertEquals("alice", rows.get(0).columns().values().iterator().next());
        }
    }

    // ==================== #9: 表达式类型检查 ====================

    @Nested
    class ExpressionTypeCheckTests {
        @Test
        void arithmeticOnVarchar_rejected() {
            // name 是 VARCHAR，不能做加法
            assertThrows(Exception.class, () ->
                execute("SELECT name + 1 FROM users"));
        }

        @Test
        void comparisonTypeMismatch_rejected() {
            // name(VARCHAR) 和 id(INT) 直接比较
            assertThrows(Exception.class, () ->
                execute("SELECT * FROM users WHERE name = id"));
        }

        @Test
        void numericArithmetic_pass() {
            // id(INT) + amount(DECIMAL) → DECIMAL
            List<Row> rows = execute("SELECT id + amount FROM orders WHERE order_id = 101");
            assertEquals(1, rows.size());
        }

        @Test
        void castFixesTypeMismatch() {
            // CAST(id AS VARCHAR) = name → 两侧都是 VARCHAR
            List<Row> rows = execute("SELECT * FROM users WHERE CAST(id AS VARCHAR) = '1'");
            assertEquals(1, rows.size());
        }
    }

    // ==================== #7: WINDOW 函数 ====================

    @Nested
    class WindowFunctionTests {
        @Test
        void rowNumberBasic() {
            List<Row> rows = execute(
                "SELECT id, name, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM users");
            assertEquals(5, rows.size());
            // ROW_NUMBER 应该从 1 递增到 5
            for (int i = 0; i < rows.size(); i++) {
                assertEquals((long)(i + 1), ((Number) rows.get(i).get("rn")).longValue());
            }
        }

        @Test
        void rowNumberWithPartition() {
            List<Row> rows = execute(
                "SELECT id, name, ROW_NUMBER() OVER (PARTITION BY name ORDER BY id) AS rn FROM users");
            assertEquals(5, rows.size());
            // alice 出现 2 次 (id=1,4)，分区内 rn 为 1,2
            // 其他人只出现 1 次，rn 为 1
            for (Row row : rows) {
                long rn = ((Number) row.get("rn")).longValue();
                assertTrue(rn >= 1);
            }
        }

        @Test
        void rankBasic() {
            List<Row> rows = execute(
                "SELECT user_id, amount, RANK() OVER (ORDER BY amount DESC) AS rnk FROM orders");
            assertEquals(5, rows.size());
            // amount: 500, 250, 130, 80, 60 → ranks: 1,2,3,4,5
            assertEquals(1L, ((Number) rows.get(0).get("rnk")).longValue());
        }

        @Test
        void denseRankBasic() {
            List<Row> rows = execute(
                "SELECT user_id, amount, DENSE_RANK() OVER (ORDER BY amount DESC) AS dr FROM orders");
            assertEquals(5, rows.size());
            assertEquals(1L, ((Number) rows.get(0).get("dr")).longValue());
        }

        @Test
        void windowFunctionWithoutPartition() {
            List<Row> rows = execute(
                "SELECT id, ROW_NUMBER() OVER (ORDER BY id DESC) AS rn FROM users");
            assertEquals(5, rows.size());
            // 第一行是 id=5 (最大)，rn=1
            assertEquals(1L, ((Number) rows.get(0).get("rn")).longValue());
        }
    }

    // ==================== 回归测试 ====================

    @Nested
    class RegressionTests {
        @Test
        void plainSelectUnaffected() {
            List<Row> rows = execute("SELECT * FROM users");
            assertEquals(5, rows.size());
        }

        @Test
        void joinUnaffected() {
            List<Row> rows = execute(
                "SELECT users.id, orders.order_id FROM users JOIN orders ON users.id = orders.user_id");
            assertEquals(5, rows.size());
        }

        @Test
        void aggregateUnaffected() {
            List<Row> rows = execute("SELECT COUNT(*) FROM users");
            assertEquals(1, rows.size());
            assertEquals(5L, ((Number) rows.get(0).columns().values().iterator().next()).longValue());
        }

        @Test
        void orderByUnaffected() {
            List<Row> rows = execute("SELECT id FROM users ORDER BY id DESC");
            assertEquals(5, rows.size());
            assertEquals(5, rows.get(0).columns().values().iterator().next());
        }
    }

    // ==================== 工具方法 ====================

    @SuppressWarnings("unchecked")
    private <T> T findNode(RelNode node, Class<T> type) {
        if (type.isInstance(node)) return (T) node;
        if (node instanceof RelProject p) return findNode(p.input(), type);
        if (node instanceof RelFilter f) return findNode(f.input(), type);
        if (node instanceof RelSort s) return findNode(s.input(), type);
        if (node instanceof RelAggregate a) return findNode(a.input(), type);
        if (node instanceof RelDistinct d) return findNode(d.input(), type);
        return null;
    }
}
