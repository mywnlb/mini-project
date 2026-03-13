package cn.zhangyis.minidb.sql.catalog;

import cn.zhangyis.minidb.storage.catalog.CatalogManager;
import cn.zhangyis.minidb.storage.catalog.TableDescriptor;
import cn.zhangyis.minidb.storage.record.schema.FieldKind;
import cn.zhangyis.minidb.sql.types.SqlType;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 存储引擎 Catalog 实现：桥接 CatalogManager → CatalogSpi。
 *
 * <p>将 storage 层的 TableDescriptor/ColumnMeta 转换为 SQL 层的 TableMeta/ColumnMeta。</p>
 */
public class StorageCatalog implements CatalogSpi {

    private final CatalogManager catalogManager;
    private final String databaseName;

    public StorageCatalog(CatalogManager catalogManager, String databaseName) {
        this.catalogManager = Objects.requireNonNull(catalogManager);
        this.databaseName = Objects.requireNonNull(databaseName);
    }

    @Override
    public TableMeta getTable(String qualifiedName) {
        try {
            TableDescriptor desc = catalogManager.getTable(databaseName, qualifiedName);
            if (desc == null) return null;
            return toTableMeta(desc);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<String> listTables(String database) {
        try {
            return catalogManager.listTables(database).stream()
                .map(TableDescriptor::getTableName)
                .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public List<ColumnMeta> getColumns(String tableName) {
        TableMeta meta = getTable(tableName);
        return meta != null ? meta.columns() : List.of();
    }

    @Override
    public boolean tableExists(String tableName) {
        return getTable(tableName) != null;
    }

    @Override
    public void createTable(TableMeta table) {
        // DDL 操作委托给 CatalogManager
        // 需要将 SQL 层 TableMeta 转换为 storage 层参数
        throw new UnsupportedOperationException(
            "CREATE TABLE via StorageCatalog requires full DDL integration");
    }

    @Override
    public void dropTable(String tableName) {
        try {
            catalogManager.dropTable(databaseName, tableName);
        } catch (Exception e) {
            throw new RuntimeException("Drop table failed: " + tableName, e);
        }
    }

    @Override
    public void addColumn(String tableName, ColumnMeta column) {
        throw new UnsupportedOperationException(
            "ALTER TABLE ADD COLUMN via StorageCatalog requires Instant DDL integration");
    }

    @Override
    public void createIndex(IndexMeta index) {
        throw new UnsupportedOperationException(
            "CREATE INDEX via StorageCatalog requires IndexManager integration");
    }

    @Override
    public void dropIndex(String tableName, String indexName) {
        throw new UnsupportedOperationException(
            "DROP INDEX via StorageCatalog requires IndexManager integration");
    }

    @Override
    public List<IndexMeta> getIndexes(String tableName) {
        // TODO: 从 CatalogManager 获取索引信息
        return List.of();
    }

    // ==================== 类型转换 ====================

    private TableMeta toTableMeta(TableDescriptor desc) {
        List<ColumnMeta> sqlColumns = desc.getColumns().stream()
            .map(this::toSqlColumnMeta)
            .collect(Collectors.toList());
        return TableMeta.of(desc.getTableName(), sqlColumns, 0);
    }

    private ColumnMeta toSqlColumnMeta(cn.zhangyis.minidb.storage.catalog.ColumnMeta storageCol) {
        SqlType sqlType = fieldKindToSqlType(storageCol.getKind());
        boolean isPrimaryKey = !storageCol.isNullable(); // 简化判断
        return new ColumnMeta(storageCol.getName(), sqlType, isPrimaryKey);
    }

    static SqlType fieldKindToSqlType(FieldKind kind) {
        return switch (kind) {
            case TINYINT, SMALLINT, INT, BIGINT -> SqlType.INT32;
            case CHAR, VARCHAR, TEXT -> SqlType.VARCHAR;
            default -> SqlType.VARCHAR;
        };
    }
}
