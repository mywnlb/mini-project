package cn.zhangyis.sql.parser.expression;

import cn.zhangyis.sql.parser.enums.LogicalOperator;

/**
 * 逻辑表达式，表示SQL中的逻辑操作
 */
public class LogicalExpression extends BinaryExpression {
    private final LogicalOperator operator;

    public LogicalExpression(Expression left, LogicalOperator operator, Expression right) {
        this.left = left;
        this.operator = operator;
        this.right = right;
    }

    public Expression getLeft() {
        return left;
    }

    public LogicalOperator getOperator() {
        return operator;
    }

    public Expression getRight() {
        return right;
    }

    @Override
    public ExpressionType getType() {
        return ExpressionType.LOGICAL;
    }

    @Override
    public String toString() {
        return "(" + left + " " + operator + " " + right + ")";
    }
}
