package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.MockCatalog;
import cn.zhangyis.minidb.sql.exec.*;
import cn.zhangyis.minidb.sql.lexer.SqlLexer;
import cn.zhangyis.minidb.sql.lexer.TokenStream;
import cn.zhangyis.minidb.sql.optimize.RuleOptimizer;
import cn.zhangyis.minidb.sql.optimize.cost.CostModel;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.*;
import cn.zhangyis.minidb.sql.validation.SqlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExplainAnalyzeTest {
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

    private List<Row> executeExplainAnalyze(String innerSql) {
        RelNode plan = buildPlan(innerSql);
        CostModel costModel = new CostModel(false);
        ExecNode execTree = new PhysicalPlanner().plan(plan);
        ExplainAnalyzeExec analyzeExec = new ExplainAnalyzeExec(execTree, plan, costModel);
        List<Row> rows = new ArrayList<>();
        analyzeExec.open();
        Row row;
        while ((row = analyzeExec.next()) != null) rows.add(row);
        analyzeExec.close();
        return rows;
    }

    private List<Row> execute(String sql) {
        ExecNode exec = new PhysicalPlanner().plan(buildPlan(sql));
        List<Row> rows = new ArrayList<>();
        exec.open();
        Row row;
        while ((row = exec.next()) != null) rows.add(row);
        exec.close();
        return rows;
    }

    @Test
    void outputContainsActRowsAndTimeMs() {
        List<Row> result = executeExplainAnalyze("SELECT * FROM users");
        assertFalse(result.isEmpty());
        Row first = result.get(0);
        // 验证输出列包含 ACT_ROWS 和 TIME_MS
        Set<String> keys = first.columns().keySet();
        assertTrue(keys.contains("ACT_ROWS"), "Missing ACT_ROWS column");
        assertTrue(keys.contains("TIME_MS"), "Missing TIME_MS column");
        assertTrue(keys.contains("EST_ROWS"), "Missing EST_ROWS column");
        assertTrue(keys.contains("OPERATOR"), "Missing OPERATOR column");
    }

    @Test
    void actRowsMatchesActualSelectCount() {
        // 实际执行 SELECT * FROM users 应该返回5行
        List<Row> actualRows = execute("SELECT * FROM users");
        List<Row> analyzeResult = executeExplainAnalyze("SELECT * FROM users");

        // 顶层算子的 ACT_ROWS 应等于实际行数
        Row topOperator = analyzeResult.get(0);
        long actRows = ((Number) topOperator.get("ACT_ROWS")).longValue();
        assertEquals(actualRows.size(), actRows,
                "ACT_ROWS should match actual row count");
    }

    @Test
    void timeMsIsPositive() {
        List<Row> result = executeExplainAnalyze("SELECT * FROM users WHERE id > 1");
        for (Row row : result) {
            String timeStr = (String) row.get("TIME_MS");
            double timeMs = Double.parseDouble(timeStr);
            assertTrue(timeMs >= 0, "TIME_MS should be >= 0, got: " + timeMs);
        }
    }

    @Test
    void multiOperatorQuery() {
        List<Row> result = executeExplainAnalyze(
                "SELECT name, COUNT(*) FROM users GROUP BY name");
        // 至少包含 Project + Aggregate + Scan 三个算子
        assertTrue(result.size() >= 2,
                "Multi-operator query should have multiple plan rows, got: " + result.size());
        // 每行都有 ACT_ROWS
        for (Row row : result) {
            assertNotNull(row.get("ACT_ROWS"));
        }
    }

    @Test
    void parseExplainAnalyzeSyntax() {
        // 验证 Parser 能正确识别 EXPLAIN ANALYZE
        SqlNode ast = parse("EXPLAIN ANALYZE SELECT * FROM users");
        assertTrue(ast instanceof cn.zhangyis.minidb.sql.ast.SqlExplain);
        cn.zhangyis.minidb.sql.ast.SqlExplain explain = (cn.zhangyis.minidb.sql.ast.SqlExplain) ast;
        assertTrue(explain.analyze(), "Should be EXPLAIN ANALYZE");
    }

    @Test
    void parseExplainWithoutAnalyze() {
        SqlNode ast = parse("EXPLAIN SELECT * FROM users");
        assertTrue(ast instanceof cn.zhangyis.minidb.sql.ast.SqlExplain);
        cn.zhangyis.minidb.sql.ast.SqlExplain explain = (cn.zhangyis.minidb.sql.ast.SqlExplain) ast;
        assertFalse(explain.analyze(), "Should be plain EXPLAIN");
    }
}
