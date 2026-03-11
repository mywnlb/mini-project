package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.MockCatalog;
import cn.zhangyis.minidb.sql.exec.*;
import cn.zhangyis.minidb.sql.lexer.*;
import cn.zhangyis.minidb.sql.optimize.RuleOptimizer;
import cn.zhangyis.minidb.sql.optimize.cost.CostOptimizer;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.*;
import cn.zhangyis.minidb.sql.validation.SqlValidator;

import java.util.ArrayList;
import java.util.List;

public class TestMain {
    public static void main(String[] args) {
        System.out.println("========== PLAN TESTS ==========");
        testPlan("SELECT * FROM users WHERE id = 1");
        testPlan("SELECT * FROM users");
        testPlan("SELECT * FROM users JOIN orders ON users.id = orders.user_id");
        testPlan("SELECT * FROM users WHERE id > 5 AND name = 'test'");
        testPlan("SELECT name, COUNT(*) FROM users GROUP BY name");
        testPlan("SELECT * FROM users ORDER BY id LIMIT 10");
        testPlan("INSERT INTO users (id, name) VALUES (1, 'alice')");
        testPlan("UPDATE users SET name = 'bob' WHERE id = 1");
        testPlan("DELETE FROM users WHERE id = 1");

        System.out.println("\n\n========== EXECUTION TESTS ==========");

        // 基本查询
        testExec("SELECT * FROM users");
        testExec("SELECT * FROM users WHERE id = 1");
        testExec("SELECT name FROM users WHERE id > 2");

        // 复合条件
        testExec("SELECT * FROM users WHERE id >= 2 AND id <= 4");

        // JOIN - 4种算法对比
        String joinSql = "SELECT * FROM users JOIN orders ON users.id = orders.user_id";
        System.out.println("\n--- JOIN Algorithm Comparison ---");
        testExecWithJoinAlgo(joinSql, PhysicalPlanner.JoinAlgorithm.NESTED_LOOP);
        testExecWithJoinAlgo(joinSql, PhysicalPlanner.JoinAlgorithm.HASH_JOIN);
        testExecWithJoinAlgo(joinSql, PhysicalPlanner.JoinAlgorithm.SORT_MERGE);
        testExecWithJoinAlgo(joinSql, PhysicalPlanner.JoinAlgorithm.INDEX_NESTED_LOOP);

        // 聚合
        testExec("SELECT name, COUNT(*) FROM users GROUP BY name");

        // 排序 + LIMIT
        testExec("SELECT * FROM users ORDER BY id");
        testExec("SELECT * FROM users ORDER BY id LIMIT 3");
    }

    private static void testPlan(String sql) {
        System.out.println("\n--- Plan: " + sql + " ---");
        try {
            RelNode optimized = buildOptimizedPlan(sql);
            CostOptimizer costOpt = new CostOptimizer();
            System.out.println("Optimized: " + optimized.explain());
            System.out.println("Physical:  " + costOpt.decidePhysicalPlan(optimized));
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
        }
    }

    private static void testExec(String sql) {
        testExecWithJoinAlgo(sql, PhysicalPlanner.JoinAlgorithm.HASH_JOIN);
    }

    private static void testExecWithJoinAlgo(String sql, PhysicalPlanner.JoinAlgorithm algo) {
        System.out.println("\n--- Exec [" + algo + "]: " + sql + " ---");
        try {
            RelNode optimized = buildOptimizedPlan(sql);

            PhysicalPlanner planner = new PhysicalPlanner();
            ExecNode exec = planner.plan(optimized, algo);

            exec.open();
            List<Row> results = new ArrayList<>();
            Row row;
            while ((row = exec.next()) != null) {
                results.add(row);
            }
            exec.close();

            System.out.println("Rows: " + results.size());
            for (Row r : results) {
                System.out.println("  " + r);
            }
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static RelNode buildOptimizedPlan(String sql) {
        SqlLexer lexer = new SqlLexer(sql);
        TokenStream tokens = new TokenStream(lexer);
        SqlParser parser = new SqlParser(tokens);
        SqlNode ast = parser.parseStatement();

        MockCatalog catalog = new MockCatalog();
        SqlValidator validator = new SqlValidator(catalog);
        SqlNode validated = validator.validate(ast);

        SqlToRelConverter converter = new SqlToRelConverter();
        RelNode logicalPlan = converter.convert(validated);

        RuleOptimizer ruleOpt = new RuleOptimizer();
        return ruleOpt.optimize(logicalPlan);
    }
}
