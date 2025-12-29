package cn.zhangyis.enums;

import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据类型枚举，定义支持的数据类型及其对应的Java类型
 */
public enum FiledType {
    // 基本数值类型
    INT(Integer.class, Types.INTEGER, 4, "INT", true),
    SMALLINT(Short.class, Types.SMALLINT, 2, "SMALLINT", true),
    BIGINT(Long.class, Types.BIGINT, 8, "BIGINT", true),
    DECIMAL(java.math.BigDecimal.class, Types.DECIMAL, -1, "DECIMAL", true),
    FLOAT(Float.class, Types.FLOAT, 4, "FLOAT", true),
    DOUBLE(Double.class, Types.DOUBLE, 8, "DOUBLE", true),

    // 字符串类型
    VARCHAR(String.class, Types.VARCHAR, -1, "VARCHAR", false),
    CHAR(String.class, Types.CHAR, -1, "CHAR", false),
    TEXT(String.class, Types.LONGVARCHAR, -1, "TEXT", false),

    // 日期时间类型
    DATE(java.time.LocalDate.class, Types.DATE, 3, "DATE", false),
    TIME(java.time.LocalTime.class, Types.TIME, 3, "TIME", false),
    DATETIME(java.time.LocalDateTime.class, Types.TIMESTAMP, 8, "DATETIME", false),
    TIMESTAMP(java.sql.Timestamp.class, Types.TIMESTAMP, 8, "TIMESTAMP", false),

    // 布尔类型
    BOOLEAN(Boolean.class, Types.BOOLEAN, 1, "BOOLEAN", false),

    // 二进制类型
    BLOB(byte[].class, Types.BLOB, -1, "BLOB", false),

    // 特殊类型
    NULL(Void.class, Types.NULL, 0, "NULL", false);

    private final Class<?> javaType; // 对应的Java类型
    private final int sqlType; // 对应的JDBC SQL类型
    private final int fixedSize; // 固定大小（字节），-1表示可变长度
    private final String sqlTypeName; // SQL类型名称
    private final boolean numeric; // 是否为数值类型

    // 缓存用于快速查找
    private static final Map<Integer, FiledType> SQL_TYPE_MAP = new HashMap<>();
    private static final Map<String, FiledType> NAME_MAP = new HashMap<>();

    static {
        for (FiledType type : values()) {
            SQL_TYPE_MAP.put(type.sqlType, type);
            NAME_MAP.put(type.sqlTypeName.toUpperCase(), type);
        }
    }

    FiledType(Class<?> javaType, int sqlType, int fixedSize, String sqlTypeName, boolean numeric) {
        this.javaType = javaType;
        this.sqlType = sqlType;
        this.fixedSize = fixedSize;
        this.sqlTypeName = sqlTypeName;
        this.numeric = numeric;
    }

    /**
     * 获取对应的Java类型
     */
    public Class<?> getJavaType() {
        return javaType;
    }

    /**
     * 获取对应的JDBC SQL类型
     */
    public int getSqlType() {
        return sqlType;
    }

    /**
     * 获取固定大小（字节），-1表示可变长度
     */
    public int getFixedSize() {
        return fixedSize;
    }

    /**
     * 获取SQL类型名称
     */
    public String getSqlTypeName() {
        return sqlTypeName;
    }

    /**
     * 判断是否为数值类型
     */
    public boolean isNumeric() {
        return numeric;
    }

    /**
     * 判断是否为日期时间类型
     */
    public boolean isDateTime() {
        return this == DATE || this == TIME || this == DATETIME || this == TIMESTAMP;
    }

    /**
     * 判断是否为字符串类型
     */
    public boolean isString() {
        return this == VARCHAR || this == CHAR || this == TEXT;
    }

    /**
     * 判断是否为可变长度类型
     */
    public boolean isVariableLength() {
        return fixedSize == -1;
    }

    /**
     * 根据SQL类型获取FiledType
     */
    public static FiledType fromSqlType(int sqlType) {
        return SQL_TYPE_MAP.getOrDefault(sqlType, NULL);
    }

    /**
     * 根据类型名称获取FiledType（不区分大小写）
     */
    public static FiledType fromName(String name) {
        if (name == null) {
            return NULL;
        }
        return NAME_MAP.getOrDefault(name.toUpperCase(), NULL);
    }

    /**
     * 检查Java对象是否与此类型兼容
     */
    public boolean isCompatibleWith(Object value) {
        if (value == null) {
            return true;
        }

        // 特殊处理: 数值类型之间的兼容性
        if (isNumeric() && value instanceof Number) {
            if (this == INT) {
                return ((Number) value).intValue() == ((Number) value).doubleValue();
            } else if (this == SMALLINT) {
                return ((Number) value).shortValue() == ((Number) value).doubleValue();
            }
            return true;
        }

        return javaType.isAssignableFrom(value.getClass());
    }

    /**
     * 获取类型的描述，包括长度信息
     */
    public String getTypeDescription(Integer length, Integer precision) {
        if (this == VARCHAR || this == CHAR) {
            return sqlTypeName + (length != null ? "(" + length + ")" : "");
        } else if (this == DECIMAL) {
            if (precision != null && length != null) {
                return sqlTypeName + "(" + length + "," + precision + ")";
            } else if (length != null) {
                return sqlTypeName + "(" + length + ")";
            }
            return sqlTypeName;
        }
        return sqlTypeName;
    }
}
