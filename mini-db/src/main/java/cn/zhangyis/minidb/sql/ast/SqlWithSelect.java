package cn.zhangyis.minidb.sql.ast;

import java.util.List;

public record SqlWithSelect(List<SqlCte> ctes, SqlSelect select) implements SqlNode {
    @Override
    public SqlKind kind() { return SqlKind.WITH_SELECT; }
}
