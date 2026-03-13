package cn.zhangyis.minidb.sql.ast;

/**
 * ORDER BY 项：列 + 排序方向
 */
public record SqlOrderByItem(SqlNode column, boolean ascending) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.ORDER_BY_ITEM; }

    @Override
    public String toString() {
        return column.toString() + (ascending ? " ASC" : " DESC");
    }
}
