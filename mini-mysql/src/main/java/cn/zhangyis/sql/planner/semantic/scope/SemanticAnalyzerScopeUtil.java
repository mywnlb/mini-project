package cn.zhangyis.sql.planner.semantic.scope;

import cn.zhangyis.sql.parser.*;
import cn.zhangyis.sql.parser.expression.*;
import cn.zhangyis.sql.planner.semantic.SemanticException;
import cn.zhangyis.storage.catalog.CatalogManager;
import cn.zhangyis.storage.catalog.Table;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 语义分析器作用域工具类 - 严格按照Apache Calcite SqlValidatorImpl标准
 * <p>
 * 专注于 Apache Calcite SqlValidatorImpl 的核心功能：
 * 1. registerQuery() - 注册查询，构建scope和namespace
 * 2. validateQueryStructure() - 基础语义验证（不与DefaultSemanticAnalyzer重复）
 * <p>
 * 职责边界：
 * - 本类：构建scope和namespace，基础的结构性验证
 * - DefaultSemanticAnalyzer：详细的表名列名验证、类型推导、常量折叠等
 * <p>
 * 参考: Apache Calcite SqlValidatorImpl.java
 * https://github.com/apache/calcite/blob/master/core/src/main/java/org/apache/calcite/sql/validate/SqlValidatorImpl.java
 *
 * @Description 严格按照Calcite标准的语义分析器工具类
 * @Date 2025/1/28 18:00
 * @Created by libo
 */
public class SemanticAnalyzerScopeUtil {

    private CatalogManager catalogManager;

    public SemanticAnalyzerScopeUtil(CatalogManager catalogManager) {
        this.catalogManager = catalogManager;
    }

    /**
     * 核心分析方法 - 按照Calcite SqlValidatorImpl的两阶段处理
     * <p>
     * 注意：此方法假设 AST 已经经过无条件重写处理
     * <p>
     * 两阶段处理：
     * 1. registerQuery() - 注册查询，构建scope和namespace
     * 2. validateQueryStructure() - 基础语义验证（结构性验证，不重复详细验证）
     *
     * @param statement   已经过无条件重写的SQL语句AST
     * @param parentScope 父作用域，可以为null
     * @return 验证后的根作用域
     * @throws SemanticException 语义错误
     */
    public SqlValidatorScope analyzeScopes(SQLStatement statement, SqlValidatorScope parentScope) throws SemanticException {
        Objects.requireNonNull(statement, "Statement cannot be null");

        // 创建根作用域
        SqlValidatorScope rootScope = parentScope != null ? parentScope : new SqlValidatorScope();

        // 阶段1: 注册查询 - 构建scope和namespace (对应Calcite的registerQuery)
        registerQuery(rootScope, null, statement, statement, null, false);

        // 阶段2: 基础验证 - 仅做结构性验证 (对应Calcite的validateQuery基础部分)
//        validateQueryStructure(statement, rootScope);

        return rootScope;
    }

    /**
     * 注册查询 - 完全按照Calcite SqlValidatorImpl.registerQuery()实现
     * <p>
     * 这是Calcite中最核心的方法，负责：
     * 1. 创建正确的作用域层次结构
     * 2. 为每个节点注册对应的namespace
     * 3. 处理表引用和子查询的递归注册
     * 4. 建立IdentityMapping关系
     *
     * @param parentScope   父作用域
     * @param usingScope    使用的作用域
     * @param node          当前节点
     * @param enclosingNode 包围节点
     * @param alias         别名
     * @param forceNullable 是否强制nullable
     */
    protected void registerQuery(
            SqlValidatorScope parentScope,
            SqlValidatorScope usingScope,
            SQLStatement node,
            SQLStatement enclosingNode,
            String alias,
            boolean forceNullable) throws SemanticException {

        Objects.requireNonNull(node);
        Objects.requireNonNull(enclosingNode);

        // 按照语句类型分发注册逻辑
        switch (node.getType()) {
            case SELECT:
                registerSelect(parentScope, usingScope, (SelectStatement) node, enclosingNode, alias, forceNullable);
                break;
            case UPDATE:
                registerUpdate(parentScope, usingScope, (UpdateStatement) node, enclosingNode, alias, forceNullable);
                break;
            case DELETE:
                registerDelete(parentScope, usingScope, (DeleteStatement) node, enclosingNode, alias, forceNullable);
                break;
            case INSERT:
                registerInsert(parentScope, usingScope, (InsertStatement) node, enclosingNode, alias, forceNullable);
                break;
            default:
                throw new SemanticException("Unsupported statement type: " + node.getType());
        }
    }

