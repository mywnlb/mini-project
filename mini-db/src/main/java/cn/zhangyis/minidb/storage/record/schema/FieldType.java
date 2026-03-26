package cn.zhangyis.minidb.storage.record.schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.Objects;

/**
 * 字段类型描述
 *
 * <p>对应 InnoDB 的 dtype_t，描述字段的完整类型信息。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class FieldType {

    /**
     * 字段类型
     */
    private final FieldKind kind;

    /**
     * 最大长度（字节）
     * <p>对于 CHAR/VARCHAR 是声明长度，对于 INT 等固定类型由 kind 决定</p>
     */
    private final int length;

    /**
     * 是否允许 NULL
     */
    private final boolean nullable;

    /**
     * 私有构造函数
     */
    private FieldType(FieldKind kind, int length, boolean nullable) {
        this.kind = Objects.requireNonNull(kind, "kind cannot be null");
        this.length = length;
        this.nullable = nullable;
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建字段类型
     *
     * @param kind     字段类型
     * @param length   长度（对于定长整型可忽略）
     * @param nullable 是否允许 NULL
     * @return FieldType 实例
     */
    public static FieldType of(FieldKind kind, int length, boolean nullable) {
        int actualLength = kind.getFixedLength() > 0 ? kind.getFixedLength() : length;
        return new FieldType(kind, actualLength, nullable);
    }

    /**
     * 创建不允许 NULL 的字段类型
     */
    public static FieldType notNull(FieldKind kind, int length) {
        return of(kind, length, false);
    }

    /**
     * 创建允许 NULL 的字段类型
     */
    public static FieldType nullable(FieldKind kind, int length) {
        return of(kind, length, true);
    }

    // ==================== 快捷工厂方法 ====================

    public static FieldType tinyint(boolean nullable) {
        return of(FieldKind.TINYINT, 1, nullable);
    }

    public static FieldType smallint(boolean nullable) {
        return of(FieldKind.SMALLINT, 2, nullable);
    }

    public static FieldType intType(boolean nullable) {
        return of(FieldKind.INT, 4, nullable);
    }

    public static FieldType bigint(boolean nullable) {
        return of(FieldKind.BIGINT, 8, nullable);
    }

    public static FieldType decimal(int precision, boolean nullable) {
        return of(FieldKind.DECIMAL, precision, nullable);
    }

    public static FieldType date(boolean nullable) {
        return of(FieldKind.DATE, 10, nullable);
    }

    public static FieldType time(boolean nullable) {
        return of(FieldKind.TIME, 16, nullable);
    }

    public static FieldType datetime(boolean nullable) {
        return of(FieldKind.DATETIME, 32, nullable);
    }

    public static FieldType charType(int length, boolean nullable) {
        return of(FieldKind.CHAR, length, nullable);
    }

    public static FieldType varchar(int length, boolean nullable) {
        return of(FieldKind.VARCHAR, length, nullable);
    }

    public static FieldType binary(int length, boolean nullable) {
        return of(FieldKind.BINARY, length, nullable);
    }

    public static FieldType varbinary(int length, boolean nullable) {
        return of(FieldKind.VARBINARY, length, nullable);
    }

    public static FieldType blob(boolean nullable) {
        return of(FieldKind.BLOB, 0, nullable);
    }

    public static FieldType text(boolean nullable) {
        return of(FieldKind.TEXT, 0, nullable);
    }

    public static FieldType json(int length, boolean nullable) {
        return of(FieldKind.JSON, length, nullable);
    }

    // ==================== Getters ====================

    public FieldKind getKind() {
        return kind;
    }

    public int getLength() {
        return length;
    }

    public boolean isNullable() {
        return nullable;
    }

    /**
     * 是否变长类型
     */
    public boolean isVariable() {
        return kind.isVariable();
    }

    /**
     * 是否定长类型
     */
    public boolean isFixedSize() {
        return kind.isFixedSize();
    }

    /**
     * 获取存储所需的最大字节数
     */
    public int getMaxStorageSize() {
        return length;
    }

    // ==================== 比较器 ====================

    /**
     * 获取该类型的比较器
     *
     * @return 用于比较该类型值的 Comparator
     */
    public Comparator<byte[]> getComparator() {
        return switch (kind) {
            case TINYINT, SMALLINT, INT, BIGINT -> getIntegerComparator();
            case DECIMAL -> comparingStrings(BigDecimal::new);
            case DATE -> comparingStrings(LocalDate::parse);
            case TIME -> comparingStrings(value -> LocalTime.parse(normalizeTimeText(value)));
            case DATETIME -> comparingStrings(value -> LocalDateTime.parse(value.replace(' ', 'T')));
            default -> getBinaryComparator();
        };
    }

    private Comparator<byte[]> getIntegerComparator() {
        return (a, b) -> {
            if (a == null && b == null) return 0;
            if (a == null) return -1;
            if (b == null) return 1;

            // 按字节数解析为长整型比较
            long va = bytesToLong(a);
            long vb = bytesToLong(b);
            return Long.compare(va, vb);
        };
    }

    private Comparator<byte[]> getBinaryComparator() {
        return (a, b) -> {
            if (a == null && b == null) return 0;
            if (a == null) return -1;
            if (b == null) return 1;

            int len = Math.min(a.length, b.length);
            for (int i = 0; i < len; i++) {
                int cmp = (a[i] & 0xFF) - (b[i] & 0xFF);
                if (cmp != 0) return cmp;
            }
            return Integer.compare(a.length, b.length);
        };
    }

    private <T extends Comparable<T>> Comparator<byte[]> comparingStrings(java.util.function.Function<String, T> parser) {
        return (a, b) -> {
            if (a == null && b == null) return 0;
            if (a == null) return -1;
            if (b == null) return 1;
            return parser.apply(new String(a)).compareTo(parser.apply(new String(b)));
        };
    }

    private static long bytesToLong(byte[] bytes) {
        long value = 0;
        for (byte b : bytes) {
            value = (value << 8) | (b & 0xFF);
        }
        // 处理符号位
        if (bytes.length > 0 && (bytes[0] & 0x80) != 0) {
            // 负数，扩展符号位
            for (int i = bytes.length; i < 8; i++) {
                value |= (0xFFL << (i * 8));
            }
        }
        return value;
    }

    // ==================== Object 方法 ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FieldType that)) return false;
        return length == that.length && nullable == that.nullable && kind == that.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, length, nullable);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(kind.name());
        if (kind == FieldKind.CHAR || kind == FieldKind.VARCHAR ||
            kind == FieldKind.BINARY || kind == FieldKind.VARBINARY
            || kind == FieldKind.DECIMAL || kind == FieldKind.JSON) {
            sb.append("(").append(length).append(")");
        }
        if (!nullable) {
            sb.append(" NOT NULL");
        }
        return sb.toString();
    }

    private static String normalizeTimeText(String value) {
        int dot = value.indexOf('.');
        return dot >= 0 ? value.substring(0, dot) : value;
    }
}
