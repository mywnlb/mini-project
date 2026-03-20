package cn.zhangyis.minidb.sql.ast;

/**
 * UNION / UNION ALL / EXCEPT / EXCEPT ALL / INTERSECT / INTERSECT ALL 集合操作
 */
public record SqlSetOperation(SqlNode left, SqlNode right, boolean all, SetOpType opType) implements SqlNode {

    public enum SetOpType { UNION, EXCEPT, INTERSECT }

    /** 兼容旧构造（默认 UNION） */
    public SqlSetOperation(SqlNode left, SqlNode right, boolean all) {
        this(left, right, all, SetOpType.UNION);
    }

    @Override
    public SqlKind kind() {
        return SqlKind.SET_OPERATION;
    }
}
