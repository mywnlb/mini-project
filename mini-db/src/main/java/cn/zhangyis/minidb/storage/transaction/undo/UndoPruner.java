package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import cn.zhangyis.minidb.storage.transaction.purge.PurgeCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Undo 链段剪枝器
 *
 * <p>识别可合并的 Undo 链段，生成合并后的 Undo 记录。</p>
 *
 * <h2>合并条件</h2>
 * <ul>
 *   <li>同一记录（RecordId/PK）</li>
 *   <li>连续 UPDATE（遇到 DELETE/INSERT 边界停止）</li>
 *   <li>链上所有 undo.trx_id < purge_view.low_limit_id</li>
 *   <li>当前 record.roll_ptr 未变化（ABA 校验）</li>
 *   <li>合并后记录大小不超页可用空间</li>
 * </ul>
 *
 * <h2>时序示例</h2>
 * <pre>
 * 原始链：record.roll_ptr → U3 → U2 → U1 → NULL
 *                         ↑    ↑    ↑
 *                       (都是 UPDATE，可合并)
 *
 * 合并后：record.roll_ptr → U_merged → NULL
 *        (U_merged 直接指向 U1 之前的版本)
 *
 * 旧 U3/U2/U1 进入正常 purge 回收流程
 * </pre>
 * </p>
 *
 * <h2>设计约束</h2>
 * <ul>
 *   <li><b>U1</b>：Undo 记录不可修改 - 追加写保证</li>
 *   <li><b>U2</b>：版本链完整性 - 合并后版本链仍完整</li>
 *   <li><b>U6</b>：Undo 记录顺序 - 合并后仍保持顺序</li>
 *   <li><b>U7</b>：Undo 空间回收 - 合并释放更多空间</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * UndoPruner pruner = new UndoPruner(undoLogManager, purgeCoordinator);
 *
 * // 识别可合并的链段
 * UndoPruner.MergeableSegment segment = pruner.identifyMergeableSegment(
 *     record,
 *     undoChain,
 *     purgeView
 * );
 *
 * if (segment.canMerge()) {
 *     // 生成合并后的 Undo 记录
 *     UpdateUndoRecord mergedUndo = pruner.createMergedUndo(segment);
 *
 *     // 追加写合并后的 Undo
 *     RollbackPointer mergedPtr = undoLogManager.appendMergedUndo(mergedUndo);
 *
 *     // 更新记录的 roll_ptr
 *     record.setRollPtr(mergedPtr);
 * }
 * }</pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class UndoPruner {

    private static final Logger logger = LoggerFactory.getLogger(UndoPruner.class);

    // ==================== 常量 ====================

    /**
     * 最小可合并链段长度
     */
    public static final int MIN_MERGEABLE_CHAIN_LENGTH = 2;

    /**
     * 最大合并后 Undo 记录大小（字节）
     */
    public static final int MAX_MERGED_UNDO_SIZE = 65536; // 64KB

    /**
     * 最大合并的 Undo 记录数
     */
    public static final int MAX_UNDO_RECORDS_TO_MERGE = 1000;

    // ==================== 字段 ====================

    /**
     * Undo Log 管理器
     */
    private final UndoLogManager undoLogManager;

    /**
     * Purge 协调器
     */
    private final PurgeCoordinator purgeCoordinator;

    // ==================== 内部类 ====================

    /**
     * 可合并的链段信息
     */
    public static class MergeableSegment {
        /**
         * 记录的主键
         */
        public final byte[] primaryKey;

        /**
         * 表 ID
         */
        public final int tableId;

        /**
         * 可合并的 Undo 链（从新到旧）
         */
        public final List<UpdateUndoRecord> undoChain;

        /**
         * 链段之前的 Undo 指针（合并后应指向这里）
         */
        public final RollbackPointer prevUndoPtr;

        /**
         * 当前记录的 roll_ptr（用于 ABA 校验）
         */
        public final RollbackPointer currentRollPtr;

        /**
         * 合并后的总列数
         */
        public final int totalColumns;

        /**
         * 合并后的估算大小
         */
        public final int estimatedSize;

        public MergeableSegment(byte[] primaryKey, int tableId,
                               List<UpdateUndoRecord> undoChain,
                               RollbackPointer prevUndoPtr,
                               RollbackPointer currentRollPtr,
                               int totalColumns,
                               int estimatedSize) {
            this.primaryKey = primaryKey;
            this.tableId = tableId;
            this.undoChain = new ArrayList<>(undoChain);
            this.prevUndoPtr = prevUndoPtr;
            this.currentRollPtr = currentRollPtr;
            this.totalColumns = totalColumns;
            this.estimatedSize = estimatedSize;
        }

        /**
         * 是否可以合并
         *
         * @return 如果可以合并返回 true
         */
        public boolean canMerge() {
            return undoChain.size() >= MIN_MERGEABLE_CHAIN_LENGTH &&
                   estimatedSize <= MAX_MERGED_UNDO_SIZE;
        }

        /**
         * 获取链段长度
         *
         * @return 链段中的 Undo 记录数
         */
        public int getChainLength() {
            return undoChain.size();
        }

        /**
         * 获取空间节省
         *
         * @return 节省的字节数
         */
        public int getSpaceSavings() {
            int originalSize = 0;
            for (UpdateUndoRecord undo : undoChain) {
                originalSize += undo.calculateSize();
            }
            return originalSize - estimatedSize;
        }

        @Override
        public String toString() {
            return String.format(
                    "MergeableSegment{pk=%d bytes, table=%d, chain=%d, cols=%d, size=%d, savings=%d}",
                    primaryKey.length, tableId, undoChain.size(), totalColumns,
                    estimatedSize, getSpaceSavings()
            );
        }
    }

    // ==================== 构造函数 ====================

    /**
     * 创建 Undo 链段剪枝器
     *
     * @param undoLogManager Undo Log 管理器
     * @param purgeCoordinator Purge 协调器
     */
    public UndoPruner(UndoLogManager undoLogManager, PurgeCoordinator purgeCoordinator) {
        this.undoLogManager = undoLogManager;
        this.purgeCoordinator = purgeCoordinator;
    }

    // ==================== 公共方法 ====================

    /**
     * 识别可合并的链段
     *
     * <p>检查 Undo 链是否满足合并条件。</p>
     *
     * @param primaryKey 记录的主键
     * @param tableId 表 ID
     * @param undoChain Undo 链（从新到旧）
     * @param currentRollPtr 当前记录的 roll_ptr
     * @return 可合并的链段，如果不可合并返回 null
     */
    public MergeableSegment identifyMergeableSegment(byte[] primaryKey, int tableId,
                                                     List<UpdateUndoRecord> undoChain,
                                                     RollbackPointer currentRollPtr) {
        if (undoChain == null || undoChain.isEmpty()) {
            return null;
        }

        // 1. 检查链段长度
        if (undoChain.size() < MIN_MERGEABLE_CHAIN_LENGTH) {
            logger.trace("Chain too short: {}", undoChain.size());
            return null;
        }

        // 2. 检查链段是否都是 UPDATE（遇到 DELETE/INSERT 边界停止）
        List<UpdateUndoRecord> mergeableChain = new ArrayList<>();
        RollbackPointer prevUndoPtr = RollbackPointer.NULL;

        for (UndoRecord undo : undoChain) {
            if (!(undo instanceof UpdateUndoRecord)) {
                // 遇到非 UPDATE 记录，停止
                logger.trace("Encountered non-UPDATE record: {}", undo.getType());
                break;
            }

            UpdateUndoRecord updateUndo = (UpdateUndoRecord) undo;
            mergeableChain.add(updateUndo);
            prevUndoPtr = updateUndo.getPrevUndoPtr();

            // 限制合并的记录数
            if (mergeableChain.size() >= MAX_UNDO_RECORDS_TO_MERGE) {
                logger.trace("Reached max merge limit: {}", MAX_UNDO_RECORDS_TO_MERGE);
                break;
            }
        }

        // 3. 再次检查链段长度
        if (mergeableChain.size() < MIN_MERGEABLE_CHAIN_LENGTH) {
            logger.trace("Mergeable chain too short: {}", mergeableChain.size());
            return null;
        }

        // 4. 检查所有 Undo 的 trx_id 是否都 < purge_limit
        TransactionId purgeLimit = purgeCoordinator.getPurgeLimit();
        for (UpdateUndoRecord undo : mergeableChain) {
            if (undo.getTrxId().getValue() >= purgeLimit.getValue()) {
                logger.trace("Undo trx_id >= purge_limit: {} >= {}",
                        undo.getTrxId().getValue(), purgeLimit.getValue());
                return null;
            }
        }

        // 5. 计算合并后的大小
        int totalColumns = calculateTotalColumns(mergeableChain);
        int estimatedSize = estimateMergedUndoSize(primaryKey, tableId, mergeableChain);

        // 6. 检查合并后的大小是否超过限制
        if (estimatedSize > MAX_MERGED_UNDO_SIZE) {
            logger.trace("Merged undo size exceeds limit: {} > {}",
                    estimatedSize, MAX_MERGED_UNDO_SIZE);
            return null;
        }

        logger.debug("Identified mergeable segment: chain={}, cols={}, size={}",
                mergeableChain.size(), totalColumns, estimatedSize);

        return new MergeableSegment(
                primaryKey, tableId,
                mergeableChain,
                prevUndoPtr,
                currentRollPtr,
                totalColumns,
                estimatedSize
        );
    }

    /**
     * 创建合并后的 Undo 记录
     *
     * <p>将多个 UPDATE Undo 记录合并为一个，只保留最终的列值。</p>
     *
     * @param segment 可合并的链段
     * @return 合并后的 Undo 记录
     */
    public UpdateUndoRecord createMergedUndo(MergeableSegment segment) {
        if (!segment.canMerge()) {
            throw new IllegalArgumentException("Segment cannot be merged");
        }

        // 1. 从新到旧遍历，收集所有列的最旧值
        Map<Integer, byte[]> mergedColumns = new HashMap<>();

        for (UpdateUndoRecord undo : segment.undoChain) {
            for (UpdateUndoRecord.OldColumnValue col : undo.getOldColumns()) {
                // 只保留最旧的值（从新到旧遍历，所以后面的值是更旧的）
                if (!mergedColumns.containsKey(col.columnId)) {
                    mergedColumns.put(col.columnId, col.value);
                }
            }
        }

        // 2. 转换为 OldColumnValue 列表
        List<UpdateUndoRecord.OldColumnValue> oldCols = new ArrayList<>();
        for (Map.Entry<Integer, byte[]> entry : mergedColumns.entrySet()) {
            oldCols.add(new UpdateUndoRecord.OldColumnValue(entry.getKey(), entry.getValue()));
        }

        // 3. 获取最新的 Undo 记录的事务 ID（用于合并后的 Undo）
        UpdateUndoRecord newestUndo = segment.undoChain.get(0);

        // 4. 创建合并后的 Undo 记录
        UpdateUndoRecord mergedUndo = new UpdateUndoRecord(
                newestUndo.getTrxId(),
                segment.tableId,
                segment.prevUndoPtr,  // 指向链段之前的 Undo
                segment.primaryKey,
                oldCols,
                UndoRecordVersion.FORMAT_V2,  // 使用 V2 格式
                UndoRecordVersion.INITIAL_SCHEMA_VERSION
        );

        logger.debug("Created merged undo: cols={}, size={}",
                oldCols.size(), mergedUndo.calculateSize());

        return mergedUndo;
    }

    /**
     * 验证 ABA 冲突
     *
     * <p>检查记录的 roll_ptr 是否在合并期间被修改。</p>
     *
     * @param segment 可合并的链段
     * @param currentRollPtr 当前的 roll_ptr
     * @return 如果没有 ABA 冲突返回 true
     */
    public boolean verifyNoABAConflict(MergeableSegment segment, RollbackPointer currentRollPtr) {
        boolean noConflict = segment.currentRollPtr.equals(currentRollPtr);

        if (!noConflict) {
            logger.warn("ABA conflict detected: expected={}, current={}",
                    segment.currentRollPtr, currentRollPtr);
        }

        return noConflict;
    }

    /**
     * 获取链段统计信息
     *
     * @param segment 可合并的链段
     * @return 统计信息
     */
    public SegmentStats getSegmentStats(MergeableSegment segment) {
        int originalSize = 0;
        int totalRecords = segment.undoChain.size();

        for (UpdateUndoRecord undo : segment.undoChain) {
            originalSize += undo.calculateSize();
        }

        int mergedSize = segment.estimatedSize;
        int spaceSavings = originalSize - mergedSize;
        double compressionRatio = (double) spaceSavings / originalSize;

        return new SegmentStats(totalRecords, originalSize, mergedSize, spaceSavings, compressionRatio);
    }

    // ==================== 辅助方法 ====================

    /**
     * 计算合并后的总列数
     *
     * @param undoChain Undo 链
     * @return 总列数
     */
    private int calculateTotalColumns(List<UpdateUndoRecord> undoChain) {
        Map<Integer, Boolean> columnIds = new HashMap<>();

        for (UpdateUndoRecord undo : undoChain) {
            for (UpdateUndoRecord.OldColumnValue col : undo.getOldColumns()) {
                columnIds.put(col.columnId, true);
            }
        }

        return columnIds.size();
    }

    /**
     * 估算合并后的 Undo 大小
     *
     * @param primaryKey 主键
     * @param tableId 表 ID
     * @param undoChain Undo 链
     * @return 估算的大小
     */
    private int estimateMergedUndoSize(byte[] primaryKey, int tableId,
                                      List<UpdateUndoRecord> undoChain) {
        // Undo 记录头部大小
        int headerSize = UndoRecord.HEADER_SIZE;

        // V2 格式版本字段
        int versionSize = 2; // formatVersion + schemaVersion

        // 主键大小
        int pkSize = 2 + primaryKey.length; // pk_len + pk_data

        // 列数量字段
        int nColsSize = 1;

        // 合并后的列大小
        Map<Integer, byte[]> mergedColumns = new HashMap<>();
        for (UpdateUndoRecord undo : undoChain) {
            for (UpdateUndoRecord.OldColumnValue col : undo.getOldColumns()) {
                if (!mergedColumns.containsKey(col.columnId)) {
                    mergedColumns.put(col.columnId, col.value);
                }
            }
        }

        int colsSize = 0;
        for (byte[] value : mergedColumns.values()) {
            colsSize += 2 + 2 + value.length; // col_id + col_len + col_value
        }

        return headerSize + versionSize + pkSize + nColsSize + colsSize;
    }

    // ==================== 内部类：统计信息 ====================

    /**
     * 链段统计信息
     */
    public static class SegmentStats {
        /**
         * Undo 记录总数
         */
        public final int totalRecords;

        /**
         * 原始大小（字节）
         */
        public final int originalSize;

        /**
         * 合并后大小（字节）
         */
        public final int mergedSize;

        /**
         * 空间节省（字节）
         */
        public final int spaceSavings;

        /**
         * 压缩比率
         */
        public final double compressionRatio;

        public SegmentStats(int totalRecords, int originalSize, int mergedSize,
                           int spaceSavings, double compressionRatio) {
            this.totalRecords = totalRecords;
            this.originalSize = originalSize;
            this.mergedSize = mergedSize;
            this.spaceSavings = spaceSavings;
            this.compressionRatio = compressionRatio;
        }

        @Override
        public String toString() {
            return String.format(
                    "SegmentStats{records=%d, original=%dB, merged=%dB, savings=%dB, ratio=%.2f%%}",
                    totalRecords, originalSize, mergedSize, spaceSavings, compressionRatio * 100
            );
        }
    }
}
