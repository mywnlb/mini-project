package cn.zhangyis.minidb.sql.catalog;

public interface StatisticsStore {
    TableStatistics getTableStatistics(String tableName);
    void putTableStatistics(String tableName, TableStatistics stats);
}
