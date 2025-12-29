package cn.zhangyis.sql;

import cn.zhangyis.sql.parser.MainParser;
import cn.zhangyis.sql.parser.SQLStatement;
import cn.zhangyis.sql.parser.SelectStatement;
import org.junit.jupiter.api.Test;

/**
 * SELECT语句解析测试类
 * 测试各种SELECT语句的解析兼容性
 */
public class SelectParserTest {

    /**
     * 测试基本SELECT语句
     */
    @Test
    public void testBasicSelect() {
        String sql = "SELECT id, name, age FROM users";
        SQLStatement statement = new MainParser(sql).parse();
        System.out.println(statement);
    }

    /**
     * 测试带有表别名的SELECT语句
     */
    @Test
    public void testSelectWithTableAlias() {
        String sql = "SELECT u.id, u.name, u.age FROM users u";
        SQLStatement statement = new MainParser(sql).parse();
        System.out.println(statement);
    }

    /**
     * 测试带有WHERE条件的SELECT语句
     */
    @Test
    public void testSelectWithWhere() {
        String sql = "SELECT id, name, age FROM users WHERE age > 18 AND status = 'active'";
        SQLStatement statement = new MainParser(sql).parse();
        System.out.println(statement);
    }

    /**
     * 测试带有GROUP BY和聚合函数的SELECT语句
     */
    @Test
    public void testSelectWithGroupBy() {
        String sql = "SELECT department, COUNT(*) as total, AVG(salary) as avg_salary FROM employees GROUP BY department";
        SQLStatement statement = new MainParser(sql).parse();
        System.out.println(statement);
    }

    /**
     * 测试带有ORDER BY的SELECT语句
     */
    @Test
    public void testSelectWithOrderBy() {
        String sql = "SELECT id, name, age FROM users ORDER BY age DESC, name ASC";
        SQLStatement statement = new MainParser(sql).parse();
        System.out.println(statement);
    }

    /**
     * 测试带有LIMIT的SELECT语句
     */
    @Test
    public void testSelectWithLimit() {
        String sql = "SELECT id, name, age FROM users LIMIT 100,10";
        SQLStatement statement = new MainParser(sql).parse();
        System.out.println(statement);
    }

    /**
     * 测试带有JOIN的SELECT语句
     */
    @Test
    public void testSelectWithJoin() {
        String sql = "SELECT u.id, u.name, o.order_id FROM users u JOIN orders o ON u.id = o.user_id";
        SQLStatement statement = new MainParser(sql).parse();
        System.out.println(statement);
    }

    /**
     * 测试带有多个JOIN的复杂SELECT语句
     */
    @Test
    public void testSelectWithMultipleJoins() {
        String sql = "SELECT u.id, u.name, o.order_id, p.product_name " +
                "FROM users u " +
                "LEFT JOIN orders o ON u.id = o.user_id " +
                "RIGHT JOIN products p ON o.product_id = p.id";
        SQLStatement statement = new MainParser(sql).parse();
        System.out.println(statement);
    }

    /**
     * 测试带有子查询的SELECT语句
     */
    @Test
    public void testSelectWithSubquery() {
        String sql = "SELECT id, name FROM users WHERE id IN (SELECT user_id FROM orders WHERE amount > 1000)";
        SQLStatement statement = new MainParser(sql).parse();
        System.out.println(statement);
    }

    /**
     * 测试带有DISTINCT的SELECT语句
     */
    @Test
    public void testSelectWithDistinct() {
        String sql = "SELECT DISTINCT department FROM employees";
        SQLStatement statement = new MainParser(sql).parse();
        System.out.println(statement);
    }

    /**
     * 测试带有聚合函数和DISTINCT的SELECT语句
     */
    @Test
    public void testSelectWithAggregateAndDistinct() {
        String sql = "SELECT COUNT(DISTINCT department) as dept_count FROM employees";
        SQLStatement statement = new MainParser(sql).parse();
        System.out.println(statement);
    }

    /**
     * 测试字符串函数
     */
    @Test
    public void testStringFunctions() {
        String sql = "SELECT id, CONCAT(first_name, ' ', last_name) as full_name, " +
                "UPPER(name) as upper_name, " +
                "LOWER(name) as lower_name, " +
                "LENGTH(name) as name_length, " +
                "SUBSTRING(description, 1, 100) as short_desc " +
                "FROM users";
        SQLStatement statement = new MainParser(sql).parse();
        System.out.println(statement);
    }

    /**
     * 测试数值函数
     */
    @Test
    public void testNumericFunctions() {
        String sql = "SELECT id, " +
                "ROUND(price, 2) as rounded_price, " +
                "CEIL(price) as ceiling_price, " +
                "FLOOR(price) as floor_price, " +
                "ABS(balance) as absolute_balance, " +
                "POWER(base, 2) as squared " +
                "FROM products";
        SQLStatement statement = new MainParser(sql).parse();
        System.out.println(statement);
    }

    /**
     * 测试日期函数
     */
    @Test
    public void testDateFunctions() {
        String sql = "SELECT id, " +
                "NOW() as current_time, " +
                "CURDATE() as today3, " +
                "YEAR(created_at) as year1, " +
                "MONTH(created_at) as month2, " +
                "DAY(created_at) as day3, " +
                "DATEDIFF(expiry_date, created_at) as days_valid " +
                "FROM subscriptions";
        SQLStatement statement = new MainParser(sql).parse();
        System.out.println(statement);
    }

