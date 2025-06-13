package cn.zhangyis.sql.parser.expression;

/**
 * SQL表达式接口
 * 所有类型的表达式都实现此接口
 */
public interface Expression {
    /**
     * 获取表达式的类型
     * @return 表达式类型
     */
    ExpressionType getType();

    /**
     * 表达式类型枚举
     */
    public enum ExpressionType {
        COLUMN,     // 列引用
        LITERAL,    // 字面量
        COMPARISON, // 比较表达式
        LOGICAL,    // 逻辑表达式
        ORDER_BY,   // 排序表达式
        GROUP_BY,    // 分组表达式
        FUNCTION,    // 函数表达式
        IN_LIST,    // IN列表表达式
        ARITHMETIC,  // 算术表达式
        SUBQUERY    // 子查询表达式
    }
}

