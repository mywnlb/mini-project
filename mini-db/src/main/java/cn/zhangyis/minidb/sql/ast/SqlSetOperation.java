package cn.zhangyis.minidb.sql.ast;

/**
 * UNION / UNION ALL 集合操作
 */
public record SqlSetOperation(SqlNode left, SqlNode right, boolean all) implements SqlNode {

    @Override
    public SqlKind kind() {
        return SqlKind.SET_OPERATION;
    }
}