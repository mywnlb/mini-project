package cn.zhangyis.sql.parser.expression;

import cn.zhangyis.sql.parser.enums.LiteralType;

import java.util.Collections;
import java.util.List;

/**
 * 字面量表达式，表示SQL中的常量值
 */
public class LiteralExpression extends Expression {
    private String value;
    private LiteralType literalType;

    /**
     * 创建字面量表达式
     *
     * @param value       字面量值
     * @param literalType 字面量类型
     */
    public LiteralExpression(String value, LiteralType literalType) {
        this.value = value;
        this.literalType = literalType;
    }

    @Override
    public ExpressionType getType() {
        return ExpressionType.LITERAL;
    }

    public String getValue() {
        return value;
    }


    @Override
    public String toString() {
        switch (literalType) {
            case STRING:
                return "'" + value + "'";
            case NULL:
                return "NULL";
            case NUMBER:
                return value;
            default:
                return value;
        }
    }

    /**
     * 获取所有子表达式
     * 字面量表达式是叶子节点，没有子表达式
     *
     * @return 空列表
     */
    @Override
    public List<Expression> getChildExpressions() {
        return Collections.emptyList();
    }

    public boolean isNumber() {
        return literalType == LiteralType.NUMBER;
    }

    public boolean isString() {
        return literalType == LiteralType.STRING;
    }

    public boolean isBoolean() {
        return literalType == LiteralType.BOOLEAN;
    }

    public boolean isNull() {
        return literalType == LiteralType.NULL;
    }
}

