package cn.zhangyis.minidb.sql.ast;

import java.util.List;

public record SqlJoin(JoinType joinType, SqlNode left, SqlNode right, SqlNode condition,
                      List<String> usingColumns, boolean natural) implements SqlNode {

    /** 向后兼容：默认 INNER */
    public SqlJoin(SqlNode left, SqlNode right, SqlNode condition) {
        this(JoinType.INNER, left, right, condition, null, false);
    }

    /** 向后兼容：指定 joinType */
    public SqlJoin(JoinType joinType, SqlNode left, SqlNode right, SqlNode condition) {
        this(joinType, left, right, condition, null, false);
    }

    @Override
    public SqlKind kind() { return SqlKind.JOIN; }
}
