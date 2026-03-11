package cn.zhangyis.minidb.sql.validation;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.TableMeta;

public record ValidatedSqlSelect(SqlSelect original, TableMeta leftTable, TableMeta rightTable) implements SqlNode {
    public ValidatedSqlSelect(SqlSelect original, TableMeta table) {
        this(original, table, null);
    }

    public TableMeta tableMeta() { return leftTable; }
    public boolean isJoin() { return rightTable != null; }

    @Override
    public SqlKind kind() { return SqlKind.SELECT; }
}