    /**
     * 测试条件函数
     */
    @Test
    public void testConditionalFunctions() {
        String sql = "SELECT id, " +
                "IFNULL(middle_name, '') as middle, " +
                "COALESCE(nickname, username, 'Anonymous') as display_name, " +
                "CASE status WHEN 'active' THEN 'Active User' WHEN 'inactive' THEN 'Inactive User' ELSE 'Unknown' END as user_status, " +
                "IF(age >= 18, 'Adult', 'Minor') as age_group " +
                "FROM users";
        SQLStatement statement = new MainParser(sql).parse();
        System.out.println(statement);
    }

    /**
     * 测试CAST和CONVERT函数
     */
    @Test
    public void testConversionFunctions() {
        String sql = "SELECT id, " +
                "CAST(price AS DECIMAL(10,2)) as formatted_price, " +
                "CONVERT(creation_date, CHAR) as date_string " +
                "FROM products";
        SQLStatement statement = new MainParser(sql).parse();
        System.out.println(statement);
    }

    /**
     * 测试复杂的嵌套函数
     */
    @Test
    public void testNestedFunctions() {
        String sql = "SELECT id, " +
                "UPPER(CONCAT(first_name, ' ', last_name)) as upper_name, " +
                "ROUND(ABS(balance * 1.05), 2) as projected_balance, " +
                "DATEDIFF(NOW(), created_at) as days_since_creation " +
                "FROM users";
        SQLStatement statement = new MainParser(sql).parse();
        System.out.println(statement);
    }

    /**
     * 测试超复杂SELECT语句，包含多种函数、JOIN、子查询等
     */
    @Test
    public void testComplexSelectWithAllFeatures() {
        String sql = "SELECT " +
                "u.id, " +
                "UPPER(CONCAT(u.first_name, ' ', u.last_name)) as name, " +
                "COUNT(DISTINCT o.order_id) as order_count, " +
                "SUM(o.amount) as total_spent, " +
                "AVG(o.amount) as avg_order, " +
                "CASE WHEN COUNT(o.order_id) > 5 THEN 'VIP' ELSE 'Regular' END as customer_type, " +
                "DATEDIFF(NOW(), MAX(o.order_date)) as days_since_last_order " +
                "FROM users u " +
                "LEFT JOIN orders o ON u.id = o.user_id " +
                "WHERE u.status = 'active' AND u.created_at > '2023-01-01' " +
                "AND u.id IN (SELECT user_id FROM user_preferences WHERE newsletter = true) " +
                "GROUP BY u.id, u.first_name, u.last_name " +
                "HAVING COUNT(o.order_id) > 0 " +
                "ORDER BY total_spent DESC " +
                "LIMIT 100";
        SQLStatement statement = new MainParser(sql).parse();
        System.out.println(statement);
    }

    /**
     * 测试多种DISTINCT用法
     */
    @Test
    public void testVariousDistinctUsages() {
        // 基本DISTINCT
        String sql1 = "SELECT DISTINCT city FROM addresses";
        SQLStatement statement1 = new MainParser(sql1).parse();
        System.out.println("基本DISTINCT: " + statement1);
        
        // 带表别名的DISTINCT
        String sql2 = "SELECT DISTINCT a.city FROM addresses a";
        SQLStatement statement2 = new MainParser(sql2).parse();
        System.out.println("带表别名的DISTINCT: " + statement2);
        
        // 多列DISTINCT
        String sql3 = "SELECT DISTINCT city, state FROM addresses";
        SQLStatement statement3 = new MainParser(sql3).parse();
        System.out.println("多列DISTINCT: " + statement3);
        
        // 聚合函数中的DISTINCT
        String sql4 = "SELECT COUNT(DISTINCT city) FROM addresses";
        SQLStatement statement4 = new MainParser(sql4).parse();
        System.out.println("聚合函数中的DISTINCT: " + statement4);
        
        // 多个聚合函数中的DISTINCT
        String sql5 = "SELECT COUNT(DISTINCT city), COUNT(DISTINCT state) FROM addresses";
        SQLStatement statement5 = new MainParser(sql5).parse();
        System.out.println("多个聚合函数中的DISTINCT: " + statement5);
    }

    /**
     * 测试IF函数中的复杂条件表达式
     */
    @Test
    public void testIfWithComplexConditions() {
        // 测试简单比较条件
        String sql1 = "SELECT id, IF(age >= 18, 'Adult', 'Minor') as age_group FROM users";
        SQLStatement statement1 = new MainParser(sql1).parse();
        System.out.println("IF简单比较: " + statement1);
        
        // 测试带AND的复合条件
        String sql2 = "SELECT id, IF(age >= 18 AND status = 'active', 'Active Adult', 'Other') as user_category FROM users";
        SQLStatement statement2 = new MainParser(sql2).parse();
        System.out.println("IF带AND条件: " + statement2);
        
        // 测试带OR的复合条件
        String sql3 = "SELECT id, IF(age < 18 OR status = 'inactive', 'Restricted', 'Full Access') as access_level FROM users";
        SQLStatement statement3 = new MainParser(sql3).parse();
        System.out.println("IF带OR条件: " + statement3);
        
        // 测试带括号的复杂条件
        String sql4 = "SELECT id, IF((age >= 18 AND status = 'active') OR role = 'admin', 'Privileged', 'Regular') as user_type FROM users";
        SQLStatement statement4 = new MainParser(sql4).parse();
        System.out.println("IF带括号复杂条件: " + statement4);
        
        // 测试嵌套IF
        String sql5 = "SELECT id, IF(age >= 18, IF(status = 'active', 'Active Adult', 'Inactive Adult'), 'Minor') as detailed_category FROM users";
        SQLStatement statement5 = new MainParser(sql5).parse();
        System.out.println("嵌套IF: " + statement5);
    }
} 