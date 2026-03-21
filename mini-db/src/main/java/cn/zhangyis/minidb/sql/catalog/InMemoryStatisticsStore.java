package cn.zhangyis.minidb.sql.catalog;

import java.util.concurrent.ConcurrentHashMap;

public class InMemoryStatisticsStore implements StatisticsStore {
    private final ConcurrentHashMap<String, TableStatistics> stats = new ConcurrentHashMap<>();

    @Override
    public TableStatistics getTableStatistics(String tableName) {
        return stats.get(tableName.toUpperCase());
    }

    @Override
    public void putTableStatistics(String tableName, TableStatistics statistics) {
        stats.put(tableName.toUpperCase(), statistics);
    }
}
