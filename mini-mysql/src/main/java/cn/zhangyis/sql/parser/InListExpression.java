package cn.zhangyis.sql.parser;

import cn.zhangyis.sql.parser.expression.Expression;

import java.util.List;

/**
 * 表示形如 "expression IN (value1, value2, ...)" 的IN列表表达式
 */
public class InListExpression implements Expression {
    private final Expression leftExpression;
    private final List<Expression> valueList;

    /**
     * 创建一个IN列表表达式
     *
     * @param leftExpression 要比较的左侧表达式
     * @param valueList IN关键字后面的值列表
     */
    public InListExpression(Expression leftExpression, List<Expression> valueList) {
        this.leftExpression = leftExpression;
        this.valueList = valueList;
    }

    /**
     * 获取左侧表达式
     */
    public Expression getLeftExpression() {
        return leftExpression;
    }

    /**
     * 获取IN列表中的值
     */
    public List<Expression> getValueList() {
        return valueList;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(leftExpression).append(" IN (");

        for (int i = 0; i < valueList.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(valueList.get(i));
        }

        sb.append(")");
        return sb.toString();
    }

    @Override
    public ExpressionType getType() {
        return ExpressionType.IN_LIST;
    }
}