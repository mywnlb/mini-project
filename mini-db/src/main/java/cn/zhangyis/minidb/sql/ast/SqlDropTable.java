package cn.zhangyis.minidb.sql.ast;

/**
 * DROP TABLE [IF EXISTS] table_name
 */
public record SqlDropTable(SqlIdentifier table, boolean ifExists) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.DROP_TABLE; }
}
