package cn.zhangyis.sql.parser.expression;

import cn.zhangyis.sql.parser.SelectStatement;

/**
 * 表示表达式中的子查询（例如，WHERE子句中）
 */
public class SubqueryExpression implements Expression {
    private final SelectStatement subquery;
    private final SubqueryType type;
    private final Expression leftExpression; // 用于IN, ANY, ALL比较
    private String dataType; // 添加数据类型字段，用于类型推断

    public enum SubqueryType {
        SCALAR, EXISTS, IN, ANY, ALL
    }

    public SubqueryExpression(SelectStatement subquery, SubqueryType type) {
        this.subquery = subquery;
        this.type = type;
        this.leftExpression = null;
    }

    public SubqueryExpression(Expression leftExpression, SelectStatement subquery, SubqueryType type) {
        this.leftExpression = leftExpression;
        this.subquery = subquery;
        this.type = type;
    }

    public SelectStatement getSubquery() {
        return subquery;
    }

    public SubqueryType getSubqueryType() {
        return type;
    }

    public Expression getLeftExpression() {
        return leftExpression;
    }

    @Override
    public String getType() {
        return dataType;
    }

    @Override
    public void setType(String type) {
        this.dataType = type;
    }

    @Override
    public ExpressionType getExpressionType() {
        return ExpressionType.SUBQUERY;
    }

    @Override
    public String toString() {
        switch (type) {
            case SCALAR:
                return "(" + subquery + ")";
            case EXISTS:
                return "EXISTS (" + subquery + ")";
            case IN:
                return leftExpression + " IN (" + subquery + ")";
            case ANY:
                return leftExpression + " = ANY (" + subquery + ")";
            case ALL:
                return leftExpression + " = ALL (" + subquery + ")";
            default:
                return "(" + subquery + ")";
        }
    }
}