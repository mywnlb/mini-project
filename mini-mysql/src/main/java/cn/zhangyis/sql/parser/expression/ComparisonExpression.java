package cn.zhangyis.sql.parser.expression;

import cn.zhangyis.sql.parser.enums.ComparisonOperator;

/**
 * 比较表达式，表示SQL中的比较操作，如 =, >, <, >=, <=, !=
 */
public class ComparisonExpression extends BinaryExpression {
    private ComparisonOperator operator;

    /**
     * 创建比较表达式
     *
     * @param left     左操作数
     * @param right    右操作数
     * @param operator 比较操作符
     */
    public ComparisonExpression(Expression left, ComparisonOperator operator, Expression right) {
        super(left, right);
        this.operator = operator;
    }


    @Override
    public ExpressionType getType() {
        return ExpressionType.COMPARISON;
    }

    public ComparisonOperator getOperator() {
        return operator;
    }

    @Override
    public String toString() {
        if (operator == ComparisonOperator.IS_NULL) {
            return getLeft().toString() + " IS NULL";
        } else if (operator == ComparisonOperator.IS_NOT_NULL) {
            return getLeft().toString() + " IS NOT NULL";
        } else {
            return getLeft().toString() + " " + operator.getSymbol() + " " + getRight().toString();
        }
    }
}

