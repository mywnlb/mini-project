package cn.zhangyis.minidb.sql.ast;

public enum SqlKind {
    SELECT, IDENTIFIER, LITERAL, STAR, NULL_LITERAL, NODE_LIST, JOIN, TABLE_REF, ALIAS,
    // 比较运算符
    BINARY_EQ, BINARY_LT, BINARY_GT, BINARY_LE, BINARY_GE, BINARY_NE,
    // 逻辑运算符
    AND, OR,
    // 聚合函数
    AGG_CALL,
    // DML
    INSERT, UPDATE, DELETE,
    // DDL
    CREATE_TABLE, DROP_TABLE, ALTER_TABLE, CREATE_INDEX, DROP_INDEX,
    // ORDER BY
    ORDER_BY_ITEM
}
