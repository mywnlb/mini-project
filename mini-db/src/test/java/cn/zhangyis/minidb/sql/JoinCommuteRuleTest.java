package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.ast.SqlBinaryOp;
import cn.zhangyis.minidb.sql.ast.SqlIdentifier;
import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.ColumnMeta;
import cn.zhangyis.minidb.sql.catalog.MockCatalog;
import cn.zhangyis.minidb.sql.catalog.TableMeta;
import cn.zhangyis.minidb.sql.exec.ExecNode;
import cn.zhangyis.minidb.sql.exec.MockDataSource;
import cn.zhangyis.minidb.sql.exec.NestedLoopJoinExec;
import cn.zhangyis.minidb.sql.exec.PhysicalPlanner;
import cn.zhangyis.minidb.sql.exec.Row;
import cn.zhangyis.minidb.sql.lexer.SqlLexer;
import cn.zhangyis.minidb.sql.lexer.TokenStream;
import cn.zhangyis.minidb.sql.optimize.JoinCommuteRule;
import cn.zhangyis.minidb.sql.optimize.RuleOptimizer;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.RelFilter;
import cn.zhangyis.minidb.sql.rel.RelJoin;
import cn.zhangyis.minidb.sql.rel.RelNode;
import cn.zhangyis.minidb.sql.rel.RelProject;
import cn.zhangyis.minidb.sql.rel.RelScan;
import cn.zhangyis.minidb.sql.types.SqlType;
import cn.zhangyis.minidb.sql.validation.SqlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinCommuteRuleTest {
    private final MockCatalog catalog = new MockCatalog();

    @BeforeEach
    void resetData() {
        MockDataSource.reset();
    }

    @Test
    void joinCommuteRuleSwapsSimpleEquiConditionOperands() {
        RelJoin join = extractJoin(buildLogical("SELECT * FROM orders JOIN users ON orders.user_id = users.id"));

        assertTrue(JoinCommuteRule.INSTANCE.matches(join));

        RelJoin commuted = assertInstanceOf(RelJoin.class, JoinCommuteRule.INSTANCE.apply(join));
        RelScan left = assertInstanceOf(RelScan.class, commuted.left());
        RelScan right = assertInstanceOf(RelScan.class, commuted.right());
        SqlBinaryOp condition = assertInstanceOf(SqlBinaryOp.class, commuted.condition());

        assertEquals("USERS", left.tableName());
        assertEquals("ORDERS", right.tableName());
        assertEquals("USERS.ID", assertInstanceOf(SqlIdentifier.class, condition.left()).name());
        assertEquals("ORDERS.USER_ID", assertInstanceOf(SqlIdentifier.class, condition.right()).name());
    }

    @Test
    void commuteBeforeAndAfterProduceSameResultsAcrossEquiJoinAlgorithms() {
        String sql = "SELECT * FROM orders JOIN users ON orders.user_id = users.id";
        RelNode original = buildLogical(sql);
        RelNode optimized = new RuleOptimizer().optimize(original);

        for (PhysicalPlanner.JoinAlgorithm algorithm : equiJoinAlgorithms()) {
            assertEquals(executeNormalized(original, algorithm), executeNormalized(optimized, algorithm));
        }
    }

    @Test
    void swappedInputOrderStillReturnsCorrectRowsAcrossEquiJoinAlgorithms() {
        RelNode ordersUsers = buildLogical("SELECT * FROM orders JOIN users ON orders.user_id = users.id");
        RelNode usersOrders = buildLogical("SELECT * FROM users JOIN orders ON orders.user_id = users.id");

        for (PhysicalPlanner.JoinAlgorithm algorithm : equiJoinAlgorithms()) {
            List<String> leftFirst = executeNormalized(ordersUsers, algorithm);
            List<String> rightFirst = executeNormalized(usersOrders, algorithm);

            assertEquals(5, leftFirst.size());
            assertEquals(leftFirst, rightFirst);
        }
    }

    @Test
    void nonEquiJoinFallsBackToNestedLoopInsteadOfExtractingKeys() {
        RelJoin join = extractJoin(buildLogical("SELECT * FROM orders JOIN users ON orders.amount > users.id"));
        PhysicalPlanner planner = new PhysicalPlanner();

        assertInstanceOf(NestedLoopJoinExec.class, planner.plan(join, PhysicalPlanner.JoinAlgorithm.HASH_JOIN));
        assertInstanceOf(NestedLoopJoinExec.class, planner.plan(join, PhysicalPlanner.JoinAlgorithm.SORT_MERGE));
    }

    @Test
    void sharedUnqualifiedColumnsStillUseEquiJoinKeys() {
        RelJoin join = extractJoin(buildLogical("SELECT * FROM users AS u JOIN users AS v ON id = id"));
        PhysicalPlanner planner = new PhysicalPlanner();

        assertEquals(5, executeNormalized(join, PhysicalPlanner.JoinAlgorithm.HASH_JOIN, "u", "v").size());
        assertEquals(5, executeNormalized(join, PhysicalPlanner.JoinAlgorithm.SORT_MERGE, "u", "v").size());

        assertTrue(!(planner.plan(join, PhysicalPlanner.JoinAlgorithm.HASH_JOIN) instanceof NestedLoopJoinExec));
        assertTrue(!(planner.plan(join, PhysicalPlanner.JoinAlgorithm.SORT_MERGE) instanceof NestedLoopJoinExec));
    }

    @Test
    void mixedQualifiedAndUnqualifiedPredicateIsNotReboundAcrossInputs() {
        RelJoin join = extractJoin(buildLogical("SELECT * FROM users AS u JOIN users AS v ON u.id = id"));
        PhysicalPlanner planner = new PhysicalPlanner();

        assertInstanceOf(NestedLoopJoinExec.class, planner.plan(join, PhysicalPlanner.JoinAlgorithm.HASH_JOIN));

        List<String> nested = executeNormalized(join, PhysicalPlanner.JoinAlgorithm.NESTED_LOOP, "u", "v");
        List<String> hashFallback = executeNormalized(join, PhysicalPlanner.JoinAlgorithm.HASH_JOIN, "u", "v");

        assertEquals(25, nested.size());
        assertEquals(nested, hashFallback);
    }

    @Test
    void sharedUnqualifiedColumnsRemainStableAfterManualCommute() {
        RelJoin original = extractJoin(buildLogical("SELECT * FROM users AS u JOIN users AS v ON id = id"));
        RelJoin commuted = assertInstanceOf(RelJoin.class, JoinCommuteRule.INSTANCE.apply(original));

        List<String> before = executeNormalized(original, PhysicalPlanner.JoinAlgorithm.HASH_JOIN, "u", "v");
        List<String> after = executeNormalized(commuted, PhysicalPlanner.JoinAlgorithm.HASH_JOIN, "u", "v");

        assertEquals(before, after);
    }

    @Test
    void nestedLoopDoesNotDegenerateSharedUnqualifiedEquiJoinIntoCartesianProduct() {
        RelJoin join = extractJoin(buildLogical("SELECT * FROM users AS u JOIN users AS v ON id = id"));

        List<String> rows = executeNormalized(join, PhysicalPlanner.JoinAlgorithm.NESTED_LOOP, "u", "v");

        assertEquals(5, rows.size());
    }

    @Test
    void conjunctiveBareSameNameEquiJoinKeepsEqualityPairingAndResidualFilter() {
        RelJoin join = extractJoin(buildLogical(
            "SELECT * FROM users AS u JOIN users AS v ON id = id AND u.name = 'alice'"));

        List<String> nested = executeNormalized(join, PhysicalPlanner.JoinAlgorithm.NESTED_LOOP, "u", "v");
        List<String> hashFallback = executeNormalized(join, PhysicalPlanner.JoinAlgorithm.HASH_JOIN, "u", "v");

        assertEquals(List.of("1|alice|1|alice", "4|alice|4|alice"), nested);
        assertEquals(nested, hashFallback);
    }

    @Test
    void multipleBareSameNameEquiTermsStayCorrectUnderFallback() {
        registerTable("t1", List.of(
            new ColumnMeta("id", SqlType.INT32, true),
            new ColumnMeta("code", SqlType.VARCHAR, false)));
        registerTable("t2", List.of(
            new ColumnMeta("id", SqlType.INT32, true),
            new ColumnMeta("code", SqlType.VARCHAR, false)));

        insertRow("t1", Map.of("t1.id", 1, "t1.code", "A"));
        insertRow("t1", Map.of("t1.id", 1, "t1.code", "B"));
        insertRow("t1", Map.of("t1.id", 2, "t1.code", "A"));
        insertRow("t2", Map.of("t2.id", 1, "t2.code", "A"));
        insertRow("t2", Map.of("t2.id", 1, "t2.code", "C"));
        insertRow("t2", Map.of("t2.id", 2, "t2.code", "A"));

        RelJoin join = extractJoin(buildLogical("SELECT * FROM t1 JOIN t2 ON id = id AND code = code"));

        List<String> nested = executeRows(join, PhysicalPlanner.JoinAlgorithm.NESTED_LOOP).stream()
            .map(row -> row.get("t1.id") + "|" + row.get("t1.code") + "|" + row.get("t2.id") + "|" + row.get("t2.code"))
            .sorted()
            .toList();

        assertEquals(List.of("1|A|1|A", "2|A|2|A"), nested);
    }

    @Test
    void qualifiedEquiJoinWithResidualFilterStillWorks() {
        registerTable("members", List.of(
            new ColumnMeta("id", SqlType.INT32, true),
            new ColumnMeta("status", SqlType.INT32, false)));

        insertRow("members", mapOf("members.id", 1, "members.status", 0));
        insertRow("members", mapOf("members.id", 2, "members.status", 1));
        insertRow("members", mapOf("members.id", 3, "members.status", 1));

        RelJoin join = extractJoin(buildLogical(
            "SELECT * FROM members u JOIN members v ON u.id = v.id AND v.status = 1"));

        List<String> rows = executeRows(join, PhysicalPlanner.JoinAlgorithm.HASH_JOIN).stream()
            .map(row -> row.get("u.id") + "|" + row.get("u.status") + "|" + row.get("v.id") + "|" + row.get("v.status"))
            .sorted()
            .toList();

        assertEquals(List.of("2|1|2|1", "3|1|3|1"), rows);
    }

    private List<PhysicalPlanner.JoinAlgorithm> equiJoinAlgorithms() {
        return List.of(
            PhysicalPlanner.JoinAlgorithm.HASH_JOIN,
            PhysicalPlanner.JoinAlgorithm.SORT_MERGE
        );
    }

    private SqlNode parse(String sql) {
        return new SqlParser(new TokenStream(new SqlLexer(sql))).parseStatement();
    }

    private RelNode buildLogical(String sql) {
        SqlNode ast = parse(sql);
        SqlNode validated = new SqlValidator(catalog).validate(ast);
        return new SqlToRelConverter(catalog).convert(validated);
    }

    private RelJoin extractJoin(RelNode plan) {
        if (plan instanceof RelJoin join) {
            return join;
        }
        if (plan instanceof RelProject project) {
            return extractJoin(project.input());
        }
        if (plan instanceof RelFilter filter) {
            return extractJoin(filter.input());
        }
        throw new IllegalArgumentException("Expected join plan, got: " + plan.getClass().getSimpleName());
    }

    private List<String> executeNormalized(RelNode plan, PhysicalPlanner.JoinAlgorithm algorithm) {
        ExecNode exec = new PhysicalPlanner().plan(plan, algorithm);
        List<String> rows = new ArrayList<>();
        exec.open();
        try {
            Row row;
            while ((row = exec.next()) != null) {
                rows.add(normalize(row));
            }
        } finally {
            exec.close();
        }
        rows.sort(String::compareTo);
        return rows;
    }

    private List<String> executeNormalized(RelNode plan, PhysicalPlanner.JoinAlgorithm algorithm,
                                           String leftAlias, String rightAlias) {
        List<String> rows = executeRows(plan, algorithm).stream()
            .map(row -> normalizeAliased(row, leftAlias, rightAlias))
            .toList();
        rows = new ArrayList<>(rows);
        rows.sort(String::compareTo);
        return rows;
    }

    private String normalize(Row row) {
        return row.get("orders.order_id")
            + "|" + row.get("orders.user_id")
            + "|" + row.get("orders.amount")
            + "|" + row.get("users.id")
            + "|" + row.get("users.name");
    }

    private String normalizeAliased(Row row, String leftAlias, String rightAlias) {
        Object leftId = row.get(leftAlias + ".id");
        Object leftName = row.get(leftAlias + ".name");
        Object rightId = row.get(rightAlias + ".id");
        Object rightName = row.get(rightAlias + ".name");
        return leftId + "|" + leftName + "|" + rightId + "|" + rightName;
    }

    private List<Row> executeRows(RelNode plan, PhysicalPlanner.JoinAlgorithm algorithm) {
        ExecNode exec = new PhysicalPlanner().plan(plan, algorithm);
        List<Row> rows = new ArrayList<>();
        exec.open();
        try {
            Row row;
            while ((row = exec.next()) != null) {
                rows.add(row);
            }
        } finally {
            exec.close();
        }
        return rows;
    }

    private void registerTable(String tableName, List<ColumnMeta> columns) {
        catalog.createTable(TableMeta.of(tableName, columns, 100));
    }

    private void insertRow(String tableName, Map<String, Object> columns) {
        MockDataSource.insertRow(tableName, new Row(columns));
    }

    private Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(k1, v1);
        values.put(k2, v2);
        return values;
    }
}
