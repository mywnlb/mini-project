package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.space.Segment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Undo Page 池补充线程
 *
 * <p>后台线程定期补充 UndoPagePool 中的 freshPages。
 * 当 freshPages 数量 < MIN_THRESHOLD 时，异步从 Extent 分配新页。</p>
 *
 * <h2>设计目标</h2>
 * <ul>
 *   <li>异步补充，不阻塞分配操作</li>
 *   <li>减少 Extent 查询频率</li>
 *   <li>支持优雅关闭</li>
 * </ul>
 *
 * <h2>工作流程</h2>
 * <pre>
 * 1. 定期扫描所有 Rollback Segment 的池
 * 2. 对于每个需要补充的池：
 *    a. 计算需要补充的页面数
 *    b. 从 Segment 分配新页
 *    c. 将新页加入 freshPages
 * 3. 休眠指定时间后重复
 * </pre>
 *
 * <h2>关键约束</h2>
 * <ul>
 *   <li>补充操作在后台线程执行，不阻塞分配</li>
 *   <li>支持优雅关闭（graceful shutdown）</li>
 *   <li>异常不应导致线程退出</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class UndoPagePoolRefiller implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(UndoPagePoolRefiller.class);

    // ==================== 常量 ====================

    /**
     * 默认补充间隔（毫秒）
     */
    private static final long DEFAULT_REFILL_INTERVAL_MS = 1000;

    /**
     * 最大连续补充失败次数（超过此值则记录警告）
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    // ==================== 字段 ====================

    /**
     * Undo Log Manager 引用
     */
    private final UndoLogManager undoLogManager;

    /**
     * Buffer Pool 引用
     */
    private final BufferPool bufferPool;

    /**
     * Rollback Segment 对应的物理 Segment 数组
     */
    private final Segment[] rollbackSegmentPhysical;

    /**
     * 补充间隔（毫秒）
     */
    private final long refillIntervalMs;

    /**
     * 是否应该停止
     */
    private volatile boolean shouldStop = false;

    /**
     * 线程是否已启动
     */
    private volatile boolean started = false;

    /**
     * 线程是否已停止
     */
    private volatile boolean stopped = false;

    // ==================== 统计信息 ====================

    /**
     * 总补充次数
     */
    private long totalRefillCount = 0;

    /**
     * 总补充的页面数
     */
    private long totalRefillPages = 0;

    /**
     * 连续补充失败次数
     */
    private int consecutiveFailures = 0;

    // ==================== 构造函数 ====================

    /**
     * 创建 Undo Page 池补充线程
     *
     * @param undoLogManager           Undo Log Manager
     * @param bufferPool               Buffer Pool
     * @param rollbackSegmentPhysical  Rollback Segment 物理 Segment 数组
     */
    public UndoPagePoolRefiller(UndoLogManager undoLogManager,
                                BufferPool bufferPool,
                                Segment[] rollbackSegmentPhysical) {
        this(undoLogManager, bufferPool, rollbackSegmentPhysical, DEFAULT_REFILL_INTERVAL_MS);
    }

    /**
     * 创建 Undo Page 池补充线程（指定补充间隔）
     *
     * @param undoLogManager           Undo Log Manager
     * @param bufferPool               Buffer Pool
     * @param rollbackSegmentPhysical  Rollback Segment 物理 Segment 数组
     * @param refillIntervalMs         补充间隔（毫秒）
     */
    public UndoPagePoolRefiller(UndoLogManager undoLogManager,
                                BufferPool bufferPool,
                                Segment[] rollbackSegmentPhysical,
                                long refillIntervalMs) {
        this.undoLogManager = undoLogManager;
        this.bufferPool = bufferPool;
        this.rollbackSegmentPhysical = rollbackSegmentPhysical;
        this.refillIntervalMs = refillIntervalMs;

        logger.info("UndoPagePoolRefiller created: interval={}ms", refillIntervalMs);
    }

    // ==================== 主循环 ====================

    @Override
    public void run() {
        started = true;
        logger.info("UndoPagePoolRefiller started");

        try {
            while (!shouldStop) {
                try {
                    refillAllPools();
                    consecutiveFailures = 0;

                    // 休眠指定时间
                    Thread.sleep(refillIntervalMs);

                } catch (InterruptedException e) {
                    if (shouldStop) {
                        logger.debug("UndoPagePoolRefiller interrupted (shutdown)");
                        break;
                    }
                    logger.warn("UndoPagePoolRefiller interrupted unexpectedly", e);
                    Thread.currentThread().interrupt();

                } catch (Exception e) {
                    consecutiveFailures++;
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        logger.error("UndoPagePoolRefiller failed {} times consecutively, stopping",
                                MAX_CONSECUTIVE_FAILURES, e);
                        break;
                    }
                    logger.warn("UndoPagePoolRefiller error (attempt {})", consecutiveFailures, e);
                }
            }

        } finally {
            stopped = true;
            logger.info("UndoPagePoolRefiller stopped: totalRefills={}, totalPages={}",
                    totalRefillCount, totalRefillPages);
        }
    }

    /**
     * 补充所有池
     */
    private void refillAllPools() {
        UndoPagePool[] pools = undoLogManager.getAllUndoPagePools();

        for (int rsegId = 0; rsegId < pools.length; rsegId++) {
            UndoPagePool pool = pools[rsegId];

            // 检查是否需要补充
            if (pool.needsRefill()) {
                refillPool(rsegId, pool);
            }
        }
    }

    /**
     * 补充指定的池
     *
     * @param rsegId Rollback Segment ID
     * @param pool   Undo Page 池
     */
    private void refillPool(int rsegId, UndoPagePool pool) {
        int toAllocate = pool.getRefillCount();
        if (toAllocate <= 0) {
            return;
        }

        Segment physicalSegment = rollbackSegmentPhysical[rsegId];
        if (physicalSegment == null) {
            logger.warn("Physical segment not initialized for rseg {}", rsegId);
            return;
        }

        int allocated = 0;
        try {
            // 使用 MTR 分配页面
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                for (int i = 0; i < toAllocate; i++) {
                    try {
                        Page page = physicalSegment.allocatePage(mtr);
                        if (page == null) {
                            logger.debug("Failed to allocate page {} of {} for rseg {}",
                                    i + 1, toAllocate, rsegId);
                            break;
                        }

                        // 将页号加入池
                        undoLogManager.addFreshPageToPool(rsegId, page.getPageNo());
                        allocated++;

                    } catch (Exception e) {
                        logger.warn("Error allocating page for rseg {}", rsegId, e);
                        break;
                    }
                }

                // 提交 MTR
                if (allocated > 0) {
                    mtr.commit();
                }
            }

            if (allocated > 0) {
                totalRefillCount++;
                totalRefillPages += allocated;

                logger.trace("Refilled pool for rseg {}: allocated {} pages, pool={}",
                        rsegId, allocated, pool.getStats());
            }

        } catch (Exception e) {
            logger.error("Error refilling pool for rseg {}", rsegId, e);
        }
    }

    // ==================== 关闭方法 ====================

    /**
     * 请求线程停止
     *
     * <p>此方法是非阻塞的，调用后线程会在下一个循环中停止。</p>
     */
    public void requestStop() {
        shouldStop = true;
        logger.debug("UndoPagePoolRefiller stop requested");
    }

    /**
     * 等待线程停止
     *
     * @param timeoutMs 超时时间（毫秒）
     * @return 是否成功停止
     */
    public boolean waitForStop(long timeoutMs) {
        long startTime = System.currentTimeMillis();
        while (!stopped && System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return stopped;
    }

    // ==================== 状态查询 ====================

    /**
     * 是否已启动
     *
     * @return 是否已启动
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * 是否已停止
     *
     * @return 是否已停止
     */
    public boolean isStopped() {
        return stopped;
    }

    /**
     * 获取总补充次数
     *
     * @return 补充次数
     */
    public long getTotalRefillCount() {
        return totalRefillCount;
    }

    /**
     * 获取总补充的页面数
     *
     * @return 页面数
     */
    public long getTotalRefillPages() {
        return totalRefillPages;
    }

    /**
     * 获取统计信息
     *
     * @return 统计字符串
     */
    public String getStats() {
        return String.format(
                "UndoPagePoolRefiller{started=%s, stopped=%s, refills=%d, pages=%d, failures=%d}",
                started, stopped, totalRefillCount, totalRefillPages, consecutiveFailures
        );
    }
}
