package cn.zhangyis.sql;

import cn.zhangyis.sql.parser.MainParser;
import cn.zhangyis.sql.parser.SQLStatement;
import org.junit.jupiter.api.Test;

/**
 * @Description create table test
 * @Date 2025/3/3 15:33
 * @Created by libo
 */
public class CreateTableParserTest {
    @Test
    public void  testCreate(){
        String sql = "CREATE TABLE `tb_test` (\n" +
                "  `id` bigint(20) NOT NULL COMMENT '1',\n" +
                "  `name` varchar(255) DEFAULT NULL COMMENT '2',\n" +
                "  `age` int(11) DEFAULT NULL COMMENT '4',\n" +
                "  `money` double(20,5) DEFAULT NULL COMMENT '5',\n" +
                "  PRIMARY KEY (`id`),\n" +
                "  KEY `name_index` (`name`,`age`),\n" +
                "  KEY `age_index` (`age`)\n" +
                ")  COMMENT='asdasdsa';";

        SQLStatement parse = new MainParser(sql).parse();
        System.out.println(parse);
    }

    @Test
    public void testComplexSelect() {
        String sql = "SELECT a.department, COUNT(*) as countvalue, SUM(a.salary) as total_salary " +
                "FROM employees a " +
                "JOIN departments d ON a.department_id = d.id " +
                "WHERE a.salary > 5000 AND d.location IN ('Beijing', 'Shanghai') " +
                "GROUP BY a.department " +
                "HAVING COUNT(*) > 5 " +
                "ORDER BY total_salary DESC " +
                "LIMIT 10";

        SQLStatement parse = new MainParser(sql).parse();
        System.out.println(parse);
    }

    @Test
    public void testSelectAll() {
        String sql = "SELECT a.*, " +
                "FROM employees a ";

        SQLStatement parse = new MainParser(sql).parse();
        System.out.println(parse);
    }


    @Test
    public void  testDelete(){
        String sql = "delete from tb_test where  1=1 and a = 'c' and a= 1 or b = c;";

        SQLStatement parse = new MainParser(sql).parse();
        System.out.println(parse);
    }
    @Test
    public void  testDelete1(){
        String sql = "delete from tb_test where  1=1 and a = 'c' and (a= 1 or b = c);";

        SQLStatement parse = new MainParser(sql).parse();
        System.out.println(parse);
    }

    @Test
    public void testSelect(){
        String sql = "SELECT a.id, a.name FROM users a WHERE 1=1 AND a.status = 'active'";
        SQLStatement parse = new MainParser(sql).parse();
        System.out.println(parse);
    }

    /**
     * 测试 insert tb_test (a,b)? values (1,2) | select * from tb_test;
     */
    @Test
    public void testInsert(){
        String sql = "insert into tb_test (a,b) values (1,2);";
        SQLStatement parse = new MainParser(sql).parse();
        System.out.println(parse);

        String sql2 = "insert into tb_test values (1,2);";
        SQLStatement parse2 = new MainParser(sql2).parse();
        System.out.println(parse2);

        String sql3 = "insert into tb_test select * from tb_test;";
        SQLStatement parse3 = new MainParser(sql3).parse();
        System.out.println(parse3);
    }

    @Test
    public void test(){
//        // 1. 创建并配置元数据目录
//        CatalogReader catalog = new DefaultCatalogReader();
//
//// 2. 创建语义验证器
//        ValidatorRegistry validators = new ValidatorRegistry();
//        validators.register(new TableExistenceValidator());
//        validators.register(new ColumnExistenceValidator());
//        validators.register(new TypeCompatibilityValidator());
//
//// 3. 解析SQL
//        String sql = "SELECT a.id, a.name FROM users a WHERE 1=1 AND a.status = 'active'";
//        SQLLexer lexer = new SQLLexer(sql);
//        List<SQLLexer.Token> tokens = lexer.tokenize();
//        SQLParser parser = new SelectParser(tokens);
//        SQLStatement ast = parser.parse();
//
//// 4. 语义验证
//        ValidationResult validationResult = validators.validateAll(ast, catalog);
//        if (!validationResult.isValid()) {
//            // 处理验证错误
//            return;
//        }
//
//// 5. 生成初始逻辑计划
//        LogicalPlanBuilder planBuilder = new LogicalPlanBuilder(catalog);
//        RelNode logicalPlan = planBuilder.convert(ast);
//
//// 6. 创建和配置优化器
//        Optimizer optimizer = new Optimizer();
//        optimizer.addRule(new ConstantFilterOptimizationRule());
//        optimizer.addRule(new PushFilterRule());
//        optimizer.addRule(new PruneEmptyJoinsRule());
//
//// 7. 优化逻辑计划
//        RelNode optimizedPlan = optimizer.optimize(logicalPlan);
//
//// 8. 转换为物理计划并执行
//// ...
    }
}
