package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.MockCatalog;
import cn.zhangyis.minidb.sql.exec.*;
import cn.zhangyis.minidb.sql.lexer.SqlLexer;
import cn.zhangyis.minidb.sql.lexer.TokenStream;
import cn.zhangyis.minidb.sql.optimize.RuleOptimizer;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.RelJoin;
import cn.zhangyis.minidb.sql.rel.RelNode;
import cn.zhangyis.minidb.sql.rel.RelProject;
import cn.zhangyis.minidb.sql.ast.JoinType;
import cn.zhangyis.minidb.sql.validation.SqlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LEFT / RIGHT / FULL / CROSS OUTER JOIN 测试
 *
 * MockDataSource 数据:
 *   users:  (1,alice), (2,bob), (3,charlie), (4,alice), (5,dave)
 *   orders: (101,1,250), (102,2,130), (103,1,80), (104,3,500), (105,5,60)
 *
 * users.id=4 (alice) 在 orders 中无匹配 → LEFT JOIN 产生 NULL 填充行
 * 所有 orders 都有对应 user → RIGHT JOIN 无 NULL 填充行
 */
class OuterJoinTest {
    private final MockCatalog catalog = new MockCatalog();

    @BeforeEach
    void resetData() {
        MockDataSource.reset();
    }

    // ==================== 逻辑计划结构测试 ====================

    @Test
    void leftJoin_planContainsLeftJoinType() {
        RelNode plan = buildPlan(
            "SELECT users.id, users.name FROM users LEFT JOIN orders ON users.id = orders.user_id");
        assertInstanceOf(RelProject.class, plan);
        RelJoin join = findJoin(plan);
        assertNotNull(join, "计划中应包含 RelJoin");
        assertEquals(JoinType.LEFT, join.joinType());
    }

    @Test
    void rightJoin_planContainsRightJoinType() {
        RelNode plan = buildPlan(
            "SELECT users.id FROM users RIGHT JOIN orders ON users.id = orders.user_id");
        RelJoin join = findJoin(plan);
        assertNotNull(join);
        assertEquals(JoinType.RIGHT, join.joinType());
    }

    @Test
    void fullJoin_planContainsFullJoinType() {
        RelNode plan = buildPlan(
            "SELECT users.id FROM users FULL JOIN orders ON users.id = orders.user_id");
        RelJoin join = findJoin(plan);
        assertNotNull(join);
        assertEquals(JoinType.FULL, join.joinType());
    }

    @Test
    void fullOuterJoin_outerKeywordOptional() {
        RelNode plan = buildPlan(
            "SELECT users.id FROM users FULL OUTER JOIN orders ON users.id = orders.user_id");
        RelJoin join = findJoin(plan);
        assertNotNull(join);
        assertEquals(JoinType.FULL, join.joinType());
    }

    // ==================== LEFT JOIN 执行测试 ====================

    @Test
    void leftJoin_preservesAllLeftRows() {
        // users 有 5 行，user_id=4 无 orders 匹配
        // user_id=1 匹配 2 条 orders → 共 6 行
        List<Row> rows = execute(
            "SELECT users.id, orders.order_id FROM users LEFT JOIN orders ON users.id = orders.user_id");
        assertEquals(6, rows.size());

        // user_id=4 对应的行 order_id 应为 null
        List<Row> user4Rows = rows.stream()
            .filter(r -> Integer.valueOf(4).equals(r.get("users.id")))
            .toList();
        assertEquals(1, user4Rows.size());
        assertNull(user4Rows.get(0).get("orders.order_id"), "LEFT JOIN 未匹配行的右侧列应为 NULL");
    }

    @Test
    void leftJoin_matchedRowsCorrect() {
        List<Row> rows = execute(
            "SELECT users.id, orders.order_id FROM users LEFT JOIN orders ON users.id = orders.user_id");

        // user_id=1 应有 2 个 order: 101, 103
        List<Object> user1Orders = rows.stream()
            .filter(r -> Integer.valueOf(1).equals(r.get("users.id")))
            .map(r -> r.get("orders.order_id"))
            .toList();
        assertEquals(2, user1Orders.size());
        assertTrue(user1Orders.contains(101));
        assertTrue(user1Orders.contains(103));
    }

