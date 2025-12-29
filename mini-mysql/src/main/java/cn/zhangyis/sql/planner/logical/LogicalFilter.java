package cn.zhangyis.sql.planner.logical;

import cn.zhangyis.sql.parser.expression.Expression;

/**
 * 逻辑过滤节点
 * 表示WHERE或HAVING子句的过滤操作
 */
public class LogicalFilter extends AbstractRelNode {
    private final Expression condition;

    public LogicalFilter(Expression condition, RelNode input) {
        super();
        this.condition = condition;
        addInput(input);
        deriveOutput();
    }

    @Override
    public RelNodeType getType() {
        return RelNodeType.FILTER;
    }

    @Override
    protected void deriveOutput() {
        // 过滤操作不改变输出结构，直接继承输入的输出
        RelNode input = inputs.get(0);
        outputExpressions.addAll(input.getOutputExpressions());
        outputNames.addAll(input.getOutputNames());
    }

    /**
     * 获取过滤条件
     */
    public Expression getCondition() {
        return condition;
    }

    @Override
    public String toString() {
        return "LogicalFilter(condition=" + condition + ")";
    }
}