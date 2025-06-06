package cn.zhangyis.sql.planner.semantic;


import cn.zhangyis.sql.ColumnExpression;
import cn.zhangyis.sql.Expression;
import cn.zhangyis.sql.SQLStatement;
import cn.zhangyis.sql.SelectStatement;
import cn.zhangyis.sql.planner.logical.LogicalFilter;
import cn.zhangyis.sql.planner.logical.LogicalProject;
import cn.zhangyis.sql.planner.logical.LogicalScan;
import cn.zhangyis.sql.planner.logical.RelNode;
import cn.zhangyis.storage.catalog.CatalogManager;
import cn.zhangyis.storage.catalog.Column;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认语义分析器实现
 * 负责将SQL语句转换为逻辑计划
 * 参考 Apache Calcite 的语义分析流程
 * 
 * 职责：
 * 1. 协调整个语义分析流程
 * 2. 调用SQL重写器进行AST标准化
 * 3. 构建命名空间和作用域
 * 4. 执行语义验证
 * 5. 生成逻辑计划
 * 
 * 设计原则：
 * - 单一职责：专注于语义分析流程协调
 * - 依赖注入：使用外部的重写器和验证器
 * - 清晰的分层：每个阶段职责明确
 */
public class DefaultSemanticAnalyzer implements SemanticAnalyzer {
    private final CatalogManager catalogManager;
    private final SqlRewriter sqlRewriter;
    private SemanticValidator validator;
    private SemanticOptimizer optimizer;

    public DefaultSemanticAnalyzer(CatalogManager catalogManager) {
        this.catalogManager = catalogManager;
        this.sqlRewriter = new SqlRewriter(catalogManager);
        this.validator = new DefaultSemanticValidator(catalogManager);
        this.optimizer = new DefaultSemanticOptimizer();
    }

    /**
     * 构造函数（带自定义重写器）
     */
    public DefaultSemanticAnalyzer(CatalogManager catalogManager, SqlRewriter sqlRewriter) {
        this.catalogManager = catalogManager;
        this.sqlRewriter = sqlRewriter;
        this.validator = new DefaultSemanticValidator(catalogManager);
        this.optimizer = new DefaultSemanticOptimizer();
    }

    @Override
    public RelNode analyze(SQLStatement statement) throws SemanticException {
        // === Apache Calcite 标准语义分析流程 ===
        // 参考: https://calcite.apache.org/docs/tutorial.html
        
        // 初始化顶级作用域
        SqlValidatorScope rootScope = new SqlValidatorScope(null, SqlValidatorScope.ScopeType.TOP_LEVEL);

        try {
            // ====== 阶段0: 预处理 ======
            // 执行无条件重写 (Unconditional Rewrites)
            // 委托给专门的 SqlRewriter 进行处理
            statement = performUnconditionalRewrites(statement);

            // ====== 阶段1: 构建命名空间和作用域 (registerQuery) ======
            // 第一遍分析，建立作用域和命名空间
            // 这是 Apache Calcite 中 registerQuery 步骤的等价实现
            SemanticAnalyzerScopeUtil.buildNamespaces(statement, rootScope, catalogManager);

            // ====== 阶段2: 语义验证 (validate) ======
            // 第二遍分析，验证表名和列名的语义正确性
            // 包括：表存在性检查、列存在性检查、类型兼容性检查
            ValidateTablesAndColumnsUtil.validate(statement, rootScope);

            // ====== 阶段3: SELECT * 展开 ======
            // 展开星号表达式，将 SELECT * 替换为具体的列列表
            expandStarColumns(statement, rootScope);

            // ====== 阶段4: 类型推导 ======
            // 推导表达式的数据类型，为后续优化提供类型信息
            inferTypes(statement, rootScope);

            // ====== 阶段5: 常量折叠 ======
            // 在编译时计算常量表达式，减少运行时计算开销
            foldConstants(statement);

            // ====== 阶段6: 逻辑计划构建 ======
            // 将已验证的 AST 转换为逻辑关系代数表达式 (RelNode)
            // 这相当于 Apache Calcite 中的 SqlToRelConverter 步骤
            RelNode logicalPlan = buildLogicalPlan(statement, rootScope);

            // ====== 阶段7: 逻辑计划验证 ======
            // 验证生成的逻辑计划的正确性
            validator.validate(logicalPlan);

            // ====== 阶段8: 语义层优化 ======
            // 应用语义层面的优化规则（如常量折叠、谓词简化等）
            // 注意：这里只是语义层优化，物理优化在其他组件中进行
            return optimizer.optimize(logicalPlan);

        } catch (SemanticException e) {
            // 重新抛出语义异常
            throw e;
        } catch (Exception e) {
            // 包装其他异常为语义异常
            throw new SemanticException("Semantic analysis failed: " + e.getMessage(), e);
        }
    }

    @Override
    public SemanticValidator getValidator() {
        return validator;
    }

    @Override
    public void setValidator(SemanticValidator validator) {
        this.validator = validator;
    }

    @Override
    public SemanticOptimizer getOptimizer() {
        return optimizer;
    }

    @Override
    public void setOptimizer(SemanticOptimizer optimizer) {
        this.optimizer = optimizer;
    }

