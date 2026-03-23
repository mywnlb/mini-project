package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.ast.SqlNode;
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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 两阶段聚合拆分规则测试：验证拆分后结果与单阶段等价
 */
class AggregateSplitRuleTest {
    private final MockCatalog catalog = new MockCatalog();

    @BeforeEach
    void resetData() {
        MockDataSource.reset();
    }

    // ==================== helpers ====================

    private SqlNode parse(String sql) {
        return new SqlParser(new TokenStream(new SqlLexer(sql))).parseStatement();
    }

    private RelNode buildPlan(String sql, boolean enableSplit) {
        SqlNode ast = parse(sql);
        SqlNode validated = new SqlValidator(catalog).validate(ast);
        RelNode logical = new SqlToRelConverter(catalog).convert(validated);
        return new RuleOptimizer(false, catalog, null, enableSplit).optimize(logical);
    }

    private List<Row> execute(String sql, boolean enableSplit) {
        ExecNode exec = new PhysicalPlanner().plan(buildPlan(sql, enableSplit));
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

    // ==================== 计划树验证 ====================

    @Test
    void splitProducesTwoPhaseAggNodes() {
        RelNode plan = buildPlan("SELECT COUNT(*) FROM users", true);
        // 最外层应该是 RelFinalAggregate
        assertTrue(plan instanceof RelProject || plan instanceof RelFinalAggregate,
                "Top node: " + plan.getClass().getSimpleName());
        String explain = plan.explain();
        assertTrue(explain.contains("RelFinalAggregate") || explain.contains("RelPartialAggregate"),
                "Plan should contain two-phase nodes:\n" + explain);
    }

    @Test
    void noSplitWhenDisabled() {
        RelNode plan = buildPlan("SELECT COUNT(*) FROM users", false);
        String explain = plan.explain();
        assertFalse(explain.contains("RelPartialAggregate"), "Should not split when disabled");
    }

    // ==================== COUNT 等价性 ====================

    @Test
    void countStarEquivalent() {
        List<Row> single = execute("SELECT COUNT(*) FROM users", false);
        List<Row> split = execute("SELECT COUNT(*) FROM users", true);
        assertEquals(single.size(), split.size());
        Object singleVal = single.get(0).columns().values().iterator().next();
        Object splitVal = split.get(0).columns().values().iterator().next();
        assertEquals(((Number) singleVal).longValue(), ((Number) splitVal).longValue());
    }

    @Test
    void countColumnEquivalent() {
        List<Row> single = execute("SELECT COUNT(name) FROM users", false);
        List<Row> split = execute("SELECT COUNT(name) FROM users", true);
        assertEquals(((Number) single.get(0).columns().values().iterator().next()).longValue(),
                ((Number) split.get(0).columns().values().iterator().next()).longValue());
    }

    // ==================== SUM 等价性 ====================

    @Test
    void sumEquivalent() {
        List<Row> single = execute("SELECT SUM(amount) FROM orders", false);
        List<Row> split = execute("SELECT SUM(amount) FROM orders", true);
        double singleVal = ((Number) single.get(0).columns().values().iterator().next()).doubleValue();
        double splitVal = ((Number) split.get(0).columns().values().iterator().next()).doubleValue();
        assertEquals(singleVal, splitVal, 0.001);
    }

    // ==================== AVG 等价性 ====================

    @Test
    void avgEquivalent() {
        List<Row> single = execute("SELECT AVG(amount) FROM orders", false);
        List<Row> split = execute("SELECT AVG(amount) FROM orders", true);
        double singleVal = ((Number) single.get(0).columns().values().iterator().next()).doubleValue();
        double splitVal = ((Number) split.get(0).columns().values().iterator().next()).doubleValue();
        assertEquals(singleVal, splitVal, 0.001);
    }

    // ==================== MAX/MIN 等价性 ====================

    @Test
    void maxEquivalent() {
        List<Row> single = execute("SELECT MAX(amount) FROM orders", false);
        List<Row> split = execute("SELECT MAX(amount) FROM orders", true);
        Object singleVal = single.get(0).columns().values().iterator().next();
        Object splitVal = split.get(0).columns().values().iterator().next();
        assertEquals(((Number) singleVal).doubleValue(), ((Number) splitVal).doubleValue(), 0.001);
    }

    @Test
    void minEquivalent() {
        List<Row> single = execute("SELECT MIN(amount) FROM orders", false);
        List<Row> split = execute("SELECT MIN(amount) FROM orders", true);
        Object singleVal = single.get(0).columns().values().iterator().next();
        Object splitVal = split.get(0).columns().values().iterator().next();
        assertEquals(((Number) singleVal).doubleValue(), ((Number) splitVal).doubleValue(), 0.001);
    }

    // ==================== GROUP BY ====================

    @Test
    void groupByWithMultipleAggs() {
        String sql = "SELECT user_id, COUNT(*), SUM(amount) FROM orders GROUP BY user_id";
        List<Row> single = execute(sql, false);
        List<Row> split = execute(sql, true);
        assertEquals(single.size(), split.size());
        // 验证每个 group 的结果一致
        for (int i = 0; i < single.size(); i++) {
            Object sUserId = single.get(i).get("user_id");
            // 找对应 split 行
            for (Row sr : split) {
                if (sr.get("user_id").equals(sUserId)) {
                    for (String key : single.get(i).columns().keySet()) {
                        Number sv = (Number) single.get(i).get(key);
                        Number rv = (Number) sr.get(key);
                        assertEquals(sv.doubleValue(), rv.doubleValue(), 0.001, "Mismatch on " + key);
                    }
                    break;
                }
            }
        }
    }

    // ==================== 全局聚合（无 GROUP BY）====================

    @Test
    void globalAggregateMultipleFunctions() {
        String sql = "SELECT COUNT(*), SUM(amount), AVG(amount), MAX(amount), MIN(amount) FROM orders";
        List<Row> single = execute(sql, false);
        List<Row> split = execute(sql, true);
        assertEquals(1, single.size());
        assertEquals(1, split.size());
        for (String key : single.get(0).columns().keySet()) {
            Number sv = (Number) single.get(0).get(key);
            Number rv = (Number) split.get(0).get(key);
            assertNotNull(rv, "Split result missing key: " + key);
            assertEquals(sv.doubleValue(), rv.doubleValue(), 0.001, "Mismatch on " + key);
        }
    }
}
