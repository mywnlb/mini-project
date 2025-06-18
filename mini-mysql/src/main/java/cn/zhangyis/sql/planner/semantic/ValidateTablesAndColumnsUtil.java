package cn.zhangyis.sql.planner.semantic;

import cn.zhangyis.sql.parser.*;
import cn.zhangyis.sql.parser.enums.ArithmeticOperator;
import cn.zhangyis.sql.parser.enums.LiteralType;
import cn.zhangyis.sql.parser.expression.*;
import cn.zhangyis.sql.parser.expression.InListExpression;
import cn.zhangyis.storage.catalog.CatalogManager;
import cn.zhangyis.storage.catalog.Column;
import cn.zhangyis.storage.catalog.Table;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static cn.zhangyis.sql.planner.semantic.TypeSystem.isNumericType;
import static cn.zhangyis.sql.planner.semantic.TypeSystem.isTypeCompatible;

/**
 * 验证表名和列名的语义正确性工具类
 * 参考 Apache Calcite 的 validate 阶段实现
 * <p>
 * 职责：
 * 1. 验证表名和列名的存在性 - 直接使用CatalogManager
 * 2. 展开星号表达式 (expandStarColumns)
 * 3. 类型推断 (inferTypes)
 * 4. 表达式验证
 * 5. 权限检查和聚合函数限制
 * 6. GROUP BY 语义验证
 * <p>
 * 设计原则：
 * - CatalogManager 为权威数据源：直接验证表和字段存在性
 * - SqlValidatorScope 为辅助：提供作用域和别名解析
 * - 双重验证：既检查Catalog又检查Scope，确保一致性
 * <p>
 * 参考: https://zhuanlan.zhihu.com/p/58139279
 * 在校验过程中完成：类型推断(DeriveTypeVisitor)、对表达式和star做展开等
 *
 * @Description 完整的语义验证工具类，遵循Apache Calcite的CatalogReader+Scope验证模式
 * @Date 2025/6/6 14:43
 * @Created by libo
 */
public class ValidateTablesAndColumnsUtil {

    // CatalogManager 实例 - 权威数据源
    private final CatalogManager catalogManager;

    /**
     * 构造函数 - 注入CatalogManager
     */
    private ValidateTablesAndColumnsUtil(CatalogManager catalogManager) {
        this.catalogManager = catalogManager;
    }


    /**
     * 完整的验证流程 - 按照 Apache Calcite validate 阶段的标准
     *
     * @param statement      要验证的SQL语句
     * @param scope          作用域（包含命名空间和映射信息）
     * @param catalogManager 目录管理器（权威数据源）
     */
    public static void validate(SQLStatement statement, SqlValidatorScope scope, CatalogManager catalogManager) {
        try {
            ValidateTablesAndColumnsUtil validator = new ValidateTablesAndColumnsUtil(catalogManager);

            // === 按照Calcite validate阶段的标准流程 ===

            // 1. 基础表名列名验证 - 直接使用CatalogManager
            validator.validateTablesAndColumns(statement, scope);

            // 2. 展开星号表达式 (expandStarColumns)
            validator.expandStarColumns(statement, scope);

            // 3. 类型推断 (inferTypes/DeriveTypeVisitor)
            validator.inferTypes(statement, scope);

            // 4. 常量折叠 (foldConstants) - 从 DefaultSemanticAnalyzer 移入
            validator.foldConstants(statement, scope);

            // 5. 表达式验证和优化
            validator.validateExpressions(statement, scope);

            // 6. 聚合查询特殊验证
            validator.validateAggregateSemantics(statement, scope);

        } catch (SemanticException e) {
            throw new RuntimeException("Semantic validation failed: " + e.getMessage(), e);
        }
    }



    /**
     * 验证表名和列名的存在性
     * 使用作用域进行名称解析
     */
    private void validateTablesAndColumns(SQLStatement statement, SqlValidatorScope scope) throws SemanticException {
        if (statement instanceof SelectStatement) {
            SelectStatement select = (SelectStatement) statement;

            // 验证FROM子句中的表引用
            validateFromClause(select, scope);

            // 验证SELECT子句中的列（此时还包含星号）
            for (SelectStatement.SelectItem item : select.getSelectItems()) {
                validateSelectItemBasic(item, scope);
            }

            // 验证WHERE子句中的列
            if (select.getWhere() != null) {
                validateExpression(select.getWhere(), scope);
                // 检查WHERE子句中不能包含聚合函数
                validateNoAggregateInWhere(select.getWhere());
            }

            // 验证JOIN条件
            if (select.getJoins() != null) {
                for (SelectStatement.JoinClause join : select.getJoins()) {
                    if (join.getJoinCondition() != null) {
                        validateExpression(join.getJoinCondition(), scope);
                        // JOIN条件中也不能包含聚合函数
                        validateNoAggregateInWhere(join.getJoinCondition());
                    }
                }
            }

            // 验证GROUP BY子句
            if (select.getGroupByColumns() != null) {
                for (Expression groupExpr : select.getGroupByColumns()) {
                    validateExpression(groupExpr, scope);
                    // GROUP BY表达式必须是非聚合表达式
                    validateNoAggregateExpression(groupExpr, "GROUP BY");
                }
            }

            // 验证HAVING子句
            if (select.getHaving() != null) {
                validateExpression(select.getHaving(), scope);
            }

            // 验证ORDER BY子句
            if (select.getOrderByItems() != null) {
                for (SelectStatement.OrderByItem orderItem : select.getOrderByItems()) {
                    validateExpression(orderItem.getExpression(), scope);
                }
            }
        }
        // TODO: 处理其他语句类型 (UPDATE, DELETE, INSERT)
    }

    /**
     * 验证FROM子句
     * 增强：包含权限检查和别名唯一性验证
     */
    private void validateFromClause(SelectStatement select, SqlValidatorScope scope) throws SemanticException {
        if (select.getFrom() != null) {
            Set<String> usedAliases = new HashSet<>();

            for (SelectStatement.TableReference tableRef : select.getFrom()) {
                validateTableReference(tableRef, scope);

                // 验证表别名唯一性
                String alias = tableRef.getAlias();
                if (alias != null) {
                    if (usedAliases.contains(alias)) {
                        throw new SemanticException("Duplicate table alias: " + alias);
                    }
                    usedAliases.add(alias);
                }

                // 验证权限（SELECT权限）
                validateSelectPermission(tableRef, scope);
            }
        }
    }