    /**
     * 注册SELECT语句 - 按照SQL执行顺序创建作用域层次结构
     * <p>
     * 严格遵循 FROM -> WHERE -> GROUP BY -> HAVING -> SELECT -> ORDER BY 的逻辑顺序
     */
    private void registerSelect(
            SqlValidatorScope parentScope,
            SqlValidatorScope usingScope,
            SelectStatement select,
            SQLStatement enclosingNode,
            String alias,
            boolean forceNullable) throws SemanticException {


        // 1. 按SQL执行顺序构建作用域层次结构
        // FROM子句作用域 - 第一层 (最基础的作用域)
        SqlValidatorScope fromScope = null;
        if (select.getFrom() != null && !select.getFrom().isEmpty()) {
            fromScope = registerFrom(parentScope, select.getFrom(), select);
        } else {
            // 如果没有FROM子句，使用SELECT作用域作为默认作用域
            fromScope = parentScope;
        }

        // WHERE子句作用域 - 直接基于FROM (与GroupBy是兄弟关系，而非父子关系)
        SqlValidatorScope whereScope = null;
        if (select.getWhere() != null) {
            whereScope = new SqlValidatorScope(fromScope, SqlValidatorScope.ScopeType.WHERE);
        }

        // GROUP BY子句作用域 - 直接基于FROM (与Where是兄弟关系)
        SqlValidatorScope groupByScope = fromScope;
        if (select.getGroupByColumns() != null && !select.getGroupByColumns().isEmpty()) {
            groupByScope = new SqlValidatorScope(fromScope, SqlValidatorScope.ScopeType.GROUP_BY);
        }


        // HAVING子句作用域 - 基于GROUP BY
        SqlValidatorScope havingScope = groupByScope;
        if (select.getHaving() != null) {
            havingScope = new SqlValidatorScope(groupByScope, SqlValidatorScope.ScopeType.HAVING);
        }

        // SELECT项目作用域 - 基于GROUP BY或HAVING(若存在)
        // 注意：SELECT可以看到GROUP BY和HAVING中的内容
        SqlValidatorScope projectScope = null;
        if (Objects.nonNull(groupByScope)) {
            projectScope = new SqlValidatorScope(groupByScope, SqlValidatorScope.ScopeType.SELECT);
        } else {
            // 如果没有SELECT项，使用HAVING作用域
            projectScope = fromScope;

        }

//        // 处理SELECT项中的表达式和子查询
//        for (SelectStatement.SelectItem item : select.getSelectItems()) {
//            registerExpressionSubqueries(item.getExpression(), projectScope);
//        }

        // ORDER BY子句作用域 - 基于SELECT项 (可以引用SELECT的别名)
        SqlValidatorScope orderByScope = null;
        if (select.getOrderByItems() != null && !select.getOrderByItems().isEmpty()) {
            orderByScope = new SqlValidatorScope(projectScope, SqlValidatorScope.ScopeType.ORDER_BY);
        }

        // 3. 创建SelectNamespace并进行验证
//        SelectNamespace selectNamespace = new SelectNamespace(select);
//        try {
//            selectNamespace.validate(); // 尝试验证命名空间
//        } catch (SemanticException e) {
//            // 初始阶段可以忽略验证错误，稍后会进行完整验证
//        }

        // 4. 注册namespace到使用它的作用域
        // 如果有别名，使用别名；否则，使用默认名称
//        String nameInScope = alias != null ? alias : "SELECT_" + System.identityHashCode(select);
//        registerNamespace(usingScope, nameInScope, selectNamespace, forceNullable);

//        // 5. 额外处理JOIN条件中的子查询
//        // 已在各个子句中处理了表达式的子查询，这里特别处理JOIN条件
//        if (select.getJoins() != null) {
//            for (SelectStatement.JoinClause join : select.getJoins()) {
//                if (join.getJoinCondition() != null) {
//                    // 获取JOIN作用域
//                    SqlValidatorScope joinScope = fromScope;
//                    registerExpressionSubqueries(join.getJoinCondition(), joinScope);
//                }
//            }
//        }
    }


