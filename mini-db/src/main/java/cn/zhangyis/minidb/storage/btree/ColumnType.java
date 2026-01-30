package cn.zhangyis.minidb.storage.btree;

/**
 * 列类型
 *
 * @author MiniDB
 * @version 1.0
 */
public enum ColumnType {

    /**
     * 32位整数
     */
    INT(4, true),

    /**
     * 64位整数
     */
    BIGINT(8, true),

    /**
     * 变长字符串
     */
    VARCHAR(-1, false),

    /**
     * 定长字符串
     */
    CHAR(-1, true),

    /**
     * 变长字节数组
     */
    VARBINARY(-1, false),

    /**
     * 定长字节数组
     */
    BINARY(-1, true);

    /** 固定长度（-1 表示变长） */
    private final int fixedLength;

    /** 是否定长 */
    private final boolean fixedSize;

    ColumnType(int fixedLength, boolean fixedSize) {
        this.fixedLength = fixedLength;
        this.fixedSize = fixedSize;
    }

    public int getFixedLength() {
        return fixedLength;
    }

    public boolean isFixedSize() {
        return fixedSize;
    }

    public boolean isVariableSize() {
        return !fixedSize;
    }

    public boolean isNumeric() {
        return this == INT || this == BIGINT;
    }

    public boolean isString() {
        return this == VARCHAR || this == CHAR;
    }

    public boolean isBinary() {
        return this == VARBINARY || this == BINARY;
    }
}
