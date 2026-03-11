package cn.zhangyis.minidb.sql.ast;

import cn.zhangyis.minidb.sql.types.SqlType;
import java.util.List;

/**
 * CREATE TABLE table_name (col1 INT PRIMARY KEY, col2 VARCHAR, ...)
 */
public record SqlCreateTable(
    SqlIdentifier table,
    List<ColumnDef> columnDefs,
    boolean ifNotExists
) implements SqlNode {

    @Override
    public SqlKind kind() { return SqlKind.CREATE_TABLE; }

    public record ColumnDef(String name, SqlType type, boolean primaryKey) {}
}
