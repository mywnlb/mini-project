package cn.zhangyis.sql.planner.physical;

import cn.zhangyis.sql.planner.logical.RelNode;

/**
 * 物理优化器接口
 * 定义物理计划的优化方法
 */
public interface PhysicalOptimizer {
    /**
     * 优化物理计划
     * @param logicalPlan 逻辑计划
     * @return 物理计划
     */
    PhysicalOperator optimize(RelNode logicalPlan);
    
    /**
     * 获取统计信息收集器
     */
    StatisticsCollector getStatisticsCollector();
    
    /**
     * 设置统计信息收集器
     */
    void setStatisticsCollector(StatisticsCollector collector);
} 