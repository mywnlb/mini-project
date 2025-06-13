package cn.zhangyis.sql.parser.expression;

/**
 * 字面量表达式，表示SQL中的常量值
 */
public class LiteralExpression implements Expression {
    private final Object value;
    private final LiteralType literalType;
    private ExpressionType dataType; // 添加数据类型字段，用于类型推断
    
    public LiteralExpression(Object value, LiteralType literalType) {
        this.value = value;
        this.literalType = literalType;
    }
    
    public Object getValue() {
        return value;
    }

    @Override
    public ExpressionType getType() {
        return dataType;
    }


    
    @Override
    public String toString() {
        if (literalType == LiteralType.STRING) {
            return "'" + value + "'";
        }
        return value.toString();
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

    public LiteralType getLiteralType() {
        return literalType;
    }
}

/**
 * 字面量类型枚举
 */
enum LiteralType {
    NUMBER,
    STRING,
    BOOLEAN,
    NULL
}