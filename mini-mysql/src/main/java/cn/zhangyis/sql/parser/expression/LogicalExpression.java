package cn.zhangyis.sql.parser.expression;

/**
 * 逻辑表达式，表示SQL中的逻辑操作
 */
public class LogicalExpression implements Expression {
    private final Expression left;
    private final LogicalOperator operator;
    private final Expression right;

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

/**
 * 逻辑运算符枚举
 */
enum LogicalOperator {
    AND("AND"),
    OR("OR");

    private final String symbol;

    LogicalOperator(String symbol) {
        this.symbol = symbol;
    }

    @Override
    public String toString() {
        return symbol;
    }
} 