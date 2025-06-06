package cn.zhangyis.sql.planner.semantic;

import cn.zhangyis.sql.planner.logical.RelNode;
import java.util.List;

/**
 * 语义优化器接口
 * 负责对逻辑计划进行语义层面的优化
 */
public interface SemanticOptimizer {
    /**
     * 优化逻辑计划
     * @param plan 逻辑计划
     * @return 优化后的逻辑计划
     */
    RelNode optimize(RelNode plan);
    
    /**
     * 添加优化规则
     * @param rule 优化规则
     */
    void addRule(SemanticRule rule);
    
    /**
     * 获取优化规则列表
     */
    List<SemanticRule> getRules();
    
    /**
     * 清除优化规则
     */
    void clearRules();
    
    /**
     * 设置语义验证器
     */
    void setValidator(SemanticValidator validator);
    
    /**
     * 获取语义验证器
     */
    SemanticValidator getValidator();
} 