package cn.zhangyis.minidb.sql.ast;

/**
 * SET 子句中的赋值: col = value
 */
public record SqlAssignment(SqlIdentifier column, SqlNode value) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.BINARY_EQ; }
}
