package cn.zhangyis.sql.parser.expression;

/**
 * 表达式类型枚举
 */
public enum ExpressionType {
    COLUMN,     // 列引用
    LITERAL,    // 字面量
    COMPARISON, // 比较表达式
    LOGICAL,    // 逻辑表达式
    FUNCTION,    // 函数表达式
    IN_LIST,    // IN列表表达式
    ARITHMETIC,  // 算术表达式
    SUBQUERY    // 子查询表达式
}
