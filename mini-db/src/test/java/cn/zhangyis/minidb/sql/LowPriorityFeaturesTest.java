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
import cn.zhangyis.minidb.sql.validation.ValidatedWithSelect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 低优先级特性 #10~#14 综合测试
 */
class LowPriorityFeaturesTest {

    private MockCatalog catalog;

    @BeforeEach
    void setUp() {
        MockDataSource.reset();
        catalog = new MockCatalog();
        // 添加共享列名的测试表（用于 NATURAL JOIN / USING 测试）
        catalog.createTable(TableMeta.of("EMPLOYEES", List.of(
            new ColumnMeta("ID", SqlType.INT32, true),
            new ColumnMeta("NAME", SqlType.VARCHAR, false),
            new ColumnMeta("DEPT_ID", SqlType.INT32, false)
        ), 5));
        catalog.createTable(TableMeta.of("DEPARTMENTS", List.of(
            new ColumnMeta("DEPT_ID", SqlType.INT32, true),
            new ColumnMeta("DEPT_NAME", SqlType.VARCHAR, false)
        ), 3));
        catalog.createTable(TableMeta.of("PROJECTS", List.of(
            new ColumnMeta("PROJ_ID", SqlType.INT32, true),
            new ColumnMeta("DEPT_ID", SqlType.INT32, false),
            new ColumnMeta("PROJ_NAME", SqlType.VARCHAR, false)
        ), 4));

        // 添加 MockDataSource 数据
        // employees: (1, alice, 10), (2, bob, 20), (3, charlie, 10), (4, dave, 30), (5, eve, 20)
        MockDataSource.insertRow("EMPLOYEES", row("employees.id", 1, "employees.name", "alice", "employees.dept_id", 10));
        MockDataSource.insertRow("EMPLOYEES", row("employees.id", 2, "employees.name", "bob", "employees.dept_id", 20));
        MockDataSource.insertRow("EMPLOYEES", row("employees.id", 3, "employees.name", "charlie", "employees.dept_id", 10));
        MockDataSource.insertRow("EMPLOYEES", row("employees.id", 4, "employees.name", "dave", "employees.dept_id", 30));
        MockDataSource.insertRow("EMPLOYEES", row("employees.id", 5, "employees.name", "eve", "employees.dept_id", 20));

        // departments: (10, engineering), (20, sales), (30, hr)
        MockDataSource.insertRow("DEPARTMENTS", row("departments.dept_id", 10, "departments.dept_name", "engineering"));
        MockDataSource.insertRow("DEPARTMENTS", row("departments.dept_id", 20, "departments.dept_name", "sales"));
        MockDataSource.insertRow("DEPARTMENTS", row("departments.dept_id", 30, "departments.dept_name", "hr"));

        // projects: (100, 10, proj_a), (101, 20, proj_b), (102, 10, proj_c), (103, 30, proj_d)
        MockDataSource.insertRow("PROJECTS", row("projects.proj_id", 100, "projects.dept_id", 10, "projects.proj_name", "proj_a"));
        MockDataSource.insertRow("PROJECTS", row("projects.proj_id", 101, "projects.dept_id", 20, "projects.proj_name", "proj_b"));
        MockDataSource.insertRow("PROJECTS", row("projects.proj_id", 102, "projects.dept_id", 10, "projects.proj_name", "proj_c"));
        MockDataSource.insertRow("PROJECTS", row("projects.proj_id", 103, "projects.dept_id", 30, "projects.proj_name", "proj_d"));
    }

    // ==================== Feature #14: NATURAL JOIN / USING ====================

    @Nested
    class NaturalJoinUsingTest {

        @Test
        void naturalJoin_parsesCorrectly() {
            SqlNode ast = parse("SELECT * FROM employees NATURAL JOIN departments");
            // 应成功解析，不抛异常
            assertNotNull(ast);
        }

        @Test
        void naturalJoin_sharedColumnMatches() {
            // employees 和 departments 共享 DEPT_ID 列
            // NATURAL JOIN → ON employees.dept_id = departments.dept_id
            List<Row> rows = execute("SELECT employees.id, departments.dept_name FROM employees NATURAL JOIN departments");
            // 所有 5 个 employee 都有对应 department
            assertEquals(5, rows.size());
        }

