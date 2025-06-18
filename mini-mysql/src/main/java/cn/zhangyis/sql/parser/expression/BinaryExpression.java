package cn.zhangyis.sql.parser.expression;

import java.util.Arrays;
import java.util.List;

/**
 * 二元表达式的抽象基类
 * 用于表示具有左右两个操作数的表达式，如算术、比较和逻辑表达式
 */
public abstract class BinaryExpression extends Expression {
    protected Expression left;  // 左操作数
    protected Expression right; // 右操作数

    public Expression getLeft() {
        return left;
    }

    public Expression getRight() {
        return right;
    }
    
    /**
     * 获取所有子表达式
     * 二元表达式有左右两个子表达式
     * 
     * @return 包含左右操作数的列表
     */
    @Override
    public List<Expression> getChildExpressions() {
        return Arrays.asList(left, right);
    }
}
