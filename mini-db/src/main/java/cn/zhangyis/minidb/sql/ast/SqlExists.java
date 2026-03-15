package cn.zhangyis.minidb.sql.ast;

/**
 * EXISTS 子查询：EXISTS (SELECT ...)
 * 有结果返回 TRUE，无结果返回 FALSE。
 */
public record SqlExists(SqlSelect select, boolean negated) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.EXISTS; }

    @Override
    public String toString() {
        return (negated ? "NOT " : "") + "EXISTS (" + select + ")";
    }
}
