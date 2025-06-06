package cn.zhangyis.sql.planner.physical;

import cn.zhangyis.sql.Column;
import java.util.List;

/**
 * 物理操作符接口
 * 定义物理计划节点的基本操作
 */
public interface PhysicalOperator {
    /**
     * 执行操作符
     * @return 执行结果
     */
    ExecutionResult execute();
    
    /**
     * 获取输出列
     */
    List<Column> getOutputColumns();
    
    /**
     * 获取子节点
     */
    List<PhysicalOperator> getChildren();
    
    /**
     * 设置子节点
     */
    void setChildren(List<PhysicalOperator> children);
    
    /**
     * 获取操作符类型
     */
    OperatorType getType();
    
    /**
     * 操作符类型枚举
     */
    enum OperatorType {
        TABLE_SCAN,
        INDEX_SCAN,
        HASH_JOIN,
        SORT_MERGE_JOIN,
        NESTED_LOOP_JOIN,
        FILTER,
        PROJECT,
        AGGREGATE,
        SORT,
        LIMIT,
        UNION,
        INTERSECT,
        MINUS
    }
} 