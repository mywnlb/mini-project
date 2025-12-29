package cn.zhangyis.sql.planner.logical;

import cn.zhangyis.sql.parser.enums.JoinType;
import cn.zhangyis.sql.parser.expression.Expression;

/**
 * 逻辑连接节点
 * 表示JOIN操作
 */
public class LogicalJoin extends AbstractRelNode {
    private final RelNode left;
    private final RelNode right;
    private final Expression condition;
    private final JoinType joinType;

    public LogicalJoin(RelNode left, RelNode right, Expression condition, JoinType joinType) {
        super();
        this.left = left;
        this.right = right;
        this.condition = condition;
        this.joinType = joinType;

        addInput(left);
        addInput(right);
        deriveOutput();
    }

    @Override
    public RelNodeType getType() {
        return RelNodeType.JOIN;
    }

    @Override
    protected void deriveOutput() {
        // JOIN操作合并左右两侧的输出
        outputExpressions.clear();
        outputNames.clear();

        // 添加左侧输出
        outputExpressions.addAll(left.getOutputExpressions());
        outputNames.addAll(left.getOutputNames());

        // 添加右侧输出
        outputExpressions.addAll(right.getOutputExpressions());
        outputNames.addAll(right.getOutputNames());
    }

    /**
     * 获取左侧输入
     */
    public RelNode getLeft() {
        return left;
    }

    /**
     * 获取右侧输入
     */
    public RelNode getRight() {
        return right;
    }

    /**
     * 获取连接条件
     */
    public Expression getCondition() {
        return condition;
    }

    /**
     * 获取连接类型
     */
    public JoinType getJoinType() {
        return joinType;
    }



    @Override
    public String toString() {
        return "LogicalJoin(type=" + joinType +
                ", condition=" + (condition != null ? condition : "CROSS") + ")";
    }
}