    // ==================== RIGHT JOIN 执行测试 ====================

    @Test
    void rightJoin_preservesAllRightRows() {
        // 所有 orders 都有对应 user，所以 RIGHT JOIN = INNER JOIN = 5 行
        List<Row> rows = execute(
            "SELECT users.id, orders.order_id FROM users RIGHT JOIN orders ON users.id = orders.user_id");
        assertEquals(5, rows.size());

        // 每个 order_id 都应出现
        Set<Object> orderIds = rows.stream().map(r -> r.get("orders.order_id")).collect(Collectors.toSet());
        assertTrue(orderIds.contains(101));
        assertTrue(orderIds.contains(102));
        assertTrue(orderIds.contains(103));
        assertTrue(orderIds.contains(104));
        assertTrue(orderIds.contains(105));
    }

    // ==================== FULL JOIN 执行测试 ====================

    @Test
    void fullJoin_preservesBothSides() {
        // INNER 产生 5 行 + user_id=4 无匹配 → 共 6 行
        // 所有 orders 都有匹配，无右侧 orphan
        List<Row> rows = execute(
            "SELECT users.id, orders.order_id FROM users FULL JOIN orders ON users.id = orders.user_id");
        assertEquals(6, rows.size());

        // user_id=4 应有 NULL order_id
        List<Row> user4Rows = rows.stream()
            .filter(r -> Integer.valueOf(4).equals(r.get("users.id")))
            .toList();
        assertEquals(1, user4Rows.size());
        assertNull(user4Rows.get(0).get("orders.order_id"));
    }

    // ==================== INNER JOIN 回归测试 ====================

    @Test
    void innerJoin_unchanged() {
        List<Row> rows = execute(
            "SELECT users.id, orders.order_id FROM users JOIN orders ON users.id = orders.user_id");
        // user_id=4 无匹配 → 被排除，user_id=1 匹配 2 条 → 共 5 行
        assertEquals(5, rows.size());

        // user_id=4 不应出现
        boolean hasUser4 = rows.stream().anyMatch(r -> Integer.valueOf(4).equals(r.get("users.id")));
        assertFalse(hasUser4, "INNER JOIN 不应包含无匹配的行");
    }

    @Test
    void innerJoin_explicitKeyword() {
        List<Row> rows = execute(
            "SELECT users.id, orders.order_id FROM users INNER JOIN orders ON users.id = orders.user_id");
        assertEquals(5, rows.size());
    }

    // ==================== CROSS JOIN 测试 ====================

    @Test
    void crossJoin_cartesianProduct() {
        List<Row> rows = execute(
            "SELECT users.id, orders.order_id FROM users CROSS JOIN orders");
        // 5 users × 5 orders = 25
        assertEquals(25, rows.size());
    }

    // ==================== LEFT JOIN + WHERE 过滤测试 ====================

    @Test
    void leftJoin_whereFilterAppliedAfterJoin() {
        // WHERE 在 JOIN 后执行，所以 NULL 填充行也会被 WHERE 过滤
        List<Row> rows = execute(
            "SELECT users.id, orders.order_id FROM users LEFT JOIN orders ON users.id = orders.user_id "
            + "WHERE orders.order_id IS NOT NULL");
        // 过滤掉 user_id=4 的 NULL 行 → 退化为 INNER JOIN 结果 = 5 行
        assertEquals(5, rows.size());
    }

    // ==================== 多算法一致性测试 ====================

