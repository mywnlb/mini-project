//package cn.zhangyis.sql;
//
//import cn.zhangyis.sql.parser.*;
//import cn.zhangyis.sql.parser.expression.*;
//import cn.zhangyis.sql.planner.semantic.*;
//import cn.zhangyis.sql.planner.semantic.scope.SemanticAnalyzerScopeUtil;
//import cn.zhangyis.sql.planner.semantic.scope.SqlValidatorScope;
//import cn.zhangyis.storage.catalog.*;
//import org.junit.Before;
//import org.junit.Test;
//
//import java.util.*;
//
//import static org.junit.Assert.*;
//
///**
// * SemanticAnalyzerScopeUtil的单元测试
// * 测试各种SQL子句的作用域构建和验证
// */
//public class SemanticAnalyzerScopeUtilTest {
//
//    private CatalogManager catalogManager;
//    private SemanticAnalyzerScopeUtil scopeUtil;
//
//    @Before
//    public void setUp() {
//        // 初始化目录管理器并添加测试表
//        catalogManager = new CatalogManager();
//
//        // 创建测试表: users
//        List<Column> userColumns = new ArrayList<>();
//        userColumns.add(new Column("id", "INTEGER", false));
//        userColumns.add(new Column("name", "VARCHAR", true));
//        userColumns.add(new Column("age", "INTEGER", true));
//        userColumns.add(new Column("dept_id", "INTEGER", true));
//        Table usersTable = new Table("users", userColumns);
//        catalogManager.addTable(usersTable);
//
//        // 创建测试表: departments
//        List<Column> deptColumns = new ArrayList<>();
//        deptColumns.add(new Column("id", "INTEGER", false));
//        deptColumns.add(new Column("name", "VARCHAR", true));
//        deptColumns.add(new Column("location", "VARCHAR", true));
//        Table deptsTable = new Table("departments", deptColumns);
//        catalogManager.addTable(deptsTable);
//
//        // 初始化作用域工具
//        scopeUtil = new SemanticAnalyzerScopeUtil(catalogManager);
//    }
//
//    /**
//     * 测试基本的SELECT语句作用域构建
//     */
//    @Test
//    public void testBasicSelectScope() throws SemanticException {
//        // 构建简单SELECT语句
//        SelectStatement select = createSimpleSelect();
//
//        // 分析作用域
//        SqlValidatorScope rootScope = scopeUtil.analyzeScopes(select, null);
//
//        // 验证SELECT语句的作用域
//        assertNotNull("SELECT语句作用域不应为空", scopeUtil.getScope(select));
//        assertEquals("作用域类型应为SELECT", SqlValidatorScope.ScopeType.SELECT,
//                    scopeUtil.getScope(select).getScopeType());
//
//        // 验证WHERE子句的作用域
//        assertNotNull("WHERE子句作用域不应为空", scopeUtil.getExpressionScope(select.getWhere()));
//        assertEquals("WHERE作用域类型应为WHERE", SqlValidatorScope.ScopeType.WHERE,
//                    scopeUtil.getExpressionScope(select.getWhere()).getScopeType());
//    }
//
//    /**
//     * 测试带有GROUP BY和HAVING的SELECT语句作用域构建
//     */
//    @Test
//    public void testGroupByAndHavingScopes() throws SemanticException {
//        // 构建带GROUP BY和HAVING的SELECT语句
//        SelectStatement select = createGroupBySelect();
//
//        // 分析作用域
//        SqlValidatorScope rootScope = scopeUtil.analyzeScopes(select, null);
//
//        // 验证GROUP BY表达式的作用域
//        Expression groupByExpr = select.getGroupByColumns().get(0);
//        assertNotNull("GROUP BY表达式作用域不应为空", scopeUtil.getExpressionScope(groupByExpr));
//        assertEquals("GROUP BY作用域类型应为GROUP_BY", SqlValidatorScope.ScopeType.GROUP_BY,
//                    scopeUtil.getExpressionScope(groupByExpr).getScopeType());
//
//        // 验证HAVING子句的作用域
//        assertNotNull("HAVING子句作用域不应为空", scopeUtil.getExpressionScope(select.getHaving()));
//        assertEquals("HAVING作用域类型应为HAVING", SqlValidatorScope.ScopeType.HAVING,
//                    scopeUtil.getExpressionScope(select.getHaving()).getScopeType());
//    }
//
//    /**
//     * 测试带有ORDER BY的SELECT语句作用域构建
//     */
//    @Test
//    public void testOrderByScope() throws SemanticException {
//        // 构建带ORDER BY的SELECT语句
//        SelectStatement select = createOrderBySelect();
//
//        // 分析作用域
//        SqlValidatorScope rootScope = scopeUtil.analyzeScopes(select, null);
//
//        // 验证ORDER BY表达式的作用域
//        Expression orderByExpr = select.getOrderByItems().get(0).getExpression();
//        assertNotNull("ORDER BY表达式作用域不应为空", scopeUtil.getExpressionScope(orderByExpr));
//        assertEquals("ORDER BY作用域类型应为ORDER_BY", SqlValidatorScope.ScopeType.ORDER_BY,
//                    scopeUtil.getExpressionScope(orderByExpr).getScopeType());
//    }
//
//    /**
//     * 测试带有JOIN的SELECT语句作用域构建
//     */
//    @Test
//    public void testJoinScope() throws SemanticException {
//        // 构建带JOIN的SELECT语句
//        SelectStatement select = createJoinSelect();
//
//        // 分析作用域
//        SqlValidatorScope rootScope = scopeUtil.analyzeScopes(select, null);
//
//        // 验证JOIN条件的作用域
//        Expression joinCondition = select.getJoins().get(0).getJoinCondition();
//        assertNotNull("JOIN条件作用域不应为空", scopeUtil.getExpressionScope(joinCondition));
//        assertEquals("JOIN条件作用域类型应为FROM", SqlValidatorScope.ScopeType.FROM,
//                    scopeUtil.getExpressionScope(joinCondition).getScopeType());
//    }
//
//    // 辅助方法 - 创建简单SELECT语句
//    private SelectStatement createSimpleSelect() {
//        // SELECT id, name FROM users WHERE age > 18
//        List<SelectStatement.SelectItem> selectItems = new ArrayList<>();
//        selectItems.add(new SelectStatement.SelectItem(new ColumnExpression("id"), null));
//        selectItems.add(new SelectStatement.SelectItem(new ColumnExpression("name"), null));
//
//        List<SelectStatement.TableReference> fromTables = new ArrayList<>();
//        fromTables.add(new SelectStatement.TableReference("users", null));
//
//        Expression whereCondition = new ComparisonExpression(
//            new ColumnExpression("age"),
//            new LiteralExpression("18", LiteralExpression.LiteralType.INTEGER),
//            ComparisonExpression.ComparisonOperator.GREATER_THAN
//        );
//
//        return new SelectStatement(
//            selectItems, fromTables, null, whereCondition,
//            null, null, null, null, null, false
//        );
//    }
//
//    // 辅助方法 - 创建带GROUP BY和HAVING的SELECT语句
//    private SelectStatement createGroupBySelect() {
//        // SELECT dept_id, COUNT(*) as count FROM users GROUP BY dept_id HAVING COUNT(*) > 5
//        List<SelectStatement.SelectItem> selectItems = new ArrayList<>();
//        selectItems.add(new SelectStatement.SelectItem(new ColumnExpression("dept_id"), null));
//
//        List<Expression> args = new ArrayList<>();
//        args.add(new LiteralExpression("*", LiteralExpression.LiteralType.IDENTIFIER));
//        selectItems.add(new SelectStatement.SelectItem(
//            new FunctionExpression("COUNT", args, true, false),
//            "count"
//        ));
//
//        List<SelectStatement.TableReference> fromTables = new ArrayList<>();
//        fromTables.add(new SelectStatement.TableReference("users", null));
//
//        List<Expression> groupByColumns = new ArrayList<>();
//        groupByColumns.add(new ColumnExpression("dept_id"));
//
//        Expression havingCondition = new ComparisonExpression(
//            new FunctionExpression("COUNT", args, true, false),
//            new LiteralExpression("5", LiteralExpression.LiteralType.INTEGER),
//            ComparisonExpression.ComparisonOperator.GREATER_THAN
//        );
//
//        return new SelectStatement(
//            selectItems, fromTables, null, null,
//            groupByColumns, havingCondition, null, null, null, false
//        );
//    }
//
//    // 辅助方法 - 创建带ORDER BY的SELECT语句
//    private SelectStatement createOrderBySelect() {
//        // SELECT id, name FROM users ORDER BY name DESC
//        List<SelectStatement.SelectItem> selectItems = new ArrayList<>();
//        selectItems.add(new SelectStatement.SelectItem(new ColumnExpression("id"), null));
//        selectItems.add(new SelectStatement.SelectItem(new ColumnExpression("name"), null));
//
//        List<SelectStatement.TableReference> fromTables = new ArrayList<>();
//        fromTables.add(new SelectStatement.TableReference("users", null));
//
//        List<SelectStatement.OrderByItem> orderByItems = new ArrayList<>();
//        orderByItems.add(new SelectStatement.OrderByItem(new ColumnExpression("name"), false));
//
//        return new SelectStatement(
//            selectItems, fromTables, null, null,
//            null, null, orderByItems, null, null, false
//        );
//    }
//
//    // 辅助方法 - 创建带JOIN的SELECT语句
//    private SelectStatement createJoinSelect() {
//        // SELECT u.id, u.name, d.name as dept_name
//        // FROM users u JOIN departments d ON u.dept_id = d.id
//        List<SelectStatement.SelectItem> selectItems = new ArrayList<>();
//        selectItems.add(new SelectStatement.SelectItem(new ColumnExpression("u", "id"), null));
//        selectItems.add(new SelectStatement.SelectItem(new ColumnExpression("u", "name"), null));
//        selectItems.add(new SelectStatement.SelectItem(new ColumnExpression("d", "name"), "dept_name"));
//
//        List<SelectStatement.TableReference> fromTables = new ArrayList<>();
//        fromTables.add(new SelectStatement.TableReference("users", "u"));
//
//        List<SelectStatement.JoinClause> joins = new ArrayList<>();
//        SelectStatement.TableReference joinTable = new SelectStatement.TableReference("departments", "d");
//
//        Expression joinCondition = new ComparisonExpression(
//            new ColumnExpression("u", "dept_id"),
//            new ColumnExpression("d", "id"),
//            ComparisonExpression.ComparisonOperator.EQUALS
//        );
//
//        joins.add(new SelectStatement.JoinClause(
//            JoinType.INNER,
//            joinTable,
//            joinCondition
//        ));
//
//        return new SelectStatement(
//            selectItems, fromTables, joins, null,
//            null, null, null, null, null, false
//        );
//    }
//}
