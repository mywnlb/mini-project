package cn.zhangyis.minidb.sql.catalog;

import java.util.List;

public interface CatalogSpi {
    TableMeta getTable(String qualifiedName);
    List<String> listTables(String database);
    List<ColumnMeta> getColumns(String tableName);
    boolean tableExists(String tableName);
    void createTable(TableMeta table);
    void dropTable(String tableName);
    void addColumn(String tableName, ColumnMeta column);
    void createIndex(IndexMeta index);
    void dropIndex(String tableName, String indexName);
    List<IndexMeta> getIndexes(String tableName);
}