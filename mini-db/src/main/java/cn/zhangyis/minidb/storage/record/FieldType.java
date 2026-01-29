package cn.zhangyis.minidb.storage.record;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Comparator;
import java.util.Objects;

/**
 * 字段类型描述
 * 
 * <p>
 * 对应 InnoDB 的 dtype_t，描述一个字段的完整类型信息，
 * 包括类型种类、长度、是否可空、以及比较器。
 * </p>
 * 
 * <h2>核心职责</h2>
 * <ul>
 * <li>封装字段的类型信息</li>
 * <li>提供类型对应的 Comparator</li>
 * <li>支持逻辑记录的比较操作</li>
 * </ul>
 * 
 * <h2>InnoDB 源码参考</h2>
 * <ul>
 * <li>data0type.h - dtype_t 定义</li>
 * </ul>
 * 
 * @author MiniDB
 * @version 1.0
 * @see FieldKind
 * @see DataField
 */
public class FieldType {

    /** 字段类型种类 */
    private final FieldKind kind;

    /**
     * 字段长度
     * <p>
     * 对于定长整型，由 FieldKind 决定；对于 CHAR/BINARY，为固定长度；
     * 对于 VARCHAR/VARBINARY，为最大长度
     * </p>
     */
    private final int length;

    /** 是否允许 NULL */
    private final boolean nullable;

    /** 此类型的比较器（延迟初始化） */
    private transient Comparator<byte[]> comparator;

    // ==================== 静态工厂方法 ====================

    /**
     * 创建 INT 类型
     * 
     * @param nullable 是否可空
     * @return FieldType 实例
     */
    public static FieldType intType(boolean nullable) {
        return new FieldType(FieldKind.INT, 4, nullable);
    }

    /**
     * 创建 BIGINT 类型
     * 
     * @param nullable 是否可空
     * @return FieldType 实例
     */
    public static FieldType bigintType(boolean nullable) {
        return new FieldType(FieldKind.BIGINT, 8, nullable);
    }

    /**
     * 创建 VARCHAR 类型
     * 
     * @param maxLength 最大长度
     * @param nullable  是否可空
     * @return FieldType 实例
     */
    public static FieldType varcharType(int maxLength, boolean nullable) {
        return new FieldType(FieldKind.VARCHAR, maxLength, nullable);
    }

    /**
     * 创建 CHAR 类型
     * 
     * @param length   固定长度
     * @param nullable 是否可空
     * @return FieldType 实例
     */
    public static FieldType charType(int length, boolean nullable) {
        return new FieldType(FieldKind.CHAR, length, nullable);
    }

    // ==================== 构造函数 ====================

    /**
     * 创建字段类型
     * 
     * @param kind     类型种类
     * @param length   字段长度（整型使用 kind 的固定长度）
     * @param nullable 是否可空
     */
    public FieldType(FieldKind kind, int length, boolean nullable) {
        this.kind = Objects.requireNonNull(kind, "kind cannot be null");
        this.length = kind.isInteger() ? kind.getFixedByteLength() : length;
        this.nullable = nullable;
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
     * 是否为定长类型
     * 
     * @return 如果是定长类型返回 true
     */
    public boolean isFixedLength() {
        return kind.isFixedLength();
    }

    /**
     * 获取此类型的比较器
     * 
     * <p>
     * 比较规则：
     * </p>
     * <ul>
     * <li>整型：按数值大小比较（Little Endian）</li>
     * <li>字符串/二进制：按字典序（字节序）比较</li>
     * </ul>
     * 
     * @return 用于比较 byte[] 的 Comparator
     */
    public Comparator<byte[]> comparator() {
        if (comparator == null) {
            comparator = createComparator();
        }
        return comparator;
    }

    // ==================== 私有方法 ====================

    private Comparator<byte[]> createComparator() {
        return switch (kind) {
            case TINYINT -> (a, b) -> {
                byte v1 = a[0];
                byte v2 = b[0];
                return Byte.compare(v1, v2);
            };
            case SMALLINT -> (a, b) -> {
                short v1 = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN).getShort();
                short v2 = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getShort();
                return Short.compare(v1, v2);
            };
            case INT -> (a, b) -> {
                int v1 = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN).getInt();
                int v2 = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt();
                return Integer.compare(v1, v2);
            };
            case BIGINT -> (a, b) -> {
                long v1 = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN).getLong();
                long v2 = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getLong();
                return Long.compare(v1, v2);
            };
            case CHAR, VARCHAR, BINARY, VARBINARY -> this::compareBytes;
        };
    }

    /**
     * 按字节字典序比较
     */
    private int compareBytes(byte[] a, byte[] b) {
        int minLen = Math.min(a.length, b.length);
        for (int i = 0; i < minLen; i++) {
            // 无符号比较
            int v1 = a[i] & 0xFF;
            int v2 = b[i] & 0xFF;
            if (v1 != v2) {
                return v1 - v2;
            }
        }
        // 长度短的排在前面
        return Integer.compare(a.length, b.length);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        FieldType fieldType = (FieldType) o;
        return length == fieldType.length &&
                nullable == fieldType.nullable &&
                kind == fieldType.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, length, nullable);
    }

    @Override
    public String toString() {
        return String.format("FieldType{kind=%s, length=%d, nullable=%s}",
                kind, length, nullable);
    }
}
