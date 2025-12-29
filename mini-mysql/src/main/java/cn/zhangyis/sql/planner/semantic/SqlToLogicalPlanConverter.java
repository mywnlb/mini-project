package cn.zhangyis.sql.planner.semantic;

import cn.zhangyis.sql.parser.*;
import cn.zhangyis.sql.parser.enums.JoinType;
import cn.zhangyis.sql.parser.expression.*;
import cn.zhangyis.sql.planner.logical.*;
import cn.zhangyis.sql.planner.semantic.scope.*;
import cn.zhangyis.storage.catalog.CatalogManager;
import cn.zhangyis.storage.catalog.Column;
import cn.zhangyis.storage.catalog.Table;

import java.util.*;

/**
 * SQL语句到逻辑计划的转换器
 * 参考 Apache Calcite 的 SqlToRelConverter 实现
 * 
 * 职责：
 * 1. 将验证后的SQL AST转换为逻辑计划树（RelNode树）
 * 2. 处理不同类型的SQL语句（SELECT/INSERT/UPDATE/DELETE）
 * 3. 处理表达式转换（谓词下推、表达式简化等）
 * 
 * 主要组件：
 * - 表扫描处理（处理FROM子句）
 * - 连接处理（处理JOIN子句）
 * - 过滤处理（处理WHERE子句）
 * - 聚合处理（处理GROUP BY和聚合函数）
 * - 投影处理（处理SELECT子句的投影列）
 * - 排序处理（处理ORDER BY子句）
 * - 去重处理（处理DISTINCT关键字）
 */
public class SqlToLogicalPlanConverter {
    private final CatalogManager catalogManager;
    private final Map<SQLStatement, RelNode> planCache = new HashMap<>();

    public SqlToLogicalPlanConverter(CatalogManager catalogManager) {
        this.catalogManager = catalogManager;
    }

    /**
     * 转换SQL语句到逻辑计划
     * 主入口方法，根据SQL类型分发到不同的处理方法
     */
    public RelNode convert(SQLStatement statement, SqlValidatorScope scope) throws SemanticException {
        // 检查缓存
        RelNode cached = planCache.get(statement);
        if (cached != null) {
            return cached;
        }

        RelNode result = null;
        // 根据SQL类型分发
        switch (statement.getType()) {
            case SELECT:
                result = convertSelect((SelectStatement) statement, scope);
                break;
            case INSERT:
                // result = convertInsert((InsertStatement) statement, scope);
                break;
            case UPDATE:
                // result = convertUpdate((UpdateStatement) statement, scope);
                break;
            case DELETE:
                result = convertDelete((DeleteStatement) statement, scope);
                break;
            case CREATE:
                result = convertCreate((CreateStatement) statement, scope);
                break;
            default:
                throw new SemanticException("Unsupported statement type: " + statement.getType());
        }

        // 缓存结果
        planCache.put(statement, result);
        return result;
    }

    /**
     * 转换SELECT语句
     * 按照SQL执行顺序构建逻辑计划：
     * FROM -> WHERE -> GROUP BY -> HAVING -> SELECT -> DISTINCT -> ORDER BY ->
     * LIMIT
     */
    private RelNode convertSelect(SelectStatement select, SqlValidatorScope scope) throws SemanticException {
        // 1. 转换FROM子句（表扫描和JOIN）
        RelNode plan = convertFrom(select.getFrom(), select.getJoins(), scope);

        // 2. 转换WHERE子句（过滤条件）
        if (select.getWhere() != null) {
            plan = convertWhere(plan, select.getWhere(), scope);
        }

        // 3. 转换GROUP BY和聚合函数
        if (select.getGroupByColumns() != null && !select.getGroupByColumns().isEmpty()) {
            plan = convertGroupBy(plan, select.getGroupByColumns(), select.getSelectItems(), scope);
        }

        // 4. 转换HAVING子句
        if (select.getHaving() != null) {
            plan = convertHaving(plan, select.getHaving(), scope);
        }

        // 5. 转换SELECT子句（投影）
        plan = convertProjection(plan, select.getSelectItems(), scope);

        // 6. 处理DISTINCT
        if (select.isDistinct()) {
            plan = convertDistinct(plan);
        }

        // 7. 处理ORDER BY, LIMIT和OFFSET (合并处理)
        Integer limit = select.getLimit() >= 0 ? select.getLimit() : null;
        Integer offset = select.getOffset();
        if ((select.getOrderByItems() != null && !select.getOrderByItems().isEmpty()) || limit != null) {
            plan = convertOrderByAndLimit(plan, select.getOrderByItems(), limit, offset, scope);
        }

        return plan;
    }

