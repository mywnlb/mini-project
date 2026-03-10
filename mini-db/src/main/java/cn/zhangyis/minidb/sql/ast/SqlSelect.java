package cn.zhangyis.minidb.sql.ast;

import cn.zhangyis.minidb.sql.validation.ValidatedSqlSelect;
import java.util.List;

public record SqlSelect(SqlNodeList projection, SqlIdentifier table, SqlNode where) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.SELECT; }
}