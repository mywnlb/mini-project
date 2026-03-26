package cn.zhangyis.minidb.storage.btree;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 复合键值
 *
 * <p>表示复合键的具体值，包含多个列的值。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class CompositeKeyValue {

    /** NULL 标记 */
    private static final byte NULL_FLAG = 0;
    private static final byte NOT_NULL_FLAG = 1;

    /** 列值数组 */
    private final Object[] values;

    /** 键定义 */
    private final CompositeKeyDef keyDef;

    /**
     * 构造复合键值
     *
     * @param keyDef 键定义
     * @param values 列值数组
     */
    public CompositeKeyValue(CompositeKeyDef keyDef, Object... values) {
        if (values.length > keyDef.getColumnCount()) {
            throw new IllegalArgumentException("Too many values for key definition");
        }
        this.keyDef = keyDef;
        this.values = new Object[keyDef.getColumnCount()];
        System.arraycopy(values, 0, this.values, 0, values.length);
        // 剩余列为 null（用于前缀搜索）
    }

    /**
     * 创建单列整数键值
     */
    public static CompositeKeyValue of(CompositeKeyDef keyDef, int value) {
        return new CompositeKeyValue(keyDef, value);
    }

    /**
     * 创建双列整数键值
     */
    public static CompositeKeyValue of(CompositeKeyDef keyDef, int value1, int value2) {
        return new CompositeKeyValue(keyDef, value1, value2);
    }

    /**
     * 创建三列整数键值
     */
    public static CompositeKeyValue of(CompositeKeyDef keyDef, int value1, int value2, int value3) {
        return new CompositeKeyValue(keyDef, value1, value2, value3);
    }

    /**
     * 获取有效列数（非 null 的列数）
     */
    public int getEffectiveColumnCount() {
        int count = 0;
        for (Object value : values) {
            if (value != null) {
                count++;
            } else {
                break; // 遇到 null 停止（前缀语义）
            }
        }
        return count;
    }

    /**
     * 是否为前缀键（不是所有列都有值）
     */
    public boolean isPrefixKey() {
        return getEffectiveColumnCount() < keyDef.getColumnCount();
    }

    /**
     * 获取列值
     */
    public Object getValue(int index) {
        return values[index];
    }

    /**
     * 获取整数列值
     */
    public Integer getIntValue(int index) {
        Object value = values[index];
        return value instanceof Integer ? (Integer) value : null;
    }

    /**
     * 获取长整数列值
     */
    public Long getLongValue(int index) {
        Object value = values[index];
        if (value instanceof Long) {
            return (Long) value;
        } else if (value instanceof Integer) {
            return ((Integer) value).longValue();
        }
        return null;
    }

    /**
     * 获取字符串列值
     */
    public String getStringValue(int index) {
        Object value = values[index];
        return value instanceof String ? (String) value : null;
    }

    /**
     * 获取字节数组列值
     */
    public byte[] getBytesValue(int index) {
        Object value = values[index];
        return value instanceof byte[] ? (byte[]) value : null;
    }

    /**
     * 编码为字节数组
     */
    public byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(keyDef.getMaxEncodedLength());

        for (int i = 0; i < keyDef.getColumnCount(); i++) {
            KeyColumn column = keyDef.getColumn(i);
            Object value = values[i];

            encodeColumn(buffer, column, value);
        }

        // 返回实际使用的字节
        byte[] result = new byte[buffer.position()];
        buffer.flip();
        buffer.get(result);
        return result;
    }

    /**
     * 编码单个列
     */
    private void encodeColumn(ByteBuffer buffer, KeyColumn column, Object value) {
        // 处理 NULL
        if (column.isNullable()) {
            if (value == null) {
                buffer.put(NULL_FLAG);
                return;
            } else {
                buffer.put(NOT_NULL_FLAG);
            }
        }

        // 如果值为 null 但列不可空，使用默认值
        if (value == null) {
            encodeDefaultValue(buffer, column);
            return;
        }

        // 根据类型编码
        switch (column.getType()) {
            case INT:
                int intVal = ((Number) value).intValue();
                // 降序时取反
                if (column.isDescending()) {
                    intVal = ~intVal;
                }
                buffer.putInt(intVal);
                break;

            case BIGINT:
                long longVal = ((Number) value).longValue();
                if (column.isDescending()) {
                    longVal = ~longVal;
                }
                buffer.putLong(longVal);
                break;

            case VARCHAR:
            case CHAR:
                byte[] strBytes = normalizeStringValue(value).getBytes(StandardCharsets.UTF_8);
                if (column.getType() == ColumnType.VARCHAR) {
                    // 变长：2字节长度 + 数据
                    buffer.putShort((short) strBytes.length);
                    buffer.put(strBytes);
                } else {
                    // 定长：填充到固定长度
                    buffer.put(strBytes);
                    for (int j = strBytes.length; j < column.getMaxLength(); j++) {
                        buffer.put((byte) 0);
                    }
                }
                break;

            case VARBINARY:
            case BINARY:
                byte[] binBytes = (byte[]) value;
                if (column.getType() == ColumnType.VARBINARY) {
                    buffer.putShort((short) binBytes.length);
                    buffer.put(binBytes);
                } else {
                    buffer.put(binBytes);
                    for (int j = binBytes.length; j < column.getMaxLength(); j++) {
                        buffer.put((byte) 0);
                    }
                }
                break;
        }
    }

    private String normalizeStringValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return String.valueOf(value);
    }

    /**
     * 编码默认值
     */
    private void encodeDefaultValue(ByteBuffer buffer, KeyColumn column) {
        switch (column.getType()) {
            case INT:
                buffer.putInt(0);
                break;
            case BIGINT:
                buffer.putLong(0L);
                break;
            case VARCHAR:
                buffer.putShort((short) 0);
                break;
            case CHAR:
            case BINARY:
                for (int i = 0; i < column.getMaxLength(); i++) {
                    buffer.put((byte) 0);
                }
                break;
            case VARBINARY:
                buffer.putShort((short) 0);
                break;
        }
    }

    /**
     * 从字节数组解码
     */
    public static CompositeKeyValue decode(CompositeKeyDef keyDef, byte[] encoded) {
        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        Object[] values = new Object[keyDef.getColumnCount()];

        for (int i = 0; i < keyDef.getColumnCount() && buffer.hasRemaining(); i++) {
            KeyColumn column = keyDef.getColumn(i);
            values[i] = decodeColumn(buffer, column);
        }

        return new CompositeKeyValue(keyDef, values);
    }

    /**
     * 解码单个列
     */
    private static Object decodeColumn(ByteBuffer buffer, KeyColumn column) {
        // 处理 NULL
        if (column.isNullable()) {
            byte nullFlag = buffer.get();
            if (nullFlag == NULL_FLAG) {
                return null;
            }
        }

        switch (column.getType()) {
            case INT:
                int intVal = buffer.getInt();
                if (column.isDescending()) {
                    intVal = ~intVal;
                }
                return intVal;

            case BIGINT:
                long longVal = buffer.getLong();
                if (column.isDescending()) {
                    longVal = ~longVal;
                }
                return longVal;

            case VARCHAR:
                int varLen = buffer.getShort() & 0xFFFF;
                byte[] varBytes = new byte[varLen];
                buffer.get(varBytes);
                return new String(varBytes, StandardCharsets.UTF_8);

            case CHAR:
                byte[] charBytes = new byte[column.getMaxLength()];
                buffer.get(charBytes);
                // 去除尾部的 0
                int actualLen = charBytes.length;
                while (actualLen > 0 && charBytes[actualLen - 1] == 0) {
                    actualLen--;
                }
                return new String(charBytes, 0, actualLen, StandardCharsets.UTF_8);

            case VARBINARY:
                int binLen = buffer.getShort() & 0xFFFF;
                byte[] binBytes = new byte[binLen];
                buffer.get(binBytes);
                return binBytes;

            case BINARY:
                byte[] fixedBytes = new byte[column.getMaxLength()];
                buffer.get(fixedBytes);
                return fixedBytes;

            default:
                return null;
        }
    }

    public CompositeKeyDef getKeyDef() {
        return keyDef;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(", ");
            Object value = values[i];
            if (value == null) {
                sb.append("NULL");
            } else if (value instanceof byte[]) {
                sb.append("bytes[").append(((byte[]) value).length).append("]");
            } else {
                sb.append(value);
            }
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CompositeKeyValue)) return false;
        CompositeKeyValue other = (CompositeKeyValue) obj;
        return Arrays.deepEquals(this.values, other.values);
    }

    @Override
    public int hashCode() {
        return Arrays.deepHashCode(values);
    }
}
