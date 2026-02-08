package cn.zhangyis.minidb.storage.transaction.purge;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.btree.BTree;
import cn.zhangyis.minidb.storage.btree.BTreeRangeScanner;
import cn.zhangyis.minidb.storage.btree.IndexManager;
import cn.zhangyis.minidb.storage.btree.RecordComparator;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.record.format.CompactRecordFormat;
import cn.zhangyis.minidb.storage.record.physical.SystemLayout;
import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import cn.zhangyis.minidb.storage.transaction.undo.UndoCompressionManager;
import cn.zhangyis.minidb.storage.transaction.undo.UndoLogManager;
import cn.zhangyis.minidb.storage.transaction.undo.UpdateUndoRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Undo 压缩后台线程
 *
 * <p>后台定期运行，识别和压缩可合并的 Undo 链段。</p>
 *
 * <h2>工作原理</h2>
 * <ol>
 *   <li>从 PurgeCoordinator 获取当前 Purge 边界</li>
 *   <li>扫描所有记录的版本链</li>
 *   <li>识别可合并的链段</li>
 *   <li>执行压缩（追加写合并后的 Undo）</li>
 *   <li>更新记录的 roll_ptr</li>
 * </ol>
 *
 * <h2>设计约束 (Invariants)</h2>
 * <ul>
 *   <li><b>U1</b>：Undo 记录不可修改 - 追加写保证</li>
 *   <li><b>U2</b>：版本链完整性 - 合并后版本链仍完整</li>
 *   <li><b>P1</b>：不能清理活跃 ReadView 需要的 Undo</li>
 *   <li><b>P4</b>：Purge 操作幂等性</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * CompressionThread compressionThread = new CompressionThread(
 *     coordinator,
 *     undoLogManager,
 *     bufferPool
 * );
 *
 * // 启动压缩线程
 * compressionThread.start();
 *
 * // 关闭时
 * compressionThread.shutdown();
 * }</pre>
 *
 * @author MiniDB
 * @version 1.0
 * @see PurgeCoordinator
 * @see UndoLogManager
 * @see UndoCompressionManager
 */
public class CompressionThread extends Thread {

    private static final Logger logger = LoggerFactory.getLogger(CompressionThread.class);

    // ==================== 常量 ====================

    /**
     * 默认压缩间隔（毫秒）
     */
    public static final long DEFAULT_COMPRESSION_INTERVAL_MS = 5000;

    /**
     * 默认每轮压缩的最大链段数
     */
    public static final int DEFAULT_MAX_COMPRESSIONS_PER_ROUND = 100;

    /**
     * 默认最小可合并链段长度
     */
    public static final int DEFAULT_MIN_CHAIN_LENGTH = 5;

    // ==================== 字段 ====================

    /**
     * Purge 协调器
     */
    private final PurgeCoordinator coordinator;

    /**
     * Undo Log 管理器
     */
    private final UndoLogManager undoLogManager;

    /**
     * Buffer Pool
     */
    private final BufferPool bufferPool;

    /**
     * 索引管理器
     */
    private final IndexManager indexManager;

    /**
     * Undo 压缩管理器
     */
    private final UndoCompressionManager compressionManager;

    /**
     * 压缩间隔（毫秒）
     */
    private final long compressionIntervalMs;

    /**
     * 每轮压缩的最大链段数
     */
    private final int maxCompressionsPerRound;

    /**
     * 最小可合并链段长度
     */
    private final int minChainLength;

    /**
     * 运行标志
     */
    private final AtomicBoolean running;

    /**
     * 是否暂停
     */
    private final AtomicBoolean paused;

    /**
     * 统计：成功压缩的链段数
     */
    private final AtomicLong totalCompressions;

    /**
     * 统计：压缩轮数
     */
    private final AtomicLong compressionRounds;

    /**
     * 统计：总节省空间（字节）
     */
    private final AtomicLong totalSpaceSavings;

    /**
     * 上次压缩的边界
     */
    private volatile TransactionId lastCompressionLimit;

    // ==================== 构造函数 ====================

