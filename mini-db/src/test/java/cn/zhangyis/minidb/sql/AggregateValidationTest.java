package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.MockCatalog;
import cn.zhangyis.minidb.sql.lexer.SqlLexer;
import cn.zhangyis.minidb.sql.lexer.TokenStream;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.validation.SqlValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 聚合语义校验测试
 *
 * MockCatalog 提供：
 *   users(id, name)
 *   orders(order_id, user_id, amount)
 */
class AggregateValidationTest {
    private final MockCatalog catalog = new MockCatalog();

    // ==================== 应拒绝的 SQL ====================

    @Test
    void implicitAgg_nonAggColumnWithoutGroupBy() {
        // Bug 2: SELECT name, COUNT(*) FROM users → name 不在 GROUP BY
        assertValidationFails(
            "SELECT name, COUNT(*) FROM users",
            "name");
    }

    @Test
    void mixedExpression_nonAggColumnInGroupBy() {
        // Bug 1: COUNT(*) + amount 中 amount 不在 GROUP BY
        assertValidationFails(
            "SELECT COUNT(*) + amount FROM orders GROUP BY user_id",
            "amount");
    }

    @Test
    void aggInWhere_rejected() {
        // Bug 3: WHERE 中不能使用聚合函数
        assertValidationFails(
            "SELECT * FROM users WHERE COUNT(*) > 5",
            "WHERE");
    }

    @Test
    void havingNonAggColumn_rejected() {
        // Bug 4: HAVING 中 amount 不在 GROUP BY
        assertValidationFails(
            "SELECT user_id, COUNT(*) FROM orders GROUP BY user_id HAVING amount = 100",
            "amount");
    }

    @Test
    void orderByNonAggColumn_rejected() {
        // Bug 5: ORDER BY amount 不在 GROUP BY
        assertValidationFails(
            "SELECT user_id, COUNT(*) FROM orders GROUP BY user_id ORDER BY amount",
            "amount");
    }

    @Test
    void nestedAgg_rejected() {
        // Bug 6: COUNT(SUM(amount)) 嵌套聚合
        assertValidationFails(
            "SELECT COUNT(SUM(amount)) FROM orders GROUP BY user_id",
            "nested");
    }

    @Test
    void mixedExpression_multiplyNonAggColumn() {
        // SUM(amount) * user_id 中 user_id 不在 GROUP BY
        assertValidationFails(
            "SELECT SUM(amount) * user_id FROM orders GROUP BY order_id",
            "user_id");
    }

    // ==================== 应通过的 SQL ====================

    @Test
    void normalGroupBy_passes() {
        assertValidationPasses(
            "SELECT user_id, COUNT(*) FROM orders GROUP BY user_id");
    }

    @Test
    void pureAggNoGroupBy_passes() {
        assertValidationPasses("SELECT COUNT(*) FROM users");
    }

    @Test
    void multipleAggsNoGroupBy_passes() {
        assertValidationPasses("SELECT SUM(amount), COUNT(*) FROM orders");
    }

    @Test
    void aggExpressionWithGroupByColumn_passes() {
        // SUM(amount) * 2 — 纯聚合运算，合法
        assertValidationPasses(
            "SELECT user_id, SUM(amount) * 2 FROM orders GROUP BY user_id");
    }

    @Test
    void literalWithAgg_passes() {
        assertValidationPasses("SELECT 1, COUNT(*) FROM users");
    }

    @Test
    void havingWithAggOnly_passes() {
        assertValidationPasses(
            "SELECT user_id, COUNT(*) FROM orders GROUP BY user_id HAVING COUNT(*) > 1");
    }

    @Test
    void orderByGroupByColumn_passes() {
        assertValidationPasses(
            "SELECT user_id, COUNT(*) FROM orders GROUP BY user_id ORDER BY user_id");
    }

    @Test
    void orderByAgg_passes() {
        assertValidationPasses(
            "SELECT user_id, COUNT(*) FROM orders GROUP BY user_id ORDER BY COUNT(*)");
    }

    // ==================== helpers ====================

    private SqlNode parse(String sql) {
        return new SqlParser(new TokenStream(new SqlLexer(sql))).parseStatement();
    }

    private void assertValidationFails(String sql, String expectedMessagePart) {
        SqlNode ast = parse(sql);
        Exception ex = assertThrows(RuntimeException.class, () -> new SqlValidator(catalog).validate(ast));
        assertTrue(ex.getMessage().toLowerCase().contains(expectedMessagePart.toLowerCase()),
            "Expected message containing '" + expectedMessagePart + "', got: " + ex.getMessage());
    }

    private void assertValidationPasses(String sql) {
        SqlNode ast = parse(sql);
        assertDoesNotThrow(() -> new SqlValidator(catalog).validate(ast));
    }
}
