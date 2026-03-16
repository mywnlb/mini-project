package cn.zhangyis.minidb.sql.ast;

/**
 * FROM 子查询（派生表）：(SELECT ...) AS alias
 * 出现在 FROM 位置，必须有别名。
 */
public record SqlDerivedTable(SqlSelect select, String alias) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.DERIVED_TABLE; }

    public String visibleName() { return alias; }

    @Override
    public String toString() {
        return "(" + select + ") AS " + alias;
    }
}
