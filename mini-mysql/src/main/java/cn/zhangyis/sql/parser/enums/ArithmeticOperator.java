package cn.zhangyis.sql.parser.enums;

import cn.zhangyis.exceptions.ArithmeticException;

/**
 * 算术运算符枚举
 */
public enum ArithmeticOperator {
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
                throw new ArithmeticException("Unknown arithmetic operator: " + operator);
        }
    }
}
