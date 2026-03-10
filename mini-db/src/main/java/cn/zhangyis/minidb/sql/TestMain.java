package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.catalog.MockCatalog;
import cn.zhangyis.minidb.sql.lexer.*;
import cn.zhangyis.minidb.sql.optimize.RuleOptimizer;
import cn.zhangyis.minidb.sql.optimize.cost.CostOptimizer;
import cn.zhangyis.minidb.sql.parser.SqlParser;
import cn.zhangyis.minidb.sql.planner.SqlToRelConverter;
import cn.zhangyis.minidb.sql.rel.*;
import cn.zhangyis.minidb.sql.validation.SqlValidator;

public class SqlEngineTest {
    public static void main(String[] args) {
        testFullPipeline("SELECT * FROM users WHERE id = 1");
        testFullPipeline("SELECT * FROM users");
    }

    private static void testFullPipeline(String sql) {
        System.out.println("\n=== Testing: " + sql + " ===");

        // 1. Lexer
        SqlLexer lexer = new SqlLexer(sql);
        TokenStream tokens = new TokenStream(lexer);

        // 2. Parser
        SqlParser parser = new SqlParser(tokens);
        SqlSelect select = parser.parseStatement();

        // 3. Validate
        MockCatalog catalog = new MockCatalog();
        SqlValidator validator = new SqlValidator(catalog);
        SqlSelect validated = validator.validate(select);

        // 4. Logical Plan
        SqlToRelConverter converter = new SqlToRelConverter(DefaultRelFactories.INSTANCE);
        RelNode logicalPlan = converter.convert(new ValidatedSqlSelect(validated, catalog.getTable(select.table().name())));

        // 5. Rule Optimization
        RuleOptimizer ruleOpt = new RuleOptimizer();
        RelNode optimized = ruleOpt.optimize(logicalPlan);

        // 6. Cost Optimization
        CostOptimizer costOpt = new CostOptimizer();
        String physicalPlan = costOpt.decidePhysicalPlan(optimized);

        System.out.println("Logical:  " + logicalPlan.explain());
        System.out.println("Optimized: " + optimized.explain());
        System.out.println("Physical: " + physicalPlan);
    }
}