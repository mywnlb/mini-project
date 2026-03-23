package cn.zhangyis.minidb.sql.ast;

/**
 * 参数占位符节点：对应 SQL 中的 ?
 *
 * @param index 参数索引（从0开始）
 */
public record SqlParameter(int index) implements SqlNode {
    @Override
    public SqlKind kind() {
        return SqlKind.PARAMETER;
    }

    @Override
    public String toString() {
        return "?[" + index + "]";
    }
}
