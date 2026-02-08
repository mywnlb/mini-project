package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 版本链重建器
 *
 * <p>从版本链重建历史版本，支持新旧 Undo 格式混读。</p>
 *
 * <h2>重建算法</h2>
 * <pre>
 * 从新到旧遍历版本链：
 *   v_new = 当前记录
 *   for each undo in chain {
 *       if (undo.schemaVersion != v_new.schemaVersion) {
 *           // schema 变更，需要转换
 *           v_new = applySchemaEvolution(v_new, undo);
 *       }
 *       // 按需补齐列
 *       for each col in undo.fields {
 *           if (v_new[col] == NULL) {
 *               v_new[col] = undo[col];
 *           }
 *       }
 *       if (allColumnsReconstructed(v_new)) break;  // 补齐即停
 *   }
 * </pre>
 * </p>
 *
 * <h2>设计约束</h2>
 * <ul>
 *   <li><b>U2</b>：版本链完整性 - 需要确保列继承逻辑正确</li>
 *   <li><b>U8</b>：Undo 读取安全 - 仍然无需锁</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * VersionReconstructor reconstructor = new VersionReconstructor();
 *
 * // 重建历史版本
 * ReconstructedVersion version = reconstructor.reconstruct(
 *     currentRecord,
 *     undoChain,
 *     targetTrxId
 * );
 *
 * if (version.isComplete()) {
 *     byte[] historicalData = version.getReconstructedData();
 * }
 * }</pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class VersionReconstructor {

    private static final Logger logger = LoggerFactory.getLogger(VersionReconstructor.class);

    // ==================== 常量 ====================

    /**
     * 默认列数量（用于初始化）
     */
    private static final int DEFAULT_COLUMN_COUNT = 100;

    // ==================== 内部类 ====================

    /**
     * 重建后的版本信息
     */
    public static class ReconstructedVersion {
        /**
         * 是否完整（所有列都已补齐）
         */
        private final boolean complete;

        /**
         * 重建后的列值映射
         */
        private final Map<Integer, byte[]> columnValues;

        /**
         * 遍历的 Undo 记录数
         */
        private final int undoRecordsTraversed;

        /**
         * 补齐的列数
         */
        private final int columnsReconstructed;

        public ReconstructedVersion(boolean complete, Map<Integer, byte[]> columnValues,
                                   int undoRecordsTraversed, int columnsReconstructed) {
            this.complete = complete;
            this.columnValues = new HashMap<>(columnValues);
            this.undoRecordsTraversed = undoRecordsTraversed;
            this.columnsReconstructed = columnsReconstructed;
        }

        /**
         * 是否完整
         *
         * @return 如果所有列都已补齐返回 true
         */
        public boolean isComplete() {
            return complete;
        }

        /**
         * 获取列值
         *
         * @param columnId 列 ID
         * @return 列值，如果不存在返回 null
         */
        public byte[] getColumnValue(int columnId) {
            return columnValues.get(columnId);
        }

        /**
         * 获取所有列值
         *
         * @return 列值映射的副本
         */
        public Map<Integer, byte[]> getAllColumnValues() {
            return new HashMap<>(columnValues);
        }

        /**
         * 获取遍历的 Undo 记录数
         *
         * @return 记录数
         */
        public int getUndoRecordsTraversed() {
            return undoRecordsTraversed;
        }

        /**
         * 获取补齐的列数
         *
         * @return 列数
         */
        public int getColumnsReconstructed() {
            return columnsReconstructed;
        }

        @Override
        public String toString() {
            return String.format("ReconstructedVersion{complete=%s, cols=%d, traversed=%d, reconstructed=%d}",
                    complete, columnValues.size(), undoRecordsTraversed, columnsReconstructed);
        }
    }

    // ==================== 公共方法 ====================

    /**
     * 重建历史版本
     *
     * <p>从当前记录和 Undo 链重建指定事务 ID 的历史版本。
     * 使用"补齐即停"策略，不必遍历整条链。</p>
     *
     * @param currentRecord    当前记录的列值
     * @param undoChain        Undo 链（从新到旧）
     * @param targetTrxId      目标事务 ID
     * @param expectedColumns  期望的列 ID 列表（用于判断是否完整）
     * @return 重建后的版本信息
     */
    public ReconstructedVersion reconstruct(Map<Integer, byte[]> currentRecord,
                                           List<UpdateUndoRecord> undoChain,
                                           TransactionId targetTrxId,
                                           List<Integer> expectedColumns) {
        if (currentRecord == null || undoChain == null || targetTrxId == null) {
            throw new NullPointerException("Arguments cannot be null");
        }

        // 初始化重建的列值（从当前记录开始）
        Map<Integer, byte[]> reconstructed = new HashMap<>(currentRecord);

        int undoRecordsTraversed = 0;
        int columnsReconstructed = 0;

        // 从新到旧遍历 Undo 链
        for (UpdateUndoRecord undo : undoChain) {
            undoRecordsTraversed++;

            // 检查是否已到达目标事务
            if (undo.getTrxId().getValue() < targetTrxId.getValue()) {
                logger.trace("Reached target transaction, stopping reconstruction");
                break;
            }

            // 处理 Schema 变更（如果需要）
            if (undo.getSchemaVersion() != UndoRecordVersion.INITIAL_SCHEMA_VERSION) {
                logger.debug("Schema version mismatch: undo={}, current={}",
                        undo.getSchemaVersion(), UndoRecordVersion.INITIAL_SCHEMA_VERSION);
                // 这里可以添加 Schema 转换逻辑
                // applySchemaEvolution(reconstructed, undo);
            }

            // 按需补齐列
            for (UpdateUndoRecord.OldColumnValue oldCol : undo.getOldColumns()) {
                int colId = oldCol.columnId;

                // 如果该列还未被补齐，从 Undo 记录中取值
                if (!reconstructed.containsKey(colId)) {
                    reconstructed.put(colId, oldCol.value);
                    columnsReconstructed++;
                    logger.trace("Reconstructed column {}: {} bytes", colId, oldCol.value.length);
                }
            }

            // 检查是否已补齐所有期望的列（补齐即停）
            if (expectedColumns != null && isAllColumnsReconstructed(reconstructed, expectedColumns)) {
                logger.trace("All expected columns reconstructed, stopping");
                break;
            }
        }

        // 判断是否完整
        boolean complete = expectedColumns == null || isAllColumnsReconstructed(reconstructed, expectedColumns);

        return new ReconstructedVersion(complete, reconstructed, undoRecordsTraversed, columnsReconstructed);
    }

    /**
     * 重建历史版本（不指定期望列）
     *
     * @param currentRecord 当前记录的列值
     * @param undoChain     Undo 链（从新到旧）
     * @param targetTrxId   目标事务 ID
     * @return 重建后的版本信息
     */
    public ReconstructedVersion reconstruct(Map<Integer, byte[]> currentRecord,
                                           List<UpdateUndoRecord> undoChain,
                                           TransactionId targetTrxId) {
        return reconstruct(currentRecord, undoChain, targetTrxId, null);
    }

    /**
     * 计算重建版本所需的空间
     *
     * <p>估算从当前记录重建到指定事务 ID 所需的空间。</p>
     *
     * @param currentRecord 当前记录的列值
     * @param undoChain     Undo 链
     * @param targetTrxId   目标事务 ID
     * @return 估算的空间大小（字节）
     */
    public long estimateReconstructionSpace(Map<Integer, byte[]> currentRecord,
                                           List<UpdateUndoRecord> undoChain,
                                           TransactionId targetTrxId) {
        long totalSpace = 0;

        // 当前记录的空间
        for (byte[] value : currentRecord.values()) {
            totalSpace += value.length;
        }

        // Undo 链的空间
        for (UpdateUndoRecord undo : undoChain) {
            if (undo.getTrxId().getValue() < targetTrxId.getValue()) {
                break;
            }

            for (UpdateUndoRecord.OldColumnValue oldCol : undo.getOldColumns()) {
                totalSpace += oldCol.value.length;
            }
        }

        return totalSpace;
    }

    /**
     * 获取版本链的统计信息
     *
     * @param undoChain Undo 链
     * @return 统计信息
     */
    public VersionChainStats getChainStats(List<UpdateUndoRecord> undoChain) {
        if (undoChain == null || undoChain.isEmpty()) {
            return new VersionChainStats(0, 0, 0);
        }

        int totalRecords = undoChain.size();
        int totalColumns = 0;
        long totalSize = 0;

        for (UpdateUndoRecord undo : undoChain) {
            totalColumns += undo.getColumnCount();
            for (UpdateUndoRecord.OldColumnValue col : undo.getOldColumns()) {
                totalSize += col.value.length;
            }
        }

        return new VersionChainStats(totalRecords, totalColumns, totalSize);
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查是否所有期望的列都已补齐
     *
     * @param reconstructed   重建的列值
     * @param expectedColumns 期望的列 ID 列表
     * @return 如果所有列都已补齐返回 true
     */
    private boolean isAllColumnsReconstructed(Map<Integer, byte[]> reconstructed,
                                             List<Integer> expectedColumns) {
        for (int colId : expectedColumns) {
            if (!reconstructed.containsKey(colId)) {
                return false;
            }
        }
        return true;
    }

    // ==================== 内部类：版本链统计 ====================

    /**
     * 版本链统计信息
     */
    public static class VersionChainStats {
        /**
         * 版本链中的 Undo 记录数
         */
        public final int totalRecords;

        /**
         * 版本链中的列总数
         */
        public final int totalColumns;

        /**
         * 版本链占用的总空间（字节）
         */
        public final long totalSize;

        public VersionChainStats(int totalRecords, int totalColumns, long totalSize) {
            this.totalRecords = totalRecords;
            this.totalColumns = totalColumns;
            this.totalSize = totalSize;
        }

        /**
         * 获取平均每个 Undo 记录的列数
         *
         * @return 平均列数
         */
        public double getAverageColumnsPerRecord() {
            if (totalRecords == 0) return 0.0;
            return (double) totalColumns / totalRecords;
        }

        /**
         * 获取平均每个列的大小
         *
         * @return 平均大小（字节）
         */
        public double getAverageColumnSize() {
            if (totalColumns == 0) return 0.0;
            return (double) totalSize / totalColumns;
        }

        @Override
        public String toString() {
            return String.format("VersionChainStats{records=%d, cols=%d, size=%dB, avgCols=%.2f, avgSize=%.2fB}",
                    totalRecords, totalColumns, totalSize,
                    getAverageColumnsPerRecord(), getAverageColumnSize());
        }
    }
}
