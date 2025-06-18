package cn.zhangyis.sql.parser.expression;

import cn.zhangyis.sql.parser.enums.ComparisonOperator;

/**
 * 比较表达式，表示SQL中的比较操作，如 =, >, <, >=, <=, !=
 */
public class ComparisonExpression extends BinaryExpression {
    private ComparisonOperator operator;

    /**
     * 比较操作符枚举
     */
    public enum ComparisonOperator {
        EQUALS("="),
        NOT_EQUALS("!="),
        LESS_THAN("<"),
        LESS_THAN_OR_EQUALS("<="),
        GREATER_THAN(">"),
        GREATER_THAN_OR_EQUALS(">="),
        LIKE("LIKE"),
        NOT_LIKE("NOT LIKE"),
        IS_NULL("IS NULL"),
        IS_NOT_NULL("IS NOT NULL");

        private final String symbol;

        ComparisonOperator(String symbol) {
            this.symbol = symbol;
        }

        public String getSymbol() {
            return symbol;
        }
    }

    /**
     * 创建比较表达式
     * 
     * @param left 左操作数
     * @param right 右操作数
     * @param operator 比较操作符
     */
    public ComparisonExpression(Expression left, Expression right, ComparisonOperator operator) {
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

