package cn.zhangyis.minidb.sql.ast;

/**
 * UPDATE table SET col1 = v1, col2 = v2 WHERE condition
 */
public record SqlUpdate(SqlIdentifier table, SqlNodeList assignments, SqlNode where) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.UPDATE; }
}
