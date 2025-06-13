package cn.zhangyis.sql.parser.expression;

/**
 * 比较表达式，表示SQL中的比较操作
 */
public class ComparisonExpression implements Expression {
    private final Expression left;
    private final ComparisonOperator operator;
    private final Expression right;
    
    public ComparisonExpression(Expression left, ComparisonOperator operator, Expression right) {
        this.left = left;
        this.operator = operator;
        this.right = right;
    }
    
    public Expression getLeft() {
        return left;
    }
    
    public ComparisonOperator getOperator() {
        return operator;
    }
    
    public Expression getRight() {
        return right;
    }
    
    @Override
    public ExpressionType getType() {
        return ExpressionType.COMPARISON;
    }
    
    @Override
    public String toString() {
        return left + " " + operator + " " + right;
    }
}

/**
 * 比较运算符枚举
 */
enum ComparisonOperator {
    EQUALS("="),
    NOT_EQUALS("!="),
    GREATER(">"),
    GREATER_EQUALS(">="),
    LESS("<"),
    LESS_EQUALS("<=");
    
    private final String symbol;
    
    ComparisonOperator(String symbol) {
        this.symbol = symbol;
    }
    
    @Override
    public String toString() {
        return symbol;
    }
} 