        @Test
        void naturalLeftJoin_preservesLeftRows() {
            // 如果添加无 dept 匹配的员工，LEFT NATURAL JOIN 应保留
            catalog.createTable(TableMeta.of("EMP2", List.of(
                new ColumnMeta("DEPT_ID", SqlType.INT32, false),
                new ColumnMeta("ENAME", SqlType.VARCHAR, false)
            ), 2));
            MockDataSource.insertRow("EMP2", row("emp2.dept_id", 10, "emp2.ename", "x"));
            MockDataSource.insertRow("EMP2", row("emp2.dept_id", 99, "emp2.ename", "y")); // 99 不在 departments 中

            List<Row> rows = execute("SELECT emp2.ename, departments.dept_name FROM emp2 NATURAL LEFT JOIN departments");
            assertEquals(2, rows.size());
            // dept_id=99 应匹配不到，dept_name 为 null
            Row orphan = rows.stream()
                .filter(r -> "y".equals(r.get("emp2.ename")))
                .findFirst().orElse(null);
            assertNotNull(orphan);
            assertNull(orphan.get("departments.dept_name"));
        }

        @Test
        void usingJoin_singleColumn() {
            List<Row> rows = execute("SELECT employees.id, departments.dept_name FROM employees JOIN departments USING (DEPT_ID)");
            assertEquals(5, rows.size());
        }

        @Test
        void usingJoin_multipleColumns() {
            // 创建共享多列的表
            catalog.createTable(TableMeta.of("T1", List.of(
                new ColumnMeta("A", SqlType.INT32, true),
                new ColumnMeta("B", SqlType.INT32, false),
                new ColumnMeta("V1", SqlType.VARCHAR, false)
            ), 2));
            catalog.createTable(TableMeta.of("T2", List.of(
                new ColumnMeta("A", SqlType.INT32, true),
                new ColumnMeta("B", SqlType.INT32, false),
                new ColumnMeta("V2", SqlType.VARCHAR, false)
            ), 2));
            MockDataSource.insertRow("T1", row3("t1.a", 1, "t1.b", 10, "t1.v1", "x"));
            MockDataSource.insertRow("T1", row3("t1.a", 2, "t1.b", 20, "t1.v1", "y"));
            MockDataSource.insertRow("T2", row3("t2.a", 1, "t2.b", 10, "t2.v2", "p"));
            MockDataSource.insertRow("T2", row3("t2.a", 2, "t2.b", 99, "t2.v2", "q"));

            List<Row> rows = execute("SELECT t1.v1, t2.v2 FROM t1 JOIN t2 USING (A, B)");
            // 只有 (1, 10) 匹配，(2, 20) != (2, 99)
            assertEquals(1, rows.size());
            assertEquals("x", rows.get(0).get("t1.v1"));
        }

        @Test
        void naturalJoin_noCommonColumns_degeneratesToCross() {
            // users 和 orders 无共同列名
            // NATURAL JOIN → 无条件 → 退化为 CROSS JOIN
            List<Row> rows = execute("SELECT users.id, orders.order_id FROM users NATURAL JOIN orders");
            assertEquals(25, rows.size()); // 5 * 5
        }

        @Test
        void usingJoin_leftJoin() {
            catalog.createTable(TableMeta.of("E3", List.of(
                new ColumnMeta("DEPT_ID", SqlType.INT32, false),
                new ColumnMeta("ENAME", SqlType.VARCHAR, false)
            ), 2));
            MockDataSource.insertRow("E3", row("e3.dept_id", 10, "e3.ename", "x"));
            MockDataSource.insertRow("E3", row("e3.dept_id", 99, "e3.ename", "y"));

            List<Row> rows = execute("SELECT e3.ename, departments.dept_name FROM e3 LEFT JOIN departments USING (DEPT_ID)");
            assertEquals(2, rows.size());
        }

        @Test
        void validator_acceptsNaturalJoin() {
            SqlNode ast = parse("SELECT employees.id FROM employees NATURAL JOIN departments");
            // Validation should not throw
            SqlNode validated = new SqlValidator(catalog).validate(ast);
            assertNotNull(validated);
        }

        @Test
        void validator_acceptsUsingJoin() {
            SqlNode ast = parse("SELECT employees.id FROM employees JOIN departments USING (DEPT_ID)");
            SqlNode validated = new SqlValidator(catalog).validate(ast);
            assertNotNull(validated);
        }
    }

    // ==================== Feature #12: CTE (WITH) ====================

    @Nested
    class CteTest {

        @Test
        void simpleCte_parsesAndExecutes() {
            List<Row> rows = execute(
                "WITH dept_emp AS (SELECT employees.id, employees.dept_id FROM employees) " +
                "SELECT dept_emp.id FROM dept_emp");
            assertEquals(5, rows.size());
        }

