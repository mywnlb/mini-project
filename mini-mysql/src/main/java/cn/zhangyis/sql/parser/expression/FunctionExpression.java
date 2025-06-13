package cn.zhangyis.sql.parser.expression;

/**
 * 函数表达式，表示SQL中的函数调用
 *
 * @Description 函数表达式
 * @Date 2025/3/16 21:18
 * @Created by libo
 */
public class FunctionExpression implements Expression {
    private final String functionName;
    private final Expression argument;
    private ExpressionType dataType; // 添加数据类型字段，用于类型推断

    public FunctionExpression(String functionName, Expression argument) {
        this.functionName = functionName;
        this.argument = argument;
    }

    public String getFunctionName() {
        return functionName;
    }

    public Expression getArgument() {
        return argument;
    }

    @Override
    public ExpressionType getType() {
        return dataType;
    }


    @Override
    public String toString() {
        return functionName + "(" + argument + ")";
    }

}
