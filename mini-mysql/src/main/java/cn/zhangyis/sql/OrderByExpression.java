package cn.zhangyis.sql;

/**
 * @Description TODO
 * @Date 2025/3/12 16:35
 * @Created by libo
 */

public class OrderByExpression implements Expression {
    private final Expression expression;
    private final boolean ascending; // true 为 ASC, false 为 DESC

    public OrderByExpression(Expression expression, boolean ascending) {
        this.expression = expression;
        this.ascending = ascending;
    }

    public Expression getExpression() {
        return expression;
    }

    public boolean isAscending() {
        return ascending;
    }

    @Override
    public ExpressionType getType() {
        return ExpressionType.ORDER_BY;
    }

    @Override
    public String toString() {
        return expression.toString() + (ascending ? " ASC" : " DESC");
    }
}
