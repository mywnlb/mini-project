package cn.zhangyis.minidb.sql.ast;

public record SqlAlias(SqlNode expression, String alias) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.ALIAS; }

    @Override
    public String toString() {
        return expression + " AS " + alias;
    }
}
