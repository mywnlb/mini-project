package cn.zhangyis.minidb.sql.catalog;

import cn.zhangyis.minidb.sql.exec.ExecutionContext;
import cn.zhangyis.minidb.sql.exec.StorageDataSource;
import cn.zhangyis.minidb.storage.btree.IndexDescriptor;
import cn.zhangyis.minidb.storage.btree.IndexManager;
import cn.zhangyis.minidb.storage.btree.IndexType;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.*;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.record.logical.DataField;
import cn.zhangyis.minidb.storage.record.schema.FieldKind;
import cn.zhangyis.minidb.storage.record.schema.FieldType;
import cn.zhangyis.minidb.sql.types.SqlType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 存储引擎 Catalog 实现：桥接 CatalogManager → CatalogSpi。
 *
 * <p>将 storage 层的 TableDescriptor/ColumnMeta 转换为 SQL 层的 TableMeta/ColumnMeta。
 * 所有 DDL 操作委托给 CatalogManager，不维护独立元数据副本。</p>
 *
 * <h3>Invariants</h3>
 * <ul>
 *   <li>CAT-1: 单一真相源 — 所有操作委托 CatalogManager</li>
 *   <li>CAT-2: SqlType ↔ FieldKind 双向映射覆盖所有当前支持类型</li>
 *   <li>CAT-3: DDL 原子性由 CatalogManager 保证</li>
 *   <li>CAT-4: 暂不支持的能力显式 fail-fast</li>
 * </ul>
 */
public class StorageCatalog implements CatalogSpi {

    private static final int TABLE_INDEX_META_PAGE_NO = 3;

    private final CatalogManager catalogManager;
    private final BufferPool bufferPool;
    private final String databaseName;

    public StorageCatalog(CatalogManager catalogManager, String databaseName) {
        this.catalogManager = Objects.requireNonNull(catalogManager);
        this.databaseName = Objects.requireNonNull(databaseName);
        this.bufferPool = null; // 无 bufferPool 时 createIndex/dropIndex 不可用
    }

    public StorageCatalog(CatalogManager catalogManager, String databaseName, BufferPool bufferPool) {
        this.catalogManager = Objects.requireNonNull(catalogManager);
        this.databaseName = Objects.requireNonNull(databaseName);
        this.bufferPool = Objects.requireNonNull(bufferPool);
    }

    // ==================== 查询操作 ====================

    @Override
    public TableMeta getTable(String qualifiedName) {
        return getTable(databaseName, qualifiedName);
    }

    @Override
    public List<String> listDatabases() {
        try {
            return catalogManager.listDatabases().stream()
                .map(DatabaseDescriptor::getDatabaseName)
                .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of(databaseName);
        }
    }

