package cn.zhangyis.sql;

/**
 * 字面量表达式，表示SQL中的常量值
 */
public class LiteralExpression implements Expression {
    private final String value;
    private final LiteralType literalType;
    
    public LiteralExpression(String value, LiteralType literalType) {
        this.value = value;
        this.literalType = literalType;
    }
    
    public String getValue() {
        return value;
    }
    
    public LiteralType getLiteralType() {
        return literalType;
    }
    
    @Override
    public ExpressionType getType() {
        return ExpressionType.LITERAL;
    }
    
    @Override
    public String toString() {
        if (literalType == LiteralType.STRING) {
            return "'" + value + "'";
        }
        return value;
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