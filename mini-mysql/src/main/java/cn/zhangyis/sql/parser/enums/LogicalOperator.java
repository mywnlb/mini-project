package cn.zhangyis.sql.parser.enums;


/**
 * 逻辑运算符枚举
 */
public enum LogicalOperator {
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