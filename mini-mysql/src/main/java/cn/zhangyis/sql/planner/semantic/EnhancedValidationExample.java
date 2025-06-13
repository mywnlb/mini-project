package cn.zhangyis.sql.planner.semantic;

import cn.zhangyis.sql.parser.expression.ColumnExpression;
import cn.zhangyis.sql.parser.expression.Expression;
import cn.zhangyis.sql.parser.expression.LiteralExpression;
import cn.zhangyis.sql.parser.SelectStatement;
import cn.zhangyis.storage.catalog.CatalogManager;
import cn.zhangyis.storage.catalog.Column;
import cn.zhangyis.storage.catalog.Table;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 演示重构后的ValidateTablesAndColumnsUtil完整功能
 * 包括：表名列名验证、星号展开、类型推断、表达式验证
 * 
 * 参考: https://zhuanlan.zhihu.com/p/58139279
 * 在校验过程中完成：类型推断(DeriveTypeVisitor)、对表达式和star做展开等
 * 
 * @Description 增强的语义验证示例
 * @Date 2025/6/6
 * @Created by libo
 */
public class EnhancedValidationExample {

    public static void main(String[] args) {
        System.out.println("=== 演示重构后的ValidateTablesAndColumnsUtil ===");
        
        try {
            // 1. 设置测试环境
            CatalogManager catalogManager = setupTestCatalog();
            SqlValidatorScope rootScope = new SqlValidatorScope(null, SqlValidatorScope.ScopeType.TOP_LEVEL);
            
            // 2. 测试基础表验证
            testBasicTableValidation(catalogManager, rootScope);
            
            // 3. 测试星号展开功能
            testStarExpansion(catalogManager, rootScope);
            
            // 4. 测试类型推断功能
            testTypeInference(catalogManager, rootScope);
            
            // 5. 测试常量折叠功能
            testConstantFolding(catalogManager, rootScope);
            
            System.out.println("\n=== 所有测试完成 ===");
            
        } catch (Exception e) {
            System.err.println("示例执行失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 设置测试用的catalog
     */
    private static CatalogManager setupTestCatalog() {
        CatalogManager catalogManager = new CatalogManager();
        
        // 创建用户表
        List<Column> userColumns = Arrays.asList(
            new Column("id", "BIGINT"),
            new Column("name", "VARCHAR"),
            new Column("age", "INT"),
            new Column("email", "VARCHAR"),
            new Column("salary", "DOUBLE")
        );
        Table userTable = new Table("users", userColumns);
        catalogManager.createTable(userTable);
        
        return catalogManager;
    }

    /**
     * 测试基础表验证
     */
    private static void testBasicTableValidation(CatalogManager catalogManager, SqlValidatorScope rootScope) {
        System.out.println("\n--- 测试基础表验证 ---");
        
        try {
            // 构建简单SELECT语句
            SelectStatement select = new SelectStatement();
            
            // FROM子句
            List<SelectStatement.TableReference> fromList = new ArrayList<>();
            fromList.add(new SelectStatement.TableName("users"));
            select.setFrom(fromList);
            
            // SELECT子句
            List<SelectStatement.SelectItem> selectItems = new ArrayList<>();
            selectItems.add(new SelectStatement.SelectItem(
                new ColumnExpression("users", "name"), null));
            selectItems.add(new SelectStatement.SelectItem(
                new ColumnExpression("users", "age"), null));
            select.setSelectItems(selectItems);
            
            // 注册命名空间和作用域
            SemanticAnalyzerScopeUtil.buildNamespaces(select, rootScope, catalogManager);
            
            // 执行完整验证
            ValidateTablesAndColumnsUtil.validate(select, rootScope);
            
            System.out.println("✓ 基础表验证通过");
            System.out.println("  验证内容：表存在性、列存在性、类型推断");
            
            // 打印推断的类型
            for (SelectStatement.SelectItem item : select.getSelectItems()) {
                if (item.getExpression() instanceof ColumnExpression) {
                    ColumnExpression col = (ColumnExpression) item.getExpression();
                    System.out.println("  列 " + col.getColumnName() + " 类型: " + col.getType());
                }
            }
            
        } catch (Exception e) {
            System.err.println("✗ 基础表验证失败: " + e.getMessage());
        }
    }

    /**
     * 测试星号展开功能
     */
    private static void testStarExpansion(CatalogManager catalogManager, SqlValidatorScope rootScope) {
        System.out.println("\n--- 测试星号展开功能 ---");
        
        try {
            // 构建包含星号的SELECT语句
            SelectStatement select = new SelectStatement();
            
            // FROM子句
            List<SelectStatement.TableReference> fromList = new ArrayList<>();
            fromList.add(new SelectStatement.TableName("users"));
            select.setFrom(fromList);
            
            // SELECT子句 - 包含星号
            List<SelectStatement.SelectItem> selectItems = new ArrayList<>();
            ColumnExpression starExpr = new ColumnExpression(null, "*");
            selectItems.add(new SelectStatement.SelectItem(starExpr, null)); // SELECT *
            select.setSelectItems(selectItems);
            
            System.out.println("展开前的SELECT项数量: " + select.getSelectItems().size());
            
            // 注册命名空间和作用域
            rootScope = new SqlValidatorScope(null, SqlValidatorScope.ScopeType.TOP_LEVEL);
            SemanticAnalyzerScopeUtil.buildNamespaces(select, rootScope, catalogManager);
            
            // 执行完整验证（包含星号展开）
            ValidateTablesAndColumnsUtil.validate(select, rootScope);
            
            System.out.println("✓ 星号展开成功");
            System.out.println("展开后的SELECT项数量: " + select.getSelectItems().size());
            System.out.println("展开后的列:");
            for (SelectStatement.SelectItem item : select.getSelectItems()) {
                if (item.getExpression() instanceof ColumnExpression) {
                    ColumnExpression col = (ColumnExpression) item.getExpression();
                    System.out.println("  " + col.getTableAlias() + "." + col.getColumnName() + " (" + col.getType() + ")");
                }
            }
            
        } catch (Exception e) {
            System.err.println("✗ 星号展开失败: " + e.getMessage());
        }
    }

    /**
     * 测试类型推断功能
     */
    private static void testTypeInference(CatalogManager catalogManager, SqlValidatorScope rootScope) {
        System.out.println("\n--- 测试类型推断功能 ---");
        
        try {
            // 构建包含表达式的SELECT语句
            SelectStatement select = new SelectStatement();
            
            // FROM子句
            List<SelectStatement.TableReference> fromList = new ArrayList<>();
            fromList.add(new SelectStatement.TableName("users"));
            select.setFrom(fromList);
            
            // SELECT子句 - 包含算术表达式
            List<SelectStatement.SelectItem> selectItems = new ArrayList<>();
            
            // age + 1
            BinaryExpression ageExpr = new BinaryExpression(
                new ColumnExpression("users", "age"),
                "+",
                new LiteralExpression(1, LiteralType.NUMBER)
            );
            selectItems.add(new SelectStatement.SelectItem(ageExpr, "age_plus_one"));
            
            select.setSelectItems(selectItems);
            
            // 注册命名空间和作用域
            rootScope = new SqlValidatorScope(null, SqlValidatorScope.ScopeType.TOP_LEVEL);
            SemanticAnalyzerScopeUtil.buildNamespaces(select, rootScope, catalogManager);
            
            // 执行完整验证（包含类型推断）
            ValidateTablesAndColumnsUtil.validate(select, rootScope);
            
            System.out.println("✓ 类型推断成功");
            System.out.println("推断的表达式类型:");
            for (SelectStatement.SelectItem item : select.getSelectItems()) {
                System.out.println("  " + item.getAlias() + ": " + item.getExpression().getType());
            }
            
        } catch (Exception e) {
            System.err.println("✗ 类型推断失败: " + e.getMessage());
        }
    }

    /**
     * 测试常量折叠功能
     */
    private static void testConstantFolding(CatalogManager catalogManager, SqlValidatorScope rootScope) {
        System.out.println("\n--- 测试常量折叠功能 ---");
        
        try {
            // 构建包含常量表达式的SELECT语句
            SelectStatement select = new SelectStatement();
            
            // FROM子句
            List<SelectStatement.TableReference> fromList = new ArrayList<>();
            fromList.add(new SelectStatement.TableName("users"));
            select.setFrom(fromList);
            
            // SELECT子句 - 包含常量表达式
            List<SelectStatement.SelectItem> selectItems = new ArrayList<>();
            
            // 1 + 2 (应该被折叠为 3)
            BinaryExpression constExpr1 = new BinaryExpression(
                new LiteralExpression(1, LiteralType.NUMBER),
                "+",
                new LiteralExpression(2, LiteralType.NUMBER)
            );
            selectItems.add(new SelectStatement.SelectItem(constExpr1, "const_add"));
            
            // 10 * 5 (应该被折叠为 50)
            BinaryExpression constExpr2 = new BinaryExpression(
                new LiteralExpression(10, LiteralType.NUMBER),
                "*",
                new LiteralExpression(5, LiteralType.NUMBER)
            );
            selectItems.add(new SelectStatement.SelectItem(constExpr2, "const_multiply"));
            
            // 'Hello' || ' World' (应该被折叠为 'Hello World')
            BinaryExpression constExpr3 = new BinaryExpression(
                new LiteralExpression("Hello", LiteralType.STRING),
                "||",
                new LiteralExpression(" World", LiteralType.STRING)
            );
            selectItems.add(new SelectStatement.SelectItem(constExpr3, "const_concat"));
            
            // true AND false (应该被折叠为 false)
            BinaryExpression constExpr4 = new BinaryExpression(
                new LiteralExpression(true, LiteralType.BOOLEAN),
                "AND",
                new LiteralExpression(false, LiteralType.BOOLEAN)
            );
            selectItems.add(new SelectStatement.SelectItem(constExpr4, "const_and"));
            
            select.setSelectItems(selectItems);
            
            // WHERE子句 - 包含常量表达式
            // WHERE 5 > 3 (应该被折叠为 true)
            BinaryExpression whereExpr = new BinaryExpression(
                new LiteralExpression(5, LiteralType.NUMBER),
                ">",
                new LiteralExpression(3, LiteralType.NUMBER)
            );
            select.setWhere(whereExpr);
            
            System.out.println("常量折叠前的表达式:");
            for (SelectStatement.SelectItem item : select.getSelectItems()) {
                System.out.println("  " + item.getAlias() + ": " + item.getExpression());
            }
            System.out.println("  WHERE: " + select.getWhere());
            
            // 注册命名空间和作用域
            rootScope = new SqlValidatorScope(null, SqlValidatorScope.ScopeType.TOP_LEVEL);
            SemanticAnalyzerScopeUtil.buildNamespaces(select, rootScope, catalogManager);
            
            // 执行完整验证（包含常量折叠）
            ValidateTablesAndColumnsUtil.validate(select, rootScope);
            
            System.out.println("\n✓ 常量折叠成功");
            System.out.println("常量折叠后的表达式:");
            for (SelectStatement.SelectItem item : select.getSelectItems()) {
                Expression expr = item.getExpression();
                if (expr instanceof LiteralExpression) {
                    LiteralExpression literal = (LiteralExpression) expr;
                    System.out.println("  " + item.getAlias() + ": " + literal.getValue() + " (折叠为常量)");
                } else {
                    System.out.println("  " + item.getAlias() + ": " + expr + " (未折叠)");
                }
            }
            
            // 检查WHERE子句
            if (select.getWhere() instanceof LiteralExpression) {
                LiteralExpression whereResult = (LiteralExpression) select.getWhere();
                System.out.println("  WHERE: " + whereResult.getValue() + " (折叠为常量)");
            } else {
                System.out.println("  WHERE: " + select.getWhere() + " (未折叠)");
            }
            
        } catch (Exception e) {
            System.err.println("✗ 常量折叠失败: " + e.getMessage());
        }
    }
} 