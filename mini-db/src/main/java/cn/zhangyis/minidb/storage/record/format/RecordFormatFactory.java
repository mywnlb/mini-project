package cn.zhangyis.minidb.storage.record.format;

/**
 * 记录格式工厂
 *
 * <p>根据格式类型创建对应的 RecordFormat 实例。</p>
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>I4: row format 是 index 级元数据，不是每行切换</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class RecordFormatFactory {

    /**
     * 默认格式（Compact）
     */
    private static final RecordFormat COMPACT = new CompactRecordFormat();

    /**
     * 根据格式类型创建 RecordFormat
     *
     * @param type 格式类型
     * @return RecordFormat 实例
     */
    public static RecordFormat create(RecordFormatType type) {
        return switch (type) {
            case COMPACT -> COMPACT;
            case DYNAMIC -> new DynamicRecordFormat();
            case REDUNDANT -> throw new UnsupportedOperationException("REDUNDANT format not yet implemented");
            case COMPRESSED -> throw new UnsupportedOperationException("COMPRESSED format not supported");
        };
    }

    /**
     * 获取默认格式 (Compact)
     *
     * @return CompactRecordFormat 实例
     */
    public static RecordFormat getDefault() {
        return COMPACT;
    }

    /**
     * 根据格式 code 创建 RecordFormat
     *
     * @param code 格式 code
     * @return RecordFormat 实例
     */
    public static RecordFormat fromCode(int code) {
        return create(RecordFormatType.fromCode(code));
    }
}
