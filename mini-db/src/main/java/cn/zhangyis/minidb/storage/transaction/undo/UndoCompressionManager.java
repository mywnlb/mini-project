package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import cn.zhangyis.minidb.storage.transaction.purge.PurgeCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Undo 压缩管理器
 *
 * <p>管理 Undo 链段的压缩和合并操作。</p>
 *
 * <h2>压缩流程</h2>
 * <pre>
 * 1. 识别可合并的链段
 *    - 检查链长度、事务 ID、大小限制
 *
 * 2. 创建合并后的 Undo 记录
 *    - 合并多个 UPDATE Undo 为一个
 *    - 只保留最终的列值
 *
 * 3. 追加写合并后的 Undo
 *    - 在 MTR 保护下写入新 Undo 记录
 *    - 生成 redo 日志
 *
 * 4. 更新记录的 roll_ptr
 *    - X-latch 数据页
 *    - 验证 ABA 冲突
 *    - 更新 roll_ptr 指向新 Undo
 *    - 生成 redo 日志
 *
 * 5. 旧 Undo 进入正常 purge 流程
 *    - 不需要特殊处理
 *    - 最终被 purge 线程清理
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
 * UndoCompressionManager compressor = new UndoCompressionManager(
 *     undoLogManager,
 *     purgeCoordinator,
 *     bufferPool
 * );
 *
 * // 执行压缩
 * UndoCompressionManager.CompressionResult result = compressor.compressUndoChain(
 *     record,
 *     undoChain,
 *     mtr
 * );
 *
 * if (result.isSuccessful()) {
 *     logger.info("Compression successful: {}",
 *             result.getCompressionStats());
 * }
 * }</pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class UndoCompressionManager {

    private static final Logger logger = LoggerFactory.getLogger(UndoCompressionManager.class);

    // ==================== 常量 ====================

    /**
     * 默认最大并发压缩任务数
     */
    public static final int DEFAULT_MAX_CONCURRENT_COMPRESSIONS = 10;

    // ==================== 字段 ====================

    /**
     * Undo Log 管理器
     */
    private final UndoLogManager undoLogManager;

    /**
     * Purge 协调器
     */
    private final PurgeCoordinator purgeCoordinator;

    /**
     * Undo 链段剪枝器
     */
    private final UndoPruner pruner;

    /**
     * 统计：成功压缩的链段数
     */
    private final AtomicLong successfulCompressions;

    /**
     * 统计：失败的压缩尝试数
     */
    private final AtomicLong failedCompressions;

    /**
     * 统计：总节省空间（字节）
     */
    private final AtomicLong totalSpaceSavings;

    // ==================== 内部类 ====================

    /**
     * 压缩结果
     */
    public static class CompressionResult {
        /**
         * 是否成功
         */
        private final boolean successful;

        /**
         * 失败原因（如果失败）
         */
        private final String failureReason;

        /**
         * 原始链长度
         */
        private final int originalChainLength;

        /**
         * 合并后的链长度
         */
        private final int mergedChainLength;

        /**
         * 原始大小（字节）
         */
        private final int originalSize;

        /**
         * 合并后大小（字节）
         */
        private final int mergedSize;

        /**
         * 新的 roll_ptr
         */
        private final RollbackPointer newRollPtr;

        public CompressionResult(boolean successful, String failureReason,
                                int originalChainLength, int mergedChainLength,
                                int originalSize, int mergedSize,
                                RollbackPointer newRollPtr) {
            this.successful = successful;
            this.failureReason = failureReason;
            this.originalChainLength = originalChainLength;
            this.mergedChainLength = mergedChainLength;
            this.originalSize = originalSize;
            this.mergedSize = mergedSize;
            this.newRollPtr = newRollPtr;
        }

        /**
         * 是否成功
         *
         * @return 如果成功返回 true
         */
        public boolean isSuccessful() {
            return successful;
        }

        /**
         * 获取失败原因
         *
         * @return 失败原因，如果成功返回 null
         */
        public String getFailureReason() {
            return failureReason;
        }

        /**
         * 获取压缩统计
         *
         * @return 统计字符串
         */
        public String getCompressionStats() {
            if (!successful) {
                return String.format("Failed: %s", failureReason);
            }

            int spaceSavings = originalSize - mergedSize;
            double compressionRatio = (double) spaceSavings / originalSize * 100;

            return String.format(
                    "Success: chain %d→%d, size %dB→%dB, savings %dB (%.2f%%)",
                    originalChainLength, mergedChainLength,
                    originalSize, mergedSize,
                    spaceSavings, compressionRatio
            );
        }

        /**
         * 获取空间节省
         *
         * @return 节省的字节数
         */
        public int getSpaceSavings() {
            return originalSize - mergedSize;
        }

        @Override
        public String toString() {
            return getCompressionStats();
        }
    }

    // ==================== 构造函数 ====================

    /**
     * 创建 Undo 压缩管理器
     *
     * @param undoLogManager Undo Log 管理器
     * @param purgeCoordinator Purge 协调器
     */
    public UndoCompressionManager(UndoLogManager undoLogManager,
                                 PurgeCoordinator purgeCoordinator) {
        this.undoLogManager = undoLogManager;
        this.purgeCoordinator = purgeCoordinator;
        this.pruner = new UndoPruner(undoLogManager, purgeCoordinator);
        this.successfulCompressions = new AtomicLong(0);
        this.failedCompressions = new AtomicLong(0);
        this.totalSpaceSavings = new AtomicLong(0);
    }

    // ==================== 公共方法 ====================

    /**
     * 压缩 Undo 链
     *
     * <p>尝试压缩指定记录的 Undo 链。</p>
     *
     * @param primaryKey 记录的主键
     * @param tableId 表 ID
     * @param undoChain Undo 链（从新到旧）
     * @param currentRollPtr 当前的 roll_ptr
     * @param mtr 迷你事务
     * @return 压缩结果
     */
    public CompressionResult compressUndoChain(byte[] primaryKey, int tableId,
                                              List<UpdateUndoRecord> undoChain,
                                              RollbackPointer currentRollPtr,
                                              MiniTransaction mtr) {
        try {
            // 1. 识别可合并的链段
            UndoPruner.MergeableSegment segment = pruner.identifyMergeableSegment(
                    primaryKey, tableId, undoChain, currentRollPtr
            );

            if (segment == null || !segment.canMerge()) {
                failedCompressions.incrementAndGet();
                return new CompressionResult(
                        false, "Chain not mergeable",
                        undoChain.size(), undoChain.size(),
                        calculateChainSize(undoChain), calculateChainSize(undoChain),
                        currentRollPtr
                );
            }

            // 2. 创建合并后的 Undo 记录
            UpdateUndoRecord mergedUndo = pruner.createMergedUndo(segment);

            // 3. 追加写合并后的 Undo
            RollbackPointer mergedPtr = appendMergedUndo(mergedUndo, mtr);

            if (mergedPtr == null || mergedPtr.isNull()) {
                failedCompressions.incrementAndGet();
                return new CompressionResult(
                        false, "Failed to append merged undo",
                        segment.undoChain.size(), segment.undoChain.size(),
                        calculateChainSize(segment.undoChain), calculateChainSize(segment.undoChain),
                        currentRollPtr
                );
            }

            // 4. 验证 ABA 冲突
            if (!pruner.verifyNoABAConflict(segment, currentRollPtr)) {
                failedCompressions.incrementAndGet();
                return new CompressionResult(
                        false, "ABA conflict detected",
                        segment.undoChain.size(), 1,
                        calculateChainSize(segment.undoChain), mergedUndo.calculateSize(),
                        currentRollPtr
                );
            }

            // 5. 更新记录的 roll_ptr
            // 注意：调用者（通常是 CompressionThread）负责：
            // 1. 在 B+Tree 中查找实际记录
            // 2. 获取记录的页 ID 和页内偏移
            // 3. 调用 TransactionalDml.updateRollPtr() 更新 roll_ptr
            // 这里只返回新的 roll_ptr，不执行实际更新

            // 6. 记录统计信息
            int originalSize = calculateChainSize(segment.undoChain);
            int mergedSize = mergedUndo.calculateSize();
            int spaceSavings = originalSize - mergedSize;

            successfulCompressions.incrementAndGet();
            totalSpaceSavings.addAndGet(spaceSavings);

            logger.info("Undo compression successful: {}",
                    pruner.getSegmentStats(segment));

            return new CompressionResult(
                    true, null,
                    segment.undoChain.size(), 1,
                    originalSize, mergedSize,
                    mergedPtr
            );

        } catch (Exception e) {
            failedCompressions.incrementAndGet();
            logger.error("Undo compression failed", e);
            return new CompressionResult(
                    false, e.getMessage(),
                    undoChain.size(), undoChain.size(),
                    calculateChainSize(undoChain), calculateChainSize(undoChain),
                    currentRollPtr
            );
        }
    }

    /**
     * 批量压缩 Undo 链
     *
     * <p>对多个记录的 Undo 链进行压缩。</p>
     *
     * @param compressionTasks 压缩任务列表
     * @param mtr 迷你事务
     * @return 压缩结果列表
     */
    public List<CompressionResult> batchCompress(List<CompressionTask> compressionTasks,
                                                 MiniTransaction mtr) {
        List<CompressionResult> results = new ArrayList<>();

        for (CompressionTask task : compressionTasks) {
            CompressionResult result = compressUndoChain(
                    task.primaryKey,
                    task.tableId,
                    task.undoChain,
                    task.currentRollPtr,
                    mtr
            );
            results.add(result);
        }

        return results;
    }

    /**
     * 获取压缩统计信息
     *
     * @return 统计信息
     */
    public CompressionStats getCompressionStats() {
        return new CompressionStats(
                successfulCompressions.get(),
                failedCompressions.get(),
                totalSpaceSavings.get()
        );
    }

    // ==================== 辅助方法 ====================

    /**
     * 追加写合并后的 Undo 记录
     *
     * <p>在 MTR 保护下写入新 Undo 记录。</p>
     *
     * @param mergedUndo 合并后的 Undo 记录
     * @param mtr 迷你事务
     * @return 新的 roll_ptr，如果失败返回 null
     */
    private RollbackPointer appendMergedUndo(UpdateUndoRecord mergedUndo, MiniTransaction mtr) {
        try {
            // 调用 UndoLogManager 的 appendMergedUndo 方法
            // 该方法负责：
            // 1. 获取或分配 Undo Segment
            // 2. 在 MTR 保护下写入 Undo 记录
            // 3. 生成 redo 日志（MTR 自动处理）
            // 4. 返回 RollbackPointer

            logger.debug("Appending merged undo: {}", mergedUndo);
            return undoLogManager.appendMergedUndo(mergedUndo, mtr);

        } catch (Exception e) {
            logger.error("Failed to append merged undo", e);
            return null;
        }
    }

    /**
     * 计算链的总大小
     *
     * @param undoChain Undo 链
     * @return 总大小（字节）
     */
    private int calculateChainSize(List<UpdateUndoRecord> undoChain) {
        int totalSize = 0;
        for (UpdateUndoRecord undo : undoChain) {
            totalSize += undo.calculateSize();
        }
        return totalSize;
    }

    // ==================== 内部类 ====================

    /**
     * 压缩任务
     */
    public static class CompressionTask {
        /**
         * 记录的主键
         */
        public final byte[] primaryKey;

        /**
         * 表 ID
         */
        public final int tableId;

        /**
         * Undo 链
         */
        public final List<UpdateUndoRecord> undoChain;

        /**
         * 当前的 roll_ptr
         */
        public final RollbackPointer currentRollPtr;

        public CompressionTask(byte[] primaryKey, int tableId,
                              List<UpdateUndoRecord> undoChain,
                              RollbackPointer currentRollPtr) {
            this.primaryKey = primaryKey;
            this.tableId = tableId;
            this.undoChain = new ArrayList<>(undoChain);
            this.currentRollPtr = currentRollPtr;
        }
    }

    /**
     * 压缩统计信息
     */
    public static class CompressionStats {
        /**
         * 成功压缩的链段数
         */
        public final long successfulCompressions;

        /**
         * 失败的压缩尝试数
         */
        public final long failedCompressions;

        /**
         * 总节省空间（字节）
         */
        public final long totalSpaceSavings;

        public CompressionStats(long successfulCompressions, long failedCompressions,
                               long totalSpaceSavings) {
            this.successfulCompressions = successfulCompressions;
            this.failedCompressions = failedCompressions;
            this.totalSpaceSavings = totalSpaceSavings;
        }

        /**
         * 获取成功率
         *
         * @return 成功率（0-1）
         */
        public double getSuccessRate() {
            long total = successfulCompressions + failedCompressions;
            if (total == 0) return 0.0;
            return (double) successfulCompressions / total;
        }

        @Override
        public String toString() {
            return String.format(
                    "CompressionStats{success=%d, failed=%d, rate=%.2f%%, savings=%dB}",
                    successfulCompressions, failedCompressions,
                    getSuccessRate() * 100, totalSpaceSavings
            );
        }
    }
}
