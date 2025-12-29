package cn.zhangyis.sql.planner.semantic;

import cn.zhangyis.sql.parser.SQLStatement;
import cn.zhangyis.sql.parser.SelectStatement;
import cn.zhangyis.sql.planner.logical.RelNode;
import cn.zhangyis.sql.planner.semantic.scope.SemanticAnalyzerScopeUtil;
import cn.zhangyis.sql.planner.semantic.scope.SqlValidatorScope;
import cn.zhangyis.storage.catalog.CatalogManager;

/**
 * 默认语义分析器实现
 * 负责将SQL语句转换为逻辑计划
 * 参考 Apache Calcite 的语义分析流程
 *
 * 职责：
 * 1. 协调整个语义分析流程
 * 2. 调用SQL重写器进行AST标准化
 * 3. 构建命名空间和作用域
 * 4. 执行语义验证（委托给ValidateTablesAndColumnsUtil）
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
    private final SqlToLogicalPlanConverter sqlToLogicalPlanConverter;

    public DefaultSemanticAnalyzer(CatalogManager catalogManager) {
        this.catalogManager = catalogManager;
        this.sqlRewriter = new SqlRewriter(catalogManager);
        this.sqlToLogicalPlanConverter = new SqlToLogicalPlanConverter(catalogManager);
        // this.validator = new DefaultSemanticValidator(catalogManager);
        // this.optimizer = new DefaultSemanticOptimizer();
    }

    /**
     * 构造函数（带自定义重写器）
     */
    public DefaultSemanticAnalyzer(CatalogManager catalogManager, SqlRewriter sqlRewriter) {
        this.catalogManager = catalogManager;
        this.sqlRewriter = sqlRewriter;
        this.sqlToLogicalPlanConverter = new SqlToLogicalPlanConverter(catalogManager);
        // this.validator = new DefaultSemanticValidator(catalogManager);
        // this.optimizer = new DefaultSemanticOptimizer();
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

            // ====== 阶段2: 完整的语义验证 (validate) ======
            // 包括：
            // - 表名和列名验证
            // - 星号表达式展开 (expandStarColumns)
            // - 类型推断 (inferTypes/DeriveTypeVisitor)
            // - 常量折叠 (foldConstants)
            // - 表达式验证
            // 参考: https://zhuanlan.zhihu.com/p/58139279
            // ValidateTablesAndColumnsUtil.validate(statement, rootScope, catalogManager);

            // ====== 阶段3: 逻辑计划构建 ======
            // 使用SqlToLogicalPlanConverter将已验证的AST转换为逻辑关系代数表达式(RelNode)
            RelNode logicalPlan = sqlToLogicalPlanConverter.convert(statement, rootScope);

            // ====== 阶段4: 逻辑计划验证 ======
            // 验证生成的逻辑计划的正确性
            if (validator != null) {
                validator.validate(logicalPlan);
            }

            // ====== 阶段5: 语义层优化 ======
            // 应用语义层面的优化规则（如常量折叠、谓词简化等）
            // 注意：这里只是语义层优化，物理优化在其他组件中进行
            if (optimizer != null) {
                return optimizer.optimize(logicalPlan);
            }

            return logicalPlan;

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
}