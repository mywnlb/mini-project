package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.ast.SqlAlias;
import cn.zhangyis.minidb.sql.ast.SqlIdentifier;
import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.ast.SqlSelect;
import cn.zhangyis.minidb.sql.ast.SqlTableRef;
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
import cn.zhangyis.minidb.sql.rel.RelNode;
import cn.zhangyis.minidb.sql.rel.RelProject;
import cn.zhangyis.minidb.sql.rel.RelScan;
import cn.zhangyis.minidb.sql.validation.SqlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SelectAliasTest {
    private final MockCatalog catalog = new MockCatalog();

    @BeforeEach
    void resetData() {
        MockDataSource.reset();
    }

    @Test
    void parserBuildsTableAndProjectionAliases() {
        SqlSelect select = assertInstanceOf(SqlSelect.class, parse("SELECT t.id AS user_id FROM users AS t"));

        SqlTableRef from = assertInstanceOf(SqlTableRef.class, select.from());
        assertEquals("USERS", from.tableName());
        assertEquals("T", from.alias());

        SqlAlias projection = assertInstanceOf(SqlAlias.class, select.projection().get(0));
        assertEquals("USER_ID", projection.alias());
        SqlIdentifier expr = assertInstanceOf(SqlIdentifier.class, projection.expression());
        assertEquals("T.ID", expr.name());
    }

    @Test
    void plannerKeepsAliasOnScan() {
        RelNode plan = buildPlan("SELECT t.id AS user_id FROM users AS t");

        RelProject project = assertInstanceOf(RelProject.class, plan);
        RelScan scan = assertInstanceOf(RelScan.class, project.input());
        assertEquals("USERS", scan.tableName());
        assertEquals("T", scan.outputName());
    }

    @Test
    void executionSupportsTableAliasAndOrderByProjectionAlias() {
        List<Row> rows = execute("SELECT t.id AS user_id FROM users AS t WHERE t.name = 'alice' ORDER BY user_id");

        assertEquals(2, rows.size());
        assertEquals(List.of(1, 4), rows.stream().map(row -> row.get("user_id")).toList());
    }

    @Test
    void executionSupportsJoinAliases() {
        List<Row> rows = execute("""
            SELECT u.name AS user_name, o.amount
            FROM users AS u JOIN orders AS o ON u.id = o.user_id
            WHERE o.amount > 100
            ORDER BY o.amount
            LIMIT 3
            """);

        assertEquals(3, rows.size());
        assertEquals(List.of("bob", "alice", "charlie"), rows.stream().map(row -> row.get("user_name")).toList());
        assertEquals(List.of(130, 250, 500), rows.stream().map(row -> row.get("amount")).toList());
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