    /**
     * 验证SELECT权限
     */
    private void validateSelectPermission(SelectStatement.TableReference tableRef, SqlValidatorScope scope) throws SemanticException {
        if (!tableRef.isSubquery() && !tableRef.isValues() && !tableRef.isTableFunction()) {
            String tableName = tableRef.getTableName();

            // 从作用域或catalog中检查权限
            TableNamespace namespace = (TableNamespace) scope.findNamespace(tableName);
            if (namespace != null && namespace.getTable() != null) {
                // 这里可以扩展为实际的权限检查系统
                // 例如：checkPermission(user, tableName, Permission.SELECT)
                // 目前只是一个占位符实现
                boolean hasSelectPermission = checkSelectPermission(tableName);
                if (!hasSelectPermission) {
                    throw new SemanticException("Access denied: SELECT permission required for table " + tableName);
                }
            }
        }
    }

    /**
     * 检查SELECT权限的占位符方法
     * 实际实现中应该连接到权限管理系统
     */
    private boolean checkSelectPermission(String tableName) {
        // TODO: 实际的权限检查逻辑
        // 目前默认返回true，允许所有访问
        return true;
    }

    /**
     * 验证表引用 - 使用CatalogManager作为权威数据源
     * 参考 Apache Calcite 的 SqlValidatorCatalogReader.getTable() 方法
     */
    public void validateTableReference(SelectStatement.TableReference tableRef, SqlValidatorScope scope) throws SemanticException {
        if (tableRef.isSubquery() || tableRef.isValues() || tableRef.isTableFunction()) {
            return; // 特殊情况在其他地方处理
        }

        // === 普通表引用 - 双重验证 ===
        String tableName = tableRef.getTableName();

        // 1. CatalogManager验证（权威验证）
        if (!catalogManager.tableExists(tableName)) {
            throw new SemanticException.TableNotFoundException(tableName);
        }

        // 2. SqlValidatorScope验证（作用域验证）
        String scopeTableName = tableRef.getAlias() != null ? tableRef.getAlias() : tableName;
        SqlValidatorNamespace namespace = scope.findNamespace(scopeTableName);
        if (namespace == null) {
            throw new SemanticException("Table '" + scopeTableName + "' not found in current scope");
        }

        // 3. 一致性验证：确保Scope中的数据与Catalog一致
        validateScopeConsistency(tableName, namespace);
    }

    /**
     * 验证作用域与目录的一致性
     */
    private void validateScopeConsistency(String tableName, SqlValidatorNamespace namespace) throws SemanticException {
        try {
            Table catalogTable = catalogManager.getTable(tableName);
            List<Column> catalogColumns = catalogTable.getColumns();
            List<Column> scopeColumns = namespace.getColumns();

            // 简单验证：列数量是否一致
            if (catalogColumns.size() != scopeColumns.size()) {
                throw new SemanticException(
                        "Inconsistency between catalog and scope for table '" + tableName +
                                "': catalog has " + catalogColumns.size() +
                                " columns, scope has " + scopeColumns.size() + " columns");
            }

            // TODO: 更详细的一致性验证（列名、类型等）

        } catch (Exception e) {
            throw new SemanticException("Failed to validate scope consistency for table '" + tableName + "': " + e.getMessage(), e);
        }
    }

    /**
     * 基础的SELECT项验证（星号展开前）
     * 增强：处理列别名
     */
    private void validateSelectItemBasic(SelectStatement.SelectItem item, SqlValidatorScope scope) throws SemanticException {
        if (item.getExpression() instanceof ColumnExpression) {
            ColumnExpression colExpr = (ColumnExpression) item.getExpression();

            if (colExpr.getAll()) {
                // 星号表达式，在展开阶段处理
                return;
            }

            // 验证具体列名
            validateColumnExpression(colExpr, scope);
        } else {
            // 处理其他类型的表达式
            validateExpression(item.getExpression(), scope);
        }

        // 处理列别名 - 确保别名不为空且不重复
        if (item.getAlias() != null && item.getAlias().trim().isEmpty()) {
            throw new SemanticException("Column alias cannot be empty");
        }
    }

    /**
     * 展开星号表达式 - 从 DefaultSemanticAnalyzer 移入
     * 参考 Apache Calcite 的 star expansion 功能
     */
    private void expandStarColumns(SQLStatement statement, SqlValidatorScope scope) throws SemanticException {
        if (statement instanceof SelectStatement) {
            SelectStatement select = (SelectStatement) statement;
            List<SelectStatement.SelectItem> expandedItems = new ArrayList<>();

            for (SelectStatement.SelectItem item : select.getSelectItems()) {
                if (item.getExpression() instanceof ColumnExpression) {
                    ColumnExpression colExpr = (ColumnExpression) item.getExpression();

                    if (colExpr.getAll()) {
                        // 展开星号
                        expandStarColumn(colExpr, expandedItems, scope);
                    } else {
                        expandedItems.add(item);
                    }
                } else {
                    expandedItems.add(item);
                }
            }

            select.setSelectItems(expandedItems);
        }
    }

    /**
     * 展开单个星号列
     */
    private void expandStarColumn(ColumnExpression starExpr, List<SelectStatement.SelectItem> expandedItems, SqlValidatorScope scope) {
        if (starExpr.getTableAlias() != null) {
            // 限定的星号，如 t1.*
            SqlValidatorNamespace namespace = scope.findNamespace(starExpr.getTableAlias());
            if (namespace != null) {
                for (Column column : namespace.getColumns()) {
                    ColumnExpression colExpr = new ColumnExpression(starExpr.getTableAlias(), column.getName());
                    colExpr.setColumnType(column.getType());
                    expandedItems.add(new SelectStatement.SelectItem(colExpr, null));
                }
            }
        } else {
            // 非限定的星号，展开所有可见的列
            for (String nsName : scope.getNamespaces().keySet()) {
                SqlValidatorNamespace namespace = scope.findNamespace(nsName);
                if (namespace != null) {
                    for (Column column : namespace.getColumns()) {
                        ColumnExpression colExpr = new ColumnExpression(nsName, column.getName());
                        colExpr.setColumnType(column.getType());
                        expandedItems.add(new SelectStatement.SelectItem(colExpr, null));
                    }
                }
            }
        }
    }

