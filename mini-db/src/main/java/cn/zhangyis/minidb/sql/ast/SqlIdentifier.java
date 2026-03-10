package cn.zhangyis.minidb.sql.ast;

public record SqlIdentifier(String name) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.IDENTIFIER; }

    public String name() { return name; }
}