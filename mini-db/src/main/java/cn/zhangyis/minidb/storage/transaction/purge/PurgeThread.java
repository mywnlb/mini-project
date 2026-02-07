package cn.zhangyis.minidb.storage.transaction.purge;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.undo.UndoLogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Purge 后台线程
 *
 * <p>后台定期运行，清理不再需要的 UPDATE/DELETE Undo 记录。</p>
 *
 * <h2>工作原理</h2>
 * <ol>
 *   <li>从 PurgeCoordinator 获取当前 Purge 边界</li>
 *   <li>遍历已提交的 UPDATE Undo Segment</li>
 *   <li>清理所有 TRX_ID < purge_limit 的记录</li>
 *   <li>回收 Undo 页面空间</li>
 * </ol>
 *
 * <h2>设计约束 (Invariants)</h2>
 * <ul>
 *   <li><b>P1</b>: 不能清理任何活跃 ReadView 可能需要的 Undo</li>
 *   <li><b>P4</b>: Purge 操作本身是幂等的</li>
 *   <li><b>P5</b>: Purge 失败不影响系统正确性，只影响空间回收</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * PurgeCoordinator coordinator = new PurgeCoordinator(txnMgr);
 * PurgeThread purgeThread = new PurgeThread(coordinator, undoLogMgr);
 *
 * // 启动 Purge 线程
 * purgeThread.start();
 *
 * // 关闭时
 * purgeThread.shutdown();
 * }</pre>
 *
 * @author MiniDB
 * @version 1.0
 * @see PurgeCoordinator
 * @see UndoLogManager
 */
public class PurgeThread extends Thread {

    private static final Logger logger = LoggerFactory.getLogger(PurgeThread.class);

    // ==================== 常量 ====================

    /**
     * 默认 Purge 间隔（毫秒）
     */
    public static final long DEFAULT_PURGE_INTERVAL_MS = 1000;

    /**
     * 默认每轮 Purge 的最大记录数
     */
    public static final int DEFAULT_MAX_RECORDS_PER_ROUND = 10000;

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
     * Purge 间隔（毫秒）
     */
    private final long purgeIntervalMs;

    /**
     * 每轮 Purge 的最大记录数
     */
    private final int maxRecordsPerRound;

    /**
     * 运行标志
     */
    private final AtomicBoolean running;

    /**
     * 是否暂停
     */
    private final AtomicBoolean paused;

    /**
     * 统计：Purge 的记录总数
     */
    private final AtomicLong totalPurgedRecords;

    /**
     * 统计：Purge 轮数
     */
    private final AtomicLong purgeRounds;

    /**
     * 上次 Purge 的边界
     */
    private volatile TransactionId lastPurgeLimit;

    // ==================== 构造函数 ====================

    /**
     * 创建 Purge 线程
     *
     * @param coordinator    Purge 协调器
     * @param undoLogManager Undo Log 管理器
     */
    public PurgeThread(PurgeCoordinator coordinator, UndoLogManager undoLogManager) {
        this(coordinator, undoLogManager, DEFAULT_PURGE_INTERVAL_MS, DEFAULT_MAX_RECORDS_PER_ROUND);
    }

    /**
     * 创建 Purge 线程
     *
     * @param coordinator        Purge 协调器
     * @param undoLogManager     Undo Log 管理器
     * @param purgeIntervalMs    Purge 间隔（毫秒）
     * @param maxRecordsPerRound 每轮最大记录数
     */
    public PurgeThread(PurgeCoordinator coordinator, UndoLogManager undoLogManager,
                       long purgeIntervalMs, int maxRecordsPerRound) {
        super("MiniDB-Purge-Thread");
        setDaemon(true);

        if (coordinator == null || undoLogManager == null) {
            throw new NullPointerException("coordinator and undoLogManager cannot be null");
        }

        this.coordinator = coordinator;
        this.undoLogManager = undoLogManager;
        this.purgeIntervalMs = purgeIntervalMs;
        this.maxRecordsPerRound = maxRecordsPerRound;
        this.running = new AtomicBoolean(false);
        this.paused = new AtomicBoolean(false);
        this.totalPurgedRecords = new AtomicLong(0);
        this.purgeRounds = new AtomicLong(0);
        this.lastPurgeLimit = new TransactionId(0);
    }

    // ==================== 生命周期管理 ====================