    @Override
    public TableMeta getTable(String database, String tableName) {
        try {
            TableDescriptor desc = catalogManager.getTable(database, tableName);
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
        return getColumns(databaseName, tableName);
    }

    @Override
    public List<ColumnMeta> getColumns(String database, String tableName) {
        TableMeta meta = getTable(database, tableName);
        return meta != null ? meta.columns() : List.of();
    }

    @Override
    public boolean tableExists(String tableName) {
        return getTable(tableName) != null;
    }

    @Override
    public String getDatabaseCharset(String database) {
        try {
            return catalogManager.getDatabase(database).getCharset();
        } catch (Exception e) {
            return "utf8mb4";
        }
    }

    // ==================== DDL 操作 ====================

    /**
     * CREATE TABLE：将 SQL 层 TableMeta 转换为 storage 层参数，委托 CatalogManager。
     *
     * <p>映射规则：
     * <ul>
     *   <li>SqlType.INT32 → FieldType.intType</li>
     *   <li>SqlType.VARCHAR → FieldType.varchar(255)</li>
     *   <li>SqlType.DECIMAL → FieldType.decimal</li>
     *   <li>SqlType.DATETIME → FieldType.datetime</li>
     *   <li>isPrimaryKey → NOT NULL + PRIMARY index</li>
     * </ul>
     */
    @Override
    public void createTable(TableMeta table) {
        try {
            List<cn.zhangyis.minidb.storage.catalog.ColumnMeta> storageColumns = new ArrayList<>();
            List<String> primaryKeyColumns = new ArrayList<>();

            for (int i = 0; i < table.columns().size(); i++) {
                ColumnMeta sqlCol = table.columns().get(i);
                // nullable 取 SQL 层显式声明与主键约束的综合结果
                boolean nullable = sqlCol.nullable() && !sqlCol.isPrimaryKey();
                FieldType fieldType = sqlTypeToFieldType(sqlCol.type(), nullable, sqlCol.length());
                // columnId 用占位值 (i+1)，CatalogManager.assignColumnIds 会重新分配
                storageColumns.add(new cn.zhangyis.minidb.storage.catalog.ColumnMeta(
                    i + 1, sqlCol.name(), fieldType, i, null
                ));
                if (sqlCol.isPrimaryKey()) {
                    primaryKeyColumns.add(sqlCol.name());
                }
            }

            // 构建索引定义
            List<IndexDefinition> indexes = new ArrayList<>();
            if (!primaryKeyColumns.isEmpty()) {
                indexes.add(IndexDefinition.primary(primaryKeyColumns.toArray(new String[0])));
            }
            // 如果没有显式主键，CatalogManager.normalizeIndexes 会用第一列作为主键

            catalogManager.createTable(databaseName, table.name(), storageColumns, indexes);
        } catch (CatalogException e) {
            throw new RuntimeException("CREATE TABLE failed: " + table.name() + " — " + e.getMessage(), e);
        }
    }

    @Override
    public void dropTable(String tableName) {
        try {
            catalogManager.dropTable(databaseName, tableName);
        } catch (Exception e) {
            throw new RuntimeException("DROP TABLE failed: " + tableName, e);
        }
    }

    /**
     * ALTER TABLE ADD COLUMN：当前 storage 层不支持在线加列（需要 Instant DDL）。
     * 显式 fail-fast。
     */
    @Override
    public void addColumn(String tableName, ColumnMeta column) {
        requireBufferPool("addColumn");

        // 转换 SQL 类型为存储层 FieldType，nullable 由 SQL 层显式声明决定
        FieldType fieldType = sqlTypeToFieldType(column.type(), column.nullable(), column.length());

        // 转换默认值为 DataField
        DataField defaultField = convertDefaultValue(column.defaultValue(), fieldType);

        try {
            catalogManager.alterTableAddColumn(databaseName, tableName,
                    column.name(), fieldType, defaultField);
        } catch (CatalogException e) {
            throw new RuntimeException("ALTER TABLE ADD COLUMN failed: " + e.getMessage(), e);
        }
    }

    /**
     * 将 SQL 层默认值字面量转换为存储层 DataField。
     *
     * @param defaultValue SQL 层默认值（null、Number、String）
     * @param fieldType    目标存储层字段类型
     * @return DataField，null 输入返回 nullField
     */
    private DataField convertDefaultValue(Object defaultValue, FieldType fieldType) {
        if (defaultValue == null) {
            return fieldType.isNullable() ? DataField.nullField(fieldType) : null;
        }
        FieldKind kind = fieldType.getKind();
        return switch (kind) {
            case INT -> DataField.intField(((Number) defaultValue).intValue());
            case BIGINT -> DataField.bigintField(((Number) defaultValue).longValue());
            case TINYINT -> DataField.tinyintField(((Number) defaultValue).byteValue());
            case SMALLINT -> DataField.smallintField(((Number) defaultValue).shortValue());
            case DECIMAL -> DataField.decimalField(defaultValue);
            case DATE -> DataField.dateField(defaultValue);
            case TIME -> DataField.timeField(defaultValue);
            case DATETIME -> DataField.datetimeField(defaultValue);
            case VARCHAR -> DataField.varcharField(String.valueOf(defaultValue));
            case CHAR -> DataField.charField(String.valueOf(defaultValue), fieldType.getLength());
            case TEXT -> DataField.textField(String.valueOf(defaultValue));
            case JSON -> DataField.jsonField(String.valueOf(defaultValue));
            case BLOB -> defaultValue instanceof byte[] bytes
                    ? DataField.blobField(bytes)
                    : DataField.blobField(String.valueOf(defaultValue).getBytes());
            default -> throw new UnsupportedOperationException(
                    "Default value conversion not supported for type: " + kind);
        };
    }

    /**
     * CREATE INDEX：在已有表上创建二级索引。
     */
    @Override
    public void createIndex(IndexMeta index) {
        requireBufferPool("CREATE INDEX");
        try {
            catalogManager.createIndex(
                    databaseName,
                    index.tableName(),
                    index.indexName(),
                    index.unique() ? IndexType.UNIQUE : IndexType.SECONDARY,
                    index.columns()
            );
        } catch (CatalogException e) {
            throw new RuntimeException("CREATE INDEX failed: " + index.indexName() + " — " + e.getMessage(), e);
        }
    }

    /**
     * DROP INDEX：删除表上的二级索引。
     */
    @Override
    public void dropIndex(String tableName, String indexName) {
        requireBufferPool("DROP INDEX");
        try {
            catalogManager.dropIndex(databaseName, tableName, indexName);
        } catch (CatalogException e) {
            throw new RuntimeException("DROP INDEX failed: " + indexName + " — " + e.getMessage(), e);
        }
    }

    /**
     * 获取表的所有索引元数据。
     *
     * <p>从 IndexManager 读取真实索引信息，转换为 SQL 层 IndexMeta。</p>
     */
    @Override
    public List<IndexMeta> getIndexes(String tableName) {
        return getIndexes(databaseName, tableName);
    }

    @Override
    public List<IndexMeta> getIndexes(String database, String tableName) {
        try {
            TableDescriptor tableDesc = catalogManager.getTable(database, tableName);
            if (bufferPool == null) {
                // 无 bufferPool 时，从 TableDescriptor 的 indexId 列表推断最小信息
                return buildIndexMetaFromDescriptor(tableDesc);
            }

            int spaceId = tableDesc.getSpaceId();
            IndexManager indexManager = new IndexManager(bufferPool, spaceId, TABLE_INDEX_META_PAGE_NO);

            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                indexManager.initialize(mtr);
                List<IndexDescriptor> descriptors = indexManager.getTableIndexes(tableDesc.getTableId());
                mtr.commit();

                return descriptors.stream()
                    .map(desc -> new IndexMeta(
                        desc.getIndexName(),
                        tableName,
                        desc.getColumns().stream()
                            .map(IndexDescriptor.ColumnDescriptor::getName)
                            .collect(Collectors.toList()),
                        desc.isPrimary(),
                        desc.isUnique()
                    ))
                    .collect(Collectors.toList());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load indexes for table " + tableName, e);
        }
    }

    // ==================== 类型转换 ====================

    private TableMeta toTableMeta(TableDescriptor desc) {
        Set<String> primaryColumns = primaryColumnNames(desc);
        List<ColumnMeta> sqlColumns = desc.getColumns().stream()
            .map(column -> toSqlColumnMeta(column, primaryColumns))
            .collect(Collectors.toList());
        return TableMeta.of(desc.getTableName(), sqlColumns, rowCount(desc));
    }

    private ColumnMeta toSqlColumnMeta(cn.zhangyis.minidb.storage.catalog.ColumnMeta storageCol,
                                       Set<String> primaryColumns) {
        SqlType sqlType = fieldKindToSqlType(storageCol.getKind());
        boolean isPrimaryKey = primaryColumns.contains(storageCol.getName().toUpperCase());
        Object defaultValue = storageCol.getDefaultValue() != null
                ? storageCol.getDefaultValue().getValue()
                : null;
        return new ColumnMeta(storageCol.getName(), sqlType, isPrimaryKey,
                storageCol.isNullable(), defaultValue, storageCol.getType().getLength());
    }

    /**
     * FieldKind → SqlType（storage → SQL 方向）
     */
    static SqlType fieldKindToSqlType(FieldKind kind) {
        return switch (kind) {
            case TINYINT -> SqlType.TINYINT;
            case SMALLINT -> SqlType.SMALLINT;
            case INT -> SqlType.INT32;
            case BIGINT -> SqlType.BIGINT;
            case CHAR -> SqlType.CHAR;
            case VARCHAR -> SqlType.VARCHAR;
            case TEXT -> SqlType.TEXT;
            case BLOB -> SqlType.BLOB;
            case JSON -> SqlType.JSON;
            case DECIMAL -> SqlType.DECIMAL;
            case DATE -> SqlType.DATE;
            case TIME -> SqlType.TIME;
            case DATETIME -> SqlType.DATETIME;
            default -> SqlType.VARCHAR;
        };
    }

    /**
     * SqlType → FieldType（SQL → storage 方向）
     *
     * <p>映射规则：
     * <ul>
     *   <li>INT32 → FieldType.intType</li>
     *   <li>VARCHAR → FieldType.varchar(255)</li>
     *   <li>DECIMAL → FieldType.bigint（暂无 DECIMAL，用 BIGINT 近似）</li>
     *   <li>DATETIME → FieldType.bigint（暂无 DATETIME，用 BIGINT 存时间戳）</li>
     * </ul>
     */
    static FieldType sqlTypeToFieldType(SqlType sqlType, boolean nullable, Integer length) {
        int declaredLength = length != null && length > 0 ? length : 255;
        return switch (sqlType) {
            case TINYINT -> FieldType.tinyint(nullable);
            case SMALLINT -> FieldType.smallint(nullable);
            case INT32 -> FieldType.intType(nullable);
            case BIGINT -> FieldType.bigint(nullable);
            case CHAR -> FieldType.charType(declaredLength, nullable);
            case VARCHAR -> FieldType.varchar(declaredLength, nullable);
            case TEXT -> FieldType.text(nullable);
            case BLOB -> FieldType.blob(nullable);
            case JSON -> FieldType.json(declaredLength, nullable);
            case DECIMAL -> FieldType.decimal(length != null ? length : 65, nullable);
            case DATE -> FieldType.date(nullable);
            case TIME -> FieldType.time(nullable);
            case DATETIME -> FieldType.datetime(nullable);
        };
    }

    // ==================== 内部工具 ====================

    private void requireBufferPool(String operation) {
        if (bufferPool == null) {
            throw new UnsupportedOperationException(
                operation + " requires BufferPool. Use the 3-arg constructor: " +
                "StorageCatalog(catalogManager, databaseName, bufferPool)");
        }
    }

    /**
     * 从 TableDescriptor 的 indexId 列表构建最小 IndexMeta（无 bufferPool 时的降级路径）
     */
    private List<IndexMeta> buildIndexMetaFromDescriptor(TableDescriptor tableDesc) {
        List<IndexMeta> indexes = new ArrayList<>();
        // 主键索引
        if (tableDesc.getPrimaryIndexId() > 0) {
            indexes.add(new IndexMeta(
                "PRIMARY",
                tableDesc.getTableName(),
                List.of(),  // 列信息在无 bufferPool 时无法精确获取
                true,
                true
            ));
        }
        // 二级索引（仅占位）
        for (Long indexId : tableDesc.getSecondaryIndexIds()) {
            indexes.add(new IndexMeta(
                "idx_" + indexId,
                tableDesc.getTableName(),
                List.of(),
                false,
                false
            ));
        }
        return indexes;
    }

    /**
     * 从 TableDescriptor 的 indexId 列表推断主键列名
     */
    private Set<String> primaryColumnNames(TableDescriptor tableDesc) {
        try {
            return getIndexes(tableDesc.getTableName()).stream()
                .filter(IndexMeta::primary)
                .flatMap(index -> index.columns().stream())
                .map(String::toUpperCase)
                .collect(Collectors.toSet());
        } catch (Exception e) {
            // 降级路径：从 ColumnMeta 推断（非 nullable 或名称含 ID 通常为主键）
            return tableDesc.getColumns().stream()
                .filter(c -> !c.isNullable() || c.getName().toUpperCase().contains("ID"))
                .map(c -> c.getName().toUpperCase())
                .collect(Collectors.toSet());
        }
    }

    /**
     * 为当前连接创建 StorageDataSource 实例
     * 必须在 ExecutionContext 创建之后调用
     */
    public StorageDataSource createStorageDataSource(ExecutionContext executionContext) {
        if (bufferPool == null) {
            throw new IllegalStateException("BufferPool is required to create StorageDataSource");
        }
        return new StorageDataSource(catalogManager, databaseName, executionContext, bufferPool);
    }

    public String databaseName() {
        return databaseName;
    }

    private long rowCount(TableDescriptor tableDesc) {
        if (bufferPool == null || tableDesc.getPrimaryIndexId() <= 0) {
            return 0;
        }
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            IndexManager indexManager = new IndexManager(bufferPool, tableDesc.getSpaceId(), TABLE_INDEX_META_PAGE_NO);
            indexManager.initialize(mtr);
            IndexDescriptor descriptor = indexManager.getDescriptor(tableDesc.getPrimaryIndexId());
            mtr.commit();
            return descriptor != null ? descriptor.getRecordCount() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

}
