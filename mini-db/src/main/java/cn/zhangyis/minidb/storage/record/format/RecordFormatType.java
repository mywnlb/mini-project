package cn.zhangyis.minidb.storage.record.format;

/**
 * 记录格式类型枚举
 *
 * <p>对应 InnoDB 的 ROW_FORMAT 选项。</p>
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>I4: row format 是 index 级元数据，不是每行切换</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public enum RecordFormatType {

    /**
     * Redundant 格式 (MySQL 4.0)
     * <p>每行存储完整的列长度信息，兼容性最好但空间效率低。</p>
     */
    REDUNDANT(0, "Redundant"),

    /**
     * Compact 格式 (MySQL 5.0 默认)
     * <p>优化存储空间，使用变长字段长度列表和 NULL bitmap。</p>
     */
    COMPACT(1, "Compact"),

    /**
     * Dynamic 格式 (MySQL 5.7 默认)
     * <p>基于 Compact，大字段完全存储在溢出页。</p>
     */
    DYNAMIC(2, "Dynamic"),

    /**
     * Compressed 格式
     * <p>支持页级压缩，暂不实现。</p>
     */
    COMPRESSED(3, "Compressed");

    private final int code;
    private final String name;

    RecordFormatType(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public int getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    /**
     * 根据 code 获取类型
     */
    public static RecordFormatType fromCode(int code) {
        for (RecordFormatType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown format code: " + code);
    }

    /**
     * 是否支持溢出存储
     */
    public boolean supportsOverflow() {
        return this == DYNAMIC || this == COMPRESSED;
    }
}