    /**
     * 转换FROM子句
     * 处理表引用和JOIN操作
     */
    private RelNode convertFrom(List<SelectStatement.TableReference> fromList,
            List<SelectStatement.JoinClause> joins,
            SqlValidatorScope scope) throws SemanticException {
        if (fromList == null || fromList.isEmpty()) {
            throw new SemanticException("FROM clause cannot be empty");
        }

        // 首先转换第一个表引用
        RelNode leftNode = convertTableReference(fromList.get(0), scope);

        // 处理剩余的表引用（如果有多个）
        for (int i = 1; i < fromList.size(); i++) {
            RelNode rightNode = convertTableReference(fromList.get(i), scope);
            // 使用交叉连接（笛卡尔积）
            leftNode = new LogicalJoin(leftNode, rightNode, null, JoinType.CROSS);
        }

        // 处理JOIN子句
        if (joins != null && !joins.isEmpty()) {
            for (SelectStatement.JoinClause joinClause : joins) {
                // 转换右表
                RelNode rightNode = convertTableReference(joinClause.getJoinTable(), scope);

                // 转换JOIN条件
                Expression joinCondition = joinClause.getJoinCondition();

                leftNode = new LogicalJoin(leftNode, rightNode, joinCondition, joinClause.getJoinType());
            }
        }

        return leftNode;
    }

    /**
     * 转换单个表引用
     * 可以是基本表、子查询或VALUES子句
     */
    private RelNode convertTableReference(SelectStatement.TableReference tableRef,
            SqlValidatorScope scope) throws SemanticException {
        if (tableRef.isSubquery()) {
            // 子查询处理
            SelectStatement subquery = tableRef.getSubquery();
            return convert(subquery, scope);
        } else if (tableRef.isTableFunction()) {
            // 表值函数处理
            return convertTableFunction(tableRef.getFunctionName(),
                    tableRef.getFunctionArguments(),
                    tableRef.getAlias(),
                    scope);
        } else if (tableRef.isValues()) {
            // VALUES子句处理
            return convertValues(tableRef.getValuesList(), tableRef.getAlias(), scope);
        } else {
            // 基本表处理
            String tableName = tableRef.getTableName();
            if (!catalogManager.tableExists(tableName)) {
                throw new SemanticException.TableNotFoundException(tableName);
            }

            Table table = catalogManager.getTable(tableName);
            RelNode tableNode = new LogicalTableScan(table);

            // 处理表别名
            if (tableRef.getAlias() != null) {
                tableNode.setAlias(tableRef.getAlias());
            }

            return tableNode;
        }
    }

    /**
     * 转换WHERE子句
     * 创建过滤节点
     */
    private RelNode convertWhere(RelNode input, Expression whereExpression,
            SqlValidatorScope scope) throws SemanticException {
        // 创建过滤节点
        return new LogicalFilter(whereExpression, input);
    }

    /**
     * 转换GROUP BY子句
     * 创建聚合节点
     */
    private RelNode convertGroupBy(RelNode input,
            List<Expression> groupByExpressions,
            List<SelectStatement.SelectItem> selectItems,
            SqlValidatorScope scope) throws SemanticException {
        // 检查并收集聚合函数
        List<FunctionExpression> aggregateFunctions = collectAggregateFunctions(selectItems);

        // 创建聚合节点
        return new LogicalAggregate(input, groupByExpressions, aggregateFunctions);
    }

