package cn.zhangyis.minidb.storage.record.format;

import cn.zhangyis.minidb.storage.record.logical.DataTuple;
import cn.zhangyis.minidb.storage.record.physical.SystemLayout;
import cn.zhangyis.minidb.storage.record.schema.RecordSchema;

import java.nio.ByteBuffer;

/**
 * 记录格式接口
 *
 * <p>定义记录序列化/反序列化的核心操作。不同格式（Compact、Dynamic、Redundant）
 * 实现此接口以支持工厂模式。</p>
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>I4: row format 是 index 级元数据，不是每行切换</li>
 *   <li>I5: rowVersion 从记录 peek，不由调用者传入</li>
 *   <li>I8: FieldOffsets 基准 = 相对 dataStart，不含系统列</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public interface RecordFormat {

    /**
     * O(1) 读取 row version
     *
     * <p>位置固定：dataStart + OFF_ROW_VER (13)</p>
     *
     * <p>I5: rowVersion 从记录自描述，不由调用者传入</p>
     *
     * @param buffer   页面缓冲区
     * @param recStart 记录起始偏移
     * @return row version (u16)
     */
    int peekRowVersion(ByteBuffer buffer, int recStart);

    /**
     * 解析变长字段长度列表 + NULL bitmap → 字段偏移表
     *
     * <p>I8: 返回的 offset 相对 dataStart，不含系统列</p>
     *
     * @param buffer   页面缓冲区
     * @param recStart 记录起始偏移
     * @param schema   记录 Schema
     * @param layout   系统列布局
     * @return FieldOffsets
     */
    FieldOffsets parseOffsets(ByteBuffer buffer, int recStart,
                               RecordSchema schema, SystemLayout layout);

    /**
     * 编码写入目标页面
     *
     * <p>写入顺序（dataStart 起）：
     * TRX_ID → ROLL_PTR → ROW_VER → [ROW_ID] → user cols</p>
     *
     * <p>同时写入记录头之前的 varlen list 和 null bitmap</p>
     *
     * @param buffer    页面缓冲区
     * @param recStart  记录起始偏移
     * @param tuple     逻辑记录
     * @param schema    记录 Schema
     * @param layout    系统列布局
     * @param trxId     事务 ID
     * @param rollPtr   回滚指针
     * @param rowVersion 记录版本
     * @param rowId     行 ID（仅当 layout.hasRowId() 时使用）
     * @return 写入的总字节数（含 header 之前的额外空间）
     */
    int encodeTo(ByteBuffer buffer, int recStart, DataTuple tuple,
                 RecordSchema schema, SystemLayout layout,
                 long trxId, long rollPtr, int rowVersion, long rowId);

    /**
     * 读取系统列
     *
     * @param buffer   页面缓冲区
     * @param recStart 记录起始偏移
     * @param layout   系统列布局
     * @return 系统列值数组 [trxId, rollPtr, rowVersion, rowId]
     */
    long[] readSystemColumns(ByteBuffer buffer, int recStart, SystemLayout layout);

    /**
     * 解码为逻辑 Tuple
     *
     * <p>不含 Instant 默认值填充，由 SchemaRegistry 负责</p>
     *
     * @param buffer   页面缓冲区
     * @param recStart 记录起始偏移
     * @param offsets  字段偏移表
     * @param schema   记录 Schema
     * @param layout   系统列布局
     * @return DataTuple
     */
    DataTuple decode(ByteBuffer buffer, int recStart, FieldOffsets offsets,
                     RecordSchema schema, SystemLayout layout);

    /**
     * 计算编码后所需的总空间
     *
     * <p>包含：varlen list + null bitmap + header(5) + 系统列 + 用户列</p>
     *
     * @param tuple  逻辑记录
     * @param schema 记录 Schema
     * @param layout 系统列布局
     * @return 总字节数
     */
    int calculateSize(DataTuple tuple, RecordSchema schema, SystemLayout layout);

    /**
     * 计算记录头之前的额外空间
     *
     * <p>包含：varlen list + null bitmap</p>
     *
     * @param tuple  逻辑记录
     * @param schema 记录 Schema
     * @return 额外字节数
     */
    int calculateExtraBytes(DataTuple tuple, RecordSchema schema);

    /**
     * 获取格式类型
     */
    RecordFormatType getType();

    /**
     * 是否支持溢出存储
     */
    default boolean supportsOverflow() {
        return getType().supportsOverflow();
    }
}
