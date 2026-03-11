package cn.zhangyis.minidb.sql.catalog;

import cn.zhangyis.minidb.sql.types.SqlType;
import java.util.List;
import java.util.Map;

public class MockCatalog implements CatalogSpi {
    private final Map<String, TableMeta> tables = Map.of(
        "users", TableMeta.of("users", List.of(
            new ColumnMeta("id", SqlType.INT32, true),
            new ColumnMeta("name", SqlType.VARCHAR, false)
        ), 10000),
        "orders", TableMeta.of("orders", List.of(
            new ColumnMeta("order_id", SqlType.INT32, true),
            new ColumnMeta("user_id", SqlType.INT32, false),
            new ColumnMeta("amount", SqlType.DECIMAL, false)
        ), 50000)
    );

    @Override
    public TableMeta getTable(String qualifiedName) {
        return tables.get(qualifiedName.toLowerCase());
    }

    @Override
    public List<String> listTables(String database) {
        return List.copyOf(tables.keySet());
    }

    @Override
    public List<ColumnMeta> getColumns(String tableName) {
        TableMeta meta = getTable(tableName);
        return meta != null ? meta.columns() : List.of();
    }
}
