package cn.zhangyis.minidb.storage.record.logical;

import cn.zhangyis.minidb.storage.record.schema.FieldKind;
import cn.zhangyis.minidb.storage.record.schema.FieldType;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Objects;

/**
 * 逻辑字段
 *
 * <p>对应 InnoDB 的 dfield_t，表示内存中的一个字段值。
 * 与物理存储格式无关，是逻辑层的数据表示。</p>
 *
 * <h2>设计要点</h2>
 * <ul>
 *   <li>不可变对象，线程安全</li>
 *   <li>支持 NULL 值</li>
 *   <li>数据以 byte[] 存储，类型信息由 FieldType 描述</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class DataField {
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");


    /**
     * 字段类型
     */
    private final FieldType type;

    /**
     * 字段数据（NULL 时为 null）
     */
    private final byte[] data;

    /**
     * 是否为 NULL
     */
    private final boolean isNull;

    /**
     * 私有构造函数
     */
    private DataField(FieldType type, byte[] data, boolean isNull) {
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.data = data;
        this.isNull = isNull;
    }

    // ==================== NULL 值工厂方法 ====================

    /**
     * 创建 NULL 字段
     *
     * @param kind 字段类型
     * @return NULL DataField
     */
    public static DataField nullField(FieldKind kind) {
        return new DataField(FieldType.nullable(kind, 0), null, true);
    }

    /**
     * 创建 NULL 字段
     *
     * @param type 字段类型
     * @return NULL DataField
     */
    public static DataField nullField(FieldType type) {
        if (!type.isNullable()) {
            throw new IllegalArgumentException("Cannot create NULL for NOT NULL column");
        }
        return new DataField(type, null, true);
    }

    // ==================== 整型工厂方法 ====================

    /**
     * 创建 TINYINT 字段
     */
    public static DataField tinyintField(byte value) {
        return new DataField(FieldType.tinyint(false), new byte[]{value}, false);
    }

    /**
     * 创建 SMALLINT 字段
     */
    public static DataField smallintField(short value) {
        byte[] data = new byte[2];
        ByteBuffer.wrap(data).putShort(value);
        return new DataField(FieldType.smallint(false), data, false);
    }

    /**
     * 创建 INT 字段
     */
    public static DataField intField(int value) {
        byte[] data = new byte[4];
        ByteBuffer.wrap(data).putInt(value);
        return new DataField(FieldType.intType(false), data, false);
    }

    /**
     * 创建 BIGINT 字段
     */
    public static DataField bigintField(long value) {
        byte[] data = new byte[8];
        ByteBuffer.wrap(data).putLong(value);
        return new DataField(FieldType.bigint(false), data, false);
    }

    public static DataField decimalField(Object value) {
        if (value == null) {
            return nullField(FieldKind.DECIMAL);
        }
        BigDecimal decimal = value instanceof BigDecimal bd ? bd : new BigDecimal(String.valueOf(value));
        byte[] data = decimal.toPlainString().getBytes(StandardCharsets.UTF_8);
        return new DataField(FieldType.decimal(data.length, false), data, false);
    }

    public static DataField dateField(Object value) {
        if (value == null) {
            return nullField(FieldKind.DATE);
        }
        LocalDate date = value instanceof LocalDate localDate
                ? localDate
                : LocalDate.parse(String.valueOf(value));
        byte[] data = date.toString().getBytes(StandardCharsets.UTF_8);
        return new DataField(FieldType.date(false), data, false);
    }

    public static DataField timeField(Object value) {
        if (value == null) {
            return nullField(FieldKind.TIME);
        }
        LocalTime time = value instanceof LocalTime localTime
                ? localTime
                : LocalTime.parse(normalizeTimeText(String.valueOf(value)));
        byte[] data = TIME_FORMATTER.format(time.withNano(0)).getBytes(StandardCharsets.UTF_8);
        return new DataField(FieldType.time(false), data, false);
    }

    public static DataField datetimeField(Object value) {
        if (value == null) {
            return nullField(FieldKind.DATETIME);
        }
        LocalDateTime datetime;
        if (value instanceof LocalDateTime localDateTime) {
            datetime = localDateTime;
        } else {
            String normalized = String.valueOf(value).replace(' ', 'T');
            datetime = LocalDateTime.parse(normalized);
        }
        byte[] data = DATETIME_FORMATTER.format(datetime.withNano(0)).getBytes(StandardCharsets.UTF_8);
        return new DataField(FieldType.datetime(false), data, false);
    }

    // ==================== 字符串工厂方法 ====================

    /**
     * 创建 VARCHAR 字段
     */
    public static DataField varcharField(String value) {
        if (value == null) {
            return nullField(FieldKind.VARCHAR);
        }
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        return new DataField(FieldType.varchar(data.length, false), data, false);
    }

    /**
     * 创建 CHAR 字段
     *
     * @param value  字符串值
     * @param length 声明长度
     */
    public static DataField charField(String value, int length) {
        if (value == null) {
            return nullField(FieldKind.CHAR);
        }
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[length];
        Arrays.fill(data, (byte) ' '); // 填充空格
        System.arraycopy(raw, 0, data, 0, Math.min(raw.length, length));
        return new DataField(FieldType.charType(length, false), data, false);
    }

    // ==================== 二进制工厂方法 ====================

    /**
     * 创建 VARBINARY 字段
     */
    public static DataField varbinaryField(byte[] value) {
        if (value == null) {
            return nullField(FieldKind.VARBINARY);
        }
        byte[] data = Arrays.copyOf(value, value.length);
        return new DataField(FieldType.varbinary(data.length, false), data, false);
    }

    /**
     * 创建 BINARY 字段
     */
    public static DataField binaryField(byte[] value, int length) {
        if (value == null) {
            return nullField(FieldKind.BINARY);
        }
        byte[] data = new byte[length];
        System.arraycopy(value, 0, data, 0, Math.min(value.length, length));
        return new DataField(FieldType.binary(length, false), data, false);
    }

    /**
     * 创建 BLOB 字段
     */
    public static DataField blobField(byte[] value) {
        if (value == null) {
            return nullField(FieldKind.BLOB);
        }
        byte[] data = Arrays.copyOf(value, value.length);
        return new DataField(FieldType.blob(false), data, false);
    }

    /**
     * 创建 TEXT 字段
     */
    public static DataField textField(String value) {
        if (value == null) {
            return nullField(FieldKind.TEXT);
        }
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        return new DataField(FieldType.text(false), data, false);
    }

    public static DataField jsonField(String value) {
        if (value == null) {
            return nullField(FieldKind.JSON);
        }
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        return new DataField(FieldType.json(data.length, false), data, false);
    }

    // ==================== 通用工厂方法 ====================

    /**
     * 从原始字节创建字段
     *
     * @param type 字段类型
     * @param data 字段数据
     * @return DataField 实例
     */
    public static DataField fromBytes(FieldType type, byte[] data) {
        if (data == null) {
            return nullField(type);
        }
        return new DataField(type, Arrays.copyOf(data, data.length), false);
    }

    /**
     * 反序列化字段
     *
     * @param data 序列化数据
     * @param type 字段类型
     * @return DataField 实例
     */
    public static DataField deserialize(byte[] data, FieldType type) {
        if (data == null) {
            return nullField(type);
        }
        return new DataField(type, Arrays.copyOf(data, data.length), false);
    }

    // ==================== Getters ====================

    /**
     * 获取字段类型
     */
    public FieldType getType() {
        return type;
    }

    /**
     * 获取字段类型种类
     */
    public FieldKind getKind() {
        return type.getKind();
    }

    /**
     * 是否为 NULL
     */
    public boolean isNull() {
        return isNull;
    }

    /**
     * 获取原始字节数据
     *
     * @return 字节数组副本，NULL 时返回 null
     */
    public byte[] getData() {
        return data == null ? null : Arrays.copyOf(data, data.length);
    }

    /**
     * 获取数据长度
     *
     * @return 数据字节数，NULL 时返回 0
     */
    public int getLength() {
        return data == null ? 0 : data.length;
    }

    // ==================== 值读取方法 ====================

    /**
     * 作为 int 读取
     */
    public int asInt() {
        if (isNull) {
            throw new IllegalStateException("Cannot read NULL as int");
        }
        return ByteBuffer.wrap(data).getInt();
    }

    /**
     * 作为 long 读取
     */
    public long asLong() {
        if (isNull) {
            throw new IllegalStateException("Cannot read NULL as long");
        }
        if (data.length == 4) {
            return ByteBuffer.wrap(data).getInt();
        }
        return ByteBuffer.wrap(data).getLong();
    }

    /**
     * 作为 String 读取
     */
    public String asString() {
        if (isNull) {
            return null;
        }
        String s = new String(data, StandardCharsets.UTF_8);
        // CHAR 类型去除尾部空格
        if (type.getKind() == FieldKind.CHAR) {
            return s.stripTrailing();
        }
        return s;
    }

    /**
     * 获取值对象
     */
    public Object getValue() {
        if (isNull) {
            return null;
        }
        return switch (type.getKind()) {
            case TINYINT -> data[0];
            case SMALLINT -> ByteBuffer.wrap(data).getShort();
            case INT -> ByteBuffer.wrap(data).getInt();
            case BIGINT -> ByteBuffer.wrap(data).getLong();
            case DECIMAL -> new BigDecimal(asString());
            case DATE -> LocalDate.parse(asString());
            case TIME -> LocalTime.parse(normalizeTimeText(asString()));
            case DATETIME -> LocalDateTime.parse(asString().replace(' ', 'T'));
            case CHAR, VARCHAR, TEXT, JSON -> asString();
            case BINARY, VARBINARY, BLOB -> getData();
        };
    }

    // ==================== 比较方法 ====================

    /**
     * 比较两个字段
     *
     * @param other 另一个字段
     * @return 比较结果
     */
    public int compareTo(DataField other) {
        if (this.isNull && other.isNull) return 0;
        if (this.isNull) return -1;
        if (other.isNull) return 1;

        return type.getComparator().compare(this.data, other.data);
    }

    // ==================== Object 方法 ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DataField that)) return false;
        if (isNull != that.isNull) return false;
        if (isNull) return true;
        return Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        if (isNull) return 0;
        return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        if (isNull) {
            return "NULL";
        }
        Object value = getValue();
        if (value instanceof byte[] bytes) {
            return "0x" + bytesToHex(bytes);
        }
        return String.valueOf(value);
    }

    private static String normalizeTimeText(String value) {
        int dot = value.indexOf('.');
        return dot >= 0 ? value.substring(0, dot) : value;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
