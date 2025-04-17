package cn.zhangyis.sql;

/**
 * @Description TODO
 * @Date 2025/3/16 21:18
 * @Created by libo
 */

public class FunctionExpression implements Expression {
    private final String functionName;
    private final Expression argument;

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
    public String toString() {
        return functionName + "(" + argument + ")";
    }

    @Override
    public ExpressionType getType() {
        return ExpressionType.FUNCTION;
    }
}
