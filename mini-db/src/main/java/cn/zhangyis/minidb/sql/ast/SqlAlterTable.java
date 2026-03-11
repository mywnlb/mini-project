package cn.zhangyis.minidb.sql.ast;

import cn.zhangyis.minidb.sql.types.SqlType;

/**
 * ALTER TABLE table ADD COLUMN col type
 */
public record SqlAlterTable(SqlIdentifier table, String columnName, SqlType columnType) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.ALTER_TABLE; }
}
