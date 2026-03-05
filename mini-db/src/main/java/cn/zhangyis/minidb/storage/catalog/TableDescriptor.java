package cn.zhangyis.minidb.storage.catalog;

import cn.zhangyis.minidb.storage.record.schema.SchemaRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 表描述符 —— Catalog 核心数据结构
 *
 * <p>桥接所有现有模块：</p>
 * <ul>
 *   <li>SchemaRegistry（Schema 版本管理，直接复用）</li>
 *   <li>spaceId → TableSpace（物理空间管理）</li>
 *   <li>indexId 列表 → IndexManager（索引管理）</li>
 * </ul>
 *
 * <h3>不可变字段</h3>
 * <p>tableId, tableName, databaseId, spaceId, createTime 在创建后不可变。</p>
 *
 * <h3>可变字段</h3>
 * <p>primaryIndexId, secondaryIndexIds, lastUpdateTime, state 可在 DDL 操作中更新，
 * 由 CatalogManager 的 per-DB 锁保护。</p>
 */
public class TableDescriptor {

    /** 表状态 */
    public enum TableState {
        ACTIVE,
        DROPPING,
        DROPPED
    }

    // === 标识（不可变）===
    private final long tableId;
    private final String tableName;
    private final int databaseId;

    // === 物理映射（不可变）===
    private final int spaceId;

    // === Schema（不可变引用，SchemaRegistry 内部管理版本）===
    private final SchemaRegistry schemaRegistry;
    private final List<ColumnMeta> columns;

    // === 索引（可变）===
    private volatile long primaryIndexId;
    private final List<Long> secondaryIndexIds;

    // === 元信息 ===
    private final long createTime;
    private volatile long lastUpdateTime;
    private volatile TableState state;

    /**
     * 完整构造器（用于从持久化恢复）
     */
    public TableDescriptor(long tableId, String tableName, int databaseId,
                           int spaceId, SchemaRegistry schemaRegistry,
                           List<ColumnMeta> columns, long primaryIndexId,
                           List<Long> secondaryIndexIds, long createTime,
                           long lastUpdateTime, TableState state) {
        this.tableId = tableId;
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        this.databaseId = databaseId;
        this.spaceId = spaceId;
        this.schemaRegistry = Objects.requireNonNull(schemaRegistry, "schemaRegistry");
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
        this.primaryIndexId = primaryIndexId;
        this.secondaryIndexIds = new ArrayList<>(secondaryIndexIds);
        this.createTime = createTime;
        this.lastUpdateTime = lastUpdateTime;
        this.state = state;
    }

    /**
     * 简化构造器（用于新建表）
     */
    public TableDescriptor(long tableId, String tableName, int databaseId,
                           int spaceId, SchemaRegistry schemaRegistry,
                           List<ColumnMeta> columns) {
        this(tableId, tableName, databaseId, spaceId, schemaRegistry,
                columns, 0, Collections.emptyList(),
                System.currentTimeMillis(), System.currentTimeMillis(),
                TableState.ACTIVE);
    }

    // ==================== Getters（不可变字段）====================

    public long getTableId() {
        return tableId;
    }

    public String getTableName() {
        return tableName;
    }

    public int getDatabaseId() {
        return databaseId;
    }

    public int getSpaceId() {
        return spaceId;
    }

    public SchemaRegistry getSchemaRegistry() {
        return schemaRegistry;
    }

    public List<ColumnMeta> getColumns() {
        return columns;
    }

    public long getCreateTime() {
        return createTime;
    }

    // ==================== Getters/Setters（可变字段）====================

    public long getPrimaryIndexId() {
        return primaryIndexId;
    }

    public void setPrimaryIndexId(long primaryIndexId) {
        this.primaryIndexId = primaryIndexId;
    }

    public List<Long> getSecondaryIndexIds() {
        return Collections.unmodifiableList(secondaryIndexIds);
    }

    public void addSecondaryIndexId(long indexId) {
        secondaryIndexIds.add(indexId);
    }

    public boolean removeSecondaryIndexId(long indexId) {
        return secondaryIndexIds.remove(Long.valueOf(indexId));
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    public void setLastUpdateTime(long lastUpdateTime) {
        this.lastUpdateTime = lastUpdateTime;
    }

    public TableState getState() {
        return state;
    }

    public void setState(TableState state) {
        this.state = state;
    }

    // ==================== 便捷查询 ====================

    /**
     * 按列名查找 ColumnMeta
     *
     * @param columnName 列名
     * @return ColumnMeta，不存在返回 null
     */
    public ColumnMeta findColumn(String columnName) {
        for (ColumnMeta col : columns) {
            if (col.getName().equals(columnName)) {
                return col;
            }
        }
        return null;
    }

    /**
     * 按列 ID 查找 ColumnMeta
     */
    public ColumnMeta findColumnById(long columnId) {
        for (ColumnMeta col : columns) {
            if (col.getColumnId() == columnId) {
                return col;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return String.format(
                "TableDescriptor{id=%d, name='%s', dbId=%d, spaceId=%d, cols=%d, state=%s}",
                tableId, tableName, databaseId, spaceId, columns.size(), state);
    }
}
