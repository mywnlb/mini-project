package cn.zhangyis.sql.parser.expression;

import cn.zhangyis.sql.parser.enums.ArithmeticOperator;

/**
 * 算术表达式，表示SQL中的算术运算
 */
public class ArithmeticExpression extends BinaryExpression {
    private final ArithmeticOperator operator;

    public ArithmeticExpression(Expression left, ArithmeticOperator operator, Expression right) {
        super();
        this.left = left;
        this.operator = operator;
        this.right = right;
    }

    public Expression getLeft() {
        return left;
    }

    public ArithmeticOperator getOperator() {
        return operator;
    }

    public Expression getRight() {
        return right;
    }

    @Override
    public ExpressionType getType() {
        return ExpressionType.ARITHMETIC;
    }

    @Override
    public String toString() {
        return "(" + left + " " + operator + " " + right + ")";
    }

    /**
     * 获取操作符的字符串表示
     */
    public String getOperatorString() {
        return operator.toString();
    }
}

