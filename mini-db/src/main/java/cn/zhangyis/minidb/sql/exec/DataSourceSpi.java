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
}
