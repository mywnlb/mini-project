package cn.zhangyis.sql;

/**
 * 表示表达式中的子查询（例如，WHERE子句中）
 */
public class SubqueryExpression implements Expression {
    @Override
    public ExpressionType getType() {
        return ExpressionType.SUBQUERY;
    }

    public enum SubqueryType {
        SCALAR, EXISTS, IN, ANY, ALL
    }

    private final SelectStatement subquery;
    private final SubqueryType type;
    private final Expression leftExpression; // 用于IN, ANY, ALL比较

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



    public Expression getLeftExpression() {
        return leftExpression;
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