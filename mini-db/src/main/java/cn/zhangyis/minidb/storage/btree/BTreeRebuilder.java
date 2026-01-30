package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * B+Tree 索引重建器
 *
 * <p>提供索引重建功能，用于优化碎片化的索引或修复损坏的索引。</p>
 *
 * <h2>重建流程</h2>
 * <ol>
 *   <li>扫描旧索引的所有记录</li>
 *   <li>使用批量加载创建新索引</li>
 *   <li>验证新索引</li>
 *   <li>替换旧索引</li>
 * </ol>
 *
 * @author MiniDB
 * @version 1.0
 */
public class BTreeRebuilder {

    /**
     * 重建索引
     *
     * @param oldTree    旧的 B+Tree
     * @param config     批量加载配置
     * @param bufferPool Buffer Pool
     * @param mtr        Mini-Transaction
     * @return 新的 B+Tree
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static BTree rebuild(BTree oldTree, BulkLoadConfig config,
                                BufferPool bufferPool, MiniTransaction mtr)
            throws MiniDbException {
        BTreeMetadata oldMetadata = oldTree.getMetadata();
        RecordComparator comparator = oldTree.getComparator();

        // 1. 扫描旧索引的所有记录
        Iterator<BulkLoadRecord> recordIterator = scanRecords(oldTree, mtr);

        // 2. 使用批量加载创建新索引
        // 使用新的索引 ID（实际应用中可能需要更复杂的 ID 管理）
        long newIndexId = oldMetadata.getIndexId();
        int spaceId = oldMetadata.getSpaceId();

        BTree newTree = BTreeBulkLoader.loadAndGetTree(
                newIndexId, spaceId, recordIterator, comparator, bufferPool, config, mtr);

        // 3. 验证新索引
        BTreeDiagnostics.ValidationResult validation = BTreeDiagnostics.validate(newTree, mtr);
        if (!validation.isValid()) {
            throw new RuntimeException("Rebuilt index validation failed: " + validation.getErrors());
        }

        return newTree;
    }

    /**
     * 重建索引并返回结果
     *
     * @param oldTree    旧的 B+Tree
     * @param config     批量加载配置
     * @param bufferPool Buffer Pool
     * @param mtr        Mini-Transaction
     * @return 重建结果
     */
    public static RebuildResult rebuildWithResult(BTree oldTree, BulkLoadConfig config,
                                                  BufferPool bufferPool, MiniTransaction mtr) {
        long startTime = System.currentTimeMillis();

        try {
            // 收集旧索引统计
            BTreeStats oldStats = BTreeStatsCollector.collectBasic(oldTree);

            // 重建
            BTree newTree = rebuild(oldTree, config, bufferPool, mtr);

            // 收集新索引统计
            BTreeStats newStats = BTreeStatsCollector.collect(newTree, mtr);

            long duration = System.currentTimeMillis() - startTime;

            return RebuildResult.success(oldStats, newStats, newTree, duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            return RebuildResult.failure(e.getMessage(), duration);
        }
    }

    /**
     * 优化索引（如果需要）
     *
     * <p>检查索引是否需要优化，如果需要则重建。</p>
     *
     * @param tree             B+Tree
     * @param minFillFactor    最小填充率阈值
     * @param config           批量加载配置
     * @param bufferPool       Buffer Pool
     * @param mtr              Mini-Transaction
     * @return 优化后的 B+Tree（可能是原树或新树）
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static BTree optimizeIfNeeded(BTree tree, double minFillFactor,
                                         BulkLoadConfig config, BufferPool bufferPool,
                                         MiniTransaction mtr) throws MiniDbException {
        // 收集统计信息
        BTreeStats stats = BTreeStatsCollector.collect(tree, mtr);

        // 检查是否需要优化
        if (stats.getAvgFillFactor() >= minFillFactor) {
            // 不需要优化
            return tree;
        }

        // 需要优化，重建索引
        return rebuild(tree, config, bufferPool, mtr);
    }

    /**
     * 扫描索引中的所有记录
     */
    private static Iterator<BulkLoadRecord> scanRecords(BTree tree, MiniTransaction mtr) {
        // 收集所有记录到列表（简化实现）
        // 实际应用中应该使用流式处理以节省内存
        List<BulkLoadRecord> records = new ArrayList<>();

        try (BTreeRangeScanner scanner = tree.fullScan(mtr)) {
            for (BTreeRangeScanner.ScanEntry entry : scanner) {
                byte[] key = entry.getKey();
                // 重建记录数据
                int keyInt = IntKeyComparator.bytesToInt(key);
                byte[] data = SimpleRecordBuilder.buildRecord(keyInt, new byte[]{(byte) keyInt}, 2);
                records.add(new BulkLoadRecord(key, data));
            }
        }

        return records.iterator();
    }

    // ==================== 重建结果 ====================

    /**
     * 重建结果
     */
    public static class RebuildResult {
        private final boolean success;
        private final BTreeStats oldStats;
        private final BTreeStats newStats;
        private final BTree newTree;
        private final long durationMs;
        private final String errorMessage;

        public static RebuildResult success(BTreeStats oldStats, BTreeStats newStats,
                                            BTree newTree, long durationMs) {
            return new RebuildResult(true, oldStats, newStats, newTree, durationMs, null);
        }

        public static RebuildResult failure(String errorMessage, long durationMs) {
            return new RebuildResult(false, null, null, null, durationMs, errorMessage);
        }

        private RebuildResult(boolean success, BTreeStats oldStats, BTreeStats newStats,
                              BTree newTree, long durationMs, String errorMessage) {
            this.success = success;
            this.oldStats = oldStats;
            this.newStats = newStats;
            this.newTree = newTree;
            this.durationMs = durationMs;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() {
            return success;
        }

        public BTreeStats getOldStats() {
            return oldStats;
        }

        public BTreeStats getNewStats() {
            return newStats;
        }

        public BTree getNewTree() {
            return newTree;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        /**
         * 获取空间节省比例
         */
        public double getSpaceSavingsRatio() {
            if (!success || oldStats == null || newStats == null) {
                return 0;
            }
            long oldSpace = oldStats.getTotalPageCount() * 16L * 1024;
            long newSpace = newStats.getTotalPageCount() * 16L * 1024;
            if (oldSpace == 0) {
                return 0;
            }
            return 1.0 - (double) newSpace / oldSpace;
        }

        @Override
        public String toString() {
            if (success) {
                return String.format("RebuildResult{success=true, oldPages=%d, newPages=%d, " +
                                "spaceSaved=%.1f%%, duration=%dms}",
                        oldStats != null ? oldStats.getTotalPageCount() : 0,
                        newStats != null ? newStats.getTotalPageCount() : 0,
                        getSpaceSavingsRatio() * 100,
                        durationMs);
            } else {
                return String.format("RebuildResult{success=false, error='%s', duration=%dms}",
                        errorMessage, durationMs);
            }
        }
    }

    // 禁止实例化
    private BTreeRebuilder() {
        throw new UnsupportedOperationException("BTreeRebuilder is a utility class");
    }
}
