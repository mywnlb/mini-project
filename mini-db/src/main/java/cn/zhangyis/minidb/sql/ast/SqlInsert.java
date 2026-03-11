package cn.zhangyis.minidb.sql.ast;

/**
 * INSERT INTO table (col1, col2) VALUES (v1, v2), (v3, v4)
 */
public record SqlInsert(SqlIdentifier table, SqlNodeList columns, SqlNodeList valueRows) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.INSERT; }
}
