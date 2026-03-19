package cn.zhangyis.minidb.storage.record.schema;

import cn.zhangyis.minidb.storage.record.logical.DataField;
import cn.zhangyis.minidb.storage.record.logical.DataTuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Schema 注册表
 *
 * <p>管理所有 rowVersion 对应的 RecordSchema 快照，以及 Instant DDL 列元数据。</p>
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>I5: rowVersion 从记录 peek，不由调用者传入</li>
 *   <li>I6: Instant 默认值按列级 introducedVersion + columnId 填充</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class SchemaRegistry {

    /**
     * rowVersion → RecordSchema 映射
     */
    private final Map<Integer, RecordSchema> schemas = new ConcurrentHashMap<>();

    /**
     * 当前最新 Schema
     */
    private volatile RecordSchema currentSchema;

    /**
     * Instant 列元数据列表。
     *
     * <p>使用 CopyOnWriteArrayList 保证读路径（fillInstantDefaults）在无锁遍历时
     * 不会因 DDL 写路径并发 add 而抛出 ConcurrentModificationException。
     * DDL 写入频率极低（仅 ALTER TABLE ADD COLUMN），读路径高频，COW 语义合适。</p>
     */
    private final List<InstantColumnMeta> instantColumns = new CopyOnWriteArrayList<>();

    /**
     * 创建空注册表
     */
    public SchemaRegistry() {
    }

    /**
     * 使用初始 Schema 创建注册表
     *
     * @param initialSchema 初始 Schema
     */
    public SchemaRegistry(RecordSchema initialSchema) {
        register(initialSchema);
        this.currentSchema = initialSchema;
    }

    // ==================== Schema 管理 ====================

    /**
     * 注册 Schema 版本
     *
     * @param schema 要注册的 Schema
     */
    public void register(RecordSchema schema) {
        schemas.put(schema.getVersion(), schema);
        if (currentSchema == null || schema.getVersion() > currentSchema.getVersion()) {
            currentSchema = schema;
        }
    }

    /**
     * 获取指定版本的 Schema
     *
     * @param version rowVersion
     * @return RecordSchema
     * @throws IllegalArgumentException 如果版本不存在
     */
    public RecordSchema get(int version) {
        RecordSchema schema = schemas.get(version);
        if (schema == null) {
            if (currentSchema != null) {
                // 兼容旧测试数据、旧行版本或 Instant DDL 场景，退化为当前 schema
                return currentSchema;
            }
            throw new IllegalArgumentException("Unknown schema version: " + version);
        }
        return schema;
    }

    /**
     * 获取当前最新 Schema
     *
     * @return 当前 RecordSchema
     */
    public RecordSchema getCurrentSchema() {
        return currentSchema;
    }

    /**
     * 设置当前 Schema
     *
     * @param schema 当前 Schema
     */
    public void setCurrentSchema(RecordSchema schema) {
        register(schema);
        this.currentSchema = schema;
    }

    // ==================== Instant DDL 支持 ====================

    /**
     * 添加 Instant 列元数据
     *
     * @param meta Instant 列元数据
     */
    public void addInstantColumn(InstantColumnMeta meta) {
        instantColumns.add(meta);
    }

    /**
     * 获取所有 Instant 列
     *
     * @return Instant 列列表
     */
    public List<InstantColumnMeta> getInstantColumns() {
        return new ArrayList<>(instantColumns);
    }

    /**
     * 填充 Instant 默认值
     *
     * <p>I6: 按列级 introducedVersion + columnId 填充</p>
     *
     * @param tuple      原始 Tuple
     * @param rowVersion 记录的 rowVersion
     * @return 填充后的 Tuple
     */
    public DataTuple fillInstantDefaults(DataTuple tuple, int rowVersion) {
        if (currentSchema == null) {
            return tuple;
        }

        // 如果记录版本已经是最新的，无需填充
        if (rowVersion >= currentSchema.getVersion()) {
            return tuple;
        }

        // I-EXPAND: 旧行字段数可能 < currentSchema 列数，需要扩展 tuple
        int requiredFields = currentSchema.getColumnCount();
        if (tuple.getFieldCount() < requiredFields) {
            DataTuple expanded = DataTuple.create(requiredFields);
            for (int i = 0; i < tuple.getFieldCount(); i++) {
                expanded.setField(i, tuple.getField(i));
            }
            expanded.setInfoBits(tuple.getInfoBits());
            tuple = expanded;
        }

        // 遍历 Instant 列，按列的 introducedVersion 判断是否需要填充
        for (InstantColumnMeta meta : instantColumns) {
            if (meta.introducedVersion() > rowVersion) {
                // 该列在 rowVersion 之后引入，需要填充默认值
                // 通过 columnId 找到当前 schema 中的 index
                int index = currentSchema.indexOfColumn(meta.columnId());
                if (index >= 0 && index < tuple.getFieldCount()) {
                    DataField currentField = tuple.getField(index);
                    // 只有当字段为 null（未填充）时才填充
                    if (currentField == null) {
                        tuple.setField(index, meta.getDefaultValue());
                    }
                }
            }
        }

        return tuple;
    }

    /**
     * 执行 Instant ADD COLUMN
     *
     * <p>创建新版本的 Schema 并注册 Instant 列元数据</p>
     *
     * @param column       新列描述符
     * @param defaultValue 默认值
     * @return 新版本的 Schema
     */
    public RecordSchema addColumnInstant(ColumnDescriptor column, DataField defaultValue) {
        if (currentSchema == null) {
            throw new IllegalStateException("No current schema");
        }

        // 创建新版本 Schema
        int newVersion = currentSchema.getVersion() + 1;
        RecordSchema.Builder builder = RecordSchema.builder().version(newVersion);

        // 复制现有列
        for (ColumnDescriptor existingCol : currentSchema.getColumns()) {
            builder.column(existingCol);
        }

        // 添加新列
        builder.column(column);
        RecordSchema newSchema = builder.build();

        // 注册 Instant 列元数据
        InstantColumnMeta meta = InstantColumnMeta.of(
            column.getColumnId(),
            newVersion,
            defaultValue,
            column.getType()
        );
        addInstantColumn(meta);

        // 更新注册表
        register(newSchema);
        this.currentSchema = newSchema;

        return newSchema;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SchemaRegistry{\n");
        sb.append("  current: v").append(currentSchema != null ? currentSchema.getVersion() : "none").append("\n");
        sb.append("  versions: ").append(schemas.keySet()).append("\n");
        sb.append("  instantColumns: ").append(instantColumns.size()).append("\n");
        sb.append("}");
        return sb.toString();
    }
}
