package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.ast.SqlKind;
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

    private static final MockCatalog catalog = new MockCatalog();

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

        System.out.println("\n\n========== CBO JOIN ALGORITHM SELECTION ==========");
        testCboJoinSelection("SELECT * FROM users JOIN orders ON users.id = orders.user_id");

        System.out.println("\n\n========== EXECUTION TESTS ==========");

        testExec("SELECT * FROM users");
        testExec("SELECT * FROM users WHERE id = 1");
        testExec("SELECT name FROM users WHERE id > 2");
        testExec("SELECT * FROM users WHERE id >= 2 AND id <= 4");

        String joinSql = "SELECT * FROM users JOIN orders ON users.id = orders.user_id";
        System.out.println("\n--- CBO Auto-Select JOIN ---");
        testExecCbo(joinSql);
        System.out.println("\n--- Manual JOIN Algorithm Comparison ---");
        testExecWithJoinAlgo(joinSql, PhysicalPlanner.JoinAlgorithm.NESTED_LOOP);
        testExecWithJoinAlgo(joinSql, PhysicalPlanner.JoinAlgorithm.HASH_JOIN);
        testExecWithJoinAlgo(joinSql, PhysicalPlanner.JoinAlgorithm.SORT_MERGE);
        testExecWithJoinAlgo(joinSql, PhysicalPlanner.JoinAlgorithm.INDEX_NESTED_LOOP);

        testExec("SELECT name, COUNT(*) FROM users GROUP BY name");
        testExec("SELECT * FROM users ORDER BY id");
        testExec("SELECT * FROM users ORDER BY id LIMIT 3");

        System.out.println("\n\n========== DML EXECUTION TESTS ==========");
        testDml();

        System.out.println("\n\n========== DDL EXECUTION TESTS ==========");
        testDdl();
    }

    private static void testDml() {
        MockDataSource.reset();
        System.out.println("\n--- Before DML ---");
        testExec("SELECT * FROM users");

        System.out.println("\n--- INSERT ---");
        testExecDml("INSERT INTO users (id, name) VALUES (6, 'eve')");
        testExec("SELECT * FROM users");

        System.out.println("\n--- INSERT multi-row ---");
        testExecDml("INSERT INTO users (id, name) VALUES (7, 'frank'), (8, 'grace')");
        testExec("SELECT * FROM users");

        System.out.println("\n--- UPDATE with WHERE ---");
        testExecDml("UPDATE users SET name = 'ALICE_UPDATED' WHERE id = 1");
        testExec("SELECT * FROM users");

        System.out.println("\n--- UPDATE without WHERE ---");
        testExecDml("UPDATE users SET name = 'anon'");
        testExec("SELECT * FROM users");

        MockDataSource.reset();
        System.out.println("\n--- After reset ---");
        testExec("SELECT * FROM users");

        System.out.println("\n--- DELETE with WHERE ---");
        testExecDml("DELETE FROM users WHERE id = 1");
        testExec("SELECT * FROM users");

        System.out.println("\n--- DELETE without WHERE ---");
        testExecDml("DELETE FROM users");
        testExec("SELECT * FROM users");
    }

    private static void testDdl() {
        MockDataSource.reset();

        // CREATE TABLE
        System.out.println("\n--- CREATE TABLE ---");
        testExecDml("CREATE TABLE products (id INT PRIMARY KEY, name VARCHAR, price DECIMAL)");
        testExecDml("INSERT INTO products (id, name, price) VALUES (1, 'laptop', 999)");
        testExecDml("INSERT INTO products (id, name, price) VALUES (2, 'phone', 599)");
        testExec("SELECT * FROM products");

        // CREATE TABLE IF NOT EXISTS (已存在，应跳过)
        System.out.println("\n--- CREATE TABLE IF NOT EXISTS (skip) ---");
        testExecDml("CREATE TABLE IF NOT EXISTS products (id INT)");

        // ALTER TABLE ADD COLUMN
        System.out.println("\n--- ALTER TABLE ADD COLUMN ---");
        testExecDml("ALTER TABLE products ADD COLUMN category VARCHAR");
        testExecDml("INSERT INTO products (id, name, price, category) VALUES (3, 'tablet', 399, 'electronics')");
        testExec("SELECT * FROM products");

        // ALTER TABLE ADD COLUMN (省略 COLUMN 关键字)
        System.out.println("\n--- ALTER TABLE ADD (without COLUMN keyword) ---");
        testExecDml("ALTER TABLE products ADD stock INT");

        // CREATE INDEX
        System.out.println("\n--- CREATE INDEX ---");
        testExecDml("CREATE INDEX idx_name ON products (name)");
        testExecDml("CREATE INDEX idx_price_cat ON products (price, category)");

        // DROP INDEX
        System.out.println("\n--- DROP INDEX ---");
        testExecDml("DROP INDEX idx_name ON products");

        // DROP TABLE
        System.out.println("\n--- DROP TABLE ---");
        testExecDml("DROP TABLE products");

        // DROP TABLE IF EXISTS (已删除，应跳过)
        System.out.println("\n--- DROP TABLE IF EXISTS (skip) ---");
        testExecDml("DROP TABLE IF EXISTS products");

        // CREATE TABLE IF NOT EXISTS (不存在，应创建)
        System.out.println("\n--- CREATE TABLE IF NOT EXISTS (create) ---");
        testExecDml("CREATE TABLE IF NOT EXISTS items (id INT PRIMARY KEY, desc VARCHAR)");
        testExecDml("INSERT INTO items (id, desc) VALUES (1, 'widget')");
        testExec("SELECT * FROM items");
    }

    private static void testExecDml(String sql) {
        System.out.println("\n--- DML/DDL: " + sql + " ---");
        try {
            RelNode plan = buildPlan(sql);
            PhysicalPlanner planner = new PhysicalPlanner();
            ExecNode exec = planner.plan(plan);

            exec.open();
            Row result = exec.next();
            exec.close();

            System.out.println("Result: " + result);
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testPlan(String sql) {
        System.out.println("\n--- Plan: " + sql + " ---");
        try {
            RelNode optimized = buildPlan(sql);
            CostOptimizer costOpt = new CostOptimizer();
            System.out.println("Optimized: " + optimized.explain());
            System.out.println("Physical:  " + costOpt.decidePhysicalPlan(optimized));
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
        }
    }

    private static void testCboJoinSelection(String sql) {
        System.out.println("\n--- CBO Analysis: " + sql + " ---");
        try {
            RelNode optimized = buildPlan(sql);
            CostOptimizer costOpt = new CostOptimizer();

            RelJoin join = findJoin(optimized);
            if (join != null) {
                var detail = costOpt.costModel().joinCostDetail(join.left(), join.right(), join.condition());
                var chosen = costOpt.chooseJoinAlgorithm(join);
                System.out.println("Join cost analysis:\n" + detail);
                System.out.println("CBO chosen algorithm: " + chosen);
            }

            System.out.println("Full physical plan:\n" + costOpt.decidePhysicalPlan(optimized));
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
        }
    }

    private static RelJoin findJoin(RelNode node) {
        if (node instanceof RelJoin join) return join;
        if (node instanceof RelProject p) return findJoin(p.input());
        if (node instanceof RelDistinct d) return findJoin(d.input());
        if (node instanceof RelFilter f) return findJoin(f.input());
        if (node instanceof RelSort s) return findJoin(s.input());
        if (node instanceof RelAggregate a) return findJoin(a.input());
        return null;
    }

    private static void testExec(String sql) {
        testExecCbo(sql);
    }

    private static void testExecCbo(String sql) {
        System.out.println("\n--- Exec [CBO]: " + sql + " ---");
        try {
            RelNode optimized = buildPlan(sql);

            PhysicalPlanner planner = new PhysicalPlanner();
            ExecNode exec = planner.plan(optimized);

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

    private static void testExecWithJoinAlgo(String sql, PhysicalPlanner.JoinAlgorithm algo) {
        System.out.println("\n--- Exec [" + algo + "]: " + sql + " ---");
        try {
            RelNode optimized = buildPlan(sql);

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

    private static RelNode buildPlan(String sql) {
        SqlLexer lexer = new SqlLexer(sql);
        TokenStream tokens = new TokenStream(lexer);
        SqlParser parser = new SqlParser(tokens);
        SqlNode ast = parser.parseStatement();

        SqlValidator validator = new SqlValidator(catalog);
        SqlNode validated = validator.validate(ast);

        SqlToRelConverter converter = new SqlToRelConverter(catalog);
        RelNode logicalPlan = converter.convert(validated);

        // DDL 不需要优化
        if (logicalPlan instanceof RelCreateTable || logicalPlan instanceof RelDropTable
            || logicalPlan instanceof RelAlterTable || logicalPlan instanceof RelCreateIndex
            || logicalPlan instanceof RelDropIndex) {
            return logicalPlan;
        }

        RuleOptimizer ruleOpt = new RuleOptimizer();
        return ruleOpt.optimize(logicalPlan);
    }
}
