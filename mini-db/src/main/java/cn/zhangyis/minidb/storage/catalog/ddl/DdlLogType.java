package cn.zhangyis.minidb.storage.catalog.ddl;

/**
 * DDL Log 记录类型
 *
 * <p>每种类型对应一种 Post-DDL / Recovery 时需要执行的清理操作。</p>
 *
 * <h3>类型说明</h3>
 * <ul>
 *   <li>{@link #FREE_TREE} — 删除索引树（释放 B+Tree root page 及其子页面）</li>
 *   <li>{@link #DELETE_SPACE} — 删除表空间文件（.ibd）</li>
 *   <li>{@link #REMOVE_CACHE} — 清除内存中的 Catalog 缓存</li>
 * </ul>
 */
public enum DdlLogType {

    /** 删除索引树 */
    FREE_TREE((byte) 1),

    /** 删除表空间文件 */
    DELETE_SPACE((byte) 2),

    /** 清除内存缓存 */
    REMOVE_CACHE((byte) 3);

    private final byte code;

    DdlLogType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    /**
     * 从持久化字节恢复枚举
     *
     * @param code 字节值
     * @return 对应的枚举值
     * @throws IllegalArgumentException 未知类型
     */
    public static DdlLogType fromCode(byte code) {
        for (DdlLogType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown DdlLogType code: " + code);
    }
}
