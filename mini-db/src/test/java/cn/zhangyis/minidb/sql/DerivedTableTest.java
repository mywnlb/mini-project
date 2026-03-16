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
 * FROM 子查询（派生表）端到端测试
 *
 * <p>测试数据（MockDataSource）：
 * <pre>
 * users:  (1,alice) (2,bob) (3,charlie) (4,alice) (5,dave)
 * orders: (101,1,250) (102,2,130) (103,1,80) (104,3,500) (105,5,60)
 * </pre>
 */
class DerivedTableTest {
    private final MockCatalog catalog = new MockCatalog();

    @BeforeEach
    void resetData() {
        MockDataSource.reset();
    }

    /**
     * 基本派生表：外层 SELECT * 从内层子查询
     */
    @Test
    void basicDerivedTable() {
        List<Row> rows = execute(
            "SELECT * FROM (SELECT id, name FROM users WHERE id > 2) AS t");
        assertEquals(3, rows.size());
        Set<Integer> ids = rows.stream().map(r -> (int) r.get("id")).collect(Collectors.toSet());
        assertEquals(Set.of(3, 4, 5), ids);
    }

    /**
     * 派生表 + 外层 WHERE 过滤
     */
    @Test
    void derivedTableWithOuterFilter() {
        List<Row> rows = execute(
            "SELECT * FROM (SELECT id, name FROM users WHERE id > 2) AS t WHERE t.id < 5");
        assertEquals(2, rows.size());
        Set<Integer> ids = rows.stream().map(r -> (int) r.get("id")).collect(Collectors.toSet());
        assertEquals(Set.of(3, 4), ids);
    }

    /**
     * 派生表 + 外层投影指定列
     */
    @Test
    void derivedTableWithProjection() {
        List<Row> rows = execute(
            "SELECT t.name FROM (SELECT id, name FROM users WHERE id <= 2) AS t");
        assertEquals(2, rows.size());
        Set<String> names = rows.stream().map(r -> (String) r.get("name")).collect(Collectors.toSet());
        assertEquals(Set.of("alice", "bob"), names);
    }

    /**
     * 派生表内层 SELECT *
     */
    @Test
    void derivedTableSelectStar() {
        List<Row> rows = execute(
            "SELECT * FROM (SELECT * FROM users) AS t WHERE t.id = 1");
        assertEquals(1, rows.size());
        assertEquals("alice", rows.get(0).get("name"));
    }

    /**
     * 派生表内层有聚合
     */
    @Test
    void derivedTableWithAggregation() {
        List<Row> rows = execute(
            "SELECT * FROM (SELECT user_id, COUNT(*) AS cnt FROM orders GROUP BY user_id) AS t WHERE t.cnt > 1");
        // user_id=1 有 2 笔订单
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).get("user_id"));
    }

    /**
     * 派生表空结果
     */
    @Test
    void derivedTableEmptyResult() {
        List<Row> rows = execute(
            "SELECT * FROM (SELECT id, name FROM users WHERE id > 999) AS t");
        assertEquals(0, rows.size());
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