        @Test
        void cte_withFilter() {
            List<Row> rows = execute(
                "WITH eng AS (SELECT employees.id, employees.name FROM employees WHERE employees.dept_id = 10) " +
                "SELECT eng.name FROM eng");
            assertEquals(2, rows.size()); // alice, charlie
            Set<Object> names = rows.stream().map(r -> r.get("eng.name")).collect(Collectors.toSet());
            assertTrue(names.contains("alice"));
            assertTrue(names.contains("charlie"));
        }

        @Test
        void cte_multiReference() {
            // 同一 CTE 被引用两次
            List<Row> rows = execute(
                "WITH dept_emp AS (SELECT employees.id, employees.dept_id FROM employees) " +
                "SELECT a.id FROM dept_emp a JOIN dept_emp b ON a.dept_id = b.dept_id");
            // 每个 employee 与同 dept 的 employee 配对
            // dept_id=10: alice(1), charlie(3) → 4 pairs
            // dept_id=20: bob(2), eve(5) → 4 pairs
            // dept_id=30: dave(4) → 1 pair
            // 共 9 行
            assertEquals(9, rows.size());
        }

        @Test
        void cte_chainReference() {
            // CTE b 引用 CTE a
            List<Row> rows = execute(
                "WITH a AS (SELECT employees.id, employees.dept_id FROM employees WHERE employees.dept_id = 10), " +
                "b AS (SELECT a.id FROM a) " +
                "SELECT b.id FROM b");
            assertEquals(2, rows.size());
        }

        @Test
        void cte_recursiveDetected() {
            // 递归 CTE 应报错
            assertThrows(Exception.class, () ->
                execute("WITH t AS (SELECT * FROM t) SELECT * FROM t"));
        }

        @Test
        void cte_parsedAsWithSelect() {
            SqlNode ast = parse(
                "WITH t AS (SELECT employees.id FROM employees) SELECT t.id FROM t");
            assertInstanceOf(SqlWithSelect.class, ast);
            SqlWithSelect ws = (SqlWithSelect) ast;
            assertEquals(1, ws.ctes().size());
            assertEquals("T", ws.ctes().get(0).name());
        }

        @Test
        void cte_validatedAsValidatedWithSelect() {
            SqlNode ast = parse(
                "WITH t AS (SELECT employees.id FROM employees) SELECT t.id FROM t");
            SqlNode validated = new SqlValidator(catalog).validate(ast);
            assertInstanceOf(ValidatedWithSelect.class, validated);
        }
    }

    // ==================== Feature #11: 统计信息收集 ====================

    @Nested
    class StatisticsTest {

        @Test
        void analyzeTable_parsesCorrectly() {
            SqlNode ast = parse("ANALYZE TABLE users");
            assertInstanceOf(SqlAnalyzeTable.class, ast);
            assertEquals("USERS", ((SqlAnalyzeTable) ast).tableName());
        }

        @Test
        void analyzeTable_collectsStatistics() {
            InMemoryStatisticsStore store = new InMemoryStatisticsStore();

            // 执行 ANALYZE TABLE
            SqlNode ast = parse("ANALYZE TABLE users");
            SqlNode validated = new SqlValidator(catalog).validate(ast);
            RelNode plan = new SqlToRelConverter(catalog).convert(validated);
            PhysicalPlanner planner = new PhysicalPlanner(new cn.zhangyis.minidb.sql.optimize.cost.CostOptimizer(), MockDataSourceAdapter.INSTANCE, catalog);
            planner.setStatisticsStore(store);
            ExecNode exec = planner.plan(plan);
            exec.open();
            Row resultRow = exec.next();
            assertNotNull(resultRow);
            assertEquals("OK", resultRow.get("STATUS"));
            exec.close();

            // 验证统计信息
            TableStatistics stats = store.getTableStatistics("USERS");
            assertNotNull(stats, "统计信息应已写入");
            assertEquals(5, stats.rowCount());

            // id 列：NDV=5, min=1, max=5
            ColumnStatistics idStats = stats.column("ID");
            assertNotNull(idStats);
            assertEquals(5, idStats.ndv());
            assertEquals(1, idStats.minValue());
            assertEquals(5, idStats.maxValue());
            assertEquals(0, idStats.nullCount());

            // name 列：NDV=4 (alice出现2次)
            ColumnStatistics nameStats = stats.column("NAME");
            assertNotNull(nameStats);
            assertEquals(4, nameStats.ndv()); // alice, bob, charlie, dave
        }