    /**
     * 注册FROM子句 - 完全按照Calcite SqlValidatorImpl.validateFrom()的模式
     */
    private SqlValidatorScope registerFrom(
            SqlValidatorScope selectScope,
            List<SelectStatement.TableReference> fromList,
            SelectStatement select) throws SemanticException {

        // 1. 创建FromScope - Calcite标准做法
        SqlValidatorScope fromScope = new SqlValidatorScope(selectScope, SqlValidatorScope.ScopeType.FROM);

        // 2. 处理FROM子句中的每个表引用
        for (SelectStatement.TableReference tableRef : fromList) {
            registerTableReference(fromScope, tableRef, select);
        }

        // 3. 处理JOIN子句 - 增强的JOIN处理
        if (select.getJoins() != null) {
            for (SelectStatement.JoinClause join : select.getJoins()) {
                registerJoinOperation(fromScope, join, select);
            }
        }

        return fromScope;
    }

    /**
     * 注册单个表引用 - 按照Calcite的标准模式
     */
    private void registerTableReference(
            SqlValidatorScope fromScope,
            SelectStatement.TableReference tableRef,
            SelectStatement enclosingSelect) throws SemanticException {

        SqlValidatorNamespace namespace = null;

        if (tableRef.isSubquery()) {
            // 子查询处理 - 递归调用registerQuery
            SelectStatement subquery = tableRef.getSubquery();

            // 为子查询创建新的作用域
            SqlValidatorScope subqueryScope = new SqlValidatorScope(fromScope, SqlValidatorScope.ScopeType.SUBQUERY);

            // 递归注册子查询 - 这是关键的递归点
            registerQuery(subqueryScope, null, subquery, enclosingSelect, tableRef.getAlias(), false);

            // 创建子查询namespace
            namespace = new SubqueryNamespace(subquery, tableRef.getAlias());

        } else if (tableRef.isValues()) {
            // VALUES子句处理 - 新增支持
            namespace = new ValuesNamespace(tableRef.getValuesList(), tableRef.getAlias());

        } else if (tableRef.isTableFunction()) {
            // 表值函数处理 - 新增支持
//            namespace = new TableFunctionNamespace(
//                    tableRef.getFunctionName(),
//                    tableRef.getFunctionArguments(),
//                    tableRef.getAlias()
//            );

        } else {
            // 普通表引用处理
            String tableName = tableRef.getTableName();

            // 验证表是否存在
            if (!catalogManager.tableExists(tableName)) {
                throw new SemanticException.TableNotFoundException(tableName);
            }

            Table table = catalogManager.getTable(tableName);

            // 创建表namespace
            namespace = new TableNamespace(table, tableRef.getAlias());
        }

        // 注册namespace到当前作用域 - 按照Calcite的registerNamespace()
        String nameInScope = tableRef.getAlias() != null ? tableRef.getAlias() : tableRef.getTableName();
        registerNamespace(fromScope, nameInScope, namespace, false);
    }

    /**
     * 注册JOIN操作 - 不创建，直接注册JOIN的右表
     * 直接判断 condation中的表
     */
    private void registerJoinOperation(
            SqlValidatorScope fromScope,
            SelectStatement.JoinClause join,
            SelectStatement select) throws SemanticException {

        // 注册JOIN的右表
        registerTableReference(fromScope, join.getJoinTable(), select);

        //todo 判断join condation 中的字段是否存在from scope中 还是移动到 专门校验的模块中去
    }

