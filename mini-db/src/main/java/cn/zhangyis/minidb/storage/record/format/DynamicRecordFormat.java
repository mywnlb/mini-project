package cn.zhangyis.minidb.storage.record.format;

import cn.zhangyis.minidb.storage.record.logical.DataTuple;
import cn.zhangyis.minidb.storage.record.physical.SystemLayout;
import cn.zhangyis.minidb.storage.record.schema.RecordSchema;

import java.nio.ByteBuffer;

/**
 * Dynamic 行格式实现
 *
 * <p>MySQL 5.7 默认格式，基于 Compact，大字段完全存储在溢出页。</p>
 *
 * <p>当前实现与 Compact 相同，溢出功能需要外部页支持后再实现。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class DynamicRecordFormat extends CompactRecordFormat {

    @Override
    public RecordFormatType getType() {
        return RecordFormatType.DYNAMIC;
    }

    // TODO: 实现溢出存储逻辑
    // 需要外部页 + 溢出指针机制
}
