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

class PredicateTest {
    private final MockCatalog catalog = new MockCatalog();

    @BeforeEach
    void resetData() {
        MockDataSource.reset();
    }

    // ==================== LIKE ====================

    @Test
    void likePrefix() {
        List<Row> rows = execute("SELECT * FROM users WHERE name LIKE 'ali%'");
        assertEquals(2, rows.size()); // alice x2
        rows.forEach(r -> assertTrue(String.valueOf(r.get("name")).startsWith("ali")));
    }

    @Test
    void likeSuffix() {
        List<Row> rows = execute("SELECT * FROM users WHERE name LIKE '%ob'");
        assertEquals(1, rows.size());
        assertEquals("bob", rows.get(0).get("name"));
    }

    @Test
    void likeContains() {
        List<Row> rows = execute("SELECT * FROM users WHERE name LIKE '%ar%'");
        assertEquals(1, rows.size());
        assertEquals("charlie", rows.get(0).get("name"));
    }

    @Test
    void likeSingleChar() {
        List<Row> rows = execute("SELECT * FROM users WHERE name LIKE 'b_b'");
        assertEquals(1, rows.size());
        assertEquals("bob", rows.get(0).get("name"));
    }

    @Test
    void notLike() {
        List<Row> rows = execute("SELECT * FROM users WHERE name NOT LIKE 'ali%'");
        assertEquals(3, rows.size()); // bob, charlie, dave
    }

    // ==================== BETWEEN ====================

    @Test
    void betweenInclusive() {
        List<Row> rows = execute("SELECT * FROM users WHERE id BETWEEN 2 AND 4");
        assertEquals(3, rows.size());
        rows.forEach(r -> {
            int id = (int) r.get("id");
            assertTrue(id >= 2 && id <= 4);
        });
    }

    @Test
    void betweenSingleMatch() {
        List<Row> rows = execute("SELECT * FROM users WHERE id BETWEEN 5 AND 5");
        assertEquals(1, rows.size());
        assertEquals(5, rows.get(0).get("id"));
    }

    @Test
    void notBetween() {
        List<Row> rows = execute("SELECT * FROM users WHERE id NOT BETWEEN 2 AND 4");
        assertEquals(2, rows.size()); // id=1, id=5
    }

    // ==================== IN ====================

    @Test
    void inList() {
        List<Row> rows = execute("SELECT * FROM users WHERE id IN (1, 3, 5)");
        assertEquals(3, rows.size());
    }

    @Test
    void inSingleValue() {
        List<Row> rows = execute("SELECT * FROM users WHERE id IN (2)");
        assertEquals(1, rows.size());
        assertEquals(2, rows.get(0).get("id"));
    }

    @Test
    void inStringValues() {
        List<Row> rows = execute("SELECT * FROM users WHERE name IN ('alice', 'bob')");
        assertEquals(3, rows.size()); // alice x2 + bob
    }

    @Test
    void notIn() {
        List<Row> rows = execute("SELECT * FROM users WHERE id NOT IN (1, 2)");
        assertEquals(3, rows.size()); // id=3,4,5
    }

    // ==================== IS NULL / IS NOT NULL ====================

    @Test
    void isNotNullOnExistingColumn() {
        List<Row> rows = execute("SELECT * FROM users WHERE name IS NOT NULL");
        assertEquals(5, rows.size());
    }

    @Test
    void parseIsNull() {
        SqlNode ast = parse("SELECT * FROM users WHERE name IS NULL");
        assertInstanceOf(SqlSelect.class, ast);
        SqlSelect select = (SqlSelect) ast;
        SqlBinaryOp where = assertInstanceOf(SqlBinaryOp.class, select.where());
        assertEquals(SqlKind.IS_NULL, where.kind());
    }

    @Test
    void parseIsNotNull() {
        SqlNode ast = parse("SELECT * FROM users WHERE name IS NOT NULL");
        assertInstanceOf(SqlSelect.class, ast);
        SqlSelect select = (SqlSelect) ast;
        SqlBinaryOp where = assertInstanceOf(SqlBinaryOp.class, select.where());
        assertEquals(SqlKind.IS_NOT_NULL, where.kind());
    }

    // ==================== 组合谓词 ====================

    @Test
    void likeAndBetweenCombined() {
        List<Row> rows = execute("SELECT * FROM users WHERE name LIKE 'a%' AND id BETWEEN 1 AND 3");
        assertEquals(1, rows.size()); // alice with id=1
        assertEquals("alice", rows.get(0).get("name"));
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
