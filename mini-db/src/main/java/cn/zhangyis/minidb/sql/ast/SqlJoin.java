package cn.zhangyis.minidb.sql.ast;

public record SqlJoin(SqlNode left, SqlNode right, SqlNode condition) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.JOIN; }
}
