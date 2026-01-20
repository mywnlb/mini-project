package cn.zhangyis.minidb.storage.redo.recovery;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.redo.record.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redo Log Applier - 应用 redo records 到页面
 *
 * <p>将解析出的 redo records 重放到对应的页面上。
 * 支持幂等性检查，避免重复应用已经持久化的修改。</p>
 *
 * <h2>幂等性规则</h2>
 * <pre>
 * if (page.lsn >= record.lsn) {
 *     skip;  // 页面已经包含此修改
 * } else {
 *     apply; // 需要重放此修改
 * }
 * </pre>
 *
 * <h2>支持的 Record 类型</h2>
 * <ul>
 *   <li>MLOG_WRITE_BYTES - 页内字节修改</li>
 *   <li>MLOG_FULL_PAGE - 整页替换 (兜底)</li>
 *   <li>MLOG_MULTI_REC_END - 忽略 (组结束标记)</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class RedoLogApplier {

    private static final Logger logger = LoggerFactory.getLogger(RedoLogApplier.class);

    // ==================== 依赖 ====================

    /** Buffer Pool */
    private final BufferPool bufferPool;

    // ==================== 统计 ====================

    /** 已应用的 record 数量 */
    private long appliedCount = 0;

    /** 跳过的 record 数量 (幂等) */
    private long skippedCount = 0;

    /** 失败的 record 数量 */
    private long failedCount = 0;

    // ==================== 构造函数 ====================

    /**
     * 创建 RedoLogApplier
     *
     * @param bufferPool Buffer Pool
     */
    public RedoLogApplier(BufferPool bufferPool) {
        this.bufferPool = bufferPool;
    }

    // ==================== 核心方法 ====================

    /**
     * 应用 redo record
     *
     * @param record redo record
     * @param lsn    record 的 LSN
     * @return true 如果应用了修改，false 如果跳过 (幂等)
     * @throws RecoveryException 如果应用失败
     */
    public boolean apply(RedoRecord record, long lsn) throws RecoveryException {
        if (record == null) {
            return false;
        }

        // 设置 record 的 LSN (用于幂等性检查)
        record.setLsn(lsn);

        try {
            if (record instanceof WriteBytesRecord) {
                return applyWriteBytes((WriteBytesRecord) record);
            } else if (record instanceof FullPageRecord) {
                return applyFullPage((FullPageRecord) record);
            } else if (record instanceof MultiRecEndRecord) {
                // 组结束标记，不需要应用
                return false;
            } else {
                logger.warn("Unknown record type: {}", record.getClass().getSimpleName());
                return false;
            }
        } catch (Exception e) {
            failedCount++;
            throw new RecoveryException("Failed to apply redo record: " + record, e);
        }
    }

    /**
     * 应用 redo record (使用 record 内部的 LSN)
     *
     * @param record redo record
     * @return true 如果应用了修改
     * @throws RecoveryException 如果应用失败
     */
    public boolean apply(RedoRecord record) throws RecoveryException {
        return apply(record, record.getLsn());
    }

    // ==================== Record 类型处理 ====================

    /**
     * 应用页内字节修改 (MLOG_WRITE_BYTES)
     */
    private boolean applyWriteBytes(WriteBytesRecord record) throws MiniDbException {
        PageId pageId = record.getPageId();

        // 获取页面
        BufferFrame frame;
        try {
            frame = bufferPool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
        } catch (MiniDbException e) {
            // 页面可能不存在 (表空间尚未创建等)
            logger.warn("Cannot load page {} for redo: {}", pageId, e.getMessage());
            skippedCount++;
            return false;
        }

        Page page = frame.getPage();

        try {
            // 幂等性检查
            if (page.getLsn() >= record.getLsn()) {
                logger.trace("Skip redo: page_lsn={} >= redo_lsn={}, page={}",
                        page.getLsn(), record.getLsn(), pageId);
                skippedCount++;
                return false;
            }

            // 应用修改
            page.putBytes(record.getOffset(), record.getData());
            page.setLsn(record.getLsn());
            page.markDirty();

            appliedCount++;
            logger.debug("Applied WRITE_BYTES: page={}, offset={}, len={}, lsn={}",
                    pageId, record.getOffset(), record.getData().length, record.getLsn());

            return true;

        } finally {
            // 释放页面 (标记为脏)
            bufferPool.unpinPage(pageId, true);
        }
    }

    /**
     * 应用整页替换 (MLOG_FULL_PAGE)
     */
    private boolean applyFullPage(FullPageRecord record) throws MiniDbException {
        PageId pageId = record.getPageId();

        // 获取页面
        BufferFrame frame;
        try {
            frame = bufferPool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
        } catch (MiniDbException e) {
            logger.warn("Cannot load page {} for full page redo: {}", pageId, e.getMessage());
            skippedCount++;
            return false;
        }

        Page page = frame.getPage();

        try {
            // 幂等性检查
            if (page.getLsn() >= record.getLsn()) {
                logger.trace("Skip full page redo: page_lsn={} >= redo_lsn={}, page={}",
                        page.getLsn(), record.getLsn(), pageId);
                skippedCount++;
                return false;
            }

            // 替换整页数据
            byte[] pageData = record.getPageData();
            page.putBytes(0, pageData);
            page.setLsn(record.getLsn());
            page.markDirty();

            appliedCount++;
            logger.debug("Applied FULL_PAGE: page={}, lsn={}", pageId, record.getLsn());

            return true;

        } finally {
            bufferPool.unpinPage(pageId, true);
        }
    }

    // ==================== 统计信息 ====================

    /**
     * 获取已应用的 record 数量
     */
    public long getAppliedCount() {
        return appliedCount;
    }

    /**
     * 获取跳过的 record 数量
     */
    public long getSkippedCount() {
        return skippedCount;
    }

    /**
     * 获取失败的 record 数量
     */
    public long getFailedCount() {
        return failedCount;
    }

    /**
     * 获取统计信息字符串
     */
    public String getStats() {
        return String.format("applied=%d, skipped=%d, failed=%d",
                appliedCount, skippedCount, failedCount);
    }

    /**
     * 重置统计信息
     */
    public void resetStats() {
        appliedCount = 0;
        skippedCount = 0;
        failedCount = 0;
    }
}