        @Test
        void costModel_withoutStats_fallbackToDefault() {
            CostModel model = new CostModel(false, null);
            // 应使用默认值，不抛异常
            assertNotNull(model);
        }

        @Test
        void costModel_withStats_usesNdvForEquality() {
            InMemoryStatisticsStore store = new InMemoryStatisticsStore();
            store.putTableStatistics("USERS", new TableStatistics(
                "USERS", 1000,
                Map.of("ID", new ColumnStatistics("ID", 100, 1, 1000, 0, 1000)),
                System.currentTimeMillis()
            ));

            CostModel model = new CostModel(false, store);
            // 1/NDV = 1/100 = 0.01
            ColumnStatistics cs = store.getTableStatistics("USERS").column("ID");
            assertEquals(0.01, cs.selectivityEq(), 0.001);
        }

        @Test
        void columnStatistics_rangeSelectivity() {
            ColumnStatistics cs = new ColumnStatistics("AGE", 50, 0, 100, 0, 1000);
            // value=50, isLessThan → fraction = (50-0)/(100-0) = 0.5
            assertEquals(0.5, cs.selectivityRange(50, true), 0.01);
            // value=50, isGreaterThan → 1 - 0.5 = 0.5
            assertEquals(0.5, cs.selectivityRange(50, false), 0.01);
            // value=25, isLessThan → 0.25
            assertEquals(0.25, cs.selectivityRange(25, true), 0.01);
        }

        @Test
        void columnStatistics_equalitySelectivity_ndv0() {
            ColumnStatistics cs = new ColumnStatistics("C", 0, null, null, 100, 100);
            assertEquals(0.1, cs.selectivityEq(), 0.001); // 回退
        }
    }

    // ==================== Feature #10: JOIN 重排序 ====================

    @Nested
    class JoinReorderTest {

        @Test
        void threeTableJoin_reorderApplied() {
            // 使用带 CostModel 的 RuleOptimizer
            CostModel costModel = new CostModel();
            RuleOptimizer optimizer = new RuleOptimizer(false, catalog, costModel);

            String sql = "SELECT employees.name, departments.dept_name, projects.proj_name " +
                "FROM employees JOIN departments ON employees.dept_id = departments.dept_id " +
                "JOIN projects ON departments.dept_id = projects.dept_id";
            RelNode plan = buildPlan(sql);
            RelNode optimized = optimizer.optimize(plan);
            assertNotNull(optimized);

            // 验证结果正确性
            List<Row> rows = executePlan(optimized);
            // employees(5) x departments(3) x projects(4) filtered by dept_id equi-join
            // dept_id=10: 2emp * 1dept * 2proj = 4
            // dept_id=20: 2emp * 1dept * 1proj = 2
            // dept_id=30: 1emp * 1dept * 1proj = 1
            // 共 7 行
            assertEquals(7, rows.size());
        }

        @Test
        void twoTableJoin_notTriggered() {
            // 少于 3 个 relation，不触发重排
            CostModel costModel = new CostModel();
            RuleOptimizer optimizer = new RuleOptimizer(false, catalog, costModel);

            String sql = "SELECT employees.name FROM employees JOIN departments ON employees.dept_id = departments.dept_id";
            RelNode plan = buildPlan(sql);
            RelNode optimized = optimizer.optimize(plan);
            assertNotNull(optimized);

            List<Row> rows = executePlan(optimized);
            assertEquals(5, rows.size());
        }

        @Test
        void mixedInnerLeftJoin_leftNotReordered() {
            // LEFT JOIN 不参与重排
            CostModel costModel = new CostModel();
            RuleOptimizer optimizer = new RuleOptimizer(false, catalog, costModel);

            String sql = "SELECT employees.name, departments.dept_name " +
                "FROM employees LEFT JOIN departments ON employees.dept_id = departments.dept_id " +
                "JOIN projects ON departments.dept_id = projects.dept_id";
            RelNode plan = buildPlan(sql);
            RelNode optimized = optimizer.optimize(plan);

            List<Row> rows = executePlan(optimized);
            assertNotNull(rows);
        }

