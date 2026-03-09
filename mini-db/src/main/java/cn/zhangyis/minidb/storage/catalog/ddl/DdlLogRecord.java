package cn.zhangyis.minidb.storage.catalog.ddl;

/**
 * DDL Log 记录（内存数据结构）
 *
 * <p>对应 DDL Log 页面中的一条 entry。所有字段在构造后不可变。</p>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>ddlOpId — DDL 操作 ID，同一次 DDL 的多条记录共享相同的 ddlOpId</li>
 *   <li>logType — 记录类型（首版只使用 DELETE_SPACE）</li>
 *   <li>replayGuard — recovery 时何时允许 replay</li>
 *   <li>spaceId — 目标表空间 ID</li>
 *   <li>tableId — 目标表 ID</li>
 *   <li>indexId — 预留字段；file-per-table 首版固定为 0</li>
 * </ul>
 *
 * <h3>页面序列化大小</h3>
 * <pre>
 * entryLen(4) + ddlOpId(8) + logType(1) + replayGuard(1) + reserved(2)
 * + spaceId(4) + tableId(8) + indexId(8) = 36 bytes
 * </pre>
 */
public record DdlLogRecord(
        long ddlOpId,
        DdlLogType logType,
        DdlReplayGuard replayGuard,
        int spaceId,
        long tableId,
        long indexId
) {

    /** 序列化后的 payload 大小（不含 entryLen 前缀） */
    public static final int PAYLOAD_SIZE = 8 + 1 + 1 + 2 + 4 + 8 + 8; // 32

    /** 序列化后的总大小（含 entryLen 前缀） */
    public static final int SERIALIZED_SIZE = 4 + PAYLOAD_SIZE; // 36

    /**
     * 创建 DELETE_SPACE 类型记录
     */
    public static DdlLogRecord deleteSpace(long ddlOpId, int spaceId, long tableId) {
        return new DdlLogRecord(
                ddlOpId,
                DdlLogType.DELETE_SPACE,
                DdlReplayGuard.TABLE_ABSENT,
                spaceId,
                tableId,
                0
        );
    }
}
