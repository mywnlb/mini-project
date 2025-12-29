package cn.zhangyis.sql.planner.logical;

import cn.zhangyis.sql.parser.expression.Expression;
import cn.zhangyis.sql.parser.expression.FunctionExpression;

import java.util.ArrayList;
import java.util.List;

/**
 * 逻辑聚合节点
 * 表示GROUP BY和聚合函数操作
 */
public class LogicalAggregate extends AbstractRelNode {
    private final List<Expression> groupByExpressions;
    private final List<FunctionExpression> aggregateFunctions;

    /**
     * 创建聚合节点
     * 
     * @param input              输入节点
     * @param groupByExpressions GROUP BY表达式列表，为空表示无分组
     * @param aggregateFunctions 聚合函数列表
     */
    public LogicalAggregate(RelNode input, List<Expression> groupByExpressions,
            List<FunctionExpression> aggregateFunctions) {
        super();
        this.groupByExpressions = new ArrayList<>(groupByExpressions != null ? groupByExpressions : new ArrayList<>());
        this.aggregateFunctions = new ArrayList<>(aggregateFunctions != null ? aggregateFunctions : new ArrayList<>());
        addInput(input);
        deriveOutput();
    }

    @Override
    public RelNodeType getType() {
        return RelNodeType.AGGREGATE;
    }

    @Override
    protected void deriveOutput() {
        outputExpressions.clear();
        outputNames.clear();

        // 1. 添加GROUP BY列
        for (Expression groupExpr : groupByExpressions) {
            outputExpressions.add(groupExpr);

            // 为GROUP BY表达式生成默认名称
            String name;
            if (groupExpr.toString().length() <= 20) {
                name = groupExpr.toString();
            } else {
                name = "group_" + outputExpressions.size();
            }
            outputNames.add(name);
        }

        // 2. 添加聚合函数
        for (FunctionExpression aggFunction : aggregateFunctions) {
            outputExpressions.add(aggFunction);

            // 为聚合函数生成默认名称
            String name = aggFunction.getName() + "_" + outputExpressions.size();
            outputNames.add(name);
        }

        // 如果既没有GROUP BY也没有聚合函数，那么表示去重操作(DISTINCT)
        // 此时直接继承输入的输出
        if (groupByExpressions.isEmpty() && aggregateFunctions.isEmpty()) {
            RelNode input = inputs.get(0);
            outputExpressions.addAll(input.getOutputExpressions());
            outputNames.addAll(input.getOutputNames());
        }
    }

    /**
     * 获取GROUP BY表达式列表
     */
    public List<Expression> getGroupByExpressions() {
        return new ArrayList<>(groupByExpressions);
    }

    /**
     * 获取聚合函数列表
     */
    public List<FunctionExpression> getAggregateFunctions() {
        return new ArrayList<>(aggregateFunctions);
    }

    /**
     * 检查是否为去重操作
     */
    public boolean isDistinct() {
        return groupByExpressions.isEmpty() && aggregateFunctions.isEmpty();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("LogicalAggregate(");

        if (!groupByExpressions.isEmpty()) {
            sb.append("group=[");
            for (int i = 0; i < groupByExpressions.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(groupByExpressions.get(i));
            }
            sb.append("]");
        }

        if (!aggregateFunctions.isEmpty()) {
            if (!groupByExpressions.isEmpty()) {
                sb.append(", ");
            }
            sb.append("agg=[");
            for (int i = 0; i < aggregateFunctions.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(aggregateFunctions.get(i));
            }
            sb.append("]");
        }

        if (isDistinct()) {
            sb.append("distinct");
        }

        sb.append(")");
        return sb.toString();
    }
}