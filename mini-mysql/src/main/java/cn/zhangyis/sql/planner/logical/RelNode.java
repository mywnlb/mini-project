package cn.zhangyis.sql.planner.logical;

import cn.zhangyis.sql.parser.expression.Expression;

import java.util.List;

/**
 * 关系代数节点接口
 * 所有逻辑计划节点的基类
 * 参考 Apache Calcite 的 RelNode 接口
 */
public interface RelNode {
    /**
     * 获取节点的类型
     */
    RelNodeType getType();

    /**
     * 获取节点的输入节点列表
     */
    List<RelNode> getInputs();

    /**
     * 获取节点的输出表达式列表
     */
    List<Expression> getOutputExpressions();

    /**
     * 获取节点的输出列名列表
     */
    List<String> getOutputNames();

    /**
     * 设置节点的别名
     */
    void setAlias(String alias);

    /**
     * 获取节点的别名
     */
    String getAlias();

    /**
     * 节点类型枚举
     */
    enum RelNodeType {
        TABLE_SCAN, // 表扫描
        PROJECT, // 投影
        FILTER, // 过滤
        JOIN, // 连接
        AGGREGATE, // 聚合
        SORT, // 排序
        VALUES, // 值列表
        INSERT, // 插入
        UPDATE, // 更新
        DELETE, // 删除
        CREATE, // 创建
        UNION, // 并集
        INTERSECT, // 交集
        EXCEPT // 差集
    }
}