    /**
     * 收集SELECT项中的聚合函数
     */
    private List<FunctionExpression> collectAggregateFunctions(List<SelectStatement.SelectItem> selectItems) {
        List<FunctionExpression> aggregateFunctions = new ArrayList<>();

        for (SelectStatement.SelectItem item : selectItems) {
            collectAggregatesInExpression(item.getExpression(), aggregateFunctions);
        }

        return aggregateFunctions;
    }

    /**
     * 递归收集表达式中的聚合函数
     */
    private void collectAggregatesInExpression(Expression expr, List<FunctionExpression> aggregates) {
        if (expr == null) {
            return;
        }

        if (expr.getType() == ExpressionType.FUNCTION) {
            FunctionExpression funcExpr = (FunctionExpression) expr;
            String funcName = funcExpr.getName().toUpperCase();

            // 检查是否是聚合函数
            if (isAggregateFunction(funcName)) {
                aggregates.add(funcExpr);
                return; // 聚合函数内部不再收集
            }

            // 处理函数参数
            for (Expression arg : funcExpr.getArguments()) {
                collectAggregatesInExpression(arg, aggregates);
            }
        } else if (expr instanceof BinaryExpression) {
            // 处理二元表达式
            BinaryExpression binExpr = (BinaryExpression) expr;
            collectAggregatesInExpression(binExpr.getLeft(), aggregates);
            collectAggregatesInExpression(binExpr.getRight(), aggregates);
        } else if (expr instanceof SubqueryExpression) {
            // 子查询暂不处理其中的聚合函数
            SubqueryExpression subExpr = (SubqueryExpression) expr;
            if (subExpr.getLeftExpression() != null) {
                collectAggregatesInExpression(subExpr.getLeftExpression(), aggregates);
            }
        }

        // 其他类型表达式的子表达式处理
        for (Expression child : expr.getChildExpressions()) {
            if (child != null && child != expr) { // 避免无限递归
                collectAggregatesInExpression(child, aggregates);
            }
        }
    }

    /**
     * 判断函数是否为聚合函数
     */
    private boolean isAggregateFunction(String funcName) {
        return "COUNT".equals(funcName) ||
                "SUM".equals(funcName) ||
                "AVG".equals(funcName) ||
                "MIN".equals(funcName) ||
                "MAX".equals(funcName) ||
                "STDDEV".equals(funcName) ||
                "VARIANCE".equals(funcName);
    }

    /**
     * 转换HAVING子句
     * 在GROUP BY之后应用过滤
     */
    private RelNode convertHaving(RelNode input, Expression havingExpression,
            SqlValidatorScope scope) throws SemanticException {
        // 创建过滤节点
        return new LogicalFilter(havingExpression, input);
    }

    /**
     * 转换SELECT子句（投影）
     */
    private RelNode convertProjection(RelNode input,
            List<SelectStatement.SelectItem> selectItems,
            SqlValidatorScope scope) throws SemanticException {
        List<Expression> projections = new ArrayList<>();
        List<String> names = new ArrayList<>();

        for (SelectStatement.SelectItem item : selectItems) {
            projections.add(item.getExpression());

            // 使用别名或生成默认名称
            String name;
            if (item.getAlias() != null) {
                name = item.getAlias();
            } else if (item.getExpression() instanceof ColumnExpression) {
                name = ((ColumnExpression) item.getExpression()).getColumnName();
            } else {
                name = "expr_" + projections.size();
            }
            names.add(name);
        }

        return new LogicalProject(projections, names, input);
    }

    /**
     * 处理DISTINCT
     * 创建去重节点
     */
    private RelNode convertDistinct(RelNode input) {
        return new LogicalAggregate(input, input.getOutputExpressions(), Collections.emptyList());
    }

