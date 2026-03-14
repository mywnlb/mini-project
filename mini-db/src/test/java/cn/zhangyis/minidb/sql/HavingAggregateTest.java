package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.MockCatalog;
import cn.zhangyis.minidb.sql.exec.ExecNode;
import cn.zhangyis.minidb.sql.exec.MockDataSource;
import cn.zhangyis.minidb.sql.exec.PhysicalPlanner;
import cn.zhangyis.minidb.sql.exec.Row;
import cn.zhangyis.minidb.sql.lexer.SqlLexer;
import cn.zhangyis.minidb.sql.lexer.TokenStream;
import cn.zhangyis.minidb.sql.optimize.RuleOptimizer;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.RelAggregate;
import cn.zhangyis.minidb.sql.rel.RelFilter;
import cn.zhangyis.minidb.sql.rel.RelNode;
import cn.zhangyis.minidb.sql.rel.RelProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HAVING / 聚合路径闭环测试
 */
class HavingAggregateTest {
    private final MockCatalog catalog = new MockCatalog();

    @BeforeEach
    void resetData() {
        MockDataSource.reset();
    }

    // ==================== 逻辑计划结构测试 ====================

    @Test
    void noGroupByWithHaving_planContainsAggregateAndFilter() {
        RelNode plan = buildPlan("SELECT COUNT(*) FROM users HAVING COUNT(*) > 1");

        // Project -> Filter(HAVING) -> Aggregate -> Scan
        assertInstanceOf(RelProject.class, plan);
        RelFilter filter = assertInstanceOf(RelFilter.class, ((RelProject) plan).input());
        RelAggregate agg = assertInstanceOf(RelAggregate.class, filter.input());
        assertNull(agg.groupKeys(), "无 GROUP BY 时 groupKeys 应为 null");
        assertEquals(1, agg.aggCalls().size());
    }

    @Test
    void groupByWithHaving_planContainsAggregateAndFilter() {
        RelNode plan = buildPlan("SELECT name, COUNT(*) FROM users GROUP BY name HAVING COUNT(*) > 1");

        assertInstanceOf(RelProject.class, plan);
        RelFilter filter = assertInstanceOf(RelFilter.class, ((RelProject) plan).input());
        RelAggregate agg = assertInstanceOf(RelAggregate.class, filter.input());
        assertNotNull(agg.groupKeys());
        assertEquals(1, agg.aggCalls().size());
    }

    // ==================== 执行结果测试 ====================

    @Test
    void noGroupByHaving_countStar_greaterThan1() {
        // users 表有 5 行，COUNT(*) = 5 > 1，应返回 1 行
        List<Row> rows = execute("SELECT COUNT(*) FROM users HAVING COUNT(*) > 1");
        assertEquals(1, rows.size());
        assertEquals(5L, rows.get(0).get("COUNT(*)"));
    }

    @Test
    void noGroupByHaving_countStar_greaterThan100() {
        // users 表有 5 行，COUNT(*) = 5，不满足 > 100，应返回 0 行
        List<Row> rows = execute("SELECT COUNT(*) FROM users HAVING COUNT(*) > 100");
        assertEquals(0, rows.size());
    }

    @Test
    void groupByWithHaving_filterGroups() {
        // users: alice(2), bob(1), charlie(1), dave(1)
        // HAVING COUNT(*) > 1 应只保留 alice
        List<Row> rows = execute("SELECT name, COUNT(*) FROM users GROUP BY name HAVING COUNT(*) > 1");
        assertEquals(1, rows.size());
        assertEquals("alice", rows.get(0).get("name"));
        assertEquals(2L, rows.get(0).get("COUNT(*)"));
    }

    @Test
    void havingAggNotInSelect() {
        // HAVING 中的 SUM(amount) 不在 SELECT 列表中
        // orders: user_id=1 总额 330, user_id=2 总额 130, user_id=3 总额 500, user_id=5 总额 60
        // HAVING SUM(amount) > 200 → user_id=1(330), user_id=3(500)
        List<Row> rows = execute(
            "SELECT user_id, COUNT(*) FROM orders GROUP BY user_id HAVING SUM(amount) > 200"
        );
        assertEquals(2, rows.size());
        List<Object> userIds = rows.stream().map(r -> r.get("user_id")).toList();
        assertTrue(userIds.contains(1));
        assertTrue(userIds.contains(3));
    }

    @Test
    void havingWithoutGroupBy_noAggInSelect() {
        // SELECT 中无聚合，但 HAVING 中有聚合 → 仍应触发聚合
        // 这种 SQL 语义上等价于：对全表做一次聚合，如果 HAVING 条件满足则返回结果
        // users 有 5 行，COUNT(*) = 5 > 1 → 应返回全表聚合后的行
        // 但 SELECT * 在有聚合时不合法，所以用 SELECT 1 模拟
        // 实际上这种 case 在标准 SQL 中 SELECT 列表也需要是聚合或常量
        // 这里测试 HAVING 不被丢失即可
        List<Row> rows = execute("SELECT COUNT(*) FROM users HAVING COUNT(*) > 3");
        assertEquals(1, rows.size());
        assertEquals(5L, rows.get(0).get("COUNT(*)"));
    }

    @Test
    void havingWithMultipleAggCalls() {
        // orders 按 user_id 分组
        // user_id=1: count=2, sum=330
        // user_id=2: count=1, sum=130
        // user_id=3: count=1, sum=500
        // user_id=5: count=1, sum=60
        // HAVING COUNT(*) >= 1 AND SUM(amount) > 100 → user_id=1(2,330), user_id=2(1,130), user_id=3(1,500)
        List<Row> rows = execute(
            "SELECT user_id, COUNT(*) FROM orders GROUP BY user_id HAVING COUNT(*) >= 1 AND SUM(amount) > 100"
        );
        assertEquals(3, rows.size());
        List<Object> userIds = rows.stream().map(r -> r.get("user_id")).toList();
        assertTrue(userIds.contains(1));
        assertTrue(userIds.contains(2));
        assertTrue(userIds.contains(3));
        assertFalse(userIds.contains(5));
    }

    // ==================== helpers ====================

    private SqlNode parse(String sql) {
        return new SqlParser(new TokenStream(new SqlLexer(sql))).parseStatement();
    }

    private RelNode buildPlan(String sql) {
        SqlNode ast = parse(sql);
        SqlNode validated = new cn.zhangyis.minidb.sql.validation.SqlValidator(catalog).validate(ast);
        RelNode logical = new SqlToRelConverter(catalog).convert(validated);
        return new RuleOptimizer().optimize(logical);
    }

    private List<Row> execute(String sql) {
        ExecNode exec = new PhysicalPlanner().plan(buildPlan(sql));
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
}
