package cn.zhangyis.minidb.storage.record.schema;

import cn.zhangyis.minidb.storage.record.logical.DataField;

import java.util.*;

/**
 * 记录 Schema
 *
 * <p>某个 rowVersion 对应的 schema 快照，描述记录的列结构。</p>
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>I8: FieldOffsets 基准 = 相对 dataStart，不含系统列</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class RecordSchema {

    /**
     * Schema 版本（对应记录中的 ROW_VERSION）
     */
    private final int version;

    /**
     * 列描述符列表（按 ordinal 顺序）
     */
    private final List<ColumnDescriptor> columns;

    /**
     * columnId → ordinal 映射（用于 Instant DDL 填充）
     */
    private final Map<Long, Integer> columnIdToIndex;

    /**
     * 变长列的 ordinal 列表（逆序，用于解析 varlen list）
     */
    private final int[] variableColumnOrdinals;

    /**
     * Nullable 列的 ordinal 列表（用于解析 NULL bitmap）
     */
    private final int[] nullableColumnOrdinals;

    /**
     * 私有构造函数
     */
    private RecordSchema(int version, List<ColumnDescriptor> columns) {
        this.version = version;
        this.columns = List.copyOf(columns);

        // 构建 columnId → index 映射
        this.columnIdToIndex = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            columnIdToIndex.put(columns.get(i).getColumnId(), i);
        }

        // 收集变长列（逆序）
        List<Integer> varOrdinals = new ArrayList<>();
        for (int i = columns.size() - 1; i >= 0; i--) {
            if (columns.get(i).isVariable()) {
                varOrdinals.add(i);
            }
        }
        this.variableColumnOrdinals = varOrdinals.stream().mapToInt(Integer::intValue).toArray();

        // 收集 Nullable 列
        List<Integer> nullOrdinals = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).isNullable()) {
                nullOrdinals.add(i);
            }
        }
        this.nullableColumnOrdinals = nullOrdinals.stream().mapToInt(Integer::intValue).toArray();
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    // ==================== Getters ====================

    public int getVersion() {
        return version;
    }

    public int getColumnCount() {
        return columns.size();
    }

    public ColumnDescriptor getColumn(int index) {
        return columns.get(index);
    }

    public List<ColumnDescriptor> getColumns() {
        return columns;
    }

    public FieldType getColumnType(int index) {
        return columns.get(index).getType();
    }

    /**
     * 根据 columnId 获取当前 schema 中的 index
     *
     * @param columnId 列ID
     * @return 列索引，不存在返回 -1
     */
    public int indexOfColumn(long columnId) {
        return columnIdToIndex.getOrDefault(columnId, -1);
    }

    /**
     * 获取变长列 ordinals（逆序）
     */
    public int[] getVariableColumnOrdinals() {
        return variableColumnOrdinals;
    }

    /**
     * 获取 Nullable 列 ordinals
     */
    public int[] getNullableColumnOrdinals() {
        return nullableColumnOrdinals;
    }

    /**
     * 获取变长列数量
     */
    public int getVariableColumnCount() {
        return variableColumnOrdinals.length;
    }

    /**
     * 获取 Nullable 列数量
     */
    public int getNullableColumnCount() {
        return nullableColumnOrdinals.length;
    }

    /**
     * 计算 NULL bitmap 字节数
     */
    public int getNullBitmapBytes() {
        return (nullableColumnOrdinals.length + 7) / 8;
    }

    // ==================== 字段创建 ====================

    /**
     * 根据列索引和值创建 DataField
     *
     * @param index 列索引
     * @param value 值对象
     * @return DataField 实例
     */
    public DataField createField(int index, Object value) {
        ColumnDescriptor col = columns.get(index);
        FieldType type = col.getType();

        if (value == null) {
            if (!type.isNullable()) {
                throw new IllegalArgumentException("Column " + col.getName() + " is NOT NULL");
            }
            return DataField.nullField(type);
        }

        return switch (type.getKind()) {
            case TINYINT -> DataField.tinyintField(((Number) value).byteValue());
            case SMALLINT -> DataField.smallintField(((Number) value).shortValue());
            case INT -> DataField.intField(((Number) value).intValue());
            case BIGINT -> DataField.bigintField(((Number) value).longValue());
            case DECIMAL -> DataField.decimalField(value);
            case DATE -> DataField.dateField(value);
            case TIME -> DataField.timeField(value);
            case DATETIME -> DataField.datetimeField(value);
            case CHAR -> DataField.charField((String) value, type.getLength());
            case VARCHAR -> DataField.varcharField((String) value);
            case BINARY -> DataField.binaryField((byte[]) value, type.getLength());
            case VARBINARY -> DataField.varbinaryField((byte[]) value);
            case BLOB -> DataField.blobField((byte[]) value);
            case TEXT -> DataField.textField((String) value);
            case JSON -> DataField.jsonField((String) value);
        };
    }

    // ==================== 空间计算 ====================

    /**
     * 计算定长列的总大小
     */
    public int getFixedColumnsSize() {
        int size = 0;
        for (ColumnDescriptor col : columns) {
            if (!col.isVariable()) {
                size += col.getLength();
            }
        }
        return size;
    }

    // ==================== Object 方法 ====================

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("RecordSchema v").append(version).append(" {\n");
        for (ColumnDescriptor col : columns) {
            sb.append("  ").append(col).append("\n");
        }
        sb.append("}");
        return sb.toString();
    }

    // ==================== Builder ====================

    public static class Builder {
        private int version = 1;
        private final List<ColumnDescriptor> columns = new ArrayList<>();
        private long nextColumnId = 1;

        public Builder version(int version) {
            this.version = version;
            return this;
        }

        public Builder column(String name, FieldKind kind, boolean nullable) {
            return column(name, kind, kind.getFixedLength(), nullable);
        }

        public Builder column(String name, FieldKind kind, int length, boolean nullable) {
            FieldType type = FieldType.of(kind, length, nullable);
            int ordinal = columns.size();
            columns.add(ColumnDescriptor.of(nextColumnId++, name, type, ordinal));
            return this;
        }

        public Builder column(ColumnDescriptor descriptor) {
            columns.add(descriptor.withOrdinal(columns.size()));
            return this;
        }

        public RecordSchema build() {
            return new RecordSchema(version, columns);
        }
    }
}
