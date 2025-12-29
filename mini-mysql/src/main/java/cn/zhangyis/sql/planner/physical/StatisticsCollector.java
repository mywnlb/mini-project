package cn.zhangyis.sql.planner.physical;

import java.util.Map;

/**
 * 统计信息收集器接口
 * 定义统计信息的收集方法
 */
public interface StatisticsCollector {
    /**
     * 获取表统计信息
     *
     * @param tableName 表名
     * @return 表统计信息
     */
    TableStatistics getTableStatistics(String tableName);

    /**
     * 更新表统计信息
     *
     * @param tableName  表名
     * @param statistics 表统计信息
     */
    void updateTableStatistics(String tableName, TableStatistics statistics);

    /**
     * 收集统计信息
     */
    void collectStatistics();

    /**
     * 表统计信息类
     */
    class TableStatistics {
        private final long rowCount;
        private final Map<String, ColumnStatistics> columnStatistics;

        public TableStatistics(long rowCount, Map<String, ColumnStatistics> columnStatistics) {
            this.rowCount = rowCount;
            this.columnStatistics = columnStatistics;
        }

        public long getRowCount() {
            return rowCount;
        }

        public Map<String, ColumnStatistics> getColumnStatistics() {
            return columnStatistics;
        }
    }

    /**
     * 列统计信息类
     */
    class ColumnStatistics {
        private final long distinctValues;
        private final Object minValue;
        private final Object maxValue;
        private final double nullRatio;

        public ColumnStatistics(long distinctValues, Object minValue, Object maxValue, double nullRatio) {
            this.distinctValues = distinctValues;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.nullRatio = nullRatio;
        }

        public long getDistinctValues() {
            return distinctValues;
        }

        public Object getMinValue() {
            return minValue;
        }

        public Object getMaxValue() {
            return maxValue;
        }

        public double getNullRatio() {
            return nullRatio;
        }
    }
} 