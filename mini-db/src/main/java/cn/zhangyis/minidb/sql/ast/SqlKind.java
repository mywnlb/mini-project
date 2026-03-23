package cn.zhangyis.minidb.sql.ast;

public enum SqlKind {
    SELECT, IDENTIFIER, LITERAL, STAR, NULL_LITERAL, NODE_LIST, JOIN, TABLE_REF, ALIAS,
    // 比较运算符
    BINARY_EQ, BINARY_LT, BINARY_GT, BINARY_LE, BINARY_GE, BINARY_NE,
    // 逻辑运算符
    AND, OR,CASE,FUNCTION_CALL,ORDER_BY_ITEM,
    // 算术运算符
    ADD, SUB, MUL, DIV,
    // 聚合函数
    AGG_CALL,
    // DML
    INSERT, UPDATE, DELETE,
    // DDL
    CREATE_TABLE, DROP_TABLE, ALTER_TABLE, CREATE_INDEX, DROP_INDEX,
    // 谓词
    LIKE, NOT_LIKE, BETWEEN, NOT_BETWEEN, IN, NOT_IN, IS_NULL, IS_NOT_NULL,
    // 函数调用
    SET_OPERATION,
    // 子查询
    SCALAR_SUBQUERY, EXISTS, IN_SUBQUERY, DERIVED_TABLE,
    // 类型转换
    CAST,
    // 窗口函数
    WINDOW_FUNCTION,
    // 事务控制
    BEGIN_TXN, COMMIT_TXN, ROLLBACK_TXN,
    // CTE
    CTE, WITH_SELECT,
    // 统计
    ANALYZE_TABLE,
    // 查询计划
    EXPLAIN_QUERY,
    // 参数占位符
    PARAMETER
}
