package cn.zhangyis.minidb.sql.ast;

/**
 * 标量子查询：(SELECT ...)
 * 出现在表达式位置，必须返回单行单列。
 */
public record SqlSubquery(SqlSelect select) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.SCALAR_SUBQUERY; }

    @Override
    public String toString() {
        return "(" + select + ")";
    }
}