    @Override
    public void run() {
        running.set(true);
        logger.info("Purge thread started, interval={}ms, maxRecords={}",
                purgeIntervalMs, maxRecordsPerRound);

        while (running.get()) {
            try {
                // 等待下一个 Purge 周期
                Thread.sleep(purgeIntervalMs);

                // 检查是否暂停
                if (paused.get()) {
                    continue;
                }

                // 执行 Purge
                doPurgeRound();

            } catch (InterruptedException e) {
                if (!running.get()) {
                    // 正常关闭
                    break;
                }
                logger.warn("Purge thread interrupted", e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // Purge 失败不应该终止线程
                logger.error("Purge round failed", e);
            }
        }

        logger.info("Purge thread stopped, totalPurged={}, rounds={}",
                totalPurgedRecords.get(), purgeRounds.get());
    }

    /**
     * 关闭 Purge 线程
     */
    public void shutdown() {
        running.set(false);
        interrupt();

        try {
            join(5000); // 等待最多 5 秒
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for purge thread to stop");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 暂停 Purge
     */
    public void pause() {
        paused.set(true);
        logger.info("Purge thread paused");
    }

    /**
     * 恢复 Purge
     */
    public void resumePurge() {
        paused.set(false);
        logger.info("Purge thread resumed");
    }

    // ==================== Purge 逻辑 ====================

    /**
     * 执行一轮 Purge
     */
    private void doPurgeRound() {
        // 获取当前 Purge 边界
        TransactionId purgeLimit = coordinator.getPurgeLimit();

        // 检查是否有新的可清理范围
        if (purgeLimit.getValue() <= lastPurgeLimit.getValue()) {
            // 没有新的可清理记录
            return;
        }

        logger.debug("Starting purge round: lastLimit={}, newLimit={}",
                lastPurgeLimit, purgeLimit);

        long startTime = System.currentTimeMillis();
        int purgedCount = 0;

        try {
            // 执行实际的 Purge 清理
            purgedCount = simulatePurge(purgeLimit);

            // 更新最后 Purge 边界
            lastPurgeLimit = purgeLimit;

        } finally {
            long duration = System.currentTimeMillis() - startTime;
            purgeRounds.incrementAndGet();
            totalPurgedRecords.addAndGet(purgedCount);

            if (purgedCount > 0) {
                logger.debug("Purge round completed: purged={}, duration={}ms",
                        purgedCount, duration);
            }
        }
    }

    /**
     * 执行实际的 Purge 清理逻辑
     *
     * <p>从 UndoLogManager 的 History List 获取可清理的 UPDATE Segment，
     * 按提交顺序清理，支持按 Rollback Segment 分组批量处理。</p>
     *
     * @param purgeLimit Purge 边界
     * @return 清理的 Segment 数量
     */
    private int simulatePurge(TransactionId purgeLimit) {
        int purgedCount = 0;

        // 1. 从 History List 获取可清理的条目（按提交顺序）
        HistoryList historyList = undoLogManager.getHistoryList();
        List<HistoryList.HistoryEntry> purgableEntries =
                historyList.getPurgableEntries(purgeLimit, maxRecordsPerRound);

        if (purgableEntries.isEmpty()) {
            return 0;
        }

        logger.trace("Found {} purgable entries in history list", purgableEntries.size());

        // 2. 按 Rollback Segment 分组，减少锁竞争
        Map<Integer, List<HistoryList.HistoryEntry>> entriesByRseg = new java.util.HashMap<>();
        for (HistoryList.HistoryEntry entry : purgableEntries) {
            entriesByRseg.computeIfAbsent(entry.getRsegId(), k -> new java.util.ArrayList<>())
                    .add(entry);
        }

        // 3. 按 Rollback Segment 批量清理
        for (Map.Entry<Integer, List<HistoryList.HistoryEntry>> rsegGroup : entriesByRseg.entrySet()) {
            int rsegId = rsegGroup.getKey();
            List<HistoryList.HistoryEntry> entries = rsegGroup.getValue();

            int rsegPurged = purgeRsegBatch(rsegId, entries, historyList);
            purgedCount += rsegPurged;
        }

        return purgedCount;
    }

    /**
     * 批量清理单个 Rollback Segment 的条目
     *
     * @param rsegId      Rollback Segment ID
     * @param entries     待清理的条目列表
     * @param historyList History List（用于移除已清理的条目）
     * @return 成功清理的数量
     */
    private int purgeRsegBatch(int rsegId, List<HistoryList.HistoryEntry> entries,
                                HistoryList historyList) {
        int purgedCount = 0;

        for (HistoryList.HistoryEntry entry : entries) {
            TransactionId trxId = entry.getTrxId();

            try {
                // 调用 UndoLogManager 执行实际清理
                boolean success = undoLogManager.purgeUpdateUndo(trxId);
                if (success) {
                    // 从 History List 移除
                    historyList.remove(entry);
                    purgedCount++;
                    logger.trace("Purged UPDATE undo for trxId={} in rseg {}", trxId, rsegId);
                }
            } catch (Exception e) {
                // Purge 单个 Segment 失败不影响其他清理
                logger.warn("Failed to purge UPDATE undo for trxId={} in rseg {}: {}",
                        trxId, rsegId, e.getMessage());
            }
        }

        if (purgedCount > 0) {
            logger.debug("Purged {} entries from rseg {}", purgedCount, rsegId);
        }

        return purgedCount;
    }

    /**
     * 强制执行一轮 Purge（用于测试）
     *
     * @return 清理的记录数
     */
    public int forcePurgeRound() {
        doPurgeRound();
        return (int) totalPurgedRecords.get();
    }

    // ==================== 统计信息 ====================

    /**
     * 获取 Purge 的记录总数
     *
     * @return 清理的记录总数
     */
    public long getTotalPurgedRecords() {
        return totalPurgedRecords.get();
    }

    /**
     * 获取 Purge 轮数
     *
     * @return 执行的 Purge 轮数
     */
    public long getPurgeRounds() {
        return purgeRounds.get();
    }

    /**
     * 获取上次 Purge 边界
     *
     * @return 上次 Purge 边界 TRX_ID
     */
    public TransactionId getLastPurgeLimit() {
        return lastPurgeLimit;
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
        return String.format("PurgeThread{running=%s, paused=%s, rounds=%d, totalPurged=%d, lastLimit=%s}",
                running.get(), paused.get(), purgeRounds.get(), totalPurgedRecords.get(), lastPurgeLimit);
    }
}
