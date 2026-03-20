package cn.zhangyis.minidb.sql.ast;

/**
 * INSERT INTO table [(col1, col2)] SELECT ...
 */
public record SqlInsertSelect(SqlIdentifier table, SqlNodeList columns, SqlSelect select) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.INSERT; }
}
