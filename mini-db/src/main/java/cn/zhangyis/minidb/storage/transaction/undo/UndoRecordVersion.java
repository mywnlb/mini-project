package cn.zhangyis.minidb.storage.transaction.undo;

/**
 * Undo 记录版本管理
 *
 * <p>用于支持 Undo 记录格式的演进，实现新旧格式混读。</p>
 *
 * <h2>版本历史</h2>
 * <ul>
 *   <li><b>V1 (0x01)</b>：原始格式，存储所有列的旧值</li>
 *   <li><b>V2 (0x02)</b>：增量格式，只存储修改的列（TLV 格式）</li>
 * </ul>
 *
 * <h2>格式升级</h2>
 * <pre>
 * V1 格式（原始）：
 * ┌──────┬──────┬─────────┬──────────┬───────────┬─────────────────┐
 * │ type │ len  │ trx_id  │ table_id │ prev_undo │ pk + all_columns│
 * │ 0x0C │ (2B) │ (6B)    │ (4B)     │ (7B)      │ (variable)      │
 * └──────┴──────┴─────────┴──────────┴───────────┴─────────────────┘
 *
 * V2 格式（增量）：
 * ┌──────┬──────┬─────────┬──────────┬───────────┬──────┬──────┬──────────────────┐
 * │ type │ len  │ trx_id  │ table_id │ prev_undo │ fmt  │ sch  │ pk + changed_cols│
 * │ 0x0C │ (2B) │ (6B)    │ (4B)     │ (7B)      │ (1B) │ (1B) │ (variable)       │
 * └──────┴──────┴─────────┴──────────┴───────────┴──────┴──────┴──────────────────┘
 * </pre>
 * </p>
 *
 * <h2>设计约束</h2>
 * <ul>
 *   <li><b>V1</b>：向后兼容，V2 必须能读取 V1 格式</li>
 *   <li><b>V2</b>：新旧格式混读，严格按 version 分支</li>
 *   <li><b>V3</b>：Schema 变更时需要转换逻辑</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class UndoRecordVersion {

    // ==================== 版本常量 ====================

    /**
     * 原始格式版本（存储所有列）
     */
    public static final byte FORMAT_V1 = 0x01;

    /**
     * 增量格式版本（只存储修改的列）
     */
    public static final byte FORMAT_V2 = 0x02;

    /**
     * 当前默认格式版本
     */
    public static final byte CURRENT_FORMAT_VERSION = FORMAT_V2;

    /**
     * 最小支持的格式版本
     */
    public static final byte MIN_SUPPORTED_VERSION = FORMAT_V1;

    /**
     * 最大支持的格式版本
     */
    public static final byte MAX_SUPPORTED_VERSION = FORMAT_V2;

    // ==================== Schema 版本常量 ====================

    /**
     * 初始 Schema 版本
     */
    public static final byte INITIAL_SCHEMA_VERSION = 0x00;

    /**
     * 最大 Schema 版本
     */
    public static final byte MAX_SCHEMA_VERSION = (byte) 0xFF;

    // ==================== 私有构造函数 ====================

    private UndoRecordVersion() {
        throw new UnsupportedOperationException("Utility class");
    }

    // ==================== 版本检查 ====================

    /**
     * 检查格式版本是否有效
     *
     * @param formatVersion 格式版本
     * @return 如果有效返回 true
     */
    public static boolean isValidFormatVersion(byte formatVersion) {
        return formatVersion >= MIN_SUPPORTED_VERSION && formatVersion <= MAX_SUPPORTED_VERSION;
    }

    /**
     * 检查 Schema 版本是否有效
     *
     * @param schemaVersion Schema 版本
     * @return 如果有效返回 true
     */
    public static boolean isValidSchemaVersion(byte schemaVersion) {
        return schemaVersion >= INITIAL_SCHEMA_VERSION && schemaVersion <= MAX_SCHEMA_VERSION;
    }

    /**
     * 检查是否为增量格式
     *
     * @param formatVersion 格式版本
     * @return 如果是增量格式返回 true
     */
    public static boolean isIncrementalFormat(byte formatVersion) {
        return formatVersion == FORMAT_V2;
    }

    /**
     * 检查是否为原始格式
     *
     * @param formatVersion 格式版本
     * @return 如果是原始格式返回 true
     */
    public static boolean isOriginalFormat(byte formatVersion) {
        return formatVersion == FORMAT_V1;
    }

    /**
     * 获取格式版本名称
     *
     * @param formatVersion 格式版本
     * @return 版本名称
     */
    public static String getFormatVersionName(byte formatVersion) {
        return switch (formatVersion) {
            case FORMAT_V1 -> "V1_ORIGINAL";
            case FORMAT_V2 -> "V2_INCREMENTAL";
            default -> "UNKNOWN";
        };
    }

    /**
     * 获取版本信息字符串
     *
     * @param formatVersion 格式版本
     * @param schemaVersion Schema 版本
     * @return 版本信息字符串
     */
    public static String getVersionInfo(byte formatVersion, byte schemaVersion) {
        return String.format("Format=%s(0x%02X), Schema=0x%02X",
                getFormatVersionName(formatVersion), formatVersion, schemaVersion);
    }
}
