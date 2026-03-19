package cn.zhangyis.minidb.sql.ast;

import cn.zhangyis.minidb.sql.types.SqlType;

/**
 * ALTER TABLE table ADD COLUMN col type [NOT NULL] [DEFAULT value]
 */
public record SqlAlterTable(
    SqlIdentifier table,
    String columnName,
    SqlType columnType,
    boolean nullable,
    SqlNode defaultValue
) implements SqlNode {

    /**
     * 兼容工厂方法：默认 nullable=true, defaultValue=null
     */
    public static SqlAlterTable of(SqlIdentifier table, String columnName, SqlType columnType) {
        return new SqlAlterTable(table, columnName, columnType, true, null);
    }

    @Override
    public SqlKind kind() { return SqlKind.ALTER_TABLE; }
}
