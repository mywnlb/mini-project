package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.ast.SqlIdentifier;
import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.ast.SqlSelect;
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
import cn.zhangyis.minidb.sql.rel.RelDistinct;
import cn.zhangyis.minidb.sql.rel.RelNode;
import cn.zhangyis.minidb.sql.rel.RelProject;
import cn.zhangyis.minidb.sql.rel.RelSort;
import cn.zhangyis.minidb.sql.validation.SqlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectDistinctTest {
    private final MockCatalog catalog = new MockCatalog();

    @BeforeEach
    void resetData() {
        MockDataSource.reset();
    }

    @Test
    void parserMarksSelectAsDistinct() {
        SqlSelect select = assertInstanceOf(SqlSelect.class, parse("SELECT DISTINCT name FROM users"));

        assertTrue(select.distinct());
        SqlIdentifier projection = assertInstanceOf(SqlIdentifier.class, select.projection().get(0));
        assertEquals("NAME", projection.name());
    }

    @Test
    void plannerPlacesDistinctBetweenProjectAndSort() {
        RelNode plan = buildPlan("SELECT DISTINCT name FROM users ORDER BY name LIMIT 2");

        RelSort sort = assertInstanceOf(RelSort.class, plan);
        RelDistinct distinct = assertInstanceOf(RelDistinct.class, sort.input());
        assertInstanceOf(RelProject.class, distinct.input());
    }

    @Test
    void executionRemovesDuplicatesBeforeLimit() {
        List<Row> rows = execute("SELECT DISTINCT name FROM users ORDER BY name LIMIT 2");

        assertEquals(2, rows.size());
        assertEquals(List.of("alice", "bob"), rows.stream().map(row -> row.get("name")).toList());
    }

    @Test
    void executionDistinctStarUsesFullProjectedRow() {
        List<Row> rows = execute("SELECT DISTINCT * FROM users");

        assertEquals(5, rows.size());
    }

    private SqlNode parse(String sql) {
        SqlLexer lexer = new SqlLexer(sql);
        TokenStream tokens = new TokenStream(lexer);
        return new SqlParser(tokens).parseStatement();
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
