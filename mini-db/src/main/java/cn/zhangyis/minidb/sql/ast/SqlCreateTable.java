package cn.zhangyis.minidb.sql.ast;

import cn.zhangyis.minidb.sql.types.SqlType;
import java.util.List;

/**
 * CREATE TABLE table_name (col1 INT PRIMARY KEY, col2 VARCHAR, ...)
 */
public record SqlCreateTable(
    SqlIdentifier table,
    List<ColumnDef> columnDefs,
    List<TableIndexDef> indexes,
    boolean ifNotExists
) implements SqlNode {

    @Override
    public SqlKind kind() { return SqlKind.CREATE_TABLE; }

    public record ColumnDef(String name, SqlType type, boolean primaryKey, boolean nullable,
                            Integer length) {
        /** 兼容构造：默认 nullable=true */
        public ColumnDef(String name, SqlType type, boolean primaryKey) {
            this(name, type, primaryKey, true, null);
        }

        public ColumnDef(String name, SqlType type, boolean primaryKey, boolean nullable) {
            this(name, type, primaryKey, nullable, null);
        }
    }

    public record TableIndexDef(String name, List<String> columns, boolean primary, boolean unique) {
    }
}
