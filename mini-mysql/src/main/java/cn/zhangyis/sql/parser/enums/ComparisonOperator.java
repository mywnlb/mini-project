package cn.zhangyis.sql.parser.enums;

/**
 * 比较运算符枚举
 */
public enum ComparisonOperator {
    EQUALS("="),
    NOT_EQUALS("!="),
    GREATER(">"),
    GREATER_EQUALS(">="),
    LESS("<"),
    LESS_EQUALS("<="),
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

    @Override
    public String toString() {
        return symbol;
    }

    /**
     * 获取操作符的字符串表示
     */
    public String getOperatorString() {
        return symbol;
    }
}
