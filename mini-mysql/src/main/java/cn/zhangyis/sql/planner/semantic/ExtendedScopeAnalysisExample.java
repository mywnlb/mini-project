package cn.zhangyis.sql.planner.semantic;

import cn.zhangyis.sql.parser.*;
import cn.zhangyis.sql.parser.enums.LiteralType;
import cn.zhangyis.sql.parser.expression.ColumnExpression;
import cn.zhangyis.sql.parser.expression.ComparisonExpression;
import cn.zhangyis.sql.parser.expression.Expression;
import cn.zhangyis.sql.parser.expression.LiteralExpression;
import cn.zhangyis.storage.catalog.CatalogManager;
import cn.zhangyis.storage.catalog.Table;
import cn.zhangyis.storage.catalog.Column;

import java.util.*;

/**
 * 扩展作用域解析示例
 * 展示VALUES、JOIN、TABLE_FUNCTION等新类型的作用域解析功能
 * 
 * @Description 扩展作用域解析功能演示
 * @Date 2025/1/28 19:00
 * @Created by libo
 */
public class ExtendedScopeAnalysisExample {

    public static void main(String[] args) {
        try {
            // 创建目录管理器和测试数据
            CatalogManager catalogManager = createTestCatalog();
            SemanticAnalyzerScopeUtil scopeUtil = new SemanticAnalyzerScopeUtil(catalogManager);

            System.out.println("=== 扩展作用域解析功能演示 ===\n");

            // 演示1: VALUES子句作用域解析
            demonstrateValuesScope(scopeUtil);

            // 演示2: 表值函数作用域解析
            demonstrateTableFunctionScope(scopeUtil);

            // 演示3: JOIN作用域解析
            demonstrateJoinScope(scopeUtil);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 演示VALUES子句的作用域解析
     */
    private static void demonstrateValuesScope(SemanticAnalyzerScopeUtil scopeUtil) throws SemanticException {
        System.out.println("1. VALUES子句作用域解析");
        System.out.println("-------------------");

        // 创建VALUES子句
        List<List<Expression>> valuesList = Arrays.asList(
            Arrays.asList(
                new LiteralExpression(1, LiteralType.NUMBER),
                new LiteralExpression("Alice", LiteralType.STRING)
            ),
            Arrays.asList(
                new LiteralExpression(2, LiteralType.NUMBER),
                new LiteralExpression("Bob", LiteralType.STRING)
            ),
            Arrays.asList(
                new LiteralExpression(3, LiteralType.NUMBER),
                new LiteralExpression("Charlie", LiteralType.STRING)
            )
        );

        // 创建ValuesNamespace
        ValuesNamespace valuesNamespace = new ValuesNamespace(valuesList, "v");
        valuesNamespace.validate();

        System.out.println("VALUES namespace name: " + valuesNamespace.getName());
        System.out.println("VALUES namespace type: " + valuesNamespace.getType());
        System.out.println("Column count: " + valuesNamespace.getColumnCount());

        for (Column column : valuesNamespace.getColumns()) {
            System.out.println("  - Column: " + column.getName() + " (" + column.getType() + ")");
        }

        System.out.println("VALUES子句解析完成\n");
    }

    /**
     * 演示表值函数的作用域解析
     */
    private static void demonstrateTableFunctionScope(SemanticAnalyzerScopeUtil scopeUtil) throws SemanticException {
        System.out.println("2. 表值函数作用域解析");
        System.out.println("-------------------");

        // 演示GENERATE_SERIES函数
        List<Expression> generateSeriesArgs = Arrays.asList(
            new LiteralExpression(1, LiteralType.NUMBER),
            new LiteralExpression(10, LiteralType.NUMBER)
        );

        TableFunctionNamespace generateSeriesNs = new TableFunctionNamespace(
            "GENERATE_SERIES", generateSeriesArgs, "gs"
        );
        generateSeriesNs.validate();

        System.out.println("GENERATE_SERIES namespace:");
        System.out.println("  Name: " + generateSeriesNs.getName());
        System.out.println("  Type: " + generateSeriesNs.getType());
        System.out.println("  Function: " + generateSeriesNs.getFunctionName());
        for (Column column : generateSeriesNs.getColumns()) {
            System.out.println("  - Column: " + column.getName() + " (" + column.getType() + ")");
        }

        // 演示UNNEST函数
        List<Expression> unnestArgs = Arrays.asList(
            new LiteralExpression("[1,2,3,4,5]", LiteralType.STRING) // 简化的数组表示
        );

        TableFunctionNamespace unnestNs = new TableFunctionNamespace(
            "UNNEST", unnestArgs, "u"
        );
        unnestNs.validate();

        System.out.println("\nUNNEST namespace:");
        System.out.println("  Name: " + unnestNs.getName());
        System.out.println("  Type: " + unnestNs.getType());
        System.out.println("  Function: " + unnestNs.getFunctionName());
        for (Column column : unnestNs.getColumns()) {
            System.out.println("  - Column: " + column.getName() + " (" + column.getType() + ")");
        }

        System.out.println("表值函数解析完成\n");
    }

    /**
     * 演示JOIN的作用域解析
     */
    private static void demonstrateJoinScope(SemanticAnalyzerScopeUtil scopeUtil) throws SemanticException {
        System.out.println("3. JOIN作用域解析");
        System.out.println("---------------");

        // 创建左表namespace (users表)
        Table usersTable = new Table("users", Arrays.asList(
            new Column("id", "INT"),
            new Column("name", "VARCHAR"),
            new Column("age", "INT")
        ));
        TableNamespace leftNs = new TableNamespace(usersTable, "u");

        // 创建右表namespace (orders表)
        Table ordersTable = new Table("orders", Arrays.asList(
            new Column("id", "INT"),
            new Column("user_id", "INT"),
            new Column("amount", "DECIMAL")
        ));
        TableNamespace rightNs = new TableNamespace(ordersTable, "o");

        // 创建JOIN条件
        ColumnExpression leftCol = new ColumnExpression("u", "id");
        ColumnExpression rightCol = new ColumnExpression("o", "user_id");
        ComparisonExpression joinCondition = new ComparisonExpression(
            leftCol, ComparisonOperator.EQUALS, rightCol
        );

        // 创建JOIN namespace
        JoinNamespace joinNs = new JoinNamespace(
            leftNs, rightNs, 
            SelectStatement.JoinClause.JoinType.INNER,
            joinCondition, null
        );
        joinNs.validate();

        System.out.println("JOIN namespace:");
        System.out.println("  Name: " + joinNs.getName());
        System.out.println("  Type: " + joinNs.getType());
        System.out.println("  Join Type: " + joinNs.getJoinType());
        System.out.println("  Column count: " + joinNs.getColumnCount());

        System.out.println("  Columns:");
        for (Column column : joinNs.getColumns()) {
            System.out.println("    - " + column.getName() + " (" + column.getType() + ")");
        }

        System.out.println("JOIN解析完成\n");
    }

    /**
     * 创建测试用的目录管理器
     */
    private static CatalogManager createTestCatalog() {
        CatalogManager catalogManager = new CatalogManager("test.db");

        // 创建users表
        Table usersTable = new Table("users", Arrays.asList(
            new Column("id", "INT"),
            new Column("name", "VARCHAR"),
            new Column("age", "INT"),
            new Column("email", "VARCHAR")
        ));

        // 创建orders表
        Table ordersTable = new Table("orders", Arrays.asList(
            new Column("id", "INT"),
            new Column("user_id", "INT"),
            new Column("amount", "DECIMAL"),
            new Column("order_date", "DATE")
        ));

        // 创建products表
        Table productsTable = new Table("products", Arrays.asList(
            new Column("id", "INT"),
            new Column("name", "VARCHAR"),
            new Column("price", "DECIMAL"),
            new Column("category", "VARCHAR")
        ));

        // 注册表到目录管理器
        try {
            catalogManager.createTable(usersTable);
            catalogManager.createTable(ordersTable);
            catalogManager.createTable(productsTable);
        } catch (Exception e) {
            // 忽略创建表的异常，假设目录管理器已正确设置
        }

        return catalogManager;
    }
} 