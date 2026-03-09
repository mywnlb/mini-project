package cn.zhangyis.minidb.storage.catalog.ddl;

/**
 * DDL Log replay 条件。
 *
 * <p>首版 atomic DDL 只支持基于 catalog 持久状态的 replay 判定。</p>
 */
public enum DdlReplayGuard {

    /**
     * 仅当 catalog 中不存在目标 tableId 时才执行 replay。
     */
    TABLE_ABSENT((byte) 1);

    private final byte code;

    DdlReplayGuard(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static DdlReplayGuard fromCode(byte code) {
        for (DdlReplayGuard guard : values()) {
            if (guard.code == code) {
                return guard;
            }
        }
        throw new IllegalArgumentException("Unknown DdlReplayGuard code: " + code);
    }
}
