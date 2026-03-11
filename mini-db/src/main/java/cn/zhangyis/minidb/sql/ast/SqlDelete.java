package cn.zhangyis.minidb.sql.ast;

/**
 * DELETE FROM table WHERE condition
 */
public record SqlDelete(SqlIdentifier table, SqlNode where) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.DELETE; }
}
