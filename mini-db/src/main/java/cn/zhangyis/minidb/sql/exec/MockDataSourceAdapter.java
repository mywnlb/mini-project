package cn.zhangyis.minidb.sql.exec;

import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * MockDataSource 的 SPI 适配器，保持向后兼容。
 */
public class MockDataSourceAdapter implements DataSourceSpi {

    public static final MockDataSourceAdapter INSTANCE = new MockDataSourceAdapter();

    @Override
    public Iterator<Row> scan(String tableName) {
        return MockDataSource.getTableData(tableName).iterator();
    }

    @Override
    public void insertRow(String tableName, Row row) {
        MockDataSource.insertRow(tableName, row);
    }

    @Override
    public int updateRows(String tableName, Predicate<Row> filter, Consumer<Row> updater) {
        return MockDataSource.updateRows(tableName, filter, updater);
    }

    @Override
    public int deleteRows(String tableName, Predicate<Row> filter) {
        return MockDataSource.deleteRows(tableName, filter);
    }
}
