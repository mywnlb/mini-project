package cn.zhangyis.minidb.storage.record;

/**
 * 字段类型枚举
 * 
 * <p>定义 InnoDB 支持的基本数据类型。每种类型对应不同的存储方式和比较语义。</p>
 * 
 * <h2>类型分类</h2>
 * <ul>
 *   <li><b>整型</b>: TINYINT, SMALLINT, INT, BIGINT - 定长，数值比较</li>
 *   <li><b>字符串</b>: CHAR, VARCHAR - CHAR 定长，VARCHAR 变长，字典序比较</li>
 *   <li><b>二进制</b>: BINARY, VARBINARY - 类似字符串，按字节比较</li>
 * </ul>
 * 
 * @author MiniDB
 * @version 1.0
 */
public enum FieldKind {
    
    // ==================== 整型 ====================
    
    /** 1 字节有符号整数 (-128 ~ 127) */
    TINYINT(1, true),
    
    /** 2 字节有符号整数 (-32768 ~ 32767) */
    SMALLINT(2, true),
    
    /** 4 字节有符号整数 */
    INT(4, true),
    
    /** 8 字节有符号整数 */
    BIGINT(8, true),
    
    // ==================== 字符串 ====================
    
    /** 定长字符串，长度由 FieldType.fixedLength 指定 */
    CHAR(-1, true),
    
    /** 变长字符串，最大长度由 FieldType.maxLength 指定 */
    VARCHAR(-1, false),
    
    // ==================== 二进制 ====================
    
    /** 定长二进制，长度由 FieldType.fixedLength 指定 */
    BINARY(-1, true),
    
    /** 变长二进制，最大长度由 FieldType.maxLength 指定 */
    VARBINARY(-1, false);
    
    // ==================== 实例字段 ====================
    
    /**
     * 固定字节长度
     * <p>对于整型，为固定字节数；对于 CHAR/VARCHAR，为 -1（由 FieldType 指定）</p>
     */
    private final int fixedByteLength;
    
    /**
     * 是否定长类型
     * <p>定长类型在 COMPACT 格式中不需要存储长度</p>
     */
    private final boolean fixedLength;
    
    FieldKind(int fixedByteLength, boolean fixedLength) {
        this.fixedByteLength = fixedByteLength;
        this.fixedLength = fixedLength;
    }
    
    /**
     * 获取固定字节长度
     * 
     * @return 对于整型返回固定长度，对于字符串/二进制返回 -1
     */
    public int getFixedByteLength() {
        return fixedByteLength;
    }
    
    /**
     * 是否为定长类型
     * 
     * @return 定长返回 true
     */
    public boolean isFixedLength() {
        return fixedLength;
    }
    
    /**
     * 是否为整型
     * 
     * @return 如果是 TINYINT/SMALLINT/INT/BIGINT 返回 true
     */
    public boolean isInteger() {
        return this == TINYINT || this == SMALLINT || this == INT || this == BIGINT;
    }
    
    /**
     * 是否为字符串类型
     * 
     * @return 如果是 CHAR/VARCHAR 返回 true
     */
    public boolean isString() {
        return this == CHAR || this == VARCHAR;
    }
    
    /**
     * 是否为二进制类型
     * 
     * @return 如果是 BINARY/VARBINARY 返回 true
     */
    public boolean isBinary() {
        return this == BINARY || this == VARBINARY;
    }
}
