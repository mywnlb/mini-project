package cn.zhangyis.minidb.sql.ast;

public record SqlCte(String name, SqlSelect query) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.CTE; }
}
