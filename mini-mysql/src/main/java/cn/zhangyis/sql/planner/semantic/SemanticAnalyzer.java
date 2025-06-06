package cn.zhangyis.sql.planner.semantic;

import cn.zhangyis.sql.SQLStatement;
import cn.zhangyis.sql.planner.logical.RelNode;

/**
 * 语义分析器接口
 * 负责将SQL语句转换为逻辑计划
 */
public interface SemanticAnalyzer {
    /**
     * 分析SQL语句
     * @param statement SQL语句
     * @return 逻辑计划
     * @throws SemanticException 如果语义分析失败
     */
    RelNode analyze(SQLStatement statement) throws SemanticException;
    
    /**
     * 获取语义验证器
     */
    SemanticValidator getValidator();
    
    /**
     * 设置语义验证器
     */
    void setValidator(SemanticValidator validator);
    
    /**
     * 获取语义优化器
     */
    SemanticOptimizer getOptimizer();
    
    /**
     * 设置语义优化器
     */
    void setOptimizer(SemanticOptimizer optimizer);
} 