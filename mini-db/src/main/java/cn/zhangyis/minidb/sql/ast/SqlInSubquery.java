package cn.zhangyis.minidb.sql.ast;

/**
 * IN 子查询：expr IN (SELECT ...)
 * 子查询必须返回单列。
 */
public record SqlInSubquery(SqlNode expr, SqlSelect select, boolean negated) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.IN_SUBQUERY; }

    @Override
    public String toString() {
        return expr + (negated ? " NOT" : "") + " IN (" + select + ")";
    }
}
