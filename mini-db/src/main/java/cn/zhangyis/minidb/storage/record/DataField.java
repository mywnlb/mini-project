package cn.zhangyis.minidb.storage.record;

import java.util.Arrays;
import java.util.Objects;

/**
 * 逻辑字段表示
 * 
 * <p>
 * 对应 InnoDB 的 dfield_t，表示逻辑记录中的单个字段。
 * 包含字段类型、数据、长度、NULL 标记等信息。
 * </p>
 * 
 * <h2>核心属性</h2>
 * <ul>
 * <li><b>type</b>: 字段类型描述</li>
 * <li><b>data</b>: 字段数据（字节数组）</li>
 * <li><b>len</b>: 实际数据长度</li>
 * <li><b>isNull</b>: 是否为 NULL</li>
 * <li><b>isExternal</b>: 是否外部存储（BLOB/TEXT）</li>
 * </ul>
 * 
 * <h2>NULL 语义</h2>
 * <p>
 * 如果 isNull 为 true，则 data 应为 null 或被忽略。
 * 比较时 NULL < NOT NULL。
 * </p>
 * 
 * <h2>InnoDB 源码参考</h2>
 * <ul>
 * <li>data0data.h - dfield_t 定义</li>
 * </ul>
 * 
 * @author MiniDB
 * @version 1.0
 * @see FieldType
 * @see DataTuple
 */
public class DataField {

    /** 字段类型 */
    private final FieldType type;

    /** 字段数据（NULL 时为 null） */
    private final byte[] data;

    /** 实际数据长度 */
    private final int len;

    /** 是否为 NULL */
    private final boolean isNull;

    /** 是否外部存储（BLOB/TEXT，逻辑层仅标记，不负责 I/O） */
    private final boolean isExternal;

    // ==================== 静态工厂方法 ====================

    /**
     * 创建 NULL 字段
     * 
     * @param type 字段类型
     * @return NULL 字段
     */
    public static DataField nullField(FieldType type) {
        return new DataField(type, null, 0, true, false);
    }

    /**
     * 创建 INT 字段
     * 
     * @param type  字段类型
     * @param value int 值
     * @return DataField 实例
     */
    public static DataField intField(FieldType type, int value) {
        byte[] data = new byte[4];
        // Little Endian
        data[0] = (byte) value;
        data[1] = (byte) (value >> 8);
        data[2] = (byte) (value >> 16);
        data[3] = (byte) (value >> 24);
        return new DataField(type, data, 4, false, false);
    }

    /**
     * 创建 BIGINT 字段
     * 
     * @param type  字段类型
     * @param value long 值
     * @return DataField 实例
     */
    public static DataField bigintField(FieldType type, long value) {
        byte[] data = new byte[8];
        // Little Endian
        for (int i = 0; i < 8; i++) {
            data[i] = (byte) (value >> (i * 8));
        }
        return new DataField(type, data, 8, false, false);
    }

    /**
     * 创建字符串字段
     * 
     * @param type  字段类型
     * @param value 字符串值（UTF-8 编码）
     * @return DataField 实例
     */
    public static DataField stringField(FieldType type, String value) {
        byte[] data = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new DataField(type, data, data.length, false, false);
    }

    /**
     * 创建二进制字段
     * 
     * @param type 字段类型
     * @param data 二进制数据
     * @return DataField 实例
     */
    public static DataField binaryField(FieldType type, byte[] data) {
        return new DataField(type, data, data.length, false, false);
    }

    // ==================== 构造函数 ====================

    /**
     * 创建数据字段
     * 
     * @param type       字段类型
     * @param data       字段数据
     * @param len        数据长度
     * @param isNull     是否为 NULL
     * @param isExternal 是否外部存储
     */
    public DataField(FieldType type, byte[] data, int len, boolean isNull, boolean isExternal) {
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.data = isNull ? null : (data != null ? Arrays.copyOf(data, len) : null);
        this.len = isNull ? 0 : len;
        this.isNull = isNull;
        this.isExternal = isExternal;
    }

    // ==================== Getters ====================

    public FieldType getType() {
        return type;
    }

    /**
     * 获取字段数据
     * 
     * @return 字段数据副本，NULL 时返回 null
     */
    public byte[] getData() {
        return data != null ? Arrays.copyOf(data, data.length) : null;
    }

    /**
     * 获取字段数据（零拷贝，内部使用）
     * 
     * @return 原始数据引用，调用者不应修改
     */
    byte[] getDataInternal() {
        return data;
    }

    public int getLen() {
        return len;
    }

    public boolean isNull() {
        return isNull;
    }

    public boolean isExternal() {
        return isExternal;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        DataField dataField = (DataField) o;
        return len == dataField.len &&
                isNull == dataField.isNull &&
                isExternal == dataField.isExternal &&
                Objects.equals(type, dataField.type) &&
                Arrays.equals(data, dataField.data);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(type, len, isNull, isExternal);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }

    @Override
    public String toString() {
        if (isNull) {
            return "DataField{NULL}";
        }
        return String.format("DataField{type=%s, len=%d, external=%s}",
                type.getKind(), len, isExternal);
    }
}