    /**
     * 在作用域中查找最后添加的命名空间（通常是左表）
     */
    private SqlValidatorNamespace findLastNamespaceInScope(SqlValidatorScope scope) {
        if (scope == null || scope.getNamespaces().isEmpty()) {
            return null;
        }

        // 获取作用域中的最后一个命名空间
        Map<String, SqlValidatorNamespace> namespaceMap = scope.getNamespaces();
        if (!namespaceMap.isEmpty()) {
            // 使用迭代器获取最后一个元素
            String lastKey = null;
            for (String key : namespaceMap.keySet()) {
                lastKey = key;
            }
            return namespaceMap.get(lastKey);
        }

        return null;
    }

    /**
     * 在表达式中注册子查询 - 增强版，递归处理复合表达式
     * <p>
     * 处理所有类型的表达式：
     * - SubqueryExpression: 直接注册子查询语句
     * - BinaryExpression (包括 ArithmeticExpression, ComparisonExpression, LogicalExpression): 递归处理左右操作数
     * - FunctionExpression: 递归处理所有参数
     * - InListExpression: 递归处理左侧表达式和所有值
     * - ColumnExpression 和 LiteralExpression: 叶子节点，无需处理
     */
    private void registerExpressionSubqueries(Expression expr, SqlValidatorScope scope) throws SemanticException {
        if (expr == null) {
            return;
        }

        // 根据表达式类型进行特定处理
        switch (expr.getType()) {
            case SUBQUERY:
                SubqueryExpression subqueryExpr = (SubqueryExpression) expr;

                // 处理子查询左侧表达式（用于IN, ANY, ALL比较）
                if (subqueryExpr.getLeftExpression() != null) {
                    registerExpressionSubqueries(subqueryExpr.getLeftExpression(), scope);
                }

                // 递归注册子查询
                registerQuery(scope, null, subqueryExpr.getSubquery(), subqueryExpr.getSubquery(), null, false);
                break;

            case ARITHMETIC:
            case COMPARISON:
            case LOGICAL:
                // 二元表达式：处理左右操作数
                BinaryExpression binExpr = (BinaryExpression) expr;
                registerExpressionSubqueries(binExpr.getLeft(), scope);
                registerExpressionSubqueries(binExpr.getRight(), scope);
                break;

            case FUNCTION:
                // 函数表达式：处理所有参数
                FunctionExpression funcExpr = (FunctionExpression) expr;
                for (Expression arg : funcExpr.getArguments()) {
                    registerExpressionSubqueries(arg, scope);
                }
                break;

            case IN_LIST:
                // IN列表表达式：处理左侧表达式和所有值
                InListExpression inExpr = (InListExpression) expr;
                registerExpressionSubqueries(inExpr.getLeft(), scope);
                for (Expression value : inExpr.getValues()) {
                    registerExpressionSubqueries(value, scope);
                }
                break;

            case COLUMN:
            case LITERAL:
                // 叶子节点，无需处理
                break;

            default:
                // 对于其他类型的表达式，使用通用的getChildExpressions方法
                for (Expression subExpr : expr.getChildExpressions()) {
                    registerExpressionSubqueries(subExpr, scope);
                }
                break;
        }
    }

    /**
     * 注册DELETE语句
     */
    private void registerDelete(
            SqlValidatorScope parentScope,
            SqlValidatorScope usingScope,
            DeleteStatement delete,
            SQLStatement enclosingNode,
            String alias,
            boolean forceNullable) throws SemanticException {

        SqlValidatorScope deleteScope = new SqlValidatorScope(parentScope, SqlValidatorScope.ScopeType.SELECT);

        // 注册目标表
        String tableName = delete.getTableName();
        if (!catalogManager.tableExists(tableName)) {
            throw new SemanticException.TableNotFoundException(tableName);
        }

        Table table = catalogManager.getTable(tableName);
        TableNamespace tableNamespace = new TableNamespace(table, null);

        // 注册命名空间到作用域
        registerNamespace(deleteScope, tableName, tableNamespace, false);

        // 如果有使用作用域，也注册到使用作用域
        if (usingScope != null) {
            String nameInScope = alias != null ? alias : "DELETE_" + System.identityHashCode(delete);
            registerNamespace(usingScope, nameInScope, tableNamespace, forceNullable);
        }

        // 处理WHERE条件中的子查询
        if (delete.getWhereCondition() != null) {
            registerExpressionSubqueries(delete.getWhereCondition(), deleteScope);
        }

        // 处理内嵌的SELECT语句
        if (delete.getSelectStatement() != null) {
            registerQuery(deleteScope, null, delete.getSelectStatement(), delete, null, false);
        }
    }

