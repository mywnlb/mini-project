package cn.zhangyis.minidb.storage.catalog;

import cn.zhangyis.minidb.storage.record.logical.DataField;
import cn.zhangyis.minidb.storage.record.schema.ColumnDescriptor;
import cn.zhangyis.minidb.storage.record.schema.FieldKind;
import cn.zhangyis.minidb.storage.record.schema.FieldType;

import java.util.Objects;

/**
 * 不可变列元数据
 *
 * <p>比 record 层 ColumnDescriptor 多了 defaultValue 字段，用于 Catalog 层元数据管理。</p>
 *
 * <h3>Invariants</h3>
 * <ul>
 *   <li>C2: columnId 由 CatalogManager 分配，全局唯一、单调递增</li>
 * </ul>
 */
public final class ColumnMeta {

    private final long columnId;
    private final String name;
    private final FieldType type;
    private final int ordinal;
    private final DataField defaultValue; // null = 无默认值

    public ColumnMeta(long columnId, String name, FieldType type, int ordinal, DataField defaultValue) {
        if (columnId <= 0) {
            throw new IllegalArgumentException("columnId must be positive: " + columnId);
        }
        this.columnId = columnId;
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.ordinal = ordinal;
        this.defaultValue = defaultValue;
    }

    // ==================== Getters ====================

    public long getColumnId() {
        return columnId;
    }

    public String getName() {
        return name;
    }

    public FieldType getType() {
        return type;
    }

    public FieldKind getKind() {
        return type.getKind();
    }

    public int getOrdinal() {
        return ordinal;
    }

    public DataField getDefaultValue() {
        return defaultValue;
    }

    public boolean isNullable() {
        return type.isNullable();
    }

    // ==================== 转换 ====================

    /**
     * 转换为 record 层 ColumnDescriptor（保留 CatalogManager 分配的 columnId）
     */
    public ColumnDescriptor toColumnDescriptor() {
        return ColumnDescriptor.of(columnId, name, type, ordinal);
    }

    // ==================== Object 方法 ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ColumnMeta that)) return false;
        return columnId == that.columnId;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(columnId);
    }

    @Override
    public String toString() {
        return String.format("ColumnMeta{id=%d, name='%s', type=%s, ordinal=%d}",
                columnId, name, type, ordinal);
    }
}
