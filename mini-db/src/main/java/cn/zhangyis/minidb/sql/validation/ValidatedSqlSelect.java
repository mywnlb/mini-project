package cn.zhangyis.minidb.sql.validation;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.TableMeta;

public record ValidatedSqlSelect(SqlSelect original, TableMeta tableMeta) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.SELECT; }
}