    /**
     * 类型推断 - 从 DefaultSemanticAnalyzer 移入
     * 参考 Apache Calcite 的 DeriveTypeVisitor 功能
     */
    private void inferTypes(SQLStatement statement, SqlValidatorScope scope) throws SemanticException {
        if (statement instanceof SelectStatement) {
            SelectStatement select = (SelectStatement) statement;

            // 推导SELECT子句中的类型
            for (SelectStatement.SelectItem item : select.getSelectItems()) {
                inferExpressionType(item.getExpression(), scope);
            }

            // 推导WHERE子句中的类型
            if (select.getWhere() != null) {
                inferExpressionType(select.getWhere(), scope);
            }

            // 推导JOIN条件中的类型
            if (select.getJoins() != null) {
                for (SelectStatement.JoinClause join : select.getJoins()) {
                    if (join.getJoinCondition() != null) {
                        inferExpressionType(join.getJoinCondition(), scope);
                    }
                }
            }

            // 推导GROUP BY子句中的类型
            if (select.getGroupByColumns() != null) {
                for (Expression groupExpr : select.getGroupByColumns()) {
                    inferExpressionType(groupExpr, scope);
                }
            }

            // 推导HAVING子句中的类型
            if (select.getHaving() != null) {
                inferExpressionType(select.getHaving(), scope);
            }

            // 推导ORDER BY子句中的类型
            if (select.getOrderByItems() != null) {
                for (SelectStatement.OrderByItem orderItem : select.getOrderByItems()) {
                    inferExpressionType(orderItem.getExpression(), scope);
                }
            }
        }
    }

    /**
     * 推导表达式的类型
     */
    private void inferExpressionType(Expression expr, SqlValidatorScope scope) throws SemanticException {
        if (expr instanceof ColumnExpression) {
            ColumnExpression colExpr = (ColumnExpression) expr;
            if (colExpr.getType() == null) {
                // 类型还未设置，从作用域中获取
                Column column = scope.findColumn(colExpr.getTableAlias(), colExpr.getColumnName());
                if (column != null) {
                    colExpr.setColumnType(column.getType());
                }
            }
        } else if (expr instanceof ComparisonExpression) {
            ComparisonExpression binExpr = (ComparisonExpression) expr;

            // 递归推导子表达式的类型
            inferExpressionType(binExpr.getLeft(), scope);
            inferExpressionType(binExpr.getRight(), scope);

            // 比较表达式的结果类型始终为布尔型
            binExpr.setType(ExpressionType.COMPARISON);
        } else if (expr instanceof LogicalExpression) {
            LogicalExpression logExpr = (LogicalExpression) expr;
            
            // 递归推导子表达式的类型
            inferExpressionType(logExpr.getLeft(), scope);
            inferExpressionType(logExpr.getRight(), scope);
            
            // 逻辑表达式的结果类型始终为布尔型
            logExpr.setType(ExpressionType.LOGICAL);
        } else if (expr instanceof ArithmeticExpression) {
            ArithmeticExpression arithExpr = (ArithmeticExpression) expr;
            
            // 递归推导子表达式的类型
            inferExpressionType(arithExpr.getLeft(), scope);
            inferExpressionType(arithExpr.getRight(), scope);
            
            // 推导算术表达式的结果类型
            // 如果两个操作数都是数值类型，结果也是数值类型
            // 如果涉及除法，结果可能是浮点型
            if (arithExpr.getOperator() == ArithmeticOperator.DIVIDE) {
                arithExpr.setType(ExpressionType.ARITHMETIC);
            } else {
                // 其他算术运算，保留最高精度的类型
                ExpressionType leftType = arithExpr.getLeft().getType();
                ExpressionType rightType = arithExpr.getRight().getType();
                
                if (leftType != null && rightType != null) {
                    arithExpr.setType(ExpressionType.ARITHMETIC);
                }
            }
        } else if (expr instanceof FunctionExpression) {
            FunctionExpression funcExpr = (FunctionExpression) expr;

            // 推导函数参数的类型
            Expression arg = funcExpr.getArgument();
            if (arg != null) {
                inferExpressionType(arg, scope);
            }
            
            // 设置函数返回类型
            funcExpr.setType(inferFunctionReturnType(funcExpr));
        } else if (expr instanceof LiteralExpression) {
            LiteralExpression literal = (LiteralExpression) expr;
            // 字面量的类型根据其值确定
            if (literal.isNumber()) {
                literal.setType(ExpressionType.LITERAL);
            } else if (literal.isString()) {
                literal.setType(ExpressionType.LITERAL);
            } else if (literal.isBoolean()) {
                literal.setType(ExpressionType.LITERAL);
            } else if (literal.isNull()) {
                literal.setType(ExpressionType.LITERAL);
            }
        } else if (expr instanceof InListExpression) {
            InListExpression inExpr = (InListExpression) expr;
            
            // 递归推导子表达式的类型
            inferExpressionType(inExpr.getLeft(), scope);
            for (Expression valueExpr : inExpr.getValueList()) {
                inferExpressionType(valueExpr, scope);
            }
            
            // IN表达式的结果类型始终为布尔型
            inExpr.setType(ExpressionType.IN_LIST);
        } else if (expr instanceof SubqueryExpression) {
            SubqueryExpression subqueryExpr = (SubqueryExpression) expr;
            
            // 子查询表达式的类型推导需要特殊处理
            // 这里简化处理，将其设置为子查询类型
            subqueryExpr.setType(ExpressionType.SUBQUERY);
        }
    }
    
    /**
     * 推导函数的返回类型
     */
    private ExpressionType inferFunctionReturnType(FunctionExpression funcExpr) {
        String funcName = funcExpr.getFunctionName().toUpperCase();
        
        // 聚合函数的返回类型
        if (isAggregateFunction(funcName)) {
            if (funcName.equals("COUNT")) {
                return ExpressionType.FUNCTION; // COUNT返回整数
            } else if (funcName.equals("SUM") || funcName.equals("AVG")) {
                // SUM和AVG的返回类型取决于参数类型
                Expression arg = funcExpr.getArgument();
                if (arg != null && arg.getType() != null) {
                    return ExpressionType.FUNCTION;
                }
                return ExpressionType.FUNCTION; // 默认为数值类型
            } else if (funcName.equals("MIN") || funcName.equals("MAX")) {
                // MIN和MAX的返回类型与参数类型相同
                Expression arg = funcExpr.getArgument();
                if (arg != null && arg.getType() != null) {
                    return arg.getType();
                }
                return ExpressionType.FUNCTION;
            }
        }
        
        // 其他函数的返回类型
        // 这里可以添加更多函数的类型推导逻辑
        
        return ExpressionType.FUNCTION; // 默认返回类型
    }

