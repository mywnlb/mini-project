package cn.zhangyis.minidb.storage.record.schema;

import java.util.Objects;

/**
 * 列描述符
 *
 * <p>描述表中一列的完整信息，包括列ID、名称、类型等。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class ColumnDescriptor {

    /**
     * 列ID（长期稳定标识）
     *
     * <p>I6/F5: 以 columnId 为主键，用于 Instant DDL 列映射</p>
     */
    private final long columnId;

    /**
     * 列名
     */
    private final String name;

    /**
     * 字段类型
     */
    private final FieldType type;

    /**
     * 在 schema 中的位置索引
     *
     * <p>注意：这只在某个 RecordSchema 快照内有效，列重排后可能变化</p>
     */
    private final int ordinal;

    /**
     * 私有构造函数
     */
    private ColumnDescriptor(long columnId, String name, FieldType type, int ordinal) {
        this.columnId = columnId;
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.ordinal = ordinal;
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建列描述符
     *
     * @param columnId 列ID
     * @param name     列名
     * @param type     字段类型
     * @param ordinal  位置索引
     * @return ColumnDescriptor 实例
     */
    public static ColumnDescriptor of(long columnId, String name, FieldType type, int ordinal) {
        return new ColumnDescriptor(columnId, name, type, ordinal);
    }

    /**
     * 创建列描述符（自动生成 columnId）
     *
     * @param name    列名
     * @param type    字段类型
     * @param ordinal 位置索引
     * @return ColumnDescriptor 实例
     */
    public static ColumnDescriptor of(String name, FieldType type, int ordinal) {
        // 使用名称 hash 作为临时 ID（实际应由 Catalog 分配）
        return new ColumnDescriptor(name.hashCode() & 0xFFFFFFFFL, name, type, ordinal);
    }

    /**
     * 创建列描述符（快捷方法）
     *
     * @param name     列名
     * @param kind     字段类型
     * @param length   长度
     * @param nullable 是否允许 NULL
     * @param ordinal  位置索引
     * @return ColumnDescriptor 实例
     */
    public static ColumnDescriptor of(String name, FieldKind kind, int length, boolean nullable, int ordinal) {
        return of(name, FieldType.of(kind, length, nullable), ordinal);
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

    public boolean isNullable() {
        return type.isNullable();
    }

    public boolean isVariable() {
        return type.isVariable();
    }

    public int getLength() {
        return type.getLength();
    }

    /**
     * 创建具有新 ordinal 的副本
     */
    public ColumnDescriptor withOrdinal(int newOrdinal) {
        return new ColumnDescriptor(columnId, name, type, newOrdinal);
    }

    // ==================== Object 方法 ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ColumnDescriptor that)) return false;
        return columnId == that.columnId;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(columnId);
    }

    @Override
    public String toString() {
        return String.format("%s %s", name, type);
    }
}
