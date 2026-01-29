package cn.zhangyis.minidb.storage.record.schema;

/**
 * 字段类型枚举
 *
 * <p>定义存储引擎支持的基本数据类型，参考 InnoDB 的 dtype_t。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public enum FieldKind {

    // ==================== 整型 ====================

    /**
     * TINYINT (1 byte, -128 ~ 127)
     */
    TINYINT(1, true, false),

    /**
     * SMALLINT (2 bytes, -32768 ~ 32767)
     */
    SMALLINT(2, true, false),

    /**
     * INT (4 bytes)
     */
    INT(4, true, false),

    /**
     * BIGINT (8 bytes)
     */
    BIGINT(8, true, false),

    // ==================== 字符串 ====================

    /**
     * CHAR (定长字符串)
     * <p>存储时按声明长度填充空格</p>
     */
    CHAR(0, true, false),

    /**
     * VARCHAR (变长字符串)
     * <p>最大长度 65535 字节</p>
     */
    VARCHAR(0, false, true),

    // ==================== 二进制 ====================

    /**
     * BINARY (定长二进制)
     */
    BINARY(0, true, false),

    /**
     * VARBINARY (变长二进制)
     */
    VARBINARY(0, false, true),

    // ==================== 大对象 ====================

    /**
     * BLOB (变长二进制大对象)
     */
    BLOB(0, false, true),

    /**
     * TEXT (变长文本大对象)
     */
    TEXT(0, false, true);

    /**
     * 固定字节长度（0 表示需要额外指定长度）
     */
    private final int fixedLength;

    /**
     * 是否定长类型
     */
    private final boolean fixedSize;

    /**
     * 是否变长类型（需要在 varlen list 中记录长度）
     */
    private final boolean variable;

    FieldKind(int fixedLength, boolean fixedSize, boolean variable) {
        this.fixedLength = fixedLength;
        this.fixedSize = fixedSize;
        this.variable = variable;
    }

    /**
     * 获取固定长度
     *
     * @return 固定长度，0 表示需要额外指定
     */
    public int getFixedLength() {
        return fixedLength;
    }

    /**
     * 是否定长类型
     *
     * @return true 如果是定长类型
     */
    public boolean isFixedSize() {
        return fixedSize;
    }

    /**
     * 是否变长类型
     *
     * @return true 如果是变长类型
     */
    public boolean isVariable() {
        return variable;
    }

    /**
     * 是否整型
     *
     * @return true 如果是整型
     */
    public boolean isInteger() {
        return this == TINYINT || this == SMALLINT || this == INT || this == BIGINT;
    }

    /**
     * 是否字符串类型
     *
     * @return true 如果是字符串类型
     */
    public boolean isString() {
        return this == CHAR || this == VARCHAR || this == TEXT;
    }

    /**
     * 是否二进制类型
     *
     * @return true 如果是二进制类型
     */
    public boolean isBinary() {
        return this == BINARY || this == VARBINARY || this == BLOB;
    }

    /**
     * 是否大对象类型
     *
     * @return true 如果是 BLOB 或 TEXT
     */
    public boolean isLob() {
        return this == BLOB || this == TEXT;
    }
}
