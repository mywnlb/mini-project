package cn.zhangyis.minidb.storage.record.reader;

import cn.zhangyis.minidb.storage.record.format.FieldOffsets;
import cn.zhangyis.minidb.storage.record.format.RecordFormat;
import cn.zhangyis.minidb.storage.record.format.RecordFormatFactory;
import cn.zhangyis.minidb.storage.record.logical.DataTuple;
import cn.zhangyis.minidb.storage.record.physical.SystemLayout;
import cn.zhangyis.minidb.storage.record.schema.RecordSchema;
import cn.zhangyis.minidb.storage.record.schema.SchemaRegistry;

import java.nio.ByteBuffer;

/**
 * 统一记录读取器
 *
 * <p>封装记录读取的完整流程，包括版本检测和 Instant 默认值填充。</p>
 *
 * <h2>读取流程</h2>
 * <ol>
 *   <li>peekRowVersion - O(1) 读取版本</li>
 *   <li>获取对应版本的 Schema</li>
 *   <li>解析字段偏移表</li>
 *   <li>解码用户字段</li>
 *   <li>填充 Instant 默认值</li>
 * </ol>
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>I4: row format 由 index 元数据决定，不是每行切换</li>
 *   <li>I5: rowVersion 从记录 peek，不由调用者传入</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class RowReader {

    /**
     * Schema 注册表
     */
    private final SchemaRegistry registry;

    /**
     * 记录格式
     */
    private final RecordFormat format;

    /**
     * 系统列布局
     */
    private final SystemLayout layout;

    /**
     * 构造函数
     *
     * @param registry Schema 注册表
     * @param format   记录格式
     * @param layout   系统列布局
     */
    public RowReader(SchemaRegistry registry, RecordFormat format, SystemLayout layout) {
        this.registry = registry;
        this.format = format;
        this.layout = layout;
    }

    /**
     * 使用默认格式创建
     *
     * @param registry Schema 注册表
     * @param layout   系统列布局
     */
    public RowReader(SchemaRegistry registry, SystemLayout layout) {
        this(registry, RecordFormatFactory.getDefault(), layout);
    }

    /**
     * 读取记录
     *
     * @param buffer   页面缓冲区
     * @param recStart 记录起始偏移
     * @return DataTuple
     */
    public DataTuple read(ByteBuffer buffer, int recStart) {
        // 1. O(1) peek row version (I5: 从记录自描述)
        int rowVersion = format.peekRowVersion(buffer, recStart);

        // 2. 获取该版本对应的 schema
        RecordSchema schema = registry.get(rowVersion);

        // 3. 解析 offsets (I8: 相对 dataStart，不含系统列)
        FieldOffsets offsets = format.parseOffsets(buffer, recStart, schema, layout);

        // 4. 解码用户字段
        DataTuple tuple = format.decode(buffer, recStart, offsets, schema, layout);

        // 5. 填充 Instant 默认值 (I6: 按 columnId 填充)
        return registry.fillInstantDefaults(tuple, rowVersion);
    }

    /**
     * 仅读取 rowVersion
     *
     * @param buffer   页面缓冲区
     * @param recStart 记录起始偏移
     * @return rowVersion
     */
    public int peekRowVersion(ByteBuffer buffer, int recStart) {
        return format.peekRowVersion(buffer, recStart);
    }

    /**
     * 读取记录（使用指定的 Schema，跳过版本检测）
     *
     * <p>仅用于测试或已知版本的场景</p>
     *
     * @param buffer   页面缓冲区
     * @param recStart 记录起始偏移
     * @param schema   记录 Schema
     * @return DataTuple
     */
    public DataTuple readWithSchema(ByteBuffer buffer, int recStart, RecordSchema schema) {
        FieldOffsets offsets = format.parseOffsets(buffer, recStart, schema, layout);
        return format.decode(buffer, recStart, offsets, schema, layout);
    }

    /**
     * 获取 Schema 注册表
     */
    public SchemaRegistry getRegistry() {
        return registry;
    }

    /**
     * 获取记录格式
     */
    public RecordFormat getFormat() {
        return format;
    }

    /**
     * 获取系统列布局
     */
    public SystemLayout getLayout() {
        return layout;
    }
}