    /**
     * 表达式验证
     */
    private void validateExpressions(SQLStatement statement, SqlValidatorScope scope) throws SemanticException {
        if (statement instanceof SelectStatement) {
            SelectStatement select = (SelectStatement) statement;

            // 验证所有表达式的类型兼容性
            for (SelectStatement.SelectItem item : select.getSelectItems()) {
                validateExpressionSemantics(item.getExpression(), scope);
            }

            if (select.getWhere() != null) {
                validateExpressionSemantics(select.getWhere(), scope);
                // WHERE子句必须返回布尔类型
                if (!"BOOLEAN".equals(select.getWhere().getType())) {
                    throw new SemanticException("WHERE clause must be boolean expression");
                }
            }
        }
    }

    /**
     * 验证表达式的语义
     */
    private void validateExpressionSemantics(Expression expr, SqlValidatorScope scope) throws SemanticException {
        if (expr instanceof ComparisonExpression) {
            ComparisonExpression compExpr = (ComparisonExpression) expr;

            // 递归验证子表达式
            validateExpressionSemantics(compExpr.getLeft(), scope);
            validateExpressionSemantics(compExpr.getRight(), scope);

            // 验证类型兼容性
            ExpressionType leftType = compExpr.getLeft().getType();
            ExpressionType rightType = compExpr.getRight().getType();
            if (leftType != null && rightType != null) {
                if (!isTypeCompatible(leftType.toString(), rightType.toString())) {
                    throw new SemanticException.TypeIncompatibleException(leftType, rightType);
                }
            }
        } else if (expr instanceof LogicalExpression) {
            LogicalExpression logExpr = (LogicalExpression) expr;

            // 递归验证子表达式
            validateExpressionSemantics(logExpr.getLeft(), scope);
            validateExpressionSemantics(logExpr.getRight(), scope);

            // 逻辑表达式要求操作数为布尔类型
            ExpressionType leftType = logExpr.getLeft().getType();
            ExpressionType rightType = logExpr.getRight().getType();
            if (leftType != null && !"BOOLEAN".equals(leftType)) {
                throw new SemanticException("Left operand of logical expression must be boolean, got: " + leftType);
            }
            if (rightType != null && !"BOOLEAN".equals(rightType)) {
                throw new SemanticException("Right operand of logical expression must be boolean, got: " + rightType);
            }
        } else if (expr instanceof ArithmeticExpression) {
            ArithmeticExpression arithExpr = (ArithmeticExpression) expr;
            
            // 递归验证子表达式
            validateExpressionSemantics(arithExpr.getLeft(), scope);
            validateExpressionSemantics(arithExpr.getRight(), scope);
            
            // 算术表达式要求操作数为数值类型
            ExpressionType leftType = arithExpr.getLeft().getType();
            ExpressionType rightType = arithExpr.getRight().getType();
            
            if (leftType != null && !isNumericType(leftType.toString())) {
                throw new SemanticException("Left operand of arithmetic expression must be numeric, got: " + leftType);
            }
            
            if (rightType != null && !isNumericType(rightType.toString())) {
                throw new SemanticException("Right operand of arithmetic expression must be numeric, got: " + rightType);
            }
            
            // 特殊处理除法和取模运算 - 防止除零
            if (arithExpr.getOperator() == ArithmeticOperator.DIVIDE ||
                arithExpr.getOperator() == ArithmeticOperator.MODULO) {
                
                // 如果右操作数是常量，检查除零错误
                if (arithExpr.getRight() instanceof LiteralExpression) {
                    LiteralExpression rightLit = (LiteralExpression) arithExpr.getRight();
                    if (rightLit.isNumber() && Double.parseDouble(rightLit.getValue().toString()) == 0) {
                        throw new SemanticException("Division by zero");
                    }
                }
            }
        } else if (expr instanceof InListExpression) {
            InListExpression inExpr = (InListExpression) expr;
            
            // 验证左侧表达式
            validateExpressionSemantics(inExpr.getLeft(), scope);
            
            // 验证值列表中的每个表达式
            for (Expression valueExpr : inExpr.getValueList()) {
                validateExpressionSemantics(valueExpr, scope);
                
                // 验证类型兼容性
                if (valueExpr.getType() != null && inExpr.getLeft().getType() != null) {
                    if (!isTypeCompatible(inExpr.getLeft().getType().toString(), valueExpr.getType().toString())) {
                        throw new SemanticException.TypeIncompatibleException(
                            inExpr.getLeft().getType().name(), valueExpr.getType().name());
                    }
                }
            }
        } else if (expr instanceof FunctionExpression) {
            FunctionExpression funcExpr = (FunctionExpression) expr;
            
            // 验证函数参数
            if (funcExpr.getArgument() != null) {
                validateExpressionSemantics(funcExpr.getArgument(), scope);
                
                // 可以添加更多函数特定的验证逻辑
                // 例如：验证函数参数类型是否符合函数要求
            }
        } else if (expr instanceof SubqueryExpression) {
            // 子查询的验证应该在其他地方处理
            // 这里可以添加一些额外的子查询验证逻辑
        }
    }

    /**
     * 验证列表达式
     */
    public void validateColumnExpression(ColumnExpression colExpr, SqlValidatorScope scope) throws SemanticException {
        String tableName = colExpr.getTableAlias();
        String columnName = colExpr.getColumnName();

        // === 1. CatalogManager验证（权威验证） ===
        Column catalogColumn = null;
        if (tableName != null) {
            // 带表前缀的列引用
            if (!catalogManager.tableExists(tableName)) {
                throw new SemanticException.TableNotFoundException(tableName);
            }

            Table table = catalogManager.getTable(tableName);
            catalogColumn = table.getColumn(columnName);
            if (catalogColumn == null) {
                throw new SemanticException.ColumnNotFoundException(tableName, columnName);
            }
        } else {
            // 不带表前缀的列引用 - 在所有可见表中查找
            catalogColumn = findColumnInAllTables(columnName, scope);
            if (catalogColumn == null) {
                throw new SemanticException.ColumnNotFoundException(null, columnName);
            }
        }

        // === 2. SqlValidatorScope验证（作用域验证） ===
        Column scopeColumn = scope.findColumn(tableName, columnName);
        if (scopeColumn == null) {
            throw new SemanticException("Column '" +
                    (tableName != null ? tableName + "." + columnName : columnName) +
                    "' not found in current scope");
        }

        // === 3. 一致性验证 ===
        if (!catalogColumn.getType().equals(scopeColumn.getType())) {
            throw new SemanticException("Type inconsistency for column '" + columnName +
                    "': catalog type is " + catalogColumn.getType() +
                    ", scope type is " + scopeColumn.getType());
        }

        // === 4. 设置列的类型信息 ===
        colExpr.setColumnType(catalogColumn.getType());
    }

