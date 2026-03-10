package cn.zhangyis.minidb.sql.catalog;

import cn.zhangyis.minidb.sql.types.SqlType;
import java.util.List;

public interface CatalogSpi {
    TableMeta getTable(String qualifiedName);
    List<String> listTables(String database);
    List<ColumnMeta> getColumns(String tableName);
}