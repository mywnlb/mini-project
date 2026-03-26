package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.catalog.InformationSchemaNames;
import cn.zhangyis.minidb.sql.catalog.InformationSchemaProvider;

import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * DataSource 装饰器：information_schema 命中 provider，其他表完全委托底层数据源。
 */
public class MetadataAwareDataSource implements DataSourceSpi {

    private final DataSourceSpi delegate;
    private final InformationSchemaProvider provider;

    public MetadataAwareDataSource(DataSourceSpi delegate, InformationSchemaProvider provider) {
        this.delegate = delegate;
        this.provider = provider;
    }

    @Override
    public Iterator<Row> scan(String tableName) {
        failIfUnsupportedInformationSchema(tableName);
        if (provider.supportsInternalName(tableName)) {
            return provider.scan(tableName);
        }
        return delegate.scan(tableName);
    }

    @Override
    public void insertRow(String tableName, Row row) {
        failIfVirtualWrite(tableName);
        delegate.insertRow(tableName, row);
    }

    @Override
    public int updateRows(String tableName, Predicate<Row> filter, Consumer<Row> updater) {
        failIfVirtualWrite(tableName);
        return delegate.updateRows(tableName, filter, updater);
    }

    @Override
    public int deleteRows(String tableName, Predicate<Row> filter) {
        failIfVirtualWrite(tableName);
        return delegate.deleteRows(tableName, filter);
    }

    @Override
    public int partitionCount(String tableName) {
        if (provider.supportsInternalName(tableName)) {
            return 1;
        }
        return delegate.partitionCount(tableName);
    }

    @Override
    public Iterator<Row> scanPartition(String tableName, int partitionId, int totalPartitions) {
        if (provider.supportsInternalName(tableName)) {
            return provider.scan(tableName);
        }
        return delegate.scanPartition(tableName, partitionId, totalPartitions);
    }

    @Override
    public boolean supportsLookup(String tableName, String columnName) {
        return !provider.supportsInternalName(tableName) && delegate.supportsLookup(tableName, columnName);
    }

    @Override
    public Iterator<Row> lookup(String tableName, String outputName, String columnName, Object value) {
        failIfVirtualWrite(tableName);
        return delegate.lookup(tableName, outputName, columnName, value);
    }

    @Override
    public void invalidateTable(String tableName) {
        if (!provider.supportsInternalName(tableName)) {
            delegate.invalidateTable(tableName);
        }
    }

    private void failIfVirtualWrite(String tableName) {
        failIfUnsupportedInformationSchema(tableName);
        if (provider.supportsInternalName(tableName)) {
            throw new UnsupportedOperationException("information_schema is read-only");
        }
    }

    private void failIfUnsupportedInformationSchema(String tableName) {
        if (InformationSchemaNames.isInformationSchemaQualifiedName(tableName)) {
            String suffix = tableName.substring(tableName.indexOf('.') + 1);
            throw new UnsupportedOperationException(
                    "information_schema table not supported: " + suffix);
        }
    }
}
