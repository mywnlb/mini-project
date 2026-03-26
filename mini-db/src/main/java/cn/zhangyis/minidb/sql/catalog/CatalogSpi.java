package cn.zhangyis.minidb.sql.catalog;

import java.util.List;

public interface CatalogSpi {
    TableMeta getTable(String qualifiedName);
    List<String> listTables(String database);
    default List<String> listDatabases() { return List.of("DEFAULT"); }
    default TableMeta getTable(String database, String tableName) { return getTable(tableName); }
    List<ColumnMeta> getColumns(String tableName);
    default List<ColumnMeta> getColumns(String database, String tableName) { return getColumns(tableName); }
    boolean tableExists(String tableName);
    default boolean tableExists(String database, String tableName) {
        return getTable(database, tableName) != null;
    }
    default String getDatabaseCharset(String database) { return "utf8mb4"; }
    void createTable(TableMeta table);
    void dropTable(String tableName);
    void addColumn(String tableName, ColumnMeta column);
    void createIndex(IndexMeta index);
    void dropIndex(String tableName, String indexName);
    List<IndexMeta> getIndexes(String tableName);
    default List<IndexMeta> getIndexes(String database, String tableName) { return getIndexes(tableName); }
}
