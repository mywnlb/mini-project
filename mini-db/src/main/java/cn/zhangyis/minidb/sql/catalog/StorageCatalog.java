package cn.zhangyis.minidb.sql.catalog;

import cn.zhangyis.minidb.storage.btree.IndexDescriptor;
import cn.zhangyis.minidb.storage.btree.IndexManager;
import cn.zhangyis.minidb.storage.btree.IndexType;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.*;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
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

    // ==================== DDL 操作 ====================

    /**
     * CREATE TABLE：将 SQL 层 TableMeta 转换为 storage 层参数，委托 CatalogManager。
     *
     * <p>映射规则：
     * <ul>
     *   <li>SqlType.INT32 → FieldType.intType</li>
     *   <li>SqlType.VARCHAR → FieldType.varchar(255)</li>
     *   <li>SqlType.DECIMAL → FieldType.bigint（暂无 DECIMAL 支持）</li>
     *   <li>SqlType.DATETIME → FieldType.bigint（暂无 DATETIME 支持）</li>
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
                FieldType fieldType = sqlTypeToFieldType(sqlCol.type(), sqlCol.isPrimaryKey());
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
        throw new UnsupportedOperationException(
            "ALTER TABLE ADD COLUMN is not yet supported by storage engine. " +
            "Requires Instant DDL integration (physical schema evolution).");
    }

    /**
     * CREATE INDEX：在已有表上创建二级索引。
     *
     * <p>流程：
     * <ol>
     *   <li>通过 CatalogManager 获取 TableDescriptor</li>
     *   <li>验证索引列存在</li>
     *   <li>通过 IndexManager 创建 B+Tree 索引</li>
     *   <li>更新 TableDescriptor 的 secondaryIndexIds</li>
     * </ol>
     */
    @Override
    public void createIndex(IndexMeta index) {
        requireBufferPool("CREATE INDEX");
        try {
            TableDescriptor tableDesc = catalogManager.getTable(databaseName, index.tableName());

            // 验证列存在并构建 IndexDescriptor.ColumnDescriptor
            List<IndexDescriptor.ColumnDescriptor> indexColumns = new ArrayList<>();
            for (String colName : index.columns()) {
                cn.zhangyis.minidb.storage.catalog.ColumnMeta storageCol = tableDesc.findColumn(colName);
                if (storageCol == null) {
                    throw new RuntimeException("Column not found: " + colName + " in table " + index.tableName());
                }
                indexColumns.add(TypeBridge.toIndexColumn(
                    storageCol, false));
            }

            // 通过 IndexManager 创建索引
            int spaceId = tableDesc.getSpaceId();
            IndexManager indexManager = new IndexManager(bufferPool, spaceId, TABLE_INDEX_META_PAGE_NO);
            long indexId = catalogManager.getIdGenerator().allocateIndexId();

            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                indexManager.initialize(mtr);
                indexManager.createIndex(
                    indexId,
                    index.indexName(),
                    tableDesc.getTableId(),
                    IndexType.SECONDARY,
                    indexColumns,
                    mtr
                );
                mtr.commit();
            }

            // 更新 TableDescriptor
            tableDesc.addSecondaryIndexId(indexId);
            tableDesc.setLastUpdateTime(System.currentTimeMillis());
        } catch (CatalogException e) {
            throw new RuntimeException("CREATE INDEX failed: " + index.indexName() + " — " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("CREATE INDEX failed: " + index.indexName(), e);
        }
    }

    /**
     * DROP INDEX：删除表上的二级索引。
     *
     * <p>流程：
     * <ol>
     *   <li>通过 CatalogManager 获取 TableDescriptor</li>
     *   <li>通过 IndexManager 查找并删除索引</li>
     *   <li>更新 TableDescriptor 的 secondaryIndexIds</li>
     * </ol>
     */
    @Override
    public void dropIndex(String tableName, String indexName) {
        requireBufferPool("DROP INDEX");
        try {
            TableDescriptor tableDesc = catalogManager.getTable(databaseName, tableName);
            int spaceId = tableDesc.getSpaceId();
            IndexManager indexManager = new IndexManager(bufferPool, spaceId, TABLE_INDEX_META_PAGE_NO);

            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                indexManager.initialize(mtr);
                boolean dropped = indexManager.dropIndexByName(
                    tableDesc.getTableId(), indexName, mtr);
                if (!dropped) {
                    throw new RuntimeException("Index not found: " + indexName + " on table " + tableName);
                }
                mtr.commit();
            }

            // 从 TableDescriptor 移除（需要找到对应的 indexId）
            // IndexManager 已经从自己的缓存中移除了，这里更新 TableDescriptor
            removeSecondaryIndexByName(tableDesc, indexName);
            tableDesc.setLastUpdateTime(System.currentTimeMillis());
        } catch (CatalogException e) {
            throw new RuntimeException("DROP INDEX failed: " + indexName + " — " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("DROP INDEX failed: " + indexName, e);
        }
    }

    /**
     * 获取表的所有索引元数据。
     *
     * <p>从 IndexManager 读取真实索引信息，转换为 SQL 层 IndexMeta。</p>
     */
    @Override
    public List<IndexMeta> getIndexes(String tableName) {
        try {
            TableDescriptor tableDesc = catalogManager.getTable(databaseName, tableName);
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
        return new ColumnMeta(storageCol.getName(), sqlType, isPrimaryKey);
    }

    /**
     * FieldKind → SqlType（storage → SQL 方向）
     */
    static SqlType fieldKindToSqlType(FieldKind kind) {
        return switch (kind) {
            case TINYINT, SMALLINT, INT -> SqlType.INT32;
            case BIGINT -> SqlType.BIGINT;
            case CHAR, VARCHAR, TEXT -> SqlType.VARCHAR;
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
    static FieldType sqlTypeToFieldType(SqlType sqlType, boolean isPrimaryKey) {
        boolean nullable = !isPrimaryKey;
        return switch (sqlType) {
            case INT32 -> FieldType.intType(nullable);
            case BIGINT -> FieldType.bigint(nullable);
            case VARCHAR -> FieldType.varchar(255, nullable);
            case DECIMAL -> FieldType.bigint(nullable);  // 暂无 DECIMAL 支持
            case DATETIME -> FieldType.bigint(nullable);  // 暂无 DATETIME 支持
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
        List<IndexMeta> result = new ArrayList<>();
        if (tableDesc.getPrimaryIndexId() > 0) {
            // 主键索引：列信息从 NOT NULL 列推断
            List<String> pkCols = tableDesc.getColumns().stream()
                .filter(c -> !c.isNullable())
                .map(cn.zhangyis.minidb.storage.catalog.ColumnMeta::getName)
                .collect(Collectors.toList());
            if (!pkCols.isEmpty()) {
                result.add(new IndexMeta("PRIMARY", tableDesc.getTableName(), pkCols, true, true));
            }
        }
        // 二级索引无法推断列信息，跳过
        return result;
    }

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

    /**
     * 从 TableDescriptor 移除指定名称的二级索引 ID。
     * 需要遍历 secondaryIndexIds 找到对应的 IndexDescriptor。
     */
    private void removeSecondaryIndexByName(TableDescriptor tableDesc, String indexName) {
        if (bufferPool == null) return;
        try {
            int spaceId = tableDesc.getSpaceId();
            IndexManager indexManager = new IndexManager(bufferPool, spaceId, TABLE_INDEX_META_PAGE_NO);
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                indexManager.initialize(mtr);
                // 查找已删除的索引 ID（IndexManager.dropIndexByName 已经从缓存移除了）
                // 遍历 tableDesc 的 secondaryIndexIds，检查哪个不再存在于 IndexManager
                for (Long indexId : new ArrayList<>(tableDesc.getSecondaryIndexIds())) {
                    if (!indexManager.indexExists(indexId)) {
                        tableDesc.removeSecondaryIndexId(indexId);
                        break;
                    }
                }
                mtr.commit();
            }
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }
}