    /**
     * 创建压缩线程
     *
     * @param coordinator Purge 协调器
     * @param undoLogManager Undo Log 管理器
     * @param bufferPool Buffer Pool
     * @param indexManager 索引管理器
     */
    public CompressionThread(PurgeCoordinator coordinator,
                            UndoLogManager undoLogManager,
                            BufferPool bufferPool,
                            IndexManager indexManager) {
        this(coordinator, undoLogManager, bufferPool, indexManager,
                DEFAULT_COMPRESSION_INTERVAL_MS,
                DEFAULT_MAX_COMPRESSIONS_PER_ROUND,
                DEFAULT_MIN_CHAIN_LENGTH);
    }

    /**
     * 创建压缩线程
     *
     * @param coordinator Purge 协调器
     * @param undoLogManager Undo Log 管理器
     * @param bufferPool Buffer Pool
     * @param indexManager 索引管理器
     * @param compressionIntervalMs 压缩间隔（毫秒）
     * @param maxCompressionsPerRound 每轮最大压缩数
     * @param minChainLength 最小可合并链段长度
     */
    public CompressionThread(PurgeCoordinator coordinator,
                            UndoLogManager undoLogManager,
                            BufferPool bufferPool,
                            IndexManager indexManager,
                            long compressionIntervalMs,
                            int maxCompressionsPerRound,
                            int minChainLength) {
        super("MiniDB-Compression-Thread");
        setDaemon(true);

        if (coordinator == null || undoLogManager == null || bufferPool == null || indexManager == null) {
            throw new NullPointerException("Arguments cannot be null");
        }

        this.coordinator = coordinator;
        this.undoLogManager = undoLogManager;
        this.bufferPool = bufferPool;
        this.indexManager = indexManager;
        this.compressionManager = new UndoCompressionManager(undoLogManager, coordinator);
        this.compressionIntervalMs = compressionIntervalMs;
        this.maxCompressionsPerRound = maxCompressionsPerRound;
        this.minChainLength = minChainLength;
        this.running = new AtomicBoolean(false);
        this.paused = new AtomicBoolean(false);
        this.totalCompressions = new AtomicLong(0);
        this.compressionRounds = new AtomicLong(0);
        this.totalSpaceSavings = new AtomicLong(0);
        this.lastCompressionLimit = new TransactionId(0);
    }

    // ==================== 生命周期管理 ====================

