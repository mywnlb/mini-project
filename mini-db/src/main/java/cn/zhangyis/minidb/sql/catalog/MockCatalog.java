package cn.zhangyis.minidb.sql.catalog;

import cn.zhangyis.minidb.sql.types.SqlType;
import java.util.*;
import java.util.stream.Collectors;

public class MockCatalog implements CatalogSpi {
    private final Map<String, TableMeta> tables = new HashMap<>();
    private final List<IndexMeta> indexes = new ArrayList<>();

    public MockCatalog() {
        tables.put("users", TableMeta.of("users", List.of(
            new ColumnMeta("id", SqlType.INT32, true),
            new ColumnMeta("name", SqlType.VARCHAR, false)
        ), 10000));
        tables.put("orders", TableMeta.of("orders", List.of(
            new ColumnMeta("order_id", SqlType.INT32, true),
            new ColumnMeta("user_id", SqlType.INT32, false),
            new ColumnMeta("amount", SqlType.DECIMAL, false)
        ), 50000));
    }

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

    @Override
    public boolean tableExists(String tableName) {
        return tables.containsKey(tableName.toLowerCase());
    }

    @Override
    public void createTable(TableMeta table) {
        tables.put(table.name().toLowerCase(), table);
    }

    @Override
    public void dropTable(String tableName) {
        tables.remove(tableName.toLowerCase());
        indexes.removeIf(idx -> idx.tableName().equalsIgnoreCase(tableName));
    }

    @Override
    public void addColumn(String tableName, ColumnMeta column) {
        TableMeta old = getTable(tableName);
        if (old == null) return;
        List<ColumnMeta> newCols = new ArrayList<>(old.columns());
        newCols.add(column);
        tables.put(tableName.toLowerCase(), TableMeta.of(old.name(), newCols, old.rowCount()));
    }

    @Override
    public void createIndex(IndexMeta index) {
        indexes.add(index);
    }

    @Override
    public void dropIndex(String tableName, String indexName) {
        indexes.removeIf(idx ->
            idx.tableName().equalsIgnoreCase(tableName) && idx.indexName().equalsIgnoreCase(indexName));
    }

    @Override
    public List<IndexMeta> getIndexes(String tableName) {
        return indexes.stream()
            .filter(idx -> idx.tableName().equalsIgnoreCase(tableName))
            .collect(Collectors.toList());
    }
}
