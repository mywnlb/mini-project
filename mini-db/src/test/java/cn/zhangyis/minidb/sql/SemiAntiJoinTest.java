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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Semi-Join / Anti-Join 优化测试
 *
 * MockDataSource 数据:
 *   users:  (1,alice), (2,bob), (3,charlie), (4,alice), (5,dave)
 *   orders: (101,1,250), (102,2,130), (103,1,80), (104,3,500), (105,5,60)
 *
 * orders 中有订单的 user_id: {1, 2, 3, 5}
 * 无订单的 user_id: {4}
 */
class SemiAntiJoinTest {
    private final MockCatalog catalog = new MockCatalog();

    @BeforeEach
    void resetData() {
        MockDataSource.reset();
    }

    // ==================== 逻辑计划结构测试 ====================

    @Test
    void inSubquery_planContainsSemiJoin() {
        RelNode plan = buildPlan(
            "SELECT * FROM users WHERE id IN (SELECT user_id FROM orders)");
        // 预期: Project → SemiJoin(left=Scan("users"), right=Scan("orders"))
        assertInstanceOf(RelProject.class, plan);
        RelSemiJoin semi = findNode(plan, RelSemiJoin.class);
        assertNotNull(semi, "计划中应包含 RelSemiJoin");
    }

    @Test
    void notInSubquery_planContainsAntiJoin() {
        RelNode plan = buildPlan(
            "SELECT * FROM users WHERE id NOT IN (SELECT user_id FROM orders)");
        assertInstanceOf(RelProject.class, plan);
        RelAntiJoin anti = findNode(plan, RelAntiJoin.class);
        assertNotNull(anti, "计划中应包含 RelAntiJoin");
    }

    @Test
    void existsSubquery_planContainsSemiJoin() {
        RelNode plan = buildPlan(
            "SELECT * FROM users WHERE EXISTS (SELECT * FROM orders WHERE orders.user_id = users.id)");
        RelSemiJoin semi = findNode(plan, RelSemiJoin.class);
        assertNotNull(semi, "EXISTS 应转换为 RelSemiJoin");
    }

    @Test
    void notExistsSubquery_planContainsAntiJoin() {
        RelNode plan = buildPlan(
            "SELECT * FROM users WHERE NOT EXISTS (SELECT * FROM orders WHERE orders.user_id = users.id)");
        RelAntiJoin anti = findNode(plan, RelAntiJoin.class);
        assertNotNull(anti, "NOT EXISTS 应转换为 RelAntiJoin");
    }

    // ==================== Semi-Join (IN) 执行测试 ====================

    @Test
    void inSubquery_returnsMatchedUsers() {
        // orders 中有订单的 user_id: {1, 2, 3, 5} → 4 个 users
        List<Row> rows = execute(
            "SELECT * FROM users WHERE id IN (SELECT user_id FROM orders)");
        assertEquals(4, rows.size());

        Set<Object> ids = rows.stream().map(r -> r.get("users.id")).collect(Collectors.toSet());
        assertTrue(ids.contains(1));
        assertTrue(ids.contains(2));
        assertTrue(ids.contains(3));
        assertTrue(ids.contains(5));
        assertFalse(ids.contains(4), "user_id=4 无订单，不应出现");
    }

    // ==================== Anti-Join (NOT IN) 执行测试 ====================

    @Test
    void notInSubquery_returnsUnmatchedUsers() {
        // 只有 user_id=4 无订单
        List<Row> rows = execute(
            "SELECT * FROM users WHERE id NOT IN (SELECT user_id FROM orders)");
        assertEquals(1, rows.size());
        assertEquals(4, rows.get(0).get("users.id"));
    }

    // ==================== EXISTS 执行测试 ====================

    @Test
    void existsSubquery_sameAsIn() {
        List<Row> rows = execute(
            "SELECT * FROM users WHERE EXISTS (SELECT * FROM orders WHERE orders.user_id = users.id)");
        assertEquals(4, rows.size());

        Set<Object> ids = rows.stream().map(r -> r.get("users.id")).collect(Collectors.toSet());
        assertEquals(Set.of(1, 2, 3, 5), ids);
    }

    @Test
    void notExistsSubquery_sameAsNotIn() {
        List<Row> rows = execute(
            "SELECT * FROM users WHERE NOT EXISTS (SELECT * FROM orders WHERE orders.user_id = users.id)");
        assertEquals(1, rows.size());
        assertEquals(4, rows.get(0).get("users.id"));
    }

    // ==================== 混合条件测试 ====================

    @Test
    void inSubquery_withAdditionalFilter() {
        // IN + WHERE name = 'alice': user_id=1(alice) 有订单 → 1 行
        // user_id=4(alice) 无订单 → 被 IN 过滤
        List<Row> rows = execute(
            "SELECT * FROM users WHERE id IN (SELECT user_id FROM orders) AND name = 'alice'");
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).get("users.id"));
    }

    // ==================== 回归测试 ====================

    @Test
    void plainSelect_unaffected() {
        List<Row> rows = execute("SELECT * FROM users");
        assertEquals(5, rows.size());
    }

    @Test
    void innerJoin_unaffected() {
        List<Row> rows = execute(
            "SELECT users.id, orders.order_id FROM users JOIN orders ON users.id = orders.user_id");
        assertEquals(5, rows.size());
    }

    // ==================== 聚合 + Semi-Join ====================

    @Test
    void countWithInSubquery() {
        List<Row> rows = execute(
            "SELECT COUNT(*) FROM users WHERE id IN (SELECT user_id FROM orders)");
        assertEquals(1, rows.size());
        // COUNT(*) = 4 (有订单的 users 数)
        Object count = rows.get(0).get("COUNT(*)");
        assertEquals(4L, ((Number) count).longValue());
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
            while ((row = exec.next()) != null) {
                rows.add(row);
            }
        } finally {
            exec.close();
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private <T> T findNode(RelNode node, Class<T> type) {
        if (type.isInstance(node)) return (T) node;
        if (node instanceof RelProject p) return findNode(p.input(), type);
        if (node instanceof RelFilter f) return findNode(f.input(), type);
        if (node instanceof RelSort s) return findNode(s.input(), type);
        if (node instanceof RelAggregate a) return findNode(a.input(), type);
        if (node instanceof RelDistinct d) return findNode(d.input(), type);
        if (node instanceof RelSemiJoin semi) {
            T found = findNode(semi.left(), type);
            return found != null ? found : findNode(semi.right(), type);
        }
        if (node instanceof RelAntiJoin anti) {
            T found = findNode(anti.left(), type);
            return found != null ? found : findNode(anti.right(), type);
        }
        return null;
    }
}
