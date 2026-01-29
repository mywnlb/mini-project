package cn.zhangyis.minidb.storage.record.schema;

import cn.zhangyis.minidb.storage.record.logical.DataField;

/**
 * Instant 列元数据
 *
 * <p>描述通过 Instant DDL 添加的列的元信息，用于读取旧版本记录时填充默认值。</p>
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>F5: 以 columnId 为主键，columnIndex 只在 RecordSchema 快照内有效</li>
 *   <li>I6: Instant 默认值按列级 introducedVersion + columnId 填充</li>
 * </ul>
 *
 * @param columnId          列 ID（长期稳定标识，主键）
 * @param introducedVersion 引入该列的 rowVersion
 * @param defaultBytes      默认值序列化（null 表示 NULL 默认值）
 * @param type              字段类型（用于反序列化默认值）
 *
 * @author MiniDB
 * @version 1.0
 */
public record InstantColumnMeta(
    long columnId,
    int introducedVersion,
    byte[] defaultBytes,
    FieldType type
) {

    /**
     * 获取默认值字段
     *
     * @return 默认值 DataField
     */
    public DataField getDefaultValue() {
        if (defaultBytes == null) {
            return DataField.nullField(type);
        }
        return DataField.deserialize(defaultBytes, type);
    }

    /**
     * 创建 Instant 列元数据
     *
     * @param columnId          列 ID
     * @param introducedVersion 引入版本
     * @param defaultValue      默认值
     * @param type              字段类型
     * @return InstantColumnMeta 实例
     */
    public static InstantColumnMeta of(long columnId, int introducedVersion,
                                       DataField defaultValue, FieldType type) {
        byte[] defaultBytes = (defaultValue == null || defaultValue.isNull())
            ? null
            : defaultValue.getData();
        return new InstantColumnMeta(columnId, introducedVersion, defaultBytes, type);
    }

    /**
     * 创建 NULL 默认值的 Instant 列
     */
    public static InstantColumnMeta nullDefault(long columnId, int introducedVersion, FieldType type) {
        return new InstantColumnMeta(columnId, introducedVersion, null, type);
    }

    @Override
    public String toString() {
        return String.format("InstantColumn{id=%d, ver=%d, type=%s}",
            columnId, introducedVersion, type);
    }
}
