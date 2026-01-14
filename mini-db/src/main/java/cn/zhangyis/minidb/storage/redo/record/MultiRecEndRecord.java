package cn.zhangyis.minidb.storage.redo.record;

import cn.zhangyis.minidb.storage.page.PageId;

import java.nio.ByteBuffer;

/**
 * Redo Group 结束标记 (必须)
 *
 * <p>标记一个 MTR 的 redo records 组结束。这是 Phase 1-2 的关键记录类型，
 * 用于识别原子边界和安全截断损坏的尾部。</p>
 *
 * <h2>格式</h2>
 * <pre>
 * ┌─────────────┐
 * │ type (1B)   │  ← 只有 1 字节
 * └─────────────┘
 * </pre>
 *
 * <h2>用途</h2>
 * <ul>
 *   <li><b>标识 MTR 原子边界</b>: 一个 redo group = [data records...] + MLOG_MULTI_REC_END</li>
 *   <li><b>恢复时按组解析</b>: 扫描到此标记，确认一组 redo 完整</li>
 *   <li><b>遇到损坏时截断</b>: 如果文件尾损坏，截断到最后一个完整组</li>
 * </ul>
 *
 * <h2>示例</h2>
 * <pre>
 * MTR Commit 产生的 Redo Group:
 *   WriteBytesRecord (page=100, offset=38, length=4)  ← 修改 page_lsn
 *   WriteBytesRecord (page=100, offset=56, length=8)  ← 修改 record
 *   WriteBytesRecord (page=101, offset=100, length=20) ← 修改另一页
 *   MultiRecEndRecord                                  ← 标记组结束
 *
 * 恢复时:
 *   - 读取 3 个 data records
 *   - 遇到 MultiRecEndRecord → 确认组完整，原子应用
 * </pre>
 *
 * <h2>关键规则</h2>
 * <ul>
 *   <li>每个 MTR commit 必须追加一个 MultiRecEndRecord</li>
 *   <li>恢复时遇到不完整的组 (缺少 END 标记) → 忽略整组</li>
 *   <li>不关联任何 PageId (getPageId() 返回 null)</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class MultiRecEndRecord extends RedoRecord {

    // ==================== 构造函数 ====================

    /**
     * 创建 MultiRecEndRecord
     *
     * <p>此记录没有额外数据，仅有 type 字段。</p>
     */
    public MultiRecEndRecord() {
        super(RedoRecordType.MLOG_MULTI_REC_END);
    }

    // ==================== RedoRecord 实现 ====================

    /**
     * 序列化为字节数组
     *
     * <p>格式: type(1) ← 只有 1 字节</p>
     *
     * @return 序列化后的字节数组 (长度 1)
     */
    @Override
    public byte[] serialize() {
        return new byte[]{(byte) type.getValue()};
    }

    /**
     * 获取序列化后的大小
     *
     * @return 1 字节
     */
    @Override
    public int getSize() {
        return 1;
    }

    /**
     * 获取关联的 PageId
     *
     * <p>MultiRecEndRecord 不关联任何页面，返回 null。</p>
     *
     * @return null
     */
    @Override
    public PageId getPageId() {
        return null;
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查是否是 data redo
     *
     * <p>MultiRecEndRecord 不是 data redo，不需要应用到页面。</p>
     *
     * @return false
     */
    @Override
    public boolean isDataRedo() {
        return false;
    }

    @Override
    public String toString() {
        return String.format("MultiRecEndRecord{lsn=%d, size=1}", lsn);
    }

    /**
     * 从字节数组反序列化 (用于恢复)
     *
     * <p>格式: type(1) ← 只需验证 type 字段</p>
     *
     * @param buffer 字节缓冲区 (position 指向 type 字段)
     * @return MultiRecEndRecord 实例
     * @throws IllegalArgumentException 如果格式非法
     */
    public static MultiRecEndRecord deserialize(ByteBuffer buffer) {
        // 1. type (1 byte)
        byte typeValue = buffer.get();
        assert typeValue == RedoRecordType.MLOG_MULTI_REC_END.getValue()
                : "Type mismatch: expected " + RedoRecordType.MLOG_MULTI_REC_END.getValue() + ", got " + typeValue;

        // 2. 创建 Record (无额外数据)
        return new MultiRecEndRecord();
    }
}
