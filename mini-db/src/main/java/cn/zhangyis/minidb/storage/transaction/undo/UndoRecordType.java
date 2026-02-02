package cn.zhangyis.minidb.storage.transaction.undo;

/**
 * Undo 记录类型枚举
 *
 * <p>定义 InnoDB 兼容的 Undo Log 记录类型。</p>
 *
 * <h2>类型说明</h2>
 * <ul>
 *   <li><b>INSERT</b>: INSERT 操作的 Undo，回滚时删除记录</li>
 *   <li><b>UPDATE</b>: UPDATE 操作的 Undo，回滚时还原旧值</li>
 *   <li><b>DELETE_MARK</b>: DELETE 操作的 Undo，回滚时清除删除标记</li>
 * </ul>
 *
 * <h2>InnoDB 对应</h2>
 * <ul>
 *   <li>TRX_UNDO_INSERT_REC (0x0B = 11)</li>
 *   <li>TRX_UNDO_UPD_EXIST_REC (0x0C = 12)</li>
 *   <li>TRX_UNDO_DEL_MARK_REC (0x0D = 13)</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public enum UndoRecordType {

    /**
     * INSERT 操作的 Undo 记录
     *
     * <p>存储内容：主键列数据</p>
     * <p>回滚操作：DELETE WHERE pk = primary_key</p>
     * <p>MVCC：INSERT Undo 表示版本链终点（记录之前不存在）</p>
     */
    INSERT(0x0B, "TRX_UNDO_INSERT_REC"),

    /**
     * UPDATE 操作的 Undo 记录
     *
     * <p>存储内容：被修改列的旧值</p>
     * <p>回滚操作：UPDATE SET col=old_val WHERE pk=...</p>
     * <p>MVCC：用于构建历史版本</p>
     */
    UPDATE(0x0C, "TRX_UNDO_UPD_EXIST_REC"),

    /**
     * DELETE 操作的 Undo 记录
     *
     * <p>存储内容：完整的旧行数据</p>
     * <p>回滚操作：清除 delete_mark 标志，恢复行可见性</p>
     * <p>MVCC：用于读取已删除行的历史版本</p>
     *
     * <p>注意：DELETE 在 InnoDB 中是标记删除（设置 delete_flag），
     * 真正的物理删除由 Purge 线程在所有活跃事务都不需要该版本时执行。</p>
     */
    DELETE_MARK(0x0D, "TRX_UNDO_DEL_MARK_REC");

    // ==================== 字段 ====================

    /**
     * 类型码 (1 byte)
     */
    private final int code;

    /**
     * InnoDB 名称
     */
    private final String innodbName;

    // ==================== 构造函数 ====================

    UndoRecordType(int code, String innodbName) {
        this.code = code;
        this.innodbName = innodbName;
    }

    // ==================== 访问方法 ====================

    /**
     * 获取类型码
     *
     * @return 1 字节类型码
     */
    public int getCode() {
        return code;
    }

    /**
     * 获取类型码 (byte)
     *
     * @return 类型码字节
     */
    public byte getCodeByte() {
        return (byte) code;
    }

    /**
     * 获取 InnoDB 名称
     *
     * @return InnoDB 中的宏名称
     */
    public String getInnodbName() {
        return innodbName;
    }

    /**
     * 是否是 INSERT 类型
     *
     * @return true 如果是 INSERT
     */
    public boolean isInsert() {
        return this == INSERT;
    }

    /**
     * 是否是 UPDATE 类型
     *
     * @return true 如果是 UPDATE
     */
    public boolean isUpdate() {
        return this == UPDATE;
    }

    /**
     * 是否是 DELETE 类型
     *
     * @return true 如果是 DELETE_MARK
     */
    public boolean isDelete() {
        return this == DELETE_MARK;
    }

    /**
     * 是否需要存入 UPDATE Undo Segment
     *
     * <p>UPDATE 和 DELETE_MARK 都存入 UPDATE Undo Segment，
     * 因为它们都需要保留用于 MVCC 版本链。</p>
     *
     * <p>INSERT Undo 存入单独的 INSERT Undo Segment，
     * 因为事务提交后可以立即释放（不需要 MVCC）。</p>
     *
     * @return true 如果是 UPDATE 或 DELETE_MARK
     */
    public boolean isUpdateUndo() {
        return this == UPDATE || this == DELETE_MARK;
    }

    // ==================== 静态方法 ====================

    /**
     * 从类型码获取枚举
     *
     * @param code 类型码
     * @return 枚举值
     * @throws IllegalArgumentException 如果类型码未知
     */
    public static UndoRecordType fromCode(int code) {
        for (UndoRecordType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException(
                String.format("Unknown undo record type code: 0x%02X", code));
    }

    /**
     * 从类型码获取枚举 (byte)
     *
     * @param code 类型码字节
     * @return 枚举值
     */
    public static UndoRecordType fromCode(byte code) {
        return fromCode(code & 0xFF);
    }

    @Override
    public String toString() {
        return String.format("%s(0x%02X)", name(), code);
    }
}
