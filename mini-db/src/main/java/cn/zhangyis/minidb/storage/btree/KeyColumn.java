package cn.zhangyis.minidb.storage.btree;

/**
 * 复合键列定义
 *
 * <p>定义复合键中单个列的属性。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class KeyColumn {

    /** 列名 */
    private final String name;

    /** 列类型 */
    private final ColumnType type;

    /** 最大长度（用于变长类型） */
    private final int maxLength;

    /** 是否允许 NULL */
    private final boolean nullable;

    /** 是否降序 */
    private final boolean descending;

    /**
     * 构造列定义
     *
     * @param name       列名
     * @param type       列类型
     * @param maxLength  最大长度
     * @param nullable   是否允许 NULL
     * @param descending 是否降序
     */
    public KeyColumn(String name, ColumnType type, int maxLength, boolean nullable, boolean descending) {
        this.name = name;
        this.type = type;
        this.maxLength = maxLength;
        this.nullable = nullable;
        this.descending = descending;
    }

    /**
     * 创建整数列
     */
    public static KeyColumn intColumn(String name) {
        return new KeyColumn(name, ColumnType.INT, 4, false, false);
    }

    /**
     * 创建可空整数列
     */
    public static KeyColumn nullableIntColumn(String name) {
        return new KeyColumn(name, ColumnType.INT, 4, true, false);
    }

    /**
     * 创建长整数列
     */
    public static KeyColumn bigintColumn(String name) {
        return new KeyColumn(name, ColumnType.BIGINT, 8, false, false);
    }

    /**
     * 创建变长字符串列
     */
    public static KeyColumn varcharColumn(String name, int maxLength) {
        return new KeyColumn(name, ColumnType.VARCHAR, maxLength, false, false);
    }

    /**
     * 创建可空变长字符串列
     */
    public static KeyColumn nullableVarcharColumn(String name, int maxLength) {
        return new KeyColumn(name, ColumnType.VARCHAR, maxLength, true, false);
    }

    /**
     * 创建定长字符串列
     */
    public static KeyColumn charColumn(String name, int length) {
        return new KeyColumn(name, ColumnType.CHAR, length, false, false);
    }

    /**
     * 创建变长字节数组列
     */
    public static KeyColumn varbinaryColumn(String name, int maxLength) {
        return new KeyColumn(name, ColumnType.VARBINARY, maxLength, false, false);
    }

    /**
     * 创建降序列
     */
    public KeyColumn desc() {
        return new KeyColumn(name, type, maxLength, nullable, true);
    }

    /**
     * 创建可空列
     */
    public KeyColumn nullable() {
        return new KeyColumn(name, type, maxLength, true, descending);
    }

    // ==================== Getters ====================

    public String getName() {
        return name;
    }

    public ColumnType getType() {
        return type;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public boolean isNullable() {
        return nullable;
    }

    public boolean isDescending() {
        return descending;
    }

    /**
     * 获取编码后的最大字节数
     */
    public int getMaxEncodedLength() {
        int baseLength;
        if (type.isFixedSize() && type.getFixedLength() > 0) {
            baseLength = type.getFixedLength();
        } else {
            // 变长类型：2字节长度前缀 + 数据
            baseLength = 2 + maxLength;
        }
        // 如果可空，加1字节 NULL 标记
        return nullable ? baseLength + 1 : baseLength;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" ").append(type);
        if (type.isVariableSize()) {
            sb.append("(").append(maxLength).append(")");
        }
        if (nullable) {
            sb.append(" NULL");
        }
        if (descending) {
            sb.append(" DESC");
        }
        return sb.toString();
    }
}