    /**
     * 在所有可见的表中查找列
     * 当列引用没有表前缀时使用
     */
    private Column findColumnInAllTables(String columnName, SqlValidatorScope scope) {
        for (String namespaceName : scope.getNamespaces().keySet()) {
            SqlValidatorNamespace namespace = scope.findNamespace(namespaceName);
            if (namespace != null && namespace.getTable() != null) {
                String tableName = namespace.getTable().getName();

                // 使用CatalogManager查找列
                if (catalogManager.tableExists(tableName)) {
                    Table table = catalogManager.getTable(tableName);
                    Column column = table.getColumn(columnName);
                    if (column != null) {
                        return column;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 验证一般表达式
     */
    private void validateExpression(Expression expr, SqlValidatorScope scope) throws SemanticException {
        if (expr instanceof ColumnExpression) {
            ColumnExpression colExpr = (ColumnExpression) expr;

            if (!colExpr.getAll()) {
                validateColumnExpression(colExpr, scope);
            }
        } else if (expr instanceof ComparisonExpression) {
            ComparisonExpression compExpr = (ComparisonExpression) expr;
            validateExpression(compExpr.getLeft(), scope);
            validateExpression(compExpr.getRight(), scope);
        } else if (expr instanceof LogicalExpression) {
            LogicalExpression logExpr = (LogicalExpression) expr;
            validateExpression(logExpr.getLeft(), scope);
            validateExpression(logExpr.getRight(), scope);
        } else if (expr instanceof ArithmeticExpression) {
            ArithmeticExpression arithExpr = (ArithmeticExpression) expr;
            validateExpression(arithExpr.getLeft(), scope);
            validateExpression(arithExpr.getRight(), scope);
        } else if (expr instanceof FunctionExpression) {
            FunctionExpression funcExpr = (FunctionExpression) expr;
            if (funcExpr.getArgument() != null) {
                validateExpression(funcExpr.getArgument(), scope);
            }
        } else if (expr instanceof SubqueryExpression) {
            // 子查询的验证应该在作用域注册阶段已经完成
            SubqueryExpression subqueryExpr = (SubqueryExpression) expr;
            // 这里可以做一些额外的子查询验证
        } else if (expr instanceof InListExpression) {
            InListExpression inExpr = (InListExpression) expr;
            validateExpression(inExpr.getLeft(), scope);
            for (Expression valueExpr : inExpr.getValueList()) {
                validateExpression(valueExpr, scope);
            }
        }
    }

    /**
     * 常量折叠 - 从 DefaultSemanticAnalyzer 移入
     * 在编译时计算常量表达式，减少运行时计算开销
     * <p>
     * 参考 Apache Calcite 的常量折叠优化
     */
    private void foldConstants(SQLStatement statement, SqlValidatorScope scope) throws SemanticException {
        if (statement instanceof SelectStatement) {
            SelectStatement select = (SelectStatement) statement;

            // 折叠SELECT子句中的常量表达式
            for (SelectStatement.SelectItem item : select.getSelectItems()) {
                Expression foldedExpr = foldExpressionConstants(item.getExpression(), scope);
                item.setExpression(foldedExpr);
            }

            // 折叠WHERE子句中的常量表达式
            if (select.getWhere() != null) {
                select.setWhere(foldExpressionConstants(select.getWhere(), scope));
            }

            // 折叠JOIN条件中的常量表达式
            if (select.getJoins() != null) {
                for (SelectStatement.JoinClause join : select.getJoins()) {
                    if (join.getJoinCondition() != null) {
                        join.setJoinCondition(foldExpressionConstants(join.getJoinCondition(), scope));
                    }
                }
            }

            // 折叠GROUP BY子句中的常量表达式
            if (select.getGroupByColumns() != null) {
                List<Expression> foldedGroupBy = new ArrayList<>();
                for (Expression groupExpr : select.getGroupByColumns()) {
                    foldedGroupBy.add(foldExpressionConstants(groupExpr, scope));
                }
                select.setGroupByColumns(foldedGroupBy);
            }

            // 折叠HAVING子句中的常量表达式
            if (select.getHaving() != null) {
                select.setHaving(foldExpressionConstants(select.getHaving(), scope));
            }

            // 折叠ORDER BY子句中的常量表达式
            if (select.getOrderByItems() != null) {
                for (SelectStatement.OrderByItem orderItem : select.getOrderByItems()) {
                    orderItem.setExpression(foldExpressionConstants(orderItem.getExpression(), scope));
                }
            }
        }
    }

    /**
     * 折叠表达式中的常量
     * 支持算术运算、逻辑运算、字符串连接等常量计算
     */
    private Expression foldExpressionConstants(Expression expr, SqlValidatorScope scope) throws SemanticException {
        if (expr instanceof ArithmeticExpression) {
            ArithmeticExpression arithExpr = (ArithmeticExpression) expr;

            // 递归折叠子表达式
            Expression left = foldExpressionConstants(arithExpr.getLeft(), scope);
            Expression right = foldExpressionConstants(arithExpr.getRight(), scope);

            // 如果两个操作数都是字面量，则计算表达式
            if (left instanceof LiteralExpression && right instanceof LiteralExpression) {
                LiteralExpression leftLit = (LiteralExpression) left;
                LiteralExpression rightLit = (LiteralExpression) right;

                // 使用操作符的枚举名称
                String operatorName = arithExpr.getOperator().name();
                LiteralExpression result = evaluateBinaryExpression(leftLit, operatorName, rightLit);
                if (result != null) {
                    // 保持原表达式的类型信息
                    result.setType(arithExpr.getType());
                    return result;
                }
            }

            // 创建新的算术表达式（子表达式可能已被折叠）
            ArithmeticExpression newExpr = new ArithmeticExpression(left, arithExpr.getOperator(), right);
            newExpr.setType(arithExpr.getType());
            return newExpr;
        } else if (expr instanceof ComparisonExpression) {
            ComparisonExpression compExpr = (ComparisonExpression) expr;

            // 递归折叠子表达式
            Expression left = foldExpressionConstants(compExpr.getLeft(), scope);
            Expression right = foldExpressionConstants(compExpr.getRight(), scope);

            // 如果两个操作数都是字面量，则计算表达式
            if (left instanceof LiteralExpression && right instanceof LiteralExpression) {
                LiteralExpression leftLit = (LiteralExpression) left;
                LiteralExpression rightLit = (LiteralExpression) right;

                // 使用操作符的枚举名称
                String operatorName = compExpr.getOperator().name();
                LiteralExpression result = evaluateBinaryExpression(leftLit, operatorName, rightLit);
                if (result != null) {
                    result.setType(compExpr.getType());
                    return result;
                }
            }

            // 创建新的比较表达式
            ComparisonExpression newExpr = new ComparisonExpression(left, compExpr.getOperator(), right);
            newExpr.setType(compExpr.getType());
            return newExpr;
        } else if (expr instanceof LogicalExpression) {
            LogicalExpression logExpr = (LogicalExpression) expr;

            // 递归折叠子表达式
            Expression left = foldExpressionConstants(logExpr.getLeft(), scope);
            Expression right = foldExpressionConstants(logExpr.getRight(), scope);

            // 如果两个操作数都是字面量，则计算表达式
            if (left instanceof LiteralExpression && right instanceof LiteralExpression) {
                LiteralExpression leftLit = (LiteralExpression) left;
                LiteralExpression rightLit = (LiteralExpression) right;

                // 使用操作符的枚举名称
                String operatorName = logExpr.getOperator().name();
                LiteralExpression result = evaluateBinaryExpression(leftLit, operatorName, rightLit);
                if (result != null) {
                    result.setType(logExpr.getType());
                    return result;
                }
            }

            // 创建新的逻辑表达式
            LogicalExpression newExpr = new LogicalExpression(left, logExpr.getOperator(), right);
            newExpr.setType(logExpr.getType());
            return newExpr;
        } else if (expr instanceof FunctionExpression) {
            FunctionExpression funcExpr = (FunctionExpression) expr;

            // 递归折叠函数参数
            Expression arg = funcExpr.getArgument();
            if (arg != null) {
                Expression foldedArg = foldExpressionConstants(arg, scope);

                // 尝试计算确定性函数的常量结果
                LiteralExpression result = evaluateDeterministicFunction(funcExpr.getFunctionName(), foldedArg);
                if (result != null) {
                    result.setType(funcExpr.getType());
                    return result;
                }

                // 创建新的函数表达式
                FunctionExpression newFuncExpr = new FunctionExpression(funcExpr.getFunctionName(), foldedArg);
                newFuncExpr.setType(funcExpr.getType());
                return newFuncExpr;
            }
        } else if (expr instanceof SubqueryExpression) {
            // 子查询不进行常量折叠
            return expr;
        } else if (expr instanceof InListExpression) {
            InListExpression inExpr = (InListExpression) expr;
            
            // 折叠左侧表达式
            Expression left = foldExpressionConstants(inExpr.getLeft(), scope);
            
            // 折叠值列表中的每个表达式
            List<Expression> foldedValues = new ArrayList<>();
            for (Expression valueExpr : inExpr.getValueList()) {
                foldedValues.add(foldExpressionConstants(valueExpr, scope));
            }
            
            // 创建新的IN列表表达式
            return new InListExpression(left, foldedValues);
        }

        // 其他类型的表达式直接返回
        return expr;
    }

    /**
     * 计算二元表达式的常量值
     */
    private LiteralExpression evaluateBinaryExpression(LiteralExpression left, String operator, LiteralExpression right) throws SemanticException {
        try {
            // 使用SQLLexer.TokenType替代字符串比较
            switch (operator.toUpperCase()) {
                // 算术运算
                case "+":
                case "PLUS":
                    return evaluateArithmeticAdd(left, right);
                case "-":
                case "MINUS":
                    return evaluateArithmeticSubtract(left, right);
                case "*":
                case "STAR":
                    return evaluateArithmeticMultiply(left, right);
                case "/":
                case "DIVIDE":
                    return evaluateArithmeticDivide(left, right);
                case "%":
                case "MODULO":
                    return evaluateArithmeticModulo(left, right);

                // 比较运算
                case "=":
                case "EQUALS":
                    return new LiteralExpression(compareValues(left, right) == 0, LiteralType.BOOLEAN);
                case "!=":
                case "<>":
                case "NOT_EQUALS":
                    return new LiteralExpression(compareValues(left, right) != 0, LiteralType.BOOLEAN);
                case "<":
                case "LESS":
                    return new LiteralExpression(compareValues(left, right) < 0, LiteralType.BOOLEAN);
                case "<=":
                case "LESS_EQUALS":
                    return new LiteralExpression(compareValues(left, right) <= 0, LiteralType.BOOLEAN);
                case ">":
                case "GREATER":
                    return new LiteralExpression(compareValues(left, right) > 0, LiteralType.BOOLEAN);
                case ">=":
                case "GREATER_EQUALS":
                    return new LiteralExpression(compareValues(left, right) >= 0, LiteralType.BOOLEAN);

                // 逻辑运算
                case "AND":
                    if (left.isBoolean() && right.isBoolean()) {
                        boolean leftVal = (Boolean) left.getValue();
                        boolean rightVal = (Boolean) right.getValue();
                        return new LiteralExpression(leftVal && rightVal, LiteralType.BOOLEAN);
                    }
                    break;
                case "OR":
                    if (left.isBoolean() && right.isBoolean()) {
                        boolean leftVal = (Boolean) left.getValue();
                        boolean rightVal = (Boolean) right.getValue();
                        return new LiteralExpression(leftVal || rightVal, LiteralType.BOOLEAN);
                    }
                    break;

                // 字符串连接
                case "||":
                case "CONCAT":
                    if (left.isString() && right.isString()) {
                        String result = left.getValue().toString() + right.getValue().toString();
                        return new LiteralExpression(result, LiteralType.STRING);
                    }
                    break;
            }
        } catch (Exception e) {
            // 计算失败时返回null，保持原表达式
            return null;
        }

        return null; // 无法计算的表达式
    }

    /**
     * 算术加法
     */
    private LiteralExpression evaluateArithmeticAdd(LiteralExpression left, LiteralExpression right) {
        if (left.isNumber() && right.isNumber()) {
            if (isFloatingPoint(left) || isFloatingPoint(right)) {
                double result = getDoubleValue(left) + getDoubleValue(right);
                return new LiteralExpression(result, LiteralType.NUMBER);
            } else {
                long result = getLongValue(left) + getLongValue(right);
                return new LiteralExpression(result, LiteralType.NUMBER);
            }
        }
        return null;
    }

    /**
     * 算术减法
     */
    private LiteralExpression evaluateArithmeticSubtract(LiteralExpression left, LiteralExpression right) {
        if (left.isNumber() && right.isNumber()) {
            if (isFloatingPoint(left) || isFloatingPoint(right)) {
                double result = getDoubleValue(left) - getDoubleValue(right);
                return new LiteralExpression(result, LiteralType.NUMBER);
            } else {
                long result = getLongValue(left) - getLongValue(right);
                return new LiteralExpression(result, LiteralType.NUMBER);
            }
        }
        return null;
    }

    /**
     * 算术乘法
     */
    private LiteralExpression evaluateArithmeticMultiply(LiteralExpression left, LiteralExpression right) {
        if (left.isNumber() && right.isNumber()) {
            if (isFloatingPoint(left) || isFloatingPoint(right)) {
                double result = getDoubleValue(left) * getDoubleValue(right);
                return new LiteralExpression(result, LiteralType.NUMBER);
            } else {
                long result = getLongValue(left) * getLongValue(right);
                return new LiteralExpression(result, LiteralType.NUMBER);
            }
        }
        return null;
    }

    /**
     * 算术除法
     */
    private LiteralExpression evaluateArithmeticDivide(LiteralExpression left, LiteralExpression right) {
        if (left.isNumber() && right.isNumber()) {
            double rightVal = getDoubleValue(right);
            if (rightVal == 0.0) {
                throw new SemanticException("Division by zero");
            }
            double result = getDoubleValue(left) / rightVal;
            return new LiteralExpression(result, LiteralType.NUMBER);
        }
        return null;
    }

    /**
     * 算术取模
     */
    private LiteralExpression evaluateArithmeticModulo(LiteralExpression left, LiteralExpression right) {
        if (left.isNumber() && right.isNumber()) {
            long rightVal = getLongValue(right);
            if (rightVal == 0) {
                throw new SemanticException("Division by zero in modulo operation");
            }
            long result = getLongValue(left) % rightVal;
            return new LiteralExpression(result, LiteralType.NUMBER);
        }
        return null;
    }

    /**
     * 比较两个字面量的值
     */
    private int compareValues(LiteralExpression left, LiteralExpression right) {
        // NULL值处理
        if (left.isNull() || right.isNull()) {
            if (left.isNull() && right.isNull()) return 0;
            return left.isNull() ? -1 : 1;
        }

        // 数值比较
        if (left.isNumber() && right.isNumber()) {
            double leftVal = getDoubleValue(left);
            double rightVal = getDoubleValue(right);
            return Double.compare(leftVal, rightVal);
        }

        // 字符串比较
        if (left.isString() && right.isString()) {
            return left.getValue().toString().compareTo(right.getValue().toString());
        }

        // 布尔比较
        if (left.isBoolean() && right.isBoolean()) {
            boolean leftVal = (Boolean) left.getValue();
            boolean rightVal = (Boolean) right.getValue();
            return Boolean.compare(leftVal, rightVal);
        }

        // 类型不匹配
        throw new SemanticException("Cannot compare different types");
    }

    /**
     * 计算确定性函数的常量值
     */
    private LiteralExpression evaluateDeterministicFunction(String functionName, Expression arg) {
        if (!(arg instanceof LiteralExpression)) {
            return null; // 参数不是常量
        }

        LiteralExpression literal = (LiteralExpression) arg;

        try {
            switch (functionName.toUpperCase()) {
                // 字符串函数
                case "LENGTH":
                case "CHAR_LENGTH":
                    if (literal.isString()) {
                        int length = literal.getValue().toString().length();
                        return new LiteralExpression(length, LiteralType.NUMBER);
                    }
                    break;

                case "UPPER":
                    if (literal.isString()) {
                        String result = literal.getValue().toString().toUpperCase();
                        return new LiteralExpression(result, LiteralType.STRING);
                    }
                    break;

                case "LOWER":
                    if (literal.isString()) {
                        String result = literal.getValue().toString().toLowerCase();
                        return new LiteralExpression(result, LiteralType.STRING);
                    }
                    break;

                // 数学函数
                case "ABS":
                    if (literal.isNumber()) {
                        if (isFloatingPoint(literal)) {
                            double result = Math.abs(getDoubleValue(literal));
                            return new LiteralExpression(result, LiteralType.NUMBER);
                        } else {
                            long result = Math.abs(getLongValue(literal));
                            return new LiteralExpression(result, LiteralType.NUMBER);
                        }
                    }
                    break;

                case "SQRT":
                    if (literal.isNumber()) {
                        double result = Math.sqrt(getDoubleValue(literal));
                        return new LiteralExpression(result, LiteralType.NUMBER);
                    }
                    break;

                case "ROUND":
                    if (literal.isNumber()) {
                        double result = Math.round(getDoubleValue(literal));
                        return new LiteralExpression(result, LiteralType.NUMBER);
                    }
                    break;
            }
        } catch (Exception e) {
            // 计算失败时返回null
            return null;
        }

        return null; // 无法计算的函数
    }

    /**
     * 检查是否为浮点数
     */
    private boolean isFloatingPoint(LiteralExpression literal) {
        if (!literal.isNumber()) return false;
        String value = literal.getValue().toString();
        return value.contains(".") || value.toLowerCase().contains("e");
    }

    /**
     * 获取双精度浮点值
     */
    private double getDoubleValue(LiteralExpression literal) {
        return Double.parseDouble(literal.getValue().toString());
    }

    /**
     * 获取长整型值
     */
    private long getLongValue(LiteralExpression literal) {
        return Long.parseLong(literal.getValue().toString());
    }

    /**
     * 验证聚合查询的语义规则
     * GROUP BY查询的特殊限制
     */
    private void validateAggregateSemantics(SQLStatement statement, SqlValidatorScope scope) throws SemanticException {
        if (statement instanceof SelectStatement) {
            SelectStatement select = (SelectStatement) statement;

            // 检查是否为聚合查询
            boolean hasGroupBy = select.getGroupByColumns() != null && !select.getGroupByColumns().isEmpty();
            boolean hasAggregateInSelect = hasAggregateFunction(select.getSelectItems());

            if (hasGroupBy || hasAggregateInSelect) {
                // 这是一个聚合查询，需要验证特殊规则
                validateAggregateQuery(select, scope);
            }
        }
    }

    /**
     * 验证聚合查询的特殊规则
     */
    private void validateAggregateQuery(SelectStatement select, SqlValidatorScope scope) throws SemanticException {
        List<Expression> groupByExpressions = select.getGroupByColumns();
        List<SelectStatement.SelectItem> selectItems = select.getSelectItems();

        // 在聚合查询中，SELECT子句中的每一项必须是：
        // 1. GROUP BY中出现的表达式，或
        // 2. 聚合函数
        for (SelectStatement.SelectItem item : selectItems) {
            Expression expr = item.getExpression();

            if (!isAggregateFunction(expr) && !isInGroupBy(expr, groupByExpressions)) {
                // 不是聚合函数，也不在GROUP BY中
                if (expr instanceof ColumnExpression) {
                    ColumnExpression colExpr = (ColumnExpression) expr;
                    String columnDesc = colExpr.getTableAlias() != null
                            ? colExpr.getTableAlias() + "." + colExpr.getColumnName()
                            : colExpr.getColumnName();
                    throw new SemanticException(
                            "Column '" + columnDesc + "' must appear in GROUP BY clause or be used in an aggregate function");
                } else {
                    throw new SemanticException(
                            "Expression must appear in GROUP BY clause or be an aggregate function");
                }
            }
        }
    }

    /**
     * 检查SELECT项中是否包含聚合函数
     */
    private boolean hasAggregateFunction(List<SelectStatement.SelectItem> selectItems) {
        for (SelectStatement.SelectItem item : selectItems) {
            if (containsAggregateFunction(item.getExpression())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查表达式是否包含聚合函数
     */
    private boolean containsAggregateFunction(Expression expr) {
        if (expr instanceof FunctionExpression) {
            FunctionExpression funcExpr = (FunctionExpression) expr;
            if (isAggregateFunction(funcExpr)) {
                return true;
            }
            // 检查函数参数
            if (funcExpr.getArgument() != null) {
                return containsAggregateFunction(funcExpr.getArgument());
            }
        } else if (expr instanceof BinaryExpression) {
            BinaryExpression binExpr = (BinaryExpression) expr;
            return containsAggregateFunction(binExpr.getLeft()) ||
                    containsAggregateFunction(binExpr.getRight());
        } else if (expr instanceof ArithmeticExpression) {
            ArithmeticExpression arithExpr = (ArithmeticExpression) expr;
            return containsAggregateFunction(arithExpr.getLeft()) ||
                    containsAggregateFunction(arithExpr.getRight());
        } else if (expr instanceof ComparisonExpression) {
            ComparisonExpression compExpr = (ComparisonExpression) expr;
            return containsAggregateFunction(compExpr.getLeft()) ||
                    containsAggregateFunction(compExpr.getRight());
        } else if (expr instanceof LogicalExpression) {
            LogicalExpression logExpr = (LogicalExpression) expr;
            return containsAggregateFunction(logExpr.getLeft()) ||
                    containsAggregateFunction(logExpr.getRight());
        } else if (expr instanceof InListExpression) {
            InListExpression inExpr = (InListExpression) expr;
            if (containsAggregateFunction(inExpr.getLeft())) {
                return true;
            }
            for (Expression valueExpr : inExpr.getValueList()) {
                if (containsAggregateFunction(valueExpr)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 检查是否为聚合函数
     */
    private boolean isAggregateFunction(Expression expr) {
        if (expr instanceof FunctionExpression) {
            FunctionExpression funcExpr = (FunctionExpression) expr;
            String funcName = funcExpr.getFunctionName().toUpperCase();
            return isAggregateFunction(funcName);
        }
        return false;
    }

    /**
     * 检查函数名是否为聚合函数
     */
    private boolean isAggregateFunction(String functionName) {
        String upperName = functionName.toUpperCase();
        return upperName.equals("COUNT") ||
                upperName.equals("SUM") ||
                upperName.equals("AVG") ||
                upperName.equals("MIN") ||
                upperName.equals("MAX") ||
                upperName.equals("GROUP_CONCAT");
    }

    /**
     * 检查表达式是否在GROUP BY列表中
     */
    private boolean isInGroupBy(Expression expr, List<Expression> groupByExpressions) {
        if (groupByExpressions == null || groupByExpressions.isEmpty()) {
            return false;
        }

        for (Expression groupExpr : groupByExpressions) {
            if (expressionsEqual(expr, groupExpr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 比较两个表达式是否相等
     * 简化版本 - 实际实现中需要更复杂的比较逻辑
     */
    private boolean expressionsEqual(Expression expr1, Expression expr2) {
        if (expr1.getClass() != expr2.getClass()) {
            return false;
        }

        if (expr1 instanceof ColumnExpression && expr2 instanceof ColumnExpression) {
            ColumnExpression col1 = (ColumnExpression) expr1;
            ColumnExpression col2 = (ColumnExpression) expr2;

            // 比较表别名和列名
            boolean tableEqual = (col1.getTableAlias() == null && col2.getTableAlias() == null) ||
                    (col1.getTableAlias() != null && col1.getTableAlias().equals(col2.getTableAlias()));
            boolean columnEqual = col1.getColumnName().equals(col2.getColumnName());

            return tableEqual && columnEqual;
        }

        // TODO: 处理其他类型的表达式比较
        return false;
    }

    /**
     * 验证WHERE子句中不能包含聚合函数
     */
    private void validateNoAggregateInWhere(Expression expr) throws SemanticException {
        if (containsAggregateFunction(expr)) {
            throw new SemanticException("Aggregate functions are not allowed in WHERE clause");
        }
    }

    /**
     * 验证表达式中不能包含聚合函数（用于GROUP BY等子句）
     */
    private void validateNoAggregateExpression(Expression expr, String clauseName) throws SemanticException {
        if (containsAggregateFunction(expr)) {
            throw new SemanticException("Aggregate functions are not allowed in " + clauseName + " clause");
        }
    }
}
