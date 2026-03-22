package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.*;
import cn.zhangyis.minidb.sql.exec.*;
import cn.zhangyis.minidb.sql.lexer.SqlLexer;
import cn.zhangyis.minidb.sql.lexer.TokenStream;
import cn.zhangyis.minidb.sql.optimize.RuleOptimizer;
import cn.zhangyis.minidb.sql.optimize.cost.CostModel;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.*;
import cn.zhangyis.minidb.sql.types.SqlType;
import cn.zhangyis.minidb.sql.validation.SqlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feature #15: EXPLAIN 查询计划可视化 测试
 */
class ExplainFeatureTest {

    private MockCatalog catalog;

    @BeforeEach
    void setUp() {
        MockDataSource.reset();
        catalog = new MockCatalog();

        catalog.createTable(TableMeta.of("EMPLOYEES", List.of(
            new ColumnMeta("ID", SqlType.INT32, true),
            new ColumnMeta("NAME", SqlType.VARCHAR, false),
            new ColumnMeta("DEPT_ID", SqlType.INT32, false)
        ), 5));
        catalog.createTable(TableMeta.of("DEPARTMENTS", List.of(
            new ColumnMeta("DEPT_ID", SqlType.INT32, true),
            new ColumnMeta("DEPT_NAME", SqlType.VARCHAR, false)
        ), 3));

        MockDataSource.insertRow("EMPLOYEES", row("employees.id", 1, "employees.name", "alice", "employees.dept_id", 10));
        MockDataSource.insertRow("EMPLOYEES", row("employees.id", 2, "employees.name", "bob", "employees.dept_id", 20));
        MockDataSource.insertRow("EMPLOYEES", row("employees.id", 3, "employees.name", "charlie", "employees.dept_id", 10));

        MockDataSource.insertRow("DEPARTMENTS", row("departments.dept_id", 10, "departments.dept_name", "engineering"));
        MockDataSource.insertRow("DEPARTMENTS", row("departments.dept_id", 20, "departments.dept_name", "sales"));
    }

    // ==================== 解析测试 ====================

    @Nested
    class ParserTest {

        @Test
        void explain_selectParsesCorrectly() {
            SqlNode ast = parse("EXPLAIN SELECT * FROM employees");
            assertInstanceOf(SqlExplain.class, ast);
            SqlExplain explain = (SqlExplain) ast;
            assertInstanceOf(SqlSelect.class, explain.query());
        }

        @Test
        void explain_selectWithWhereParsesCorrectly() {
            SqlNode ast = parse("EXPLAIN SELECT id FROM employees WHERE id > 1");
            assertInstanceOf(SqlExplain.class, ast);
        }

        @Test
        void explain_joinParsesCorrectly() {
            SqlNode ast = parse("EXPLAIN SELECT * FROM employees e JOIN departments d ON e.dept_id = d.dept_id");
            assertInstanceOf(SqlExplain.class, ast);
        }

        @Test
        void explain_kindIsExplainQuery() {
            SqlNode ast = parse("EXPLAIN SELECT 1");
            assertEquals(SqlKind.EXPLAIN_QUERY, ast.kind());
        }
    }

    // ==================== ExplainExec 单元测试 ====================

    @Nested
    class ExplainExecTest {

        @Test
        void explainSimpleSelect_outputsProjectAndScan() {
            RelNode plan = buildOptimizedPlan("SELECT id FROM employees");
            CostModel costModel = new CostModel();
            ExplainExec exec = new ExplainExec(plan, costModel);

            List<Row> rows = collectRows(exec);

            assertFalse(rows.isEmpty(), "EXPLAIN 应至少输出一行");

            // 每行都应有 4 列
            for (Row row : rows) {
                assertNotNull(row.get("ID"));
                assertNotNull(row.get("OPERATOR"));
                assertNotNull(row.get("EST_ROWS"));
                assertNotNull(row.get("DETAILS"));
            }
        }

        @Test
        void explainSimpleSelect_containsTableScan() {
            RelNode plan = buildOptimizedPlan("SELECT * FROM employees");
            List<Row> rows = collectRows(new ExplainExec(plan, new CostModel()));

            boolean hasTableScan = rows.stream()
                .anyMatch(r -> r.get("OPERATOR").toString().contains("TableScan"));
            assertTrue(hasTableScan, "EXPLAIN 输出应包含 TableScan");
        }

        @Test
        void explainWithFilter_containsFilterNode() {
            RelNode plan = buildOptimizedPlan("SELECT * FROM employees WHERE id > 1");
            List<Row> rows = collectRows(new ExplainExec(plan, new CostModel()));

            boolean hasFilter = rows.stream()
                .anyMatch(r -> r.get("OPERATOR").toString().contains("Filter"));
            assertTrue(hasFilter, "EXPLAIN 输出应包含 Filter");
        }

        @Test
        void explainJoin_containsJoinNode() {
            RelNode plan = buildOptimizedPlan(
                "SELECT * FROM employees e JOIN departments d ON e.dept_id = d.dept_id");
            List<Row> rows = collectRows(new ExplainExec(plan, new CostModel()));

            boolean hasJoin = rows.stream()
                .anyMatch(r -> r.get("OPERATOR").toString().contains("Join"));
            assertTrue(hasJoin, "EXPLAIN 输出应包含 Join");
        }

        @Test
        void explainJoin_hasTwoTableScans() {
            RelNode plan = buildOptimizedPlan(
                "SELECT * FROM employees e JOIN departments d ON e.dept_id = d.dept_id");
            List<Row> rows = collectRows(new ExplainExec(plan, new CostModel()));

            long scanCount = rows.stream()
                .filter(r -> r.get("OPERATOR").toString().contains("TableScan"))
                .count();
            assertEquals(2, scanCount, "两表 JOIN 应有 2 个 TableScan");
        }