    /**
     * 注册UPDATE语句
     */
    private void registerUpdate(
            SqlValidatorScope parentScope,
            SqlValidatorScope usingScope,
            UpdateStatement update,
            SQLStatement enclosingNode,
            String alias,
            boolean forceNullable) throws SemanticException {

        SqlValidatorScope updateScope = new SqlValidatorScope(parentScope, SqlValidatorScope.ScopeType.SELECT);

        // 注册目标表
        String tableName = update.getTableName();
        if (!catalogManager.tableExists(tableName)) {
            throw new SemanticException.TableNotFoundException(tableName);
        }

        Table table = catalogManager.getTable(tableName);
        TableNamespace tableNamespace = new TableNamespace(table, null);

        // 注册命名空间到作用域
        registerNamespace(updateScope, tableName, tableNamespace, false);

        // 如果有使用作用域，也注册到使用作用域
        if (usingScope != null) {
            String nameInScope = alias != null ? alias : "UPDATE_" + System.identityHashCode(update);
            registerNamespace(usingScope, nameInScope, tableNamespace, forceNullable);
        }

        // 处理SET子句中的表达式
//        if (update.getSetItems() != null) {
//            for (UpdateStatement.SetItem setItem : update.getSetItems()) {
//                registerExpressionSubqueries(setItem.getValue(), updateScope);
//            }
//        }

        // 处理WHERE条件中的子查询
        if (update.getWhereCondition() != null) {
            registerExpressionSubqueries(update.getWhereCondition(), updateScope);
        }

        // 处理内嵌的SELECT语句（来自SqlRewriter的重写）
        if (update.getSelectStatement() != null) {
            registerQuery(updateScope, null, update.getSelectStatement(), update, null, false);
        }
    }

    /**
     * 注册INSERT语句
     */
    private void registerInsert(
            SqlValidatorScope parentScope,
            SqlValidatorScope usingScope,
            InsertStatement insert,
            SQLStatement enclosingNode,
            String alias,
            boolean forceNullable) throws SemanticException {

        SqlValidatorScope insertScope = new SqlValidatorScope(parentScope, SqlValidatorScope.ScopeType.SELECT);

        // 注册目标表
        String tableName = insert.getTableName();
        if (!catalogManager.tableExists(tableName)) {
            throw new SemanticException.TableNotFoundException(tableName);
        }

        Table table = catalogManager.getTable(tableName);
        TableNamespace tableNamespace = new TableNamespace(table, null);

        // 注册命名空间到作用域
        registerNamespace(insertScope, tableName, tableNamespace, false);

        // 如果有使用作用域，也注册到使用作用域
        if (usingScope != null) {
            String nameInScope = alias != null ? alias : "INSERT_" + System.identityHashCode(insert);
            registerNamespace(usingScope, nameInScope, tableNamespace, forceNullable);
        }

        // 处理VALUES子句中的表达式
//        if (insert.getValues() != null) {
//            for (List<Expression> row : insert.getValues()) {
//                for (Expression expr : row) {
//                    registerExpressionSubqueries(expr, insertScope);
//                }
//            }
//        }

        // 处理INSERT ... SELECT
        if (insert.getSelectStatement() != null) {
            registerQuery(insertScope, null, insert.getSelectStatement(), insert, null, false);
        }
    }

    /**
     * 注册namespace - 按照Calcite SqlValidatorImpl.registerNamespace()
     */
    protected void registerNamespace(
            SqlValidatorScope usingScope,
            String alias,
            SqlValidatorNamespace namespace,
            boolean forceNullable) {

        if (usingScope != null && alias != null) {
            usingScope.addNamespace(alias, namespace);
        }
    }

    /**
     * 基础语义验证 - 仅做结构性验证，不重复DefaultSemanticAnalyzer的工作
     * <p>
     * 按照Apache Calcite的设计原则：
     * - 本方法只做最基础的结构性验证
     * - 详细的表名列名验证留给DefaultSemanticAnalyzer.validateTablesAndColumns()
     * - 类型推导留给DefaultSemanticAnalyzer.inferTypes()
     * - 星号展开留给DefaultSemanticAnalyzer.expandStarColumns()
     * <p>
     * 职责：验证scope和namespace构建是否正确，SQL结构是否基本合理
     */
    private void validateQueryStructure(SQLStatement statement, SqlValidatorScope scope) throws SemanticException {
        // 验证命名空间是否正确构建
        SqlValidatorNamespace statementNamespace = findNamespaceForStatement(statement, scope);
        if (statementNamespace == null && statement.getType() == SQLStatement.SQLType.SELECT) {
            throw new SemanticException("Statement namespace not properly registered for SELECT");
        }

        // 根据语句类型进行基础结构验证
        switch (statement.getType()) {
            case SELECT:
                validateSelectStructure((SelectStatement) statement);
                break;
            case UPDATE:
            case DELETE:
            case INSERT:
                // 对于DML语句，基础验证已在registerQuery中完成
                break;
            default:
                throw new SemanticException("Unsupported statement type: " + statement.getType());
        }
    }

    /**
     * 在作用域层次结构中查找语句对应的命名空间
     */
    private SqlValidatorNamespace findNamespaceForStatement(SQLStatement statement, SqlValidatorScope scope) {
        if (scope == null) {
            return null;
        }

        // 根据语句类型选择适当的命名空间类
        if (statement instanceof SelectStatement) {
            // 首先尝试作为SelectNamespace查找
            SqlValidatorNamespace namespace = scope.findNamespaceByStatement(statement, SelectNamespace.class);
            if (namespace != null) {
                return namespace;
            }

            // 如果没找到，尝试作为SubqueryNamespace查找
            return scope.findNamespaceByStatement(statement, SubqueryNamespace.class);
        } else if (statement instanceof DeleteStatement ||
                statement instanceof UpdateStatement ||
                statement instanceof InsertStatement) {
            // 对于DML语句，查找相关的TableNamespace
            return scope.findNamespaceByStatement(statement, TableNamespace.class);
        }

        return null;
    }

    /**
     * 验证SELECT语句的基础结构
     */
    private void validateSelectStructure(SelectStatement select) throws SemanticException {
        // 验证FROM子句的基础结构
        if (select.getFrom() == null || select.getFrom().isEmpty()) {
            throw new SemanticException("SELECT statement must have FROM clause");
        }

        // 验证SELECT项的基础结构
        if (select.getSelectItems() == null || select.getSelectItems().isEmpty()) {
            throw new SemanticException("SELECT statement must have select items");
        }

        // 其他基础结构验证...
        // 注意：这里不做详细的列名表名验证，那是DefaultSemanticAnalyzer的职责
    }

    /**
     * 注册JOIN条件 - 处理JOIN的ON子句
     */
    private void registerJoinCondition(SqlValidatorScope fromScope, SelectStatement.JoinClause join) throws SemanticException {
        if (join.getJoinCondition() != null) {
            // 处理JOIN条件中的子查询
            registerExpressionSubqueries(join.getJoinCondition(), fromScope);
        }
    }

    /**
     * 构建命名空间和作用域（向后兼容）
     * <p>
     * 注意：此方法假设传入的statement已经经过无条件重写处理
     *
     * @param statement      已经过无条件重写的SQL语句
     * @param rootScope      根作用域
     * @param catalogManager 目录管理器
     */
    public static void buildNamespaces(
            SQLStatement statement,
            SqlValidatorScope rootScope,
            CatalogManager catalogManager) throws SemanticException {

        SemanticAnalyzerScopeUtil analyzer = new SemanticAnalyzerScopeUtil(catalogManager);
        analyzer.analyzeScopes(statement, rootScope);
    }
}


