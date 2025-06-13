package cn.zhangyis.sql.parser.expression;

/**
 * 算术表达式，表示SQL中的算术运算
 */
public class ArithmeticExpression implements Expression {
    private final Expression left;
    private final ArithmeticOperator operator;
    private final Expression right;
    private ExpressionType dataType; // 添加数据类型字段，用于类型推断

    public ArithmeticExpression(Expression left, ArithmeticOperator operator, Expression right) {
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
        return dataType;
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

/**
 * 算术运算符枚举
 */
enum ArithmeticOperator {
    ADD("+"),
    SUBTRACT("-"),
    MULTIPLY("*"),
    DIVIDE("/"),
    MODULO("%");

    private final String symbol;

    ArithmeticOperator(String symbol) {
        this.symbol = symbol;
    }

    @Override
    public String toString() {
        return symbol;
    }

    /**
     * 根据字符串获取算术操作符
     */
    public static ArithmeticOperator fromString(String operator) {
        switch (operator) {
            case "+":
                return ADD;
            case "-":
                return SUBTRACT;
            case "*":
                return MULTIPLY;
            case "/":
                return DIVIDE;
            case "%":
                return MODULO;
            default:
                throw new IllegalArgumentException("Unknown arithmetic operator: " + operator);
        }
    }
} 