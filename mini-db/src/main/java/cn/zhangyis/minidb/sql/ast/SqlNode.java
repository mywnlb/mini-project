package cn.zhangyis.minidb.sql.ast;

public enum SqlKind {
    SELECT, IDENTIFIER, LITERAL, STAR, BINARY_EQ, NULL_LITERAL
}

public interface SqlNode {
    SqlKind kind();
}