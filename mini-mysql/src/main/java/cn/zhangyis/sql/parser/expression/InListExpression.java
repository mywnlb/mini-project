package cn.zhangyis.sql.parser.expression;

import java.util.ArrayList;
import java.util.List;

/**
 * IN列表表达式，表示SQL中的 column IN (value1, value2, ...) 结构
 */
public class InListExpression extends Expression {
    private Expression left;        // 左侧表达式
    private List<Expression> values; // 值列表
    private boolean isNot;          // 是否为NOT IN

    public InListExpression(Expression left, List<Expression> values, boolean isNot) {
        this.left = left;
        this.values = values != null ? values : new ArrayList<>();
        this.isNot = isNot;
    }

    @Override
    public ExpressionType getType() {
        return ExpressionType.IN_LIST;
    }

    public Expression getLeft() {
        return left;
    }

    public List<Expression> getValues() {
        return values;
    }

    public boolean isNot() {
        return isNot;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(left.toString());
        sb.append(isNot ? " NOT IN (" : " IN (");
        
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values.get(i).toString());
        }
        
        sb.append(")");
        return sb.toString();
    }
    
    /**
     * 获取所有子表达式
     * IN列表表达式的子表达式包括左侧表达式和所有值列表表达式
     * 
     * @return 包含左侧表达式和值列表的所有表达式
     */
    @Override
    public List<Expression> getChildExpressions() {
        List<Expression> children = new ArrayList<>();
        children.add(left);
        children.addAll(values);
        return children;
    }
} 