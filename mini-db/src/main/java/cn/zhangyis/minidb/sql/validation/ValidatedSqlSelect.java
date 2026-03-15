package cn.zhangyis.minidb.sql.validation;

import cn.zhangyis.minidb.sql.ast.SqlKind;
import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.ast.SqlSelect;
import cn.zhangyis.minidb.sql.catalog.TableMeta;

import java.util.LinkedHashMap;
import java.util.Map;

public record ValidatedSqlSelect(SqlSelect original, Map<String, TableMeta> tables) implements SqlNode {
    public ValidatedSqlSelect {
        tables = Map.copyOf(new LinkedHashMap<>(tables));
    }

    public TableMeta table(String visibleName) {
        return tables.get(visibleName.toUpperCase());
    }

    @Override
    public SqlKind kind() { return SqlKind.SELECT; }
}
