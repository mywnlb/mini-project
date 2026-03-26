package cn.zhangyis.minidb.sql.catalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Catalog 装饰器：将 information_schema 虚拟表接入现有 CatalogSpi。
 */
public class MetadataAwareCatalog implements CatalogSpi {

    private final CatalogSpi delegate;
    private final InformationSchemaProvider provider;

    public MetadataAwareCatalog(CatalogSpi delegate, InformationSchemaProvider provider) {
        this.delegate = delegate;
        this.provider = provider;
    }

    @Override
    public TableMeta getTable(String qualifiedName) {
        failIfUnsupportedInformationSchema(qualifiedName);
        if (provider.supportsInternalName(qualifiedName)) {
            return provider.getTable(qualifiedName);
        }
        return delegate.getTable(qualifiedName);
    }

    @Override
    public List<String> listTables(String database) {
        if (InformationSchemaNames.SCHEMA_NAME.equalsIgnoreCase(database)) {
            return InformationSchemaNames.supportedExternalTables();
        }
        return delegate.listTables(database);
    }

    @Override
    public List<String> listDatabases() {
        LinkedHashSet<String> databases = new LinkedHashSet<>(delegate.listDatabases());
        databases.add(InformationSchemaNames.SCHEMA_NAME);
        return new ArrayList<>(databases);
    }

    @Override
    public TableMeta getTable(String database, String tableName) {
        if (InformationSchemaNames.SCHEMA_NAME.equalsIgnoreCase(database)) {
            String internalName = InformationSchemaNames.internalNameFor(tableName);
            if (internalName == null) {
                throw unsupportedInformationSchemaTable(tableName);
            }
            return provider.getTable(internalName);
        }
        return delegate.getTable(database, tableName);
    }

    @Override
    public List<ColumnMeta> getColumns(String tableName) {
        failIfUnsupportedInformationSchema(tableName);
        if (provider.supportsInternalName(tableName)) {
            TableMeta table = provider.getTable(tableName);
            return table != null ? table.columns() : List.of();
        }
        return delegate.getColumns(tableName);
    }

    @Override
    public List<ColumnMeta> getColumns(String database, String tableName) {
        TableMeta table = getTable(database, tableName);
        return table != null ? table.columns() : List.of();
    }

    @Override
    public boolean tableExists(String tableName) {
        failIfUnsupportedInformationSchema(tableName);
        return provider.supportsInternalName(tableName) || delegate.tableExists(tableName);
    }

    @Override
    public boolean tableExists(String database, String tableName) {
        if (InformationSchemaNames.SCHEMA_NAME.equalsIgnoreCase(database)) {
            if (InformationSchemaNames.internalNameFor(tableName) == null) {
                throw unsupportedInformationSchemaTable(tableName);
            }
            return true;
        }
        return delegate.tableExists(database, tableName);
    }

    @Override
    public String getDatabaseCharset(String database) {
        if (InformationSchemaNames.SCHEMA_NAME.equalsIgnoreCase(database)) {
            return "utf8mb4";
        }
        return delegate.getDatabaseCharset(database);
    }

    @Override
    public void createTable(TableMeta table) {
        failIfVirtualMutation(table.name());
        delegate.createTable(table);
    }

    @Override
    public void dropTable(String tableName) {
        failIfVirtualMutation(tableName);
        delegate.dropTable(tableName);
    }

    @Override
    public void addColumn(String tableName, ColumnMeta column) {
        failIfVirtualMutation(tableName);
        delegate.addColumn(tableName, column);
    }

    @Override
    public void createIndex(IndexMeta index) {
        failIfVirtualMutation(index.tableName());
        delegate.createIndex(index);
    }

    @Override
    public void dropIndex(String tableName, String indexName) {
        failIfVirtualMutation(tableName);
        delegate.dropIndex(tableName, indexName);
    }

    @Override
    public List<IndexMeta> getIndexes(String tableName) {
        failIfUnsupportedInformationSchema(tableName);
        if (provider.supportsInternalName(tableName)) {
            return List.of();
        }
        return delegate.getIndexes(tableName);
    }

    @Override
    public List<IndexMeta> getIndexes(String database, String tableName) {
        if (InformationSchemaNames.SCHEMA_NAME.equalsIgnoreCase(database)) {
            if (InformationSchemaNames.internalNameFor(tableName) == null) {
                throw unsupportedInformationSchemaTable(tableName);
            }
            return List.of();
        }
        return delegate.getIndexes(database, tableName);
    }

    private void failIfVirtualMutation(String tableName) {
        if (provider.supportsInternalName(tableName)) {
            throw new UnsupportedOperationException("information_schema is read-only");
        }
        failIfUnsupportedInformationSchema(tableName);
    }

    private void failIfUnsupportedInformationSchema(String tableName) {
        if (InformationSchemaNames.isInformationSchemaQualifiedName(tableName)) {
            String suffix = tableName.substring(tableName.indexOf('.') + 1);
            throw unsupportedInformationSchemaTable(suffix);
        }
    }

    private UnsupportedOperationException unsupportedInformationSchemaTable(String tableName) {
        return new UnsupportedOperationException(
                "information_schema table not supported: " + tableName);
    }
}
