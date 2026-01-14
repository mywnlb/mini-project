package cn.zhangyis.minidb.storage.redo.record;

import cn.zhangyis.minidb.storage.page.PageId;

/**
 * Redo Log 记录抽象基类
 *
 * <p>所有 redo log 记录的基类。每个 redo record 记录一个页面的修改操作。</p>
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li><b>Physiological Logging</b>: Page 粒度定位 + 页内逻辑增量</li>
 *   <li><b>幂等性</b>: 通过 page_lsn 判断，支持重复重放</li>
 *   <li><b>最小化</b>: 只记录必要的修改信息</li>
 * </ul>
 *
 * <h2>子类实现</h2>
 * <ul>
 *   <li>{@link WriteBytesRecord}: 页内字节修改 (主要)</li>
 *   <li>{@link MultiRecEndRecord}: Redo Group 结束标记</li>
 *   <li>{@link FullPageRecord}: 整页 redo (兜底)</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public abstract class RedoRecord {

    // ==================== 核心字段 ====================

    /** Redo 记录类型 */
    protected final RedoRecordType type;

    /** 该 record 的起始 LSN (恢复时设置) */
    protected long lsn;

    // ==================== 构造函数 ====================

    /**
     * 创建 RedoRecord
     *
     * @param type Redo 记录类型
     */
    protected RedoRecord(RedoRecordType type) {
        this.type = type;
        this.lsn = 0;  // 写入时由 RedoLogManager 设置
    }

    // ==================== 抽象方法 (子类实现) ====================

    /**
     * 序列化为字节数组
     *
     * <p>将 redo record 序列化为二进制格式，准备写入 log buffer。</p>
     *
     * @return 序列化后的字节数组
     */
    public abstract byte[] serialize();

    /**
     * 获取序列化后的大小
     *
     * @return 字节数 (包含 header)
     */
    public abstract int getSize();

    /**
     * 获取关联的 PageId
     *
     * <p>对于 data redo，返回对应的 page。
     * 对于 MLOG_MULTI_REC_END，返回 null。</p>
     *
     * @return PageId 或 null
     */
    public abstract PageId getPageId();

    // ==================== Getter/Setter ====================

    /**
     * 获取 Redo 记录类型
     *
     * @return RedoRecordType
     */
    public RedoRecordType getType() {
        return type;
    }

    /**
     * 获取该 record 的 LSN
     *
     * @return LSN (0 表示尚未设置)
     */
    public long getLsn() {
        return lsn;
    }

    /**
     * 设置该 record 的 LSN
     *
     * <p>由 RedoLogManager 在写入时调用。</p>
     *
     * @param lsn Log Sequence Number
     */
    public void setLsn(long lsn) {
        this.lsn = lsn;
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查是否是 data redo (需要应用到页面)
     *
     * @return true 如果需要应用到页面
     */
    public boolean isDataRedo() {
        return type.isDataRedo();
    }

    @Override
    public String toString() {
        return String.format("%s{lsn=%d, size=%d}",
                type.name(), lsn, getSize());
    }
}
