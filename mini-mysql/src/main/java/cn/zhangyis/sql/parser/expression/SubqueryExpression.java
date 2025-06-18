package cn.zhangyis.sql.parser.expression;

import cn.zhangyis.sql.parser.SelectStatement;

import java.util.Collections;
import java.util.List;

/**
 * 表示表达式中的子查询（例如，WHERE子句中）
 */
public class SubqueryExpression extends Expression {
    private final SelectStatement subquery;
    private final SubqueryType type;
    private final Expression leftExpression; // 用于IN, ANY, ALL比较

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
    public ExpressionType getType() {
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

    /**
     * 获取所有子表达式
     * 子查询表达式不包含常规子表达式，因为子查询是通过特殊方式处理的
     * 
     * @return 空列表
     */
    @Override
    public List<Expression> getChildExpressions() {
        // 子查询不通过常规的子表达式机制处理，而是通过registerQuery专门处理
        return Collections.emptyList();
    }
}