package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.MockCatalog;
import cn.zhangyis.minidb.sql.exec.*;
import cn.zhangyis.minidb.sql.lexer.*;
import cn.zhangyis.minidb.sql.optimize.RuleOptimizer;
import cn.zhangyis.minidb.sql.optimize.cost.CostOptimizer;
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
 * 验证默认配置仍禁用“伪索引路径”：
 * - RuleOptimizer() 默认不产出 RelIndexedScan
 * - CostOptimizer() 默认不选中 INDEX_NESTED_LOOP
 * - explain 默认不输出 INDEX_SCAN
 *
 * 真实索引路径的启用和执行由 storage-backed 集成测试覆盖。
 */
class FakeIndexPathDisabledTest {
    private final MockCatalog catalog = new MockCatalog();

    @BeforeEach
    void resetData() {
        MockDataSource.reset();
    }

    // ==================== 规则层：PushFilterIntoScanRule 不再产出 RelIndexedScan ====================

    @Test
    void optimizerNeverProducesRelIndexedScan() {
        // WHERE 条件应保留为 Filter(Scan)，不被转为 RelIndexedScan
        RelNode plan = buildOptimized("SELECT * FROM users WHERE id = 1");
        assertNoIndexedScan(plan);
    }

    @Test
    void optimizerNeverProducesRelIndexedScanWithRangeCondition() {
        RelNode plan = buildOptimized("SELECT * FROM users WHERE id > 5");
        assertNoIndexedScan(plan);
    }

    @Test
    void optimizerNeverProducesRelIndexedScanInJoin() {
        RelNode plan = buildOptimized("SELECT * FROM users JOIN orders ON users.id = orders.user_id");
        assertNoIndexedScan(plan);
    }

    // ==================== CBO 层：不再选中 INDEX_NESTED_LOOP ====================

    @Test
    void cboNeverChoosesIndexNestedLoopByDefault() {
        RelNode plan = buildOptimized("SELECT * FROM users JOIN orders ON users.id = orders.user_id");
        RelJoin join = findJoin(plan);
        assertNotNull(join);

        CostOptimizer costOpt = new CostOptimizer();
        PhysicalPlanner.JoinAlgorithm chosen = costOpt.chooseJoinAlgorithm(join);

        // 只能是 HASH_JOIN、SORT_MERGE 或 NESTED_LOOP
        assertTrue(
            chosen == PhysicalPlanner.JoinAlgorithm.HASH_JOIN
            || chosen == PhysicalPlanner.JoinAlgorithm.SORT_MERGE
            || chosen == PhysicalPlanner.JoinAlgorithm.NESTED_LOOP,
            "CBO should not choose INDEX_NESTED_LOOP, got: " + chosen
        );
    }

    // ==================== explain 层：不再输出 INDEX_SCAN ====================

    @Test
    void explainDoesNotContainIndexScan() {
        RelNode plan = buildOptimized("SELECT * FROM users WHERE id = 1");
        CostOptimizer costOpt = new CostOptimizer();
        String explain = costOpt.decidePhysicalPlan(plan);

        assertFalse(explain.contains("INDEX_SCAN"), "explain should not contain INDEX_SCAN: " + explain);
        assertFalse(explain.contains("RelIndexedScan"), "explain should not contain RelIndexedScan: " + explain);
    }

    @Test
    void explainDoesNotContainIndexNestedLoop() {
        RelNode plan = buildOptimized("SELECT * FROM users JOIN orders ON users.id = orders.user_id");
        CostOptimizer costOpt = new CostOptimizer();
        String explain = costOpt.decidePhysicalPlan(plan);

        assertFalse(explain.contains("INDEX_NESTED_LOOP"), "explain should not contain INDEX_NESTED_LOOP: " + explain);
    }

    // ==================== 执行层：退化到真实可执行路径 ====================

    @Test
    void filterQueryFallsBackToScanPlusFilter() {
        RelNode plan = buildOptimized("SELECT * FROM users WHERE id = 1");
        PhysicalPlanner planner = new PhysicalPlanner();
        ExecNode exec = planner.plan(plan);

        // 应该是 FilterExec(ScanExec) 或 ProjectExec(FilterExec(ScanExec))
        assertNotNull(exec);
        assertFalse(exec.getClass().getSimpleName().contains("Index"),
            "Executor should not be index-based: " + exec.getClass().getSimpleName());

        // 执行应正常返回结果
        exec.open();
        List<Row> rows = new ArrayList<>();
        Row row;
        while ((row = exec.next()) != null) {
            rows.add(row);
        }
        exec.close();
        assertEquals(1, rows.size());
    }

    @Test
    void joinQueryUsesRealJoinExecutor() {
        RelNode plan = buildOptimized("SELECT * FROM users JOIN orders ON users.id = orders.user_id");
        PhysicalPlanner planner = new PhysicalPlanner();
        ExecNode exec = planner.plan(plan);

        assertNotNull(exec);
        assertFalse(exec instanceof IndexNestedLoopJoinExec,
            "Should not use IndexNestedLoopJoinExec");

        exec.open();
        List<Row> rows = new ArrayList<>();
        Row row;
        while ((row = exec.next()) != null) {
            rows.add(row);
        }
        exec.close();
        assertEquals(5, rows.size());
    }

    // ==================== 辅助方法 ====================

    private RelNode buildOptimized(String sql) {
        SqlLexer lexer = new SqlLexer(sql);
        TokenStream tokens = new TokenStream(lexer);
        SqlParser parser = new SqlParser(tokens);
        SqlNode ast = parser.parseStatement();

        SqlValidator validator = new SqlValidator(catalog);
        SqlNode validated = validator.validate(ast);

        SqlToRelConverter converter = new SqlToRelConverter(catalog);
        RelNode logicalPlan = converter.convert(validated);

        RuleOptimizer ruleOpt = new RuleOptimizer();
        return ruleOpt.optimize(logicalPlan);
    }

    private void assertNoIndexedScan(RelNode node) {
        assertFalse(node instanceof RelIndexedScan,
            "Plan should not contain RelIndexedScan: " + node.explain());
        if (node instanceof RelFilter f) assertNoIndexedScan(f.input());
        if (node instanceof RelProject p) assertNoIndexedScan(p.input());
        if (node instanceof RelJoin j) {
            assertNoIndexedScan(j.left());
            assertNoIndexedScan(j.right());
        }
        if (node instanceof RelSort s) assertNoIndexedScan(s.input());
        if (node instanceof RelAggregate a) assertNoIndexedScan(a.input());
        if (node instanceof RelDistinct d) assertNoIndexedScan(d.input());
    }

    private RelJoin findJoin(RelNode node) {
        if (node instanceof RelJoin join) return join;
        if (node instanceof RelProject p) return findJoin(p.input());
        if (node instanceof RelFilter f) return findJoin(f.input());
        if (node instanceof RelSort s) return findJoin(s.input());
        if (node instanceof RelAggregate a) return findJoin(a.input());
        return null;
    }
}
