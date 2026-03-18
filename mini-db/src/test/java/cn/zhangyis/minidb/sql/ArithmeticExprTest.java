package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.ast.*;
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

import static org.junit.jupiter.api.Assertions.*;

class ArithmeticExprTest {
    private final MockCatalog catalog = new MockCatalog();

    @BeforeEach
    void resetData() {
        MockDataSource.reset();
    }

    // ==================== 解析优先级 ====================

    @Test
    void parseMulBeforeAdd() {
        // 1 + 2 * 3 应解析为 1 + (2 * 3) = 7，而非 (1 + 2) * 3 = 9
        SqlSelect select = (SqlSelect) parse("SELECT * FROM users WHERE id = 1 + 2 * 3");
        SqlBinaryOp where = (SqlBinaryOp) select.where();
        assertEquals(SqlKind.BINARY_EQ, where.kind());
        // right 应该是 ADD(1, MUL(2, 3))
        SqlBinaryOp add = assertInstanceOf(SqlBinaryOp.class, where.right());
        assertEquals(SqlKind.ADD, add.kind());
        SqlBinaryOp mul = assertInstanceOf(SqlBinaryOp.class, add.right());
        assertEquals(SqlKind.MUL, mul.kind());
    }

    @Test
    void parseParenOverridesPrecedence() {
        // (1 + 2) * 3 应解析为 MUL(ADD(1, 2), 3)
        SqlSelect select = (SqlSelect) parse("SELECT * FROM users WHERE id = (1 + 2) * 3");
        SqlBinaryOp where = (SqlBinaryOp) select.where();
        SqlBinaryOp mul = assertInstanceOf(SqlBinaryOp.class, where.right());
        assertEquals(SqlKind.MUL, mul.kind());
        SqlBinaryOp add = assertInstanceOf(SqlBinaryOp.class, mul.left());
        assertEquals(SqlKind.ADD, add.kind());
    }

    @Test
    void parseBigintLiteral() {
        SqlSelect select = (SqlSelect) parse("SELECT * FROM users WHERE id = 2147483648");
        SqlBinaryOp where = assertInstanceOf(SqlBinaryOp.class, select.where());
        SqlLiteral literal = assertInstanceOf(SqlLiteral.class, where.right());
        assertEquals(cn.zhangyis.minidb.sql.types.SqlType.BIGINT, literal.type());
    }

    // ==================== 执行：算术 WHERE ====================

    @Test
    void whereWithMultiplication() {
        // orders: amount 列有 250, 130, 80, 500, 60
        // amount * 2 > 400 → amount > 200 → 250, 500
        List<Row> rows = execute("SELECT * FROM orders WHERE amount * 2 > 400");
        assertEquals(2, rows.size());
        rows.forEach(r -> assertTrue(((Number) r.get("amount")).intValue() > 200));
    }

    @Test
    void whereWithAddition() {
        // id + 10 > 14 → id > 4 → id=5
        List<Row> rows = execute("SELECT * FROM users WHERE id + 10 > 14");
        assertEquals(1, rows.size());
        assertEquals(5, rows.get(0).get("id"));
    }

    @Test
    void whereWithSubtraction() {
        // id - 1 = 0 → id = 1
        List<Row> rows = execute("SELECT * FROM users WHERE id - 1 = 0");
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).get("id"));
    }

    @Test
    void whereWithDivision() {
        // amount / 10 > 40 → amount > 400 → 500
        List<Row> rows = execute("SELECT * FROM orders WHERE amount / 10 > 40");
        assertEquals(1, rows.size());
        assertEquals(500, rows.get(0).get("amount"));
    }

    @Test
    void whereWithDivisionByZeroConstantProducesNoRows() {
        List<Row> rows = execute("SELECT * FROM users WHERE 1 / 0");
        assertTrue(rows.isEmpty());
    }

    @Test
    void whereWithMixedArithmetic() {
        // (id * 2) + 1 > 8 → id * 2 > 7 → id >= 4
        List<Row> rows = execute("SELECT * FROM users WHERE id * 2 + 1 > 8");
        assertEquals(2, rows.size()); // id=4, id=5
    }

    @Test
    void arithmeticOnBothSides() {
        // id + 1 = 3 - 0 → id = 2
        List<Row> rows = execute("SELECT * FROM users WHERE id + 1 = 3 - 0");
        assertEquals(1, rows.size());
        assertEquals(2, rows.get(0).get("id"));
    }

    @Test
    void decimalLiteralWorksInPredicate() {
        List<Row> rows = execute("SELECT * FROM orders WHERE amount > 79.5");
        assertEquals(4, rows.size());
    }

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
