package cn.zhangyis.sql.planner.semantic;

import cn.zhangyis.sql.planner.logical.RelNode;

/**
 * 语义优化规则接口
 * 定义了对逻辑计划进行语义优化的规则
 */
public interface SemanticRule {
    /**
     * 应用优化规则
     * @param plan 输入的逻辑计划
     * @return 优化后的逻辑计划
     */
    RelNode apply(RelNode plan);
    
    /**
     * 获取规则名称
     * @return 规则名称
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }
    
    /**
     * 检查规则是否适用于给定的计划
     * @param plan 逻辑计划
     * @return 如果规则适用返回 true，否则返回 false
     */
    default boolean isApplicable(RelNode plan) {
        return true; // 默认认为规则总是适用的
    }
    
    /**
     * 获取规则优先级
     * 数字越大优先级越高
     */
    int getPriority();
    
    /**
     * 检查规则是否可重复应用
     */
    boolean isRepeatable();
} 