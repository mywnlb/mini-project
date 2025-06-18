package cn.zhangyis.sql.planner.semantic;

import cn.zhangyis.sql.parser.*;
import cn.zhangyis.sql.parser.expression.Expression;
import cn.zhangyis.sql.parser.expression.SubqueryExpression;
import cn.zhangyis.storage.catalog.CatalogManager;
import cn.zhangyis.storage.catalog.Table;

import java.util.*;

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

    /**
     * 核心映射表 - 完全按照Calcite SqlValidatorImpl的设计
     */
    private final IdentityHashMap<SQLStatement, SqlValidatorScope> scopes = new IdentityHashMap<>();
    private final IdentityHashMap<SQLStatement, SqlValidatorNamespace> namespaces = new IdentityHashMap<>();
    private final CatalogManager catalogManager;

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
        validateQueryStructure(statement, rootScope);

        return getScope(statement);
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

        // 检查是否已经注册过（避免重复注册）
        if (scopes.containsKey(node)) {
            return;
        }

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
     * 注册SELECT语句 - 按照Calcite的标准流程
     * <p>
     * Calcite中SELECT的注册流程：
     * 1. 创建SelectScope
     * 2. 注册FROM子句 -> 创建FromScope并处理表引用
     * 3. 创建SelectNamespace并注册
     * 4. 建立scopes和namespaces映射
     */
    private void registerSelect(
            SqlValidatorScope parentScope,
            SqlValidatorScope usingScope,
            SelectStatement select,
            SQLStatement enclosingNode,
            String alias,
            boolean forceNullable) throws SemanticException {

        // 1. 创建SelectScope - Calcite标准做法
        SqlValidatorScope selectScope = new SqlValidatorScope(parentScope, SqlValidatorScope.ScopeType.SELECT);

        // 2. 注册FROM子句 - 这是关键步骤
        SqlValidatorScope fromScope = null;
        if (select.getFrom() != null && !select.getFrom().isEmpty()) {
            fromScope = registerFrom(selectScope, select.getFrom(), select);
        }

        // 3. 创建SelectNamespace
        SelectNamespace selectNamespace = new SelectNamespace(select);

        // 4. 注册namespace - 按照Calcite的registerNamespace()方法
        registerNamespace(usingScope, alias, selectNamespace, forceNullable);

        // 5. 建立IdentityMapping - 这是Calcite的核心
        scopes.put(select, selectScope);
        namespaces.put(select, selectNamespace);

        // 6. 注册WHERE子句作用域
        registerWhereClause(select, fromScope != null ? fromScope : selectScope);

        // 7. 注册GROUP BY作用域
        registerGroupByClause(select, selectScope);

        // 8. 注册HAVING作用域
        registerHavingClause(select, selectScope);

        // 9. 注册ORDER BY作用域
        registerOrderByClause(select, selectScope);

        // 10. 递归处理子查询 - 在表达式中查找
        registerSubqueries(select, selectScope);
    }

    /**
     * 注册WHERE子句作用域 - 按照Calcite SqlValidatorImpl.validateWhereClause()模式
     */
    private void registerWhereClause(SelectStatement select, SqlValidatorScope fromScope) throws SemanticException {
        if (select.getWhere() != null) {
            // 创建WHERE作用域
            SqlValidatorScope whereScope = new SqlValidatorScope(fromScope, SqlValidatorScope.ScopeType.WHERE);
            
            // 将WHERE表达式与作用域关联
            scopes.put(select.getWhere(), whereScope);
            
            // 处理WHERE子句中的子查询
            registerExpressionSubqueries(select.getWhere(), whereScope);
            
            // 在这里可以添加更多WHERE子句特定的处理逻辑
            // 例如：验证WHERE子句中不能包含聚合函数等
        }
    }

    /**
     * 注册GROUP BY子句作用域 - 按照Calcite SqlValidatorImpl.validateGroupClause()模式
     */
    private void registerGroupByClause(SelectStatement select, SqlValidatorScope selectScope) throws SemanticException {
        if (select.getGroupByColumns() != null && !select.getGroupByColumns().isEmpty()) {
            // 创建GROUP BY作用域
            SqlValidatorScope groupByScope = new SqlValidatorScope(selectScope, SqlValidatorScope.ScopeType.GROUP_BY);
            
            // 将GROUP BY表达式与作用域关联
            for (Expression groupExpr : select.getGroupByColumns()) {
                scopes.put(groupExpr, groupByScope);
                
                // 处理GROUP BY表达式中的子查询
                registerExpressionSubqueries(groupExpr, groupByScope);
            }
            
            // 在这里可以添加更多GROUP BY子句特定的处理逻辑
            // 例如：验证GROUP BY中的表达式是否有效等
        }
    }

    /**
     * 注册HAVING子句作用域 - 按照Calcite SqlValidatorImpl.validateHavingClause()模式
     */
    private void registerHavingClause(SelectStatement select, SqlValidatorScope selectScope) throws SemanticException {
        if (select.getHaving() != null) {
            // 创建HAVING作用域
            // 注意：HAVING作用域应该能够访问GROUP BY的结果和聚合函数
            SqlValidatorScope havingScope = new SqlValidatorScope(selectScope, SqlValidatorScope.ScopeType.HAVING);
            
            // 将HAVING表达式与作用域关联
            scopes.put(select.getHaving(), havingScope);
            
            // 处理HAVING子句中的子查询
            registerExpressionSubqueries(select.getHaving(), havingScope);
            
            // 在这里可以添加更多HAVING子句特定的处理逻辑
            // 例如：验证HAVING中的表达式是否有效、是否包含聚合函数等
        }
    }

    /**
     * 注册ORDER BY子句作用域 - 按照Calcite SqlValidatorImpl.validateOrderList()模式
     */
    private void registerOrderByClause(SelectStatement select, SqlValidatorScope selectScope) throws SemanticException {
        if (select.getOrderByItems() != null && !select.getOrderByItems().isEmpty()) {
            // 创建ORDER BY作用域
            SqlValidatorScope orderByScope = new SqlValidatorScope(selectScope, SqlValidatorScope.ScopeType.ORDER_BY);
            
            // 将ORDER BY表达式与作用域关联
            for (SelectStatement.OrderByItem orderItem : select.getOrderByItems()) {
                scopes.put(orderItem.getExpression(), orderByScope);
                
                // 处理ORDER BY表达式中的子查询
                registerExpressionSubqueries(orderItem.getExpression(), orderByScope);
            }
            
            // 在这里可以添加更多ORDER BY子句特定的处理逻辑
            // 例如：验证ORDER BY中的表达式是否有效等
        }
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

        SqlValidatorNamespace namespace;

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
            namespace = new TableFunctionNamespace(
                tableRef.getFunctionName(), 
                tableRef.getFunctionArguments(), 
                tableRef.getAlias()
            );

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
     * 注册JOIN操作 - 创建专门的JoinNamespace
     */
    private void registerJoinOperation(
            SqlValidatorScope fromScope, 
            SelectStatement.JoinClause join, 
            SelectStatement select) throws SemanticException {
            
        // 注册JOIN的右表
        registerTableReference(fromScope, join.getJoinTable(), select);
        
        // 创建JOIN命名空间
        SqlValidatorNamespace leftNamespace = findLastNamespaceInScope(fromScope);
        SqlValidatorNamespace rightNamespace = fromScope.findNamespace(
            join.getJoinTable().getAlias() != null ? 
            join.getJoinTable().getAlias() : 
            join.getJoinTable().getTableName()
        );
        
        if (leftNamespace != null && rightNamespace != null) {
            JoinNamespace joinNamespace = new JoinNamespace(
                leftNamespace,
                rightNamespace, 
                join.getJoinType(),
                join.getJoinCondition(),
                null // JOIN结果通常不需要别名
            );
            
            // 注册JOIN namespace
            String joinName = "JOIN_" + System.currentTimeMillis(); // 生成唯一名称
            registerNamespace(fromScope, joinName, joinNamespace, false);
        }
        
        // JOIN条件在专门的JoinScope中处理
        registerJoinCondition(fromScope, join);
    }
    
    /**
     * 在作用域中查找最后添加的命名空间（通常是左表）
     */
    private SqlValidatorNamespace findLastNamespaceInScope(SqlValidatorScope scope) {
        // 这里简化实现，实际应该根据scope中的namespace顺序来确定
        // 可以通过遍历scope的namespaceMap来找到最近添加的namespace
        return null; // TODO: 实现获取左表namespace的逻辑
    }

    /**
     * 注册子查询 - 在表达式中查找并注册子查询
     */
    private void registerSubqueries(SelectStatement select, SqlValidatorScope selectScope) throws SemanticException {
        // 在SELECT项中查找子查询
        for (SelectStatement.SelectItem item : select.getSelectItems()) {
            registerExpressionSubqueries(item.getExpression(), selectScope);
        }

        // 在WHERE子句中查找子查询 - 已在registerWhereClause中处理
        // 在HAVING子句中查找子查询 - 已在registerHavingClause中处理
        // 在ORDER BY子句中查找子查询 - 已在registerOrderByClause中处理
        // 在GROUP BY子句中查找子查询 - 已在registerGroupByClause中处理

        // 处理JOIN条件中的子查询
        if (select.getJoins() != null) {
            for (SelectStatement.JoinClause join : select.getJoins()) {
                if (join.getJoinCondition() != null) {
                    // 获取JOIN作用域
                    SqlValidatorScope joinScope = getJoinScope(select, join);
                    registerExpressionSubqueries(join.getJoinCondition(), joinScope != null ? joinScope : selectScope);
                }
            }
        }
    }

    /**
     * 获取JOIN的作用域
     */
    private SqlValidatorScope getJoinScope(SelectStatement select, SelectStatement.JoinClause join) {
        // 尝试直接从作用域映射中获取JOIN条件对应的作用域
        if (join.getJoinCondition() != null) {
            SqlValidatorScope joinScope = scopes.get(join.getJoinCondition());
            if (joinScope != null) {
                return joinScope;
            }
        }
        
        // 如果没有找到，尝试获取FROM作用域
        for (Map.Entry<SQLStatement, SqlValidatorScope> entry : scopes.entrySet()) {
            if (entry.getKey() == select && entry.getValue().getScopeType() == SqlValidatorScope.ScopeType.FROM) {
                return entry.getValue();
            }
        }
        
        return null;
    }

    /**
     * 在表达式中注册子查询 - 增强版，递归处理复合表达式
     */
    private void registerExpressionSubqueries(Expression expr, SqlValidatorScope scope) throws SemanticException {
        if (expr == null) {
            return;
        }

        if (expr instanceof SubqueryExpression) {
            SubqueryExpression subqueryExpr = (SubqueryExpression) expr;
            // 递归注册子查询
            registerQuery(scope, null, subqueryExpr.getSubquery(), subqueryExpr.getSubquery(), null, false);
        } else {
            // 递归处理复合表达式中的子表达式
            for (Expression subExpr : expr.getChildExpressions()) {
                registerExpressionSubqueries(subExpr, scope);
            }
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

        registerNamespace(updateScope, tableName, tableNamespace, false);

        // 建立映射
        scopes.put(update, updateScope);
        namespaces.put(update, tableNamespace);

        // 处理内嵌的SELECT语句（来自SqlRewriter的重写）
        if (update.getSelectStatement() != null) {
            registerQuery(updateScope, null, update.getSelectStatement(), update, null, false);
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

        registerNamespace(deleteScope, tableName, tableNamespace, false);

        // 建立映射
        scopes.put(delete, deleteScope);
        namespaces.put(delete, tableNamespace);

        // 处理内嵌的SELECT语句
        if (delete.getSelectStatement() != null) {
            registerQuery(deleteScope, null, delete.getSelectStatement(), delete, null, false);
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

        registerNamespace(insertScope, tableName, tableNamespace, false);

        // 建立映射
        scopes.put(insert, insertScope);
        namespaces.put(insert, tableNamespace);

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

        // TODO: 处理forceNullable逻辑
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
        // 验证作用域是否正确构建
        SqlValidatorScope statementScope = getScope(statement);
        if (statementScope == null) {
            throw new SemanticException("Statement scope not properly registered");
        }

        // 验证命名空间是否正确构建
        SqlValidatorNamespace statementNamespace = getNamespace(statement);
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

        // 验证WHERE子句
        validateWhereStructure(select);
        
        // 验证GROUP BY子句
        validateGroupByStructure(select);
        
        // 验证HAVING子句
        validateHavingStructure(select);
        
        // 验证ORDER BY子句
        validateOrderByStructure(select);

        // 其他基础结构验证...
        // 注意：这里不做详细的列名表名验证，那是DefaultSemanticAnalyzer的职责
    }
    
    /**
     * 验证WHERE子句的基础结构
     */
    private void validateWhereStructure(SelectStatement select) throws SemanticException {
        if (select.getWhere() != null) {
            // 检查WHERE子句的作用域是否正确构建
            if (!scopes.containsKey(select.getWhere())) {
                throw new SemanticException("WHERE clause scope not properly registered");
            }
            
            // 这里只做基础结构验证，不做详细的表达式验证
            // 详细的表达式验证由DefaultSemanticAnalyzer负责
        }
    }
    
    /**
     * 验证GROUP BY子句的基础结构
     */
    private void validateGroupByStructure(SelectStatement select) throws SemanticException {
        if (select.getGroupByColumns() != null && !select.getGroupByColumns().isEmpty()) {
            // 检查GROUP BY表达式的作用域是否正确构建
            for (Expression expr : select.getGroupByColumns()) {
                if (!scopes.containsKey(expr)) {
                    throw new SemanticException("GROUP BY expression scope not properly registered");
                }
            }
            
            // 这里只做基础结构验证，不做详细的表达式验证
        }
    }
    
    /**
     * 验证HAVING子句的基础结构
     */
    private void validateHavingStructure(SelectStatement select) throws SemanticException {
        if (select.getHaving() != null) {
            // 检查HAVING子句的作用域是否正确构建
            if (!scopes.containsKey(select.getHaving())) {
                throw new SemanticException("HAVING clause scope not properly registered");
            }
            
            // 这里只做基础结构验证，不做详细的表达式验证
            // 例如：HAVING子句通常需要包含聚合函数，但这种检查由DefaultSemanticAnalyzer负责
        }
    }
    
    /**
     * 验证ORDER BY子句的基础结构
     */
    private void validateOrderByStructure(SelectStatement select) throws SemanticException {
        if (select.getOrderByItems() != null && !select.getOrderByItems().isEmpty()) {
            // 检查ORDER BY表达式的作用域是否正确构建
            for (SelectStatement.OrderByItem orderItem : select.getOrderByItems()) {
                if (!scopes.containsKey(orderItem.getExpression())) {
                    throw new SemanticException("ORDER BY expression scope not properly registered");
                }
            }
            
            // 这里只做基础结构验证，不做详细的表达式验证
        }
    }

    /**
     * 注册JOIN条件 - 处理JOIN的ON子句
     */
    private void registerJoinCondition(SqlValidatorScope fromScope, SelectStatement.JoinClause join) throws SemanticException {
        if (join.getJoinCondition() != null) {
            // JOIN条件在专门的JoinScope中验证
            SqlValidatorScope joinScope = new SqlValidatorScope(fromScope, SqlValidatorScope.ScopeType.FROM);
            
            // 将JOIN条件与作用域关联
            scopes.put(join.getJoinCondition(), joinScope);
            
            // 处理JOIN条件中的子查询
            registerExpressionSubqueries(join.getJoinCondition(), joinScope);
        }
    }

    // ==================== 公共接口方法 ====================

    /**
     * 获取语句对应的作用域
     */
    public SqlValidatorScope getScope(SQLStatement statement) {
        return scopes.get(statement);
    }

    /**
     * 获取表达式对应的作用域
     * 
     * @param expr 表达式
     * @return 表达式对应的作用域，如果未找到则返回null
     */
    public SqlValidatorScope getExpressionScope(Expression expr) {
        return scopes.get(expr);
    }

    /**
     * 获取语句对应的命名空间
     */
    public SqlValidatorNamespace getNamespace(SQLStatement statement) {
        return namespaces.get(statement);
    }

    /**
     * 清理所有映射
     */
    public void clear() {
        scopes.clear();
        namespaces.clear();
    }

    /**
     * 获取所有作用域映射（只读）
     */
    public Map<SQLStatement, SqlValidatorScope> getScopes() {
        return Collections.unmodifiableMap(scopes);
    }

    /**
     * 获取所有命名空间映射（只读）
     */
    public Map<SQLStatement, SqlValidatorNamespace> getNamespaces() {
        return Collections.unmodifiableMap(namespaces);
    }

    // ======================== 向后兼容的便利方法 ========================

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