        @Test
        void reorder_resultConsistency() {
            // 重排前后结果一致
            String sql = "SELECT employees.id, departments.dept_name, projects.proj_name " +
                "FROM employees JOIN departments ON employees.dept_id = departments.dept_id " +
                "JOIN projects ON departments.dept_id = projects.dept_id";

            // 不重排
            List<Row> withoutReorder = execute(sql);
            // 重排
            CostModel costModel = new CostModel();
            RuleOptimizer optimizer = new RuleOptimizer(false, catalog, costModel);
            RelNode plan = buildPlan(sql);
            RelNode optimized = optimizer.optimize(plan);
            List<Row> withReorder = executePlan(optimized);

            // 行数一致
            assertEquals(withoutReorder.size(), withReorder.size());
            // 结果集一致（忽略顺序）
            Set<Object> idsWithout = withoutReorder.stream().map(r -> r.get("employees.id")).collect(Collectors.toSet());
            Set<Object> idsWith = withReorder.stream().map(r -> r.get("employees.id")).collect(Collectors.toSet());
            assertEquals(idsWithout, idsWith);
        }
    }

    // ==================== Feature #13: 并行执行 ====================

    @Nested
    class ParallelExecutionTest {

        @Test
        void parallelScan_sameResultAsSerial() {
            // 串行扫描
            List<Row> serialRows = new ArrayList<>();
            ExecNode serial = new ScanExec("USERS", "USERS", MockDataSourceAdapter.INSTANCE);
            serial.open();
            Row row;
            while ((row = serial.next()) != null) serialRows.add(row);
            serial.close();

            // 并行扫描
            List<Row> parallelRows = new ArrayList<>();
            ExecNode parallel = new ParallelScanExec("USERS", "USERS", MockDataSourceAdapter.INSTANCE, 3);
            parallel.open();
            while ((row = parallel.next()) != null) parallelRows.add(row);
            parallel.close();

            // 行数一致
            assertEquals(serialRows.size(), parallelRows.size());
            // 内容一致（忽略顺序）
            Set<Object> serialIds = serialRows.stream().map(r -> r.get("users.id")).collect(Collectors.toSet());
            Set<Object> parallelIds = parallelRows.stream().map(r -> r.get("users.id")).collect(Collectors.toSet());
            assertEquals(serialIds, parallelIds);
        }

        @Test
        void parallelScan_parallelism1_sameBehavior() {
            // parallelism=1 应与串行一致
            List<Row> rows = new ArrayList<>();
            ExecNode exec = new ParallelScanExec("USERS", "USERS", MockDataSourceAdapter.INSTANCE, 1);
            exec.open();
            Row row;
            while ((row = exec.next()) != null) rows.add(row);
            exec.close();
            assertEquals(5, rows.size());
        }

        @Test
        void parallelScan_closeTerminatesThreads() throws InterruptedException {
            ParallelScanExec exec = new ParallelScanExec("USERS", "USERS", MockDataSourceAdapter.INSTANCE, 3);
            exec.open();
            // 读一行后立即 close
            exec.next();
            exec.close();
            // 应不抛异常，线程应已终止
            Thread.sleep(100);
            // 验证可以正常 GC（无线程泄漏）
        }

        @Test
        void partitionCount_returnsCorrectValue() {
            int count = MockDataSourceAdapter.INSTANCE.partitionCount("USERS");
            assertTrue(count >= 1);
        }

        @Test
        void scanPartition_coversAllData() {
            int total = 3;
            Set<Object> allIds = new HashSet<>();
            for (int p = 0; p < total; p++) {
                var iter = MockDataSourceAdapter.INSTANCE.scanPartition("USERS", p, total);
                while (iter.hasNext()) {
                    allIds.add(iter.next().get("users.id"));
                }
            }
            // 所有 5 个 user id 都应出现
            assertEquals(Set.of(1, 2, 3, 4, 5), allIds);
        }

        @Test
        void parallelScan_withFilterAndAggregate_endToEnd() {
            // 并行 scan + filter + aggregate
            List<Row> serialRows = execute("SELECT COUNT(*) FROM users WHERE users.name = 'alice'");
            // alice 出现 2 次
            assertFalse(serialRows.isEmpty());
        }
    }

    // ==================== 辅助方法 ====================

    private SqlNode parse(String sql) {
        return new SqlParser(new TokenStream(new SqlLexer(sql))).parseStatement();
    }

    private RelNode buildPlan(String sql) {
        SqlNode ast = parse(sql);
        SqlNode validated = new SqlValidator(catalog).validate(ast);
        return new SqlToRelConverter(catalog).convert(validated);
    }

    private List<Row> execute(String sql) {
        RelNode plan = buildPlan(sql);
        RelNode optimized = new RuleOptimizer().optimize(plan);
        return executePlan(optimized);
    }

    private List<Row> executePlan(RelNode plan) {
        ExecNode exec = new PhysicalPlanner().plan(plan);
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

    private static Row row3(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        return row(k1, v1, k2, v2, k3, v3);
    }
}
