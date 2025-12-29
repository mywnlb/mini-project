package cn.zhangyis.sql.parser.expression;

import java.util.ArrayList;
import java.util.List;

/**
 * 函数表达式，表示SQL中的函数调用
 *
 * @Description 函数表达式
 * @Date 2025/3/16 21:18
 * @Created by libo
 */
public class FunctionExpression extends Expression {
    private String name;
    private List<Expression> arguments;
    private boolean isAggregate;
    private boolean isDistinct;

    /**
     * 创建函数表达式
     * 
     * @param name 函数名
     * @param arguments 函数参数列表
     * @param isAggregate 是否是聚合函数
     * @param isDistinct 是否使用了DISTINCT关键字
     */
    public FunctionExpression(String name, List<Expression> arguments, boolean isAggregate, boolean isDistinct) {
        this.name = name;
        this.arguments = arguments != null ? arguments : new ArrayList<>();
        this.isAggregate = isAggregate;
        this.isDistinct = isDistinct;
    }


    @Override
    public ExpressionType getType() {
        return ExpressionType.FUNCTION;
    }

    public String getName() {
        return name;
    }

    public List<Expression> getArguments() {
        return arguments;
    }

    public boolean isAggregate() {
        return isAggregate;
    }

    public boolean isDistinct() {
        return isDistinct;
    }
    
    public void setDistinct(boolean distinct) {
        isDistinct = distinct;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(name);
        sb.append("(");
        for (int i = 0; i < arguments.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(arguments.get(i).toString());
        }
        sb.append(")");
        return sb.toString();
    }
    
    /**
     * 获取所有子表达式
     * 函数表达式的子表达式是其所有参数
     * 
     * @return 函数参数表达式列表
     */
    @Override
    public List<Expression> getChildExpressions() {
        return new ArrayList<>(arguments);
    }
}
