package cn.zhangyis.minidb.storage.redo.record;

/**
 * Redo Log 记录类型
 *
 * <p>定义 Redo Log 中各种记录的类型。对应 InnoDB 的 MLOG_* 类型。</p>
 *
 * <h2>Phase 1-2 实现的类型</h2>
 * <ul>
 *   <li><b>MLOG_WRITE_BYTES</b>: 页内字节修改 (主要类型)</li>
 *   <li><b>MLOG_MULTI_REC_END</b>: Redo Group 结束标记 (必须)</li>
 *   <li><b>MLOG_FULL_PAGE</b>: 整页 redo (仅用于调试/特殊场景)</li>
 * </ul>
 *
 * <h2>未来扩展</h2>
 * <ul>
 *   <li>MLOG_REC_INSERT: B+Tree 记录插入</li>
 *   <li>MLOG_REC_UPDATE_IN_PLACE: 原地更新</li>
 *   <li>MLOG_PAGE_CREATE: 页面创建</li>
 *   <li>MLOG_FILE_CREATE: 文件创建</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public enum RedoRecordType {

    /**
     * 页内字节修改 (Phase 1-2 主要类型)
     *
     * <p>记录对页面内特定区域的修改 (offset + length + data)。</p>
     *
     * <h3>格式</h3>
     * <pre>
     * type(1B) | space_id(4B) | page_no(4B) | data_len(2B)
     *   | offset(2B) | length(2B) | data(N bytes)
     * </pre>
     *
     * <h3>示例</h3>
     * <p>修改 Page 100 的偏移 38 处的 4 字节：</p>
     * <pre>
     * type=1 | space_id=1 | page_no=100 | data_len=8
     *   | offset=38 | length=4 | data=[0x64,0x00,0x00,0x00]
     * 总大小: 11 + 8 = 19 bytes (而非整页 16KB)
     * </pre>
     */
    MLOG_WRITE_BYTES(1),

    /**
     * Redo Group 结束标记 (必须)
     *
     * <p>标记一个 MTR 的 redo records 组结束。
     * 恢复时用于识别原子边界和安全截断损坏的尾部。</p>
     *
     * <h3>格式</h3>
     * <pre>
     * type(1B)  // 只有 1 字节
     * </pre>
     *
     * <h3>用途</h3>
     * <ul>
     *   <li>标识 MTR 原子边界</li>
     *   <li>恢复时按组解析</li>
     *   <li>遇到损坏时截断到最后一个完整组</li>
     * </ul>
     */
    MLOG_MULTI_REC_END(31),

    /**
     * 整页 Redo (兜底机制，不推荐)
     *
     * <p>记录整个页面的 16KB 数据。
     * 仅用于调试、特殊页面格式化等场景，不应作为常规 redo 类型。</p>
     *
     * <h3>格式</h3>
     * <pre>
     * type(1B) | space_id(4B) | page_no(4B) | data_len(2B)
     *   | page_data(16384 bytes)
     * </pre>
     *
     * <h3>缺点</h3>
     * <ul>
     *   <li>Redo 体积巨大 (16KB+)</li>
     *   <li>恢复写放大严重</li>
     *   <li>无法达到性能目标</li>
     * </ul>
     */
    MLOG_FULL_PAGE(255);

    // ==================== 字段 ====================

    /** 类型值 (1 byte) */
    private final int value;

    // ==================== 构造函数 ====================

    RedoRecordType(int value) {
        assert value >= 0 && value <= 255 : "Redo record type must fit in 1 byte";
        this.value = value;
    }

    // ==================== 方法 ====================

    /**
     * 获取类型值
     *
     * @return 类型值 (0-255)
     */
    public int getValue() {
        return value;
    }

    /**
     * 从类型值获取枚举
     *
     * @param value 类型值
     * @return 对应的 RedoRecordType
     * @throws IllegalArgumentException 如果类型值未知
     */
    public static RedoRecordType fromValue(int value) {
        for (RedoRecordType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown redo record type: " + value);
    }

    /**
     * 检查是否是 data redo 类型 (需要 page_id)
     *
     * @return true 如果此类型需要 page_id
     */
    public boolean isDataRedo() {
        return this != MLOG_MULTI_REC_END;
    }

    @Override
    public String toString() {
        return name() + "(" + value + ")";
    }
}
