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

class OrderByDirectionTest {
    private final MockCatalog catalog = new MockCatalog();

    @BeforeEach
    void resetData() {
        MockDataSource.reset();
    }

    @Test
    void parseOrderByDefaultAsc() {
        SqlSelect select = assertInstanceOf(SqlSelect.class, parse("SELECT * FROM users ORDER BY id"));
        SqlOrderByItem item = assertInstanceOf(SqlOrderByItem.class, select.orderBy().get(0));
        assertTrue(item.ascending());
    }

    @Test
    void parseOrderByExplicitAsc() {
        SqlSelect select = assertInstanceOf(SqlSelect.class, parse("SELECT * FROM users ORDER BY id ASC"));
        SqlOrderByItem item = assertInstanceOf(SqlOrderByItem.class, select.orderBy().get(0));
        assertTrue(item.ascending());
    }

    @Test
    void parseOrderByDesc() {
        SqlSelect select = assertInstanceOf(SqlSelect.class, parse("SELECT * FROM users ORDER BY id DESC"));
        SqlOrderByItem item = assertInstanceOf(SqlOrderByItem.class, select.orderBy().get(0));
        assertFalse(item.ascending());
    }

    @Test
    void parseOrderByMultipleDirections() {
        SqlSelect select = assertInstanceOf(SqlSelect.class,
            parse("SELECT * FROM users ORDER BY name DESC, id ASC"));
        SqlOrderByItem first = assertInstanceOf(SqlOrderByItem.class, select.orderBy().get(0));
        SqlOrderByItem second = assertInstanceOf(SqlOrderByItem.class, select.orderBy().get(1));
        assertFalse(first.ascending());
        assertTrue(second.ascending());
    }

    @Test
    void execOrderByAscReturnsAscending() {
        List<Row> rows = execute("SELECT * FROM users ORDER BY id ASC");
        assertEquals(5, rows.size());
        assertEquals(1, rows.get(0).get("id"));
        assertEquals(5, rows.get(4).get("id"));
    }

    @Test
    void execOrderByDescReturnsDescending() {
        List<Row> rows = execute("SELECT * FROM users ORDER BY id DESC");
        assertEquals(5, rows.size());
        assertEquals(5, rows.get(0).get("id"));
        assertEquals(1, rows.get(4).get("id"));
    }

    @Test
    void execOrderByDescWithLimit() {
        List<Row> rows = execute("SELECT * FROM users ORDER BY id DESC LIMIT 3");
        assertEquals(3, rows.size());
        assertEquals(5, rows.get(0).get("id"));
        assertEquals(4, rows.get(1).get("id"));
        assertEquals(3, rows.get(2).get("id"));
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