    @Test
    void leftJoin_allAlgorithmsProduceSameRowCount() {
        String sql = "SELECT users.id, orders.order_id FROM users LEFT JOIN orders ON users.id = orders.user_id";
        RelNode plan = buildPlan(sql);

        for (PhysicalPlanner.JoinAlgorithm algo : List.of(
                PhysicalPlanner.JoinAlgorithm.NESTED_LOOP,
                PhysicalPlanner.JoinAlgorithm.HASH_JOIN,
                PhysicalPlanner.JoinAlgorithm.SORT_MERGE)) {
            List<Row> rows = executeWithAlgo(plan, algo);
            assertEquals(6, rows.size(),
                "LEFT JOIN with " + algo + " should produce 6 rows");

            // 验证 user_id=4 存在且 order_id 为 NULL
            List<Row> user4 = rows.stream()
                .filter(r -> Integer.valueOf(4).equals(r.get("users.id")))
                .toList();
            assertEquals(1, user4.size(), algo + ": user_id=4 should appear once");
            assertNull(user4.get(0).get("orders.order_id"), algo + ": order_id should be NULL");
        }
    }

    @Test
    void rightJoin_allAlgorithmsProduceSameRowCount() {
        String sql = "SELECT users.id, orders.order_id FROM users RIGHT JOIN orders ON users.id = orders.user_id";
        RelNode plan = buildPlan(sql);

        for (PhysicalPlanner.JoinAlgorithm algo : List.of(
                PhysicalPlanner.JoinAlgorithm.NESTED_LOOP,
                PhysicalPlanner.JoinAlgorithm.HASH_JOIN,
                PhysicalPlanner.JoinAlgorithm.SORT_MERGE)) {
            List<Row> rows = executeWithAlgo(plan, algo);
            assertEquals(5, rows.size(),
                "RIGHT JOIN with " + algo + " should produce 5 rows");
        }
    }

    @Test
    void fullJoin_allAlgorithmsProduceSameRowCount() {
        String sql = "SELECT users.id, orders.order_id FROM users FULL JOIN orders ON users.id = orders.user_id";
        RelNode plan = buildPlan(sql);

        for (PhysicalPlanner.JoinAlgorithm algo : List.of(
                PhysicalPlanner.JoinAlgorithm.NESTED_LOOP,
                PhysicalPlanner.JoinAlgorithm.HASH_JOIN,
                PhysicalPlanner.JoinAlgorithm.SORT_MERGE)) {
            List<Row> rows = executeWithAlgo(plan, algo);
            assertEquals(6, rows.size(),
                "FULL JOIN with " + algo + " should produce 6 rows");
        }
    }

    // ==================== helpers ====================

    private SqlNode parse(String sql) {
        return new SqlParser(new TokenStream(new SqlLexer(sql))).parseStatement();
    }

    private RelNode buildPlan(String sql) {
        SqlNode ast = parse(sql);
        SqlNode validated = new SqlValidator(catalog).validate(ast);
        RelNode logical = new SqlToRelConverter(catalog).convert(validated);
        return new RuleOptimizer().optimize(logical);
    }

    private List<Row> execute(String sql) {
        ExecNode exec = new PhysicalPlanner().plan(buildPlan(sql));
        return collectRows(exec);
    }

    private List<Row> executeWithAlgo(RelNode plan, PhysicalPlanner.JoinAlgorithm algo) {
        ExecNode exec = new PhysicalPlanner().plan(plan, algo);
        return collectRows(exec);
    }

    private List<Row> collectRows(ExecNode exec) {
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

    private RelJoin findJoin(RelNode node) {
        if (node instanceof RelJoin join) return join;
        if (node instanceof RelProject p) return findJoin(p.input());
        if (node instanceof cn.zhangyis.minidb.sql.rel.RelFilter f) return findJoin(f.input());
        if (node instanceof cn.zhangyis.minidb.sql.rel.RelSort s) return findJoin(s.input());
        if (node instanceof cn.zhangyis.minidb.sql.rel.RelAggregate a) return findJoin(a.input());
        if (node instanceof cn.zhangyis.minidb.sql.rel.RelDistinct d) return findJoin(d.input());
        return null;
    }
}