    /**
     * 执行无条件重写
     * 委托给专门的 SqlRewriter 进行处理
     */
    private SQLStatement performUnconditionalRewrites(SQLStatement statement) throws SemanticException {
        return sqlRewriter.rewrite(statement);
    }

    /**
     * 展开星号
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
            SqlValidatorNamespace namespace = scope.getNamespaces().get(starExpr.getTableAlias());
            if (namespace != null) {
                for (Column column : namespace.getColumns()) {
                    ColumnExpression colExpr = new ColumnExpression(starExpr.getTableAlias(), column.getName());
                    expandedItems.add(new SelectStatement.SelectItem(colExpr, null));
                }
            }
        } else {
            // 非限定的星号，展开所有可见的列
            for (SqlValidatorNamespace namespace : scope.getNamespaces().values()) {
                for (Column column : namespace.getColumns()) {
                    ColumnExpression colExpr = new ColumnExpression(namespace.getName(), column.getName());
                    colExpr.setType(column.getType());
                    expandedItems.add(new SelectStatement.SelectItem(colExpr, null));
                }
            }
        }
    }

    /**
     * 类型推导
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
        }
    }

    /**
     * 推导表达式的类型
     */
    private void inferExpressionType(Expression expr, SqlValidatorScope scope) throws SemanticException {
        if (expr instanceof ColumnExpression) {
            ColumnExpression colExpr = (ColumnExpression) expr;
            if (colExpr.getType() == null) {
                // 类型还未设置，尝试从作用域中获取
                Column column = scope.findColumn(colExpr.getTableName(), colExpr.getColumnName());
                if (column != null) {
                    colExpr.setType(column.getType());
                }
            }
        } else if (expr instanceof BinaryExpression) {
            BinaryExpression binExpr = (BinaryExpression) expr;
            inferExpressionType(binExpr.getLeft(), scope);
            inferExpressionType(binExpr.getRight(), scope);

            // 推导二元表达式的类型
            String leftType = binExpr.getLeft().getType();
            String rightType = binExpr.getRight().getType();
            if (leftType != null && rightType != null) {
                validator.validateTypeCompatibility(leftType, rightType);
                binExpr.setType(leftType); // 简化处理，使用左操作数的类型
            }
        }
        // TODO: 处理其他类型的表达式
    }

    /**
     * 常量折叠
     */
    private void foldConstants(SQLStatement statement) throws SemanticException {
        if (statement instanceof SelectStatement) {
            SelectStatement select = (SelectStatement) statement;

            // 折叠WHERE子句中的常量
            if (select.getWhere() != null) {
                select.setWhere(foldExpressionConstants(select.getWhere()));
            }
        }
    }

    /**
     * 构建逻辑计划
     */
    private RelNode buildLogicalPlan(SQLStatement statement, SqlValidatorScope scope) throws SemanticException {
        if (statement instanceof SelectStatement) {
            return buildSelectPlan((SelectStatement) statement, scope);
        }
        throw new SemanticException("Unsupported statement type: " + statement.getClass().getName());
    }

    /**
     * 构建SELECT语句的逻辑计划
     */
    private RelNode buildSelectPlan(SelectStatement select, SqlValidatorScope scope) throws SemanticException {
        // 1. 构建表扫描节点
        RelNode plan = buildTableScan(select.getFrom().get(0), scope);

        // 2. 添加过滤节点
        if (select.getWhere() != null) {
            plan = new LogicalFilter(select.getWhere(), plan);
        }

        // 3. 添加投影节点
        List<Column> outputColumns = new ArrayList<>();
        for (SelectStatement.SelectItem item : select.getSelectItems()) {
            if (item.getExpression() instanceof ColumnExpression) {
                ColumnExpression col = (ColumnExpression) item.getExpression();
                outputColumns.add(new Column(col.getColumnName(), col.getType()));
            }
        }
        plan = new LogicalProject(outputColumns, plan);

        return plan;
    }

    /**
     * 构建表扫描节点
     */
    private RelNode buildTableScan(SelectStatement.TableReference ref, SqlValidatorScope scope) throws SemanticException {
        if (ref instanceof SelectStatement.TableName) {
            SelectStatement.TableName tableName = (SelectStatement.TableName) ref;
            Table table = catalogManager.getTable(tableName.getName());
            return new LogicalScan(table);
        }
        throw new SemanticException("Unsupported table reference type: " + ref.getClass().getName());
    }

    /**
     * 折叠表达式中的常量
     */
    private Expression foldExpressionConstants(Expression expr) throws SemanticException {
        if (expr instanceof BinaryExpression) {
            BinaryExpression bin = (BinaryExpression) expr;
            Expression left = foldExpressionConstants(bin.getLeft());
            Expression right = foldExpressionConstants(bin.getRight());

            // 如果两个操作数都是常量，则计算表达式
            if (left instanceof Literal && right instanceof Literal) {
                return evaluateBinaryExpression((Literal) left, bin.getOperator(), (Literal) right);
            }

            return new BinaryExpression(left, bin.getOperator(), right);
        }
        return expr;
    }

    /**
     * 计算二元表达式
     */
    private Literal evaluateBinaryExpression(Literal left, String operator, Literal right) throws SemanticException {
        // TODO: 实现常量表达式的计算
        return left; // 临时返回左操作数
    }
} 