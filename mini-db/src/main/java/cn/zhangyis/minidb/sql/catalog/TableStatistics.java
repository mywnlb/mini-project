package cn.zhangyis.minidb.sql.catalog;

import java.util.Map;

/**
 * 表级统计信息快照
 */
public record TableStatistics(
    String tableName,
    long rowCount,
    Map<String, ColumnStatistics> columnStats,
    long collectionTimestamp
) {
    public ColumnStatistics column(String name) {
        return columnStats.get(name.toUpperCase());
    }
}
