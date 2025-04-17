package cn.zhangyis.enums;

/**
 * 数据类型枚举，定义支持的数据类型及其对应的Java类型
 */
public enum FiledType {
    INT(Integer.class),
    BIGINT(Long.class),
    VARCHAR(String.class),
    DECIMAL(java.math.BigDecimal.class),
    DATETIME(java.time.LocalDateTime.class),
    DATE(java.time.LocalDate.class),
    TIME(java.time.LocalTime.class),
    DOUBLE(Double.class),
    BOOLEAN(Boolean.class);

    private final Class<?> javaType;

    FiledType(Class<?> javaType) {
        this.javaType = javaType;
    }

    public Class<?> getJavaType() {
        return javaType;
    }
}
