package cn.zhangyis.minidb.sql.ast;

public record SqlTableRef(String tableName, String alias) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.TABLE_REF; }

    public String visibleName() {
        return alias != null ? alias : tableName;
    }

    @Override
    public String toString() {
        return alias != null ? tableName + " AS " + alias : tableName;
    }
}
