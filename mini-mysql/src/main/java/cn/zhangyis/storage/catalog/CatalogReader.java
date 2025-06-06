package cn.zhangyis.storage.catalog;

import cn.zhangyis.enums.FiledType;

import java.util.List;

public interface CatalogReader {
    // 检查表是否存在
    boolean tableExists(String schema, String table);

    // 获取表的列信息
    List<ColumnMeta> getTableColumns(String schema, String table);

    // 获取列的数据类型
    FiledType getColumnType(String schema, String table, String column);
}