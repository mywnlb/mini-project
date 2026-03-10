package cn.zhangyis.minidb.sql.ast;

public record SqlBinaryOp(SqlKind opKind, SqlNode left, SqlNode right) implements SqlNode {
    @Override
    public SqlKind kind() { return opKind; }
}