package cn.zhangyis.sql.catalog;

public interface CatalogReader {
    // 检查表是否存在
    boolean tableExists(String schema, String table);

    // 获取表的列信息
    List<ColumnMeta> getTableColumns(String schema, String table);

    // 获取列的数据类型
    DataType getColumnType(String schema, String table, String column);
}