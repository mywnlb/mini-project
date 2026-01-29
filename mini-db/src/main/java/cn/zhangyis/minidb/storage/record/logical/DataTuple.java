package cn.zhangyis.minidb.storage.record.logical;

import cn.zhangyis.minidb.storage.record.schema.RecordSchema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 逻辑记录（元组）
 *
 * <p>对应 InnoDB 的 dtuple_t，表示内存中的一行数据。
 * 是逻辑层的核心数据结构，与物理存储格式无关。</p>
 *
 * <h2>设计要点</h2>
 * <ul>
 *   <li>字段按列顺序存储</li>
 *   <li>支持 Instant DDL 的默认值填充</li>
 *   <li>可变对象，支持字段设置（用于默认值填充）</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class DataTuple {

    /**
     * 字段数组
     */
    private final DataField[] fields;

    /**
     * 用于比较的字段数（可能小于总字段数）
     * <p>在索引比较时，只比较前 nFieldsCmp 个字段</p>
     */
    private int nFieldsCmp;

    /**
     * info bits（删除标记、最小记录标记等）
     */
    private int infoBits;

    /**
     * 私有构造函数
     */
    private DataTuple(DataField[] fields) {
        this.fields = Objects.requireNonNull(fields, "fields cannot be null");
        this.nFieldsCmp = fields.length;
        this.infoBits = 0;
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建空元组
     *
     * @param fieldCount 字段数量
     * @return DataTuple 实例
     */
    public static DataTuple create(int fieldCount) {
        return new DataTuple(new DataField[fieldCount]);
    }

    /**
     * 从字段数组创建
     *
     * @param fields 字段数组
     * @return DataTuple 实例
     */
    public static DataTuple of(DataField... fields) {
        DataField[] copy = Arrays.copyOf(fields, fields.length);
        return new DataTuple(copy);
    }

    /**
     * 从字段列表创建
     *
     * @param fields 字段列表
     * @return DataTuple 实例
     */
    public static DataTuple of(List<DataField> fields) {
        return new DataTuple(fields.toArray(new DataField[0]));
    }

    /**
     * 从值和 Schema 创建
     *
     * @param schema 记录 Schema
     * @param values 值数组
     * @return DataTuple 实例
     */
    public static DataTuple fromValues(RecordSchema schema, Object... values) {
        if (values.length != schema.getColumnCount()) {
            throw new IllegalArgumentException(
                "Value count " + values.length + " doesn't match schema column count " + schema.getColumnCount());
        }

        DataField[] fields = new DataField[values.length];
        for (int i = 0; i < values.length; i++) {
            fields[i] = schema.createField(i, values[i]);
        }
        return new DataTuple(fields);
    }

    // ==================== 字段访问 ====================

    /**
     * 获取字段数量
     */
    public int getFieldCount() {
        return fields.length;
    }

    /**
     * 获取指定位置的字段
     *
     * @param index 字段索引
     * @return DataField
     */
    public DataField getField(int index) {
        return fields[index];
    }

    /**
     * 设置指定位置的字段
     *
     * <p>主要用于 Instant DDL 默认值填充</p>
     *
     * @param index 字段索引
     * @param field 字段值
     */
    public void setField(int index, DataField field) {
        fields[index] = field;
    }

    /**
     * 获取所有字段
     *
     * @return 字段数组副本
     */
    public DataField[] getFields() {
        return Arrays.copyOf(fields, fields.length);
    }

    // ==================== 比较相关 ====================

    /**
     * 获取用于比较的字段数
     */
    public int getFieldsToCompare() {
        return nFieldsCmp;
    }

    /**
     * 设置用于比较的字段数
     *
     * @param n 字段数
     */
    public void setFieldsToCompare(int n) {
        if (n < 0 || n > fields.length) {
            throw new IllegalArgumentException("Invalid nFieldsCmp: " + n);
        }
        this.nFieldsCmp = n;
    }

    /**
     * 比较两个元组
     *
     * <p>只比较前 min(this.nFieldsCmp, other.nFieldsCmp) 个字段</p>
     *
     * @param other 另一个元组
     * @return 比较结果
     */
    public int compareTo(DataTuple other) {
        int n = Math.min(this.nFieldsCmp, other.nFieldsCmp);
        for (int i = 0; i < n; i++) {
            DataField a = this.fields[i];
            DataField b = other.fields[i];

            if (a == null && b == null) continue;
            if (a == null) return -1;
            if (b == null) return 1;

            int cmp = a.compareTo(b);
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    // ==================== Info Bits ====================

    /** 删除标记 */
    public static final int INFO_DELETED_FLAG = 0x01;

    /** 最小记录标记 */
    public static final int INFO_MIN_REC_FLAG = 0x02;

    /**
     * 获取 info bits
     */
    public int getInfoBits() {
        return infoBits;
    }

    /**
     * 设置 info bits
     */
    public void setInfoBits(int infoBits) {
        this.infoBits = infoBits;
    }

    /**
     * 是否标记为删除
     */
    public boolean isDeleted() {
        return (infoBits & INFO_DELETED_FLAG) != 0;
    }

    /**
     * 设置删除标记
     */
    public void setDeleted(boolean deleted) {
        if (deleted) {
            infoBits |= INFO_DELETED_FLAG;
        } else {
            infoBits &= ~INFO_DELETED_FLAG;
        }
    }

    /**
     * 是否是最小记录
     */
    public boolean isMinRec() {
        return (infoBits & INFO_MIN_REC_FLAG) != 0;
    }

    /**
     * 设置最小记录标记
     */
    public void setMinRec(boolean minRec) {
        if (minRec) {
            infoBits |= INFO_MIN_REC_FLAG;
        } else {
            infoBits &= ~INFO_MIN_REC_FLAG;
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 检查是否有 NULL 字段
     */
    public boolean hasNull() {
        for (DataField field : fields) {
            if (field != null && field.isNull()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取所有字段的总数据长度
     */
    public int getTotalDataLength() {
        int total = 0;
        for (DataField field : fields) {
            if (field != null && !field.isNull()) {
                total += field.getLength();
            }
        }
        return total;
    }

    /**
     * 创建副本
     */
    public DataTuple copy() {
        DataField[] copyFields = Arrays.copyOf(fields, fields.length);
        DataTuple copy = new DataTuple(copyFields);
        copy.nFieldsCmp = this.nFieldsCmp;
        copy.infoBits = this.infoBits;
        return copy;
    }

    // ==================== Object 方法 ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DataTuple that)) return false;
        return Arrays.equals(fields, that.fields);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(fields);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(fields[i] == null ? "?" : fields[i].toString());
        }
        sb.append(")");
        return sb.toString();
    }

    // ==================== 构建器 ====================

    /**
     * 创建构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * DataTuple 构建器
     */
    public static class Builder {
        private final List<DataField> fields = new ArrayList<>();

        public Builder add(DataField field) {
            fields.add(field);
            return this;
        }

        public Builder addInt(int value) {
            fields.add(DataField.intField(value));
            return this;
        }

        public Builder addLong(long value) {
            fields.add(DataField.bigintField(value));
            return this;
        }

        public Builder addString(String value) {
            fields.add(DataField.varcharField(value));
            return this;
        }

        public Builder addNull(cn.zhangyis.minidb.storage.record.schema.FieldKind kind) {
            fields.add(DataField.nullField(kind));
            return this;
        }

        public DataTuple build() {
            return DataTuple.of(fields);
        }
    }
}