    /**
     * 合并转换ORDER BY, LIMIT和OFFSET子句
     */
    private RelNode convertOrderByAndLimit(RelNode input,
            List<SelectStatement.OrderByItem> orderByItems,
            Integer limit,
            Integer offset,
            SqlValidatorScope scope) {
        // 处理排序表达式
        List<Expression> sortExpressions = new ArrayList<>();
        List<Boolean> directions = new ArrayList<>();

        if (orderByItems != null && !orderByItems.isEmpty()) {
            for (SelectStatement.OrderByItem item : orderByItems) {
                sortExpressions.add(item.getExpression());
                directions.add(item.isAscending());
            }
        }

        // 创建同时包含排序和限制的节点
        return new LogicalSort(input, sortExpressions, directions, limit, offset);
    }

    /**
     * 转换表值函数
     */
    private RelNode convertTableFunction(String functionName,
            List<Expression> arguments,
            String alias,
            SqlValidatorScope scope) throws SemanticException {
        // 实现表值函数转换逻辑
        // 这里简化处理，实际应该支持更多内置和自定义表值函数
        throw new SemanticException("Table function not yet implemented: " + functionName);
    }

    /**
     * 转换VALUES子句
     */
    private RelNode convertValues(List<List<Expression>> valuesList,
            String alias,
            SqlValidatorScope scope) throws SemanticException {
        // 检查VALUES是否为空
        if (valuesList == null || valuesList.isEmpty()) {
            throw new SemanticException("VALUES clause cannot be empty");
        }

        // 检查所有行的列数是否一致
        int columnCount = valuesList.get(0).size();
        for (List<Expression> row : valuesList) {
            if (row.size() != columnCount) {
                throw new SemanticException("VALUES clause rows must have the same number of columns");
            }
        }

        // 创建VALUES节点
        // 注意：这里需要在项目中添加LogicalValues类
        return new LogicalValues(valuesList, alias);
    }

    /**
     * 转换INSERT语句
     */
    // private RelNode convertInsert(InsertStatement insert, SqlValidatorScope
    // scope) throws SemanticException {
    // // 获取目标表
    // String tableName = insert.getTableName();
    // if (!catalogManager.tableExists(tableName)) {
    // throw new SemanticException.TableNotFoundException(tableName);
    // }
    // Table targetTable = catalogManager.getTable(tableName);
    //
    // // 获取要插入的列
    // List<String> targetColumns = insert.getColumns();
    //
    // // 转换VALUES或SELECT
    // RelNode sourceNode;
    // if (insert.getValues() != null) {
    // // VALUES子句
    // sourceNode = convertValues(insert.getValues(), null, scope);
    // } else if (insert.getSelectStatement() != null) {
    // // SELECT子句
    // sourceNode = convert(insert.getSelectStatement(), scope);
    // } else {
    // throw new SemanticException("INSERT statement must have VALUES or SELECT");
    // }
    //
    // // 创建INSERT节点
    // // 注意：这里需要在项目中添加LogicalInsert类
    // return new LogicalInsert(targetTable, targetColumns, sourceNode);
    // }

    /**
     * 转换UPDATE语句
     */
    // private RelNode convertUpdate(UpdateStatement update, SqlValidatorScope
    // scope) throws SemanticException {
    // // 获取目标表
    // String tableName = update.getTableName();
    // if (!catalogManager.tableExists(tableName)) {
    // throw new SemanticException.TableNotFoundException(tableName);
    // }
    // Table targetTable = catalogManager.getTable(tableName);
    //
    // // 创建表扫描节点
    // RelNode tableNode = new LogicalTableScan(targetTable);
    //
    // // 转换WHERE条件（如果有）
    // if (update.getWhereCondition() != null) {
    // tableNode = convertWhere(tableNode, update.getWhereCondition(), scope);
    // }
    //
    // // 获取SET子句信息
    // Map<String, Expression> updateMap = new HashMap<>();
    // for (UpdateStatement.SetItem item : update.getSetItems()) {
    // updateMap.put(item.getColumnName(), item.getValue());
    // }
    //
    // // 创建UPDATE节点
    // // 注意：这里需要在项目中添加LogicalUpdate类
    // return new LogicalUpdate(targetTable, updateMap, tableNode);
    // }

