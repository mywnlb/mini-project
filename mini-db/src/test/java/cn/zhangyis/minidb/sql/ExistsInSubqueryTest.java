package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.MockCatalog;
import cn.zhangyis.minidb.sql.exec.*;
import cn.zhangyis.minidb.sql.lexer.*;
import cn.zhangyis.minidb.sql.optimize.RuleOptimizer;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.RelNode;
import cn.zhangyis.minidb.sql.validation.SqlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EXISTS / IN 子查询端到端测试
 *
 * <p>测试数据（MockDataSource）：
 * <pre>
 * users:  (1,alice) (2,bob) (3,charlie) (4,alice) (5,dave)
 * orders: (101,1,250) (102,2,130) (103,1,80) (104,3,500) (105,5,60)
 * </pre>
 * user_id=4 没有订单。
 */
class ExistsInSubqueryTest {
    private final MockCatalog catalog = new MockCatalog();

    @BeforeEach
    void resetData() {
        MockDataSource.reset();
    }

    // ==================== EXISTS ====================

    /**
     * EXISTS 基本测试：有订单的用户
     * user_id 1,2,3,5 有订单 → 返回 4 行
     */
    @Test
    void existsBasic() {
        List<Row> rows = execute(
            "SELECT * FROM users WHERE EXISTS (SELECT 1 FROM orders WHERE orders.user_id = users.id)");
        assertEquals(4, rows.size());
        Set<Integer> ids = rows.stream().map(r -> (int) r.get("id")).collect(Collectors.toSet());
        assertEquals(Set.of(1, 2, 3, 5), ids);
    }

    /**
     * NOT EXISTS：没有订单的用户
     * 只有 user_id=4 没有订单
     */
    @Test
    void notExistsBasic() {
        List<Row> rows = execute(
            "SELECT * FROM users WHERE NOT EXISTS (SELECT 1 FROM orders WHERE orders.user_id = users.id)");
        assertEquals(1, rows.size());
        assertEquals(4, rows.get(0).get("id"));
    }

    /**
     * EXISTS 子查询无结果 → 所有外层行都被过滤
     */
    @Test
    void existsEmptySubquery() {
        List<Row> rows = execute(
            "SELECT * FROM users WHERE EXISTS (SELECT 1 FROM orders WHERE orders.amount > 9999)");
        assertEquals(0, rows.size());
    }

    // ==================== IN (SELECT ...) ====================

    /**
     * IN 子查询基本测试：amount > 200 的订单对应 user_id = 1, 3
     */
    @Test
    void inSubqueryBasic() {
        List<Row> rows = execute(
            "SELECT * FROM users WHERE id IN (SELECT user_id FROM orders WHERE amount > 200)");
        Set<Integer> ids = rows.stream().map(r -> (int) r.get("id")).collect(Collectors.toSet());
        assertEquals(Set.of(1, 3), ids);
    }

    /**
     * NOT IN 子查询：不在 orders 中的用户 → user_id=4
     */
    @Test
    void notInSubqueryBasic() {
        List<Row> rows = execute(
            "SELECT * FROM users WHERE id NOT IN (SELECT user_id FROM orders)");
        assertEquals(1, rows.size());
        assertEquals(4, rows.get(0).get("id"));
    }

    /**
     * IN 子查询空结果 → 无匹配
     */
    @Test
    void inSubqueryEmptyResult() {
        List<Row> rows = execute(
            "SELECT * FROM users WHERE id IN (SELECT user_id FROM orders WHERE amount > 9999)");
        assertEquals(0, rows.size());
    }

    /**
     * IN 子查询 + 外层额外条件组合
     */
    @Test
    void inSubqueryWithOuterCondition() {
        List<Row> rows = execute(
            "SELECT * FROM users WHERE id IN (SELECT user_id FROM orders) AND name = 'alice'");
        // alice 有 id=1（有订单）和 id=4（无订单），只有 id=1 匹配
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).get("id"));
    }

    // ==================== 辅助方法 ====================

    private RelNode buildPlan(String sql) {
        SqlNode ast = new SqlParser(new TokenStream(new SqlLexer(sql))).parseStatement();
        SqlNode validated = new SqlValidator(catalog).validate(ast);
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
