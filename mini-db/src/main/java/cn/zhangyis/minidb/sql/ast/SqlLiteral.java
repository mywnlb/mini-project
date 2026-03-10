package cn.zhangyis.minidb.sql.ast;

import cn.zhangyis.minidb.sql.types.SqlType;

public record SqlLiteral(String value, SqlType type) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.LITERAL; }
}