    /**
     * 转换DELETE语句
     */
    private RelNode convertDelete(DeleteStatement delete, SqlValidatorScope scope) throws SemanticException {
        // 获取目标表
        String tableName = delete.getTableName();
        if (!catalogManager.tableExists(tableName)) {
            throw new SemanticException.TableNotFoundException(tableName);
        }
        Table targetTable = catalogManager.getTable(tableName);

        // 创建表扫描节点
        RelNode tableNode = new LogicalTableScan(targetTable);

        // 转换WHERE条件（如果有）
        if (delete.getWhereCondition() != null) {
            tableNode = convertWhere(tableNode, delete.getWhereCondition(), scope);
        }

        // 创建DELETE节点
        // 注意：这里需要在项目中添加LogicalDelete类
        return new LogicalDelete(targetTable, tableNode);
    }

    /**
     * 转换CREATE语句
     */
    private RelNode convertCreate(CreateStatement create, SqlValidatorScope scope) throws SemanticException {
        // 创建CREATE节点
        // 注意：这里需要在项目中添加LogicalCreate类
        return new LogicalCreate(create);
    }

    /**
     * 清除计划缓存
     */
    public void clearCache() {
        planCache.clear();
    }
}

/**
 * 逻辑插入节点
 */
class LogicalInsert extends AbstractRelNode {
    private final Table targetTable;
    private final List<String> targetColumns;

    public LogicalInsert(Table targetTable, List<String> targetColumns, RelNode source) {
        this.targetTable = targetTable;
        this.targetColumns = targetColumns != null ? new ArrayList<>(targetColumns) : new ArrayList<>();
        addInput(source);
        deriveOutput();
    }

    @Override
    public RelNodeType getType() {
        return RelNodeType.INSERT;
    }

    @Override
    protected void deriveOutput() {
        // INSERT语句没有输出列
    }

    public Table getTargetTable() {
        return targetTable;
    }

    public List<String> getTargetColumns() {
        return targetColumns;
    }
}

/**
 * 逻辑更新节点
 */
class LogicalUpdate extends AbstractRelNode {
    private final Table targetTable;
    private final Map<String, Expression> updateMap;

    public LogicalUpdate(Table targetTable, Map<String, Expression> updateMap, RelNode source) {
        this.targetTable = targetTable;
        this.updateMap = new HashMap<>(updateMap);
        addInput(source);
        deriveOutput();
    }

    @Override
    public RelNodeType getType() {
        return RelNodeType.UPDATE;
    }

    @Override
    protected void deriveOutput() {
        // UPDATE语句没有输出列
    }

    public Table getTargetTable() {
        return targetTable;
    }

    public Map<String, Expression> getUpdateMap() {
        return updateMap;
    }
}

/**
 * 逻辑删除节点
 */
class LogicalDelete extends AbstractRelNode {
    private final Table targetTable;

    public LogicalDelete(Table targetTable, RelNode source) {
        this.targetTable = targetTable;
        addInput(source);
        deriveOutput();
    }

    @Override
    public RelNodeType getType() {
        return RelNodeType.DELETE;
    }

    @Override
    protected void deriveOutput() {
        // DELETE语句没有输出列
    }

    public Table getTargetTable() {
        return targetTable;
    }
}

/**
 * 逻辑创建节点
 */
class LogicalCreate extends AbstractRelNode {
    private final CreateStatement createStatement;

    public LogicalCreate(CreateStatement createStatement) {
        this.createStatement = createStatement;
        deriveOutput();
    }

    @Override
    public RelNodeType getType() {
        return RelNodeType.CREATE;
    }

    @Override
    protected void deriveOutput() {
        // CREATE语句没有输出列
    }

    public CreateStatement getCreateStatement() {
        return createStatement;
    }
}