package cn.zhangyis.minidb.sql.ast;

public record SqlJoin(JoinType joinType, SqlNode left, SqlNode right, SqlNode condition) implements SqlNode {

    /** 向后兼容：默认 INNER */
    public SqlJoin(SqlNode left, SqlNode right, SqlNode condition) {
        this(JoinType.INNER, left, right, condition);
    }

    @Override
    public SqlKind kind() { return SqlKind.JOIN; }
}
