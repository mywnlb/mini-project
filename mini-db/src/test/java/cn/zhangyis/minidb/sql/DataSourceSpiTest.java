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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 DataSourceSpi 对接后，通过 MockDataSourceAdapter 的现有功能不被破坏。
 */
class DataSourceSpiTest {
    private final MockCatalog catalog = new MockCatalog();

    @BeforeEach
    void resetData() {
        MockDataSource.reset();
    }

    @Test
    void scanViaAdapter() {
        List<Row> rows = execute("SELECT * FROM users");
        assertEquals(5, rows.size());
    }

    @Test
    void filterViaAdapter() {
        List<Row> rows = execute("SELECT * FROM users WHERE id = 1");
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).get("id"));
    }

    @Test
    void insertViaAdapter() {
        List<Row> before = execute("SELECT * FROM users");
        execute("INSERT INTO users (id, name) VALUES (6, 'eve')");
        List<Row> after = execute("SELECT * FROM users");
        assertEquals(before.size() + 1, after.size());
    }

    @Test
    void updateViaAdapter() {
        execute("UPDATE users SET name = 'updated' WHERE id = 1");
        List<Row> rows = execute("SELECT * FROM users WHERE id = 1");
        assertEquals(1, rows.size());
        assertEquals("updated", rows.get(0).get("name"));
    }

    @Test
    void updateExpressionViaAdapter() {
        execute("UPDATE orders SET amount = amount + 20 WHERE order_id = 103");
        List<Row> rows = execute("SELECT * FROM orders WHERE order_id = 103");
        assertEquals(1, rows.size());
        assertEquals(100.0, rows.get(0).get("amount"));
    }

    @Test
    void deleteViaAdapter() {
        execute("DELETE FROM users WHERE id = 5");
        List<Row> rows = execute("SELECT * FROM users");
        assertEquals(4, rows.size());
    }

    @Test
    void explicitAdapterMatchesDefault() {
        // 显式传入 MockDataSourceAdapter 应与默认行为一致
        PhysicalPlanner defaultPlanner = new PhysicalPlanner();
        PhysicalPlanner explicitPlanner = new PhysicalPlanner(MockDataSourceAdapter.INSTANCE);

        RelNode plan = buildPlan("SELECT * FROM users WHERE id > 2");
        List<Row> defaultRows = collect(defaultPlanner.plan(plan));
        List<Row> explicitRows = collect(explicitPlanner.plan(plan));

        assertEquals(defaultRows.size(), explicitRows.size());
    }

    @Test
    void joinViaAdapter() {
        List<Row> rows = execute("SELECT * FROM users JOIN orders ON users.id = orders.user_id");
        assertFalse(rows.isEmpty());
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
        return collect(exec);
    }

    private List<Row> collect(ExecNode exec) {
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
