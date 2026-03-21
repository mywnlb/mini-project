package cn.zhangyis.minidb.sql.exec;

import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 数据源 SPI：SQL 执行器通过此接口访问表数据。
 * 实现可以是内存 Mock 或真实存储引擎。
 */
public interface DataSourceSpi {

    /**
     * 全表扫描
     *
     * @param tableName 表名
     * @return 行迭代器
     */
    Iterator<Row> scan(String tableName);

    /**
     * 插入一行
     */
    void insertRow(String tableName, Row row);

    /**
     * 更新满足条件的行
     *
     * @return 受影响行数
     */
    int updateRows(String tableName, Predicate<Row> filter, Consumer<Row> updater);

    /**
     * 删除满足条件的行
     *
     * @return 受影响行数
     */
    int deleteRows(String tableName, Predicate<Row> filter);

    /**
     * 是否支持基于某一列的真实索引点查。
     */
    /** 表的分区数（默认 1 = 不分区） */
    default int partitionCount(String tableName) { return 1; }

    /** 按分区扫描（默认退化为全表扫描） */
    default Iterator<Row> scanPartition(String tableName, int partitionId, int totalPartitions) {
        return scan(tableName);
    }

    default boolean supportsLookup(String tableName, String columnName) {
        return false;
    }

    /**
     * 基于索引的点查。
     */
    default Iterator<Row> lookup(String tableName, String outputName, String columnName, Object value) {
        throw new UnsupportedOperationException(
            "Indexed lookup not supported for table=" + tableName + ", column=" + columnName);
    }

    /**
     * DDL 后失效表缓存，使后续 DML 使用最新 schema。
     */
    default void invalidateTable(String tableName) {}
}