    @Override
    public void run() {
        running.set(true);
        logger.info("Compression thread started, interval={}ms, maxCompressions={}",
                compressionIntervalMs, maxCompressionsPerRound);

        while (running.get()) {
            try {
                // 等待下一个压缩周期
                Thread.sleep(compressionIntervalMs);

                // 检查是否暂停
                if (paused.get()) {
                    continue;
                }

                // 执行压缩
                doCompressionRound();

            } catch (InterruptedException e) {
                if (!running.get()) {
                    // 正常关闭
                    break;
                }
                logger.warn("Compression thread interrupted", e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // 压缩失败不应该终止线程
                logger.error("Compression round failed", e);
            }
        }

        logger.info("Compression thread stopped, totalCompressions={}, rounds={}, spaceSavings={}B",
                totalCompressions.get(), compressionRounds.get(), totalSpaceSavings.get());
    }

    /**
     * 关闭压缩线程
     */
    public void shutdown() {
        running.set(false);
        interrupt();

        try {
            join(5000); // 等待最多 5 秒
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for compression thread to stop");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 暂停压缩
     */
    public void pause() {
        paused.set(true);
        logger.info("Compression thread paused");
    }

    /**
     * 恢复压缩
     */
    public void resume() {
        paused.set(false);
        logger.info("Compression thread resumed");
    }

    // ==================== 压缩逻辑 ====================

    /**
     * 执行一轮压缩
     */
    private void doCompressionRound() {
        // 获取当前 Purge 边界
        TransactionId purgeLimit = coordinator.getPurgeLimit();

        // 检查是否有新的可压缩范围
        if (purgeLimit.getValue() <= lastCompressionLimit.getValue()) {
            // 没有新的可压缩记录
            return;
        }

        logger.debug("Starting compression round: lastLimit={}, newLimit={}",
                lastCompressionLimit, purgeLimit);

        long startTime = System.currentTimeMillis();
        int compressedCount = 0;
        long spaceSavings = 0;

        try {
            // 识别待压缩的链段
            List<CompressionCandidate> candidates = identifyCompressionCandidates(purgeLimit);

            if (candidates.isEmpty()) {
                logger.trace("No compression candidates found");
                return;
            }

            logger.debug("Found {} compression candidates", candidates.size());

            // 执行压缩
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                for (CompressionCandidate candidate : candidates) {
                    if (compressedCount >= maxCompressionsPerRound) {
                        logger.trace("Reached max compressions per round: {}", maxCompressionsPerRound);
                        break;
                    }

                    UndoCompressionManager.CompressionResult result =
                            compressionManager.compressUndoChain(
                                    candidate.primaryKey,
                                    candidate.tableId,
                                    candidate.undoChain,
                                    candidate.currentRollPtr,
                                    mtr
                            );

                    if (result.isSuccessful()) {
                        compressedCount++;
                        spaceSavings += result.getSpaceSavings();
                        logger.trace("Compression successful: {}", result);
                    } else {
                        logger.trace("Compression failed: {}", result.getFailureReason());
                    }
                }

                mtr.commit();
            }

            // 更新最后压缩边界
            lastCompressionLimit = purgeLimit;

        } finally {
            long duration = System.currentTimeMillis() - startTime;
            compressionRounds.incrementAndGet();
            totalCompressions.addAndGet(compressedCount);
            totalSpaceSavings.addAndGet(spaceSavings);

            if (compressedCount > 0) {
                logger.debug("Compression round completed: compressed={}, savings={}B, duration={}ms",
                        compressedCount, spaceSavings, duration);
            }
        }
    }

    /**
     * 识别待压缩的链段
     *
     * <p>扫描所有记录的版本链，识别可合并的链段。</p>
     *
     * <p>设计约束：
     * <ul>
     *   <li><b>I4</b>：Purge 边界尊重 - 只识别 trx_id < purgeLimit 的候选</li>
     *   <li><b>I2</b>：版本链完整性 - 只识别可安全合并的链</li>
     * </ul>
     * </p>
     *
     * @param purgeLimit Purge 边界
     * @return 待压缩的链段列表
     */
    private List<CompressionCandidate> identifyCompressionCandidates(TransactionId purgeLimit) {
        List<CompressionCandidate> candidates = new ArrayList<>();

        try {
            // 1. 获取所有表的 ID
            Set<Long> tableIds = indexManager.getAllTableIds();

            if (tableIds.isEmpty()) {
                logger.debug("No tables found for compression candidate identification");
                return candidates;
            }

            logger.debug("Scanning {} tables for compression candidates", tableIds.size());

            // 2. 对每个表进行全表扫描
            for (long tableId : tableIds) {
                if (candidates.size() >= maxCompressionsPerRound) {
                    logger.debug("Reached max compressions per round: {}",
                            maxCompressionsPerRound);
                    return candidates;
                }

                try {
                    // 获取表的所有索引（通常第一个是聚簇索引）
                    List<cn.zhangyis.minidb.storage.btree.IndexDescriptor> indexes =
                            indexManager.getTableIndexes(tableId);

                    if (indexes.isEmpty()) {
                        logger.trace("No indexes found for table: {}", tableId);
                        continue;
                    }

                    // 使用聚簇索引进行全表扫描
                    cn.zhangyis.minidb.storage.btree.IndexDescriptor clusterIndexDesc = indexes.get(0);

                    try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                        BTree btree = indexManager.openIndex(clusterIndexDesc.getIndexId(), mtr);

                        if (btree == null) {
                            logger.warn("Failed to open index: {}", clusterIndexDesc.getIndexId());
                            continue;
                        }

                        // 3. 创建全表扫描器
                        try (BTreeRangeScanner scanner = BTreeRangeScanner.fullScan(
                                btree, bufferPool, btree.getComparator(), mtr)) {

                            // 4. 遍历所有记录
                            for (BTreeRangeScanner.ScanEntry entry : scanner) {
                                if (candidates.size() >= maxCompressionsPerRound) {
                                    logger.debug("Reached max compressions per round: {}",
                                            maxCompressionsPerRound);
                                    return candidates;
                                }

                                try {
                                    // 从记录中提取 roll_ptr
                                    byte[] recordData = entry.getValue();

                                    if (recordData == null || recordData.length <
                                            SystemLayout.OFF_ROLL_PTR + SystemLayout.ROLL_PTR_SIZE) {
                                        continue;
                                    }

                                    // 计算 ROLL_PTR 的偏移（相对于数据部分）
                                    int rollPtrOffset = SystemLayout.OFF_ROLL_PTR;

                                    // 读取 ROLL_PTR（7 字节，大端序）
                                    long rollPtrValue = 0;
                                    for (int i = 0; i < SystemLayout.ROLL_PTR_SIZE; i++) {
                                        rollPtrValue = (rollPtrValue << 8) |
                                                (recordData[rollPtrOffset + i] & 0xFF);
                                    }

                                    RollbackPointer rollPtr = RollbackPointer.fromValue(rollPtrValue);

                                    // 如果 roll_ptr 为 null，跳过
                                    if (rollPtr.isNull()) {
                                        continue;
                                    }

                                    // 5. 遍历 undo 链，计算链长度
                                    List<UpdateUndoRecord> undoChain = new ArrayList<>();
                                    RollbackPointer currentPtr = rollPtr;
                                    int chainLength = 0;
                                    TransactionId oldestTrxId = null;

                                    while (!currentPtr.isNull() && chainLength < 1000) { // 防止无限循环
                                        try {
                                            UpdateUndoRecord undo = (UpdateUndoRecord)
                                                    undoLogManager.readUndoRecord(currentPtr);

                                            if (undo == null) {
                                                break;
                                            }

                                            undoChain.add(undo);
                                            chainLength++;
                                            oldestTrxId = undo.getTrxId();

                                            currentPtr = undo.getPrevUndoPtr();

                                        } catch (Exception e) {
                                            logger.trace("Failed to read undo record: {}", currentPtr);
                                            break;
                                        }
                                    }

                                    // 6. 检查 purge 边界：只识别 trx_id < purgeLimit 的候选
                                    if (oldestTrxId != null &&
                                            oldestTrxId.getValue() >= purgeLimit.getValue()) {
                                        logger.trace("Skipping candidate: oldest_trx_id={} >= purge_limit={}",
                                                oldestTrxId.getValue(), purgeLimit.getValue());
                                        continue;
                                    }

                                    // 7. 如果链长度 >= minChainLength，加入候选列表
                                    if (chainLength >= minChainLength) {
                                        CompressionCandidate candidate = new CompressionCandidate(
                                                entry.getKey(),
                                                (int) tableId,
                                                undoChain,
                                                rollPtr
                                        );
                                        candidates.add(candidate);

                                        logger.debug("Found compression candidate: table={}, " +
                                                "chain_length={}, pk_size={}, oldest_trx_id={}",
                                                tableId, chainLength, entry.getKey().length,
                                                oldestTrxId != null ? oldestTrxId.getValue() : "null");
                                    }

                                } catch (Exception e) {
                                    logger.trace("Error processing record during compression candidate " +
                                            "identification", e);
                                    // 继续处理下一条记录
                                }
                            }

                        } catch (Exception e) {
                            logger.error("Error scanning table {} for compression candidates",
                                    tableId, e);
                        }
                    }

                } catch (Exception e) {
                    logger.error("Error processing table {} for compression candidates", tableId, e);
                }
            }

            logger.debug("Identified {} compression candidates", candidates.size());

        } catch (Exception e) {
            logger.error("Failed to identify compression candidates", e);
        }

        return candidates;
    }

    /**
     * 强制执行一轮压缩（用于测试）
     *
     * @return 压缩的链段数
     */
    public int forceCompressionRound() {
        doCompressionRound();
        return (int) totalCompressions.get();
    }

    // ==================== 统计信息 ====================

    /**
     * 获取成功压缩的链段总数
     *
     * @return 压缩的链段总数
     */
    public long getTotalCompressions() {
        return totalCompressions.get();
    }

    /**
     * 获取压缩轮数
     *
     * @return 执行的压缩轮数
     */
    public long getCompressionRounds() {
        return compressionRounds.get();
    }

    /**
     * 获取总节省空间
     *
     * @return 节省的总空间（字节）
     */
    public long getTotalSpaceSavings() {
        return totalSpaceSavings.get();
    }

    /**
     * 获取上次压缩边界
     *
     * @return 上次压缩边界 TRX_ID
     */
    public TransactionId getLastCompressionLimit() {
        return lastCompressionLimit;
    }

    /**
     * 获取压缩统计信息
     *
     * @return 统计信息
     */
    public CompressionStats getCompressionStats() {
        UndoCompressionManager.CompressionStats managerStats = compressionManager.getCompressionStats();
        return new CompressionStats(
                totalCompressions.get(),
                compressionRounds.get(),
                totalSpaceSavings.get(),
                managerStats.successfulCompressions,
                managerStats.failedCompressions
        );
    }

    /**
     * 是否正在运行
     *
     * @return 如果正在运行返回 true
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 是否暂停
     *
     * @return 如果暂停返回 true
     */
    public boolean isPaused() {
        return paused.get();
    }

    @Override
    public String toString() {
        return String.format("CompressionThread{running=%s, paused=%s, rounds=%d, compressions=%d, savings=%dB}",
                running.get(), paused.get(), compressionRounds.get(), totalCompressions.get(), totalSpaceSavings.get());
    }

    // ==================== 内部类 ====================

    /**
     * 压缩候选
     */
    public static class CompressionCandidate {
        /**
         * 记录的主键
         */
        public final byte[] primaryKey;

        /**
         * 表 ID
         */
        public final int tableId;

        /**
         * Undo 链（从新到旧）
         */
        public final List<UpdateUndoRecord> undoChain;

        /**
         * 当前的 roll_ptr
         */
        public final RollbackPointer currentRollPtr;

        /**
         * 链长度
         */
        public final int chainLength;

        public CompressionCandidate(byte[] primaryKey, int tableId,
                                   List<UpdateUndoRecord> undoChain,
                                   RollbackPointer currentRollPtr) {
            this.primaryKey = primaryKey;
            this.tableId = tableId;
            this.undoChain = new ArrayList<>(undoChain);
            this.currentRollPtr = currentRollPtr;
            this.chainLength = undoChain.size();
        }

        @Override
        public String toString() {
            return String.format("CompressionCandidate{table=%d, pk=%d bytes, chain=%d}",
                    tableId, primaryKey.length, chainLength);
        }
    }

    /**
     * 压缩统计信息
     */
    public static class CompressionStats {
        /**
         * 成功压缩的链段数
         */
        public final long totalCompressions;

        /**
         * 压缩轮数
         */
        public final long compressionRounds;

        /**
         * 总节省空间（字节）
         */
        public final long totalSpaceSavings;

        /**
         * 管理器成功压缩数
         */
        public final long managerSuccessful;

        /**
         * 管理器失败压缩数
         */
        public final long managerFailed;

        public CompressionStats(long totalCompressions, long compressionRounds,
                               long totalSpaceSavings, long managerSuccessful,
                               long managerFailed) {
            this.totalCompressions = totalCompressions;
            this.compressionRounds = compressionRounds;
            this.totalSpaceSavings = totalSpaceSavings;
            this.managerSuccessful = managerSuccessful;
            this.managerFailed = managerFailed;
        }

        /**
         * 获取成功率
         *
         * @return 成功率（0-1）
         */
        public double getSuccessRate() {
            long total = managerSuccessful + managerFailed;
            if (total == 0) return 0.0;
            return (double) managerSuccessful / total;
        }

        @Override
        public String toString() {
            return String.format(
                    "CompressionStats{compressions=%d, rounds=%d, savings=%dB, success=%.2f%%}",
                    totalCompressions, compressionRounds, totalSpaceSavings,
                    getSuccessRate() * 100
            );
        }
    }
}
