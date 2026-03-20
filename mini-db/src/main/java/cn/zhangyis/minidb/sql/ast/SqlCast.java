package cn.zhangyis.minidb.sql.ast;

import cn.zhangyis.minidb.sql.types.SqlType;

/**
 * CAST(expr AS type) 表达式
 */
public record SqlCast(SqlNode expr, SqlType targetType) implements SqlNode {
    @Override
    public SqlKind kind() {
        return SqlKind.CAST;
    }

    @Override
    public String toString() {
        return "CAST(" + expr + " AS " + targetType + ")";
    }
}