        @Test
        void explainAggregate_containsAggregateNode() {
            RelNode plan = buildOptimizedPlan("SELECT COUNT(*) FROM employees");
            List<Row> rows = collectRows(new ExplainExec(plan, new CostModel()));

            boolean hasAggregate = rows.stream()
                .anyMatch(r -> r.get("OPERATOR").toString().contains("Aggregate"));
            assertTrue(hasAggregate, "EXPLAIN 输出应包含 Aggregate");
        }

        @Test
        void explainSort_containsSortNode() {
            RelNode plan = buildOptimizedPlan("SELECT * FROM employees ORDER BY id");
            List<Row> rows = collectRows(new ExplainExec(plan, new CostModel()));

            boolean hasSort = rows.stream()
                .anyMatch(r -> r.get("OPERATOR").toString().contains("Sort"));
            assertTrue(hasSort, "EXPLAIN 输出应包含 Sort");
        }

        @Test
        void explainIdSequence_isIncreasing() {
            RelNode plan = buildOptimizedPlan(
                "SELECT * FROM employees e JOIN departments d ON e.dept_id = d.dept_id WHERE e.id > 1");
            List<Row> rows = collectRows(new ExplainExec(plan, new CostModel()));

            for (int i = 0; i < rows.size(); i++) {
                assertEquals(i + 1, rows.get(i).get("ID"), "ID 应从 1 递增");
            }
        }

        @Test
        void explainChildNodes_areIndented() {
            RelNode plan = buildOptimizedPlan(
                "SELECT * FROM employees WHERE id > 1");
            List<Row> rows = collectRows(new ExplainExec(plan, new CostModel()));

            // 根节点不缩进，子节点有缩进
            if (rows.size() >= 2) {
                String rootOp = rows.get(0).get("OPERATOR").toString();
                String childOp = rows.get(1).get("OPERATOR").toString();
                assertTrue(childOp.startsWith("  "), "子节点应有缩进");
                assertFalse(rootOp.startsWith(" "), "根节点不应有缩进");
            }
        }

        @Test
        void explainTableScanDetails_containsTableName() {
            RelNode plan = buildOptimizedPlan("SELECT * FROM employees");
            List<Row> rows = collectRows(new ExplainExec(plan, new CostModel()));

            Row scanRow = rows.stream()
                .filter(r -> r.get("OPERATOR").toString().contains("TableScan"))
                .findFirst().orElse(null);
            assertNotNull(scanRow);
            assertTrue(scanRow.get("DETAILS").toString().contains("table="),
                "TableScan 的 DETAILS 应包含表名");
        }

        @Test
        void explainEstRows_isNonNegative() {
            RelNode plan = buildOptimizedPlan("SELECT * FROM employees");
            List<Row> rows = collectRows(new ExplainExec(plan, new CostModel()));

            for (Row row : rows) {
                String estRows = row.get("EST_ROWS").toString();
                double value = Double.parseDouble(estRows);
                assertTrue(value >= 0, "EST_ROWS 不应为负数");
            }
        }
    }

    // ==================== SqlSession 端到端测试 ====================

    @Nested
    class SqlSessionExplainTest {

        @Test
        void explainViaSqlSession_returnsRows() {
            SqlSession session = createSession();
            List<Row> rows = session.execute("EXPLAIN SELECT * FROM employees");
            assertFalse(rows.isEmpty(), "EXPLAIN 应返回结果");
        }

        @Test
        void explainViaSqlSession_hasCorrectColumns() {
            SqlSession session = createSession();
            List<Row> rows = session.execute("EXPLAIN SELECT * FROM employees");

            Row first = rows.get(0);
            assertNotNull(first.get("ID"));
            assertNotNull(first.get("OPERATOR"));
            assertNotNull(first.get("EST_ROWS"));
            assertNotNull(first.get("DETAILS"));
        }

        @Test
        void explainViaSqlSession_joinQuery() {
            SqlSession session = createSession();
            List<Row> rows = session.execute(
                "EXPLAIN SELECT * FROM employees e JOIN departments d ON e.dept_id = d.dept_id");

            // 至少应有 Join + 2 个 TableScan = 3 行
            assertTrue(rows.size() >= 3, "JOIN EXPLAIN 应至少有 3 行输出");
        }

        @Test
        void explainBegin_throwsException() {
            SqlSession session = createSession();
            assertThrows(IllegalArgumentException.class, () ->
                session.execute("EXPLAIN BEGIN"));
        }

        @Test
        void explainExplain_throwsException() {
            SqlSession session = createSession();
            assertThrows(IllegalArgumentException.class, () ->
                session.execute("EXPLAIN EXPLAIN SELECT 1"));
        }
    }

    // ==================== 辅助方法 ====================

    private SqlNode parse(String sql) {
        return new SqlParser(new TokenStream(new SqlLexer(sql))).parseStatement();
    }

    private RelNode buildOptimizedPlan(String sql) {
        SqlNode ast = parse(sql);
        SqlNode validated = new SqlValidator(catalog).validate(ast);
        RelNode logicalPlan = new SqlToRelConverter(catalog).convert(validated);
        return new RuleOptimizer().optimize(logicalPlan);
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

    private SqlSession createSession() {
        ExecutionContext ctx = new ExecutionContext(null);
        return new SqlSession(catalog, ctx, MockDataSourceAdapter.INSTANCE);
    }

    private static Row row(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return new Row(map);
    }

    private static Row row(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        return new Row(map);
    }
}
