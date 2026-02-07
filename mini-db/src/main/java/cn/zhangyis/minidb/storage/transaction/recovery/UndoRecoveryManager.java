package cn.zhangyis.minidb.storage.transaction.recovery;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.undo.UndoLogManager;
import cn.zhangyis.minidb.storage.transaction.undo.UndoPage;
import cn.zhangyis.minidb.storage.transaction.undo.UndoPageHeader;
import cn.zhangyis.minidb.storage.transaction.undo.UndoRecord;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Undo Recovery Manager - 崩溃恢复时的 Undo 回滚管理器
 *
 * <p>负责在崩溃恢复的第二阶段（Undo 回滚阶段）处理未提交的事务。</p>
 *
 * <h2>InnoDB 崩溃恢复流程</h2>
 * <pre>
 * 1. Redo 重放阶段：从 checkpoint 开始重放 redo log，恢复页面到崩溃前状态
 * 2. Undo 回滚阶段：扫描活跃事务，对未提交的事务执行回滚
 * </pre>
 *
 * <h2>Undo 回滚流程</h2>
 * <ol>
 *   <li>扫描 Undo 表空间的所有 Rollback Segment</li>
 *   <li>找出状态为 ACTIVE 的 Undo Page（未提交事务）</li>
 *   <li>按事务 ID 分组，收集需要回滚的事务</li>
 *   <li>对每个事务执行回滚操作（逆序应用 Undo 记录）</li>
 *   <li>清理 Undo 资源</li>
 * </ol>
 *
 * <h2>设计约束</h2>
 * <ul>
 *   <li><b>幂等性</b>: 回滚操作可以重复执行</li>
 *   <li><b>顺序性</b>: 同一事务的 Undo 记录按逆序应用</li>
 *   <li><b>原子性</b>: 每个回滚操作在 MTR 中完成</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class UndoRecoveryManager {

    private static final Logger logger = LoggerFactory.getLogger(UndoRecoveryManager.class);

    // ==================== 依赖 ====================

    /** Buffer Pool */
    private final BufferPool bufferPool;

    /** Undo 表空间 ID */
    private final int undoSpaceId;

    /** Undo Page 扫描范围（起始页号） */
    private final int undoPageStart;

    /** Undo Page 扫描范围（结束页号，不包含） */
    private final int undoPageEnd;

    // ==================== 统计信息 ====================

    /** 恢复统计 */
    private final RecoveryStats stats = new RecoveryStats();

    // ==================== 构造函数 ====================

    /**
     * 创建 UndoRecoveryManager
     *
     * @param bufferPool   Buffer Pool
     * @param undoSpaceId  Undo 表空间 ID
     * @param undoPageStart Undo Page 起始页号
     * @param undoPageEnd   Undo Page 结束页号（不包含）
     */
    public UndoRecoveryManager(BufferPool bufferPool, int undoSpaceId,
                                int undoPageStart, int undoPageEnd) {
        this.bufferPool = bufferPool;
        this.undoSpaceId = undoSpaceId;
        this.undoPageStart = undoPageStart;
        this.undoPageEnd = undoPageEnd;
    }

    // ==================== 核心方法 ====================

    /**
     * 执行 Undo 回滚恢复
     *
     * <p>扫描 Undo 表空间，找出未提交的事务并执行回滚。</p>
     *
     * @param undoRecordApplier Undo 记录应用器（执行实际的回滚操作）
     * @throws MiniDbException 如果恢复失败
     */
    public void recover(UndoRecordApplier undoRecordApplier) throws MiniDbException {
        logger.info("=== Starting Undo Recovery ===");
        long startTime = System.currentTimeMillis();

        try {
            // Step 1: 扫描 Undo Pages，找出活跃事务
            Map<TransactionId, List<ActiveUndoInfo>> activeTransactions = scanActiveTransactions();

            if (activeTransactions.isEmpty()) {
                logger.info("No active transactions found, Undo recovery not needed");
                return;
            }

            logger.info("Found {} active transactions to rollback", activeTransactions.size());
            stats.activeTransactions = activeTransactions.size();

            // Step 2: 按事务 ID 排序（可选：按 TRX_ID 逆序回滚，先回滚最新的事务）
            List<TransactionId> sortedTrxIds = new ArrayList<>(activeTransactions.keySet());
            sortedTrxIds.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

            // Step 3: 对每个事务执行回滚
            for (TransactionId trxId : sortedTrxIds) {
                List<ActiveUndoInfo> undoInfos = activeTransactions.get(trxId);
                rollbackTransaction(trxId, undoInfos, undoRecordApplier);
            }

            // Step 4: 清理 Undo 资源
            cleanupUndoResources(activeTransactions);

            long duration = System.currentTimeMillis() - startTime;
            stats.recoveryTimeMs = duration;

            logger.info("=== Undo Recovery Completed in {} ms ===", duration);
            logger.info("Recovery stats: {}", stats);

        } catch (Exception e) {
            logger.error("Undo recovery failed", e);
            throw new MiniDbException("Undo recovery failed: " + e.getMessage(), e);
        }
    }

    /**
     * 扫描活跃事务
     *
     * <p>扫描 Undo 表空间中的所有 Undo Page，找出状态为 ACTIVE 的页面。</p>
     *
     * @return 活跃事务映射（TRX_ID → Undo 信息列表）
     * @throws MiniDbException 如果扫描失败
     */
    private Map<TransactionId, List<ActiveUndoInfo>> scanActiveTransactions() throws MiniDbException {
        Map<TransactionId, List<ActiveUndoInfo>> result = new HashMap<>();

        logger.debug("Scanning Undo pages from {} to {} in space {}",
                undoPageStart, undoPageEnd, undoSpaceId);

        for (int pageNo = undoPageStart; pageNo < undoPageEnd; pageNo++) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                PageId pageId = PageId.of(undoSpaceId, pageNo);

                // 尝试读取页面
                Page page;
                try {
                    page = mtr.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
                } catch (Exception e) {
                    // 页面不存在或无法读取，跳过
                    stats.pagesScanned++;
                    continue;
                }

                ByteBuffer buf = page.getBuffer();
                buf.order(ByteOrder.LITTLE_ENDIAN);

                // 检查是否是有效的 Undo Page
                if (!isValidUndoPage(buf)) {
                    stats.pagesScanned++;
                    continue;
                }

                // 检查页面状态
                int state = UndoPageHeader.getState(buf);
                if (state != UndoPageHeader.STATE_ACTIVE) {
                    stats.pagesScanned++;
                    continue;
                }

                // 发现活跃的 Undo Page
                long trxIdValue = UndoPageHeader.getTrxId(buf);
                TransactionId trxId = new TransactionId(trxIdValue);
                int undoType = UndoPageHeader.getUndoType(buf);
                int rsegId = UndoPageHeader.getRsegId(buf);

                ActiveUndoInfo info = new ActiveUndoInfo(pageNo, undoType, rsegId);
                result.computeIfAbsent(trxId, k -> new ArrayList<>()).add(info);

                stats.activePagesFound++;
                logger.debug("Found active Undo page: pageNo={}, trxId={}, type={}, rseg={}",
                        pageNo, trxId, undoType == UndoPageHeader.UNDO_INSERT ? "INSERT" : "UPDATE", rsegId);

                stats.pagesScanned++;
                mtr.commit();
            }
        }

        logger.info("Scanned {} pages, found {} active Undo pages",
                stats.pagesScanned, stats.activePagesFound);

        return result;
    }

    /**
     * 检查是否是有效的 Undo Page
     */
    private boolean isValidUndoPage(ByteBuffer buf) {
        try {
            int undoType = UndoPageHeader.getUndoType(buf);
            return undoType == UndoPageHeader.UNDO_INSERT || undoType == UndoPageHeader.UNDO_UPDATE;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 回滚单个事务
     *
     * @param trxId            事务 ID
     * @param undoInfos        该事务的 Undo 信息列表
     * @param undoRecordApplier Undo 记录应用器
     * @throws MiniDbException 如果回滚失败
     */
    private void rollbackTransaction(TransactionId trxId, List<ActiveUndoInfo> undoInfos,
                                      UndoRecordApplier undoRecordApplier) throws MiniDbException {
        logger.info("Rolling back transaction: trxId={}, undoPages={}", trxId, undoInfos.size());

        int recordsRolledBack = 0;

        // 按页号逆序处理（先处理最新的修改）
        undoInfos.sort((a, b) -> Integer.compare(b.pageNo, a.pageNo));

        for (ActiveUndoInfo info : undoInfos) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                PageId pageId = PageId.of(undoSpaceId, info.pageNo);
                Page page = mtr.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
                ByteBuffer buf = page.getBuffer();
                buf.order(ByteOrder.LITTLE_ENDIAN);

                // 遍历页面中的所有 Undo 记录（逆序）
                List<UndoRecord> records = readUndoRecordsReverse(buf);

                for (UndoRecord record : records) {
                    // 应用回滚操作
                    boolean applied = undoRecordApplier.apply(record, mtr);
                    if (applied) {
                        recordsRolledBack++;
                    }
                }

                // 更新页面状态为 TO_FREE
                UndoPageHeader.setState(buf, UndoPageHeader.STATE_TO_FREE);
                mtr.markDirty(page);
                mtr.commit();
            }
        }

        stats.recordsRolledBack += recordsRolledBack;
        stats.transactionsRolledBack++;

        logger.info("Rolled back transaction {}: {} records", trxId, recordsRolledBack);
    }

    /**
     * 逆序读取 Undo Page 中的所有 Undo 记录
     */
    private List<UndoRecord> readUndoRecordsReverse(ByteBuffer buf) {
        List<UndoRecord> records = new ArrayList<>();

        try {
            // 使用 UndoPage 的迭代器
            int lastLogOffset = UndoPageHeader.getLastLogOffset(buf);
            int logStart = UndoPageHeader.getLogStart(buf);

            if (lastLogOffset == 0 || logStart == 0) {
                return records;
            }

            // 简化实现：从 lastLogOffset 开始逆向遍历
            int currentOffset = lastLogOffset;
            while (currentOffset >= logStart) {
                try {
                    UndoRecord record = UndoPage.readUndoRecord(buf, currentOffset);
                    if (record == null) {
                        break;
                    }
                    records.add(record);

                    // 获取前一条记录的偏移（从记录头中读取 prev_undo）
                    int prevOffset = getPrevUndoOffset(buf, currentOffset);
                    if (prevOffset == 0 || prevOffset >= currentOffset) {
                        break;
                    }
                    currentOffset = prevOffset;
                } catch (Exception e) {
                    logger.warn("Error reading undo record at offset {}: {}", currentOffset, e.getMessage());
                    break;
                }
            }
        } catch (Exception e) {
            logger.warn("Error reading undo records: {}", e.getMessage());
        }

        return records;
    }

    /**
     * 获取前一条 Undo 记录的偏移
     */
    private int getPrevUndoOffset(ByteBuffer buf, int currentOffset) {
        // Undo Record 头部包含 prev_undo 字段
        // 格式: type(1) + len(2) + trx_id(8) + table_id(4) + prev_undo(2) = 17 bytes header
        // prev_undo 在偏移 15-16
        try {
            return buf.getShort(currentOffset + 15) & 0xFFFF;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 清理 Undo 资源
     *
     * <p>将已回滚事务的 Undo Page 标记为可释放状态。</p>
     */
    private void cleanupUndoResources(Map<TransactionId, List<ActiveUndoInfo>> activeTransactions)
            throws MiniDbException {
        logger.debug("Cleaning up Undo resources for {} transactions", activeTransactions.size());

        // 已在 rollbackTransaction 中将页面状态设为 TO_FREE
        // 后续由 Purge 线程或下次启动时清理
    }

    // ==================== 统计信息 ====================

    /**
     * 获取恢复统计信息
     */
    public RecoveryStats getStats() {
        return stats;
    }

    // ==================== 内部类 ====================

    /**
     * 活跃 Undo 信息
     */
    private static class ActiveUndoInfo {
        final int pageNo;
        final int undoType;
        final int rsegId;

        ActiveUndoInfo(int pageNo, int undoType, int rsegId) {
            this.pageNo = pageNo;
            this.undoType = undoType;
            this.rsegId = rsegId;
        }
    }

    /**
     * 恢复统计信息
     */
    public static class RecoveryStats {
        public int pagesScanned;
        public int activePagesFound;
        public int activeTransactions;
        public int transactionsRolledBack;
        public int recordsRolledBack;
        public long recoveryTimeMs;

        @Override
        public String toString() {
            return String.format(
                    "RecoveryStats{scanned=%d, active=%d, transactions=%d, rolledBack=%d, records=%d, time=%dms}",
                    pagesScanned, activePagesFound, activeTransactions,
                    transactionsRolledBack, recordsRolledBack, recoveryTimeMs);
        }
    }

    /**
     * Undo 记录应用器接口
     *
     * <p>由调用者实现，执行实际的回滚操作（如删除 INSERT 的记录、恢复 UPDATE 的旧值）。</p>
     */
    @FunctionalInterface
    public interface UndoRecordApplier {
        /**
         * 应用 Undo 记录（执行回滚操作）
         *
         * @param record Undo 记录
         * @param mtr    Mini-Transaction
         * @return true 如果成功应用
         * @throws MiniDbException 如果应用失败
         */
        boolean apply(UndoRecord record, MiniTransaction mtr) throws MiniDbException;
    }
}
