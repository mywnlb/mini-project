package cn.zhangyis.minidb.storage.buffer;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * BufferPool 性能监控指标
 *
 * <p>收集 BufferPool 的运行时统计信息，用于性能分析、容量规划和故障诊断。
 * 所有计数器使用线程安全的原子类型，支持高并发场景。</p>
 *
 * <h2>指标分类</h2>
 * <pre>
 * 1. 基础指标: 命中率、驱逐、刷盘
 * 2. 锁竞争指标: 分段锁等待时间/次数
 * 3. Flush 性能: 三阶段时间分解
 * 4. LRU 健康度: 精度、后台重排
 * 5. I/O 重试: 重试次数、成功率
 * </pre>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 在 BufferPool 中更新指标
 * metrics.recordPageHit();
 * metrics.recordPageMiss();
 * metrics.recordSegmentLockWait(waitTimeNanos);
 *
 * // 查询指标
 * double hitRate = metrics.getHitRate();
 * long lockWaits = metrics.getSegmentLockWaits();
 * String report = metrics.toDetailedString();
 * }</pre>
 *
 * <h2>参考</h2>
 * <p>设计参考 MySQL InnoDB SHOW ENGINE INNODB STATUS 输出。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class BufferPoolMetrics {

    // ==================== 基础指标 (Cache Performance) ====================

    /**
     * 页面缓存命中次数
     * <p>getPage() 在 PageHash 中找到页面的次数。</p>
     */
    private final LongAdder pageHits = new LongAdder();

    /**
     * 页面缓存未命中次数
     * <p>getPage() 需要从磁盘加载页面的次数。</p>
     */
    private final LongAdder pageMisses = new LongAdder();

    /**
     * 页面驱逐次数
     * <p>从 BufferPool 中淘汰页面的次数。</p>
     */
    private final LongAdder pageEvictions = new LongAdder();

    /**
     * 页面刷盘次数
     * <p>将脏页写回磁盘的次数（包括驱逐时刷盘和批量刷盘）。</p>
     */
    private final LongAdder pageFlushes = new LongAdder();

    // ==================== 锁竞争指标 (Lock Contention) ====================

    /**
     * 分段锁等待次数
     * <p>获取 PageHashSegment 锁时发生等待的次数。</p>
     */
    private final LongAdder segmentLockWaits = new LongAdder();

    /**
     * 分段锁等待总时间 (纳秒)
     * <p>累计等待 PageHashSegment 锁的时间。</p>
     */
    private final LongAdder segmentLockWaitTimeNanos = new LongAdder();

    /**
     * LRU 锁等待次数
     * <p>获取 LRUList 锁时发生等待的次数。</p>
     */
    private final LongAdder lruLockWaits = new LongAdder();

    /**
     * LRU 锁等待总时间 (纳秒)
     */
    private final LongAdder lruLockWaitTimeNanos = new LongAdder();

    /**
     * Flush 锁等待次数
     */
    private final LongAdder flushLockWaits = new LongAdder();

    /**
     * Flush 锁等待总时间 (纳秒)
     */
    private final LongAdder flushLockWaitTimeNanos = new LongAdder();

    // ==================== Flush 性能指标 (Flush Performance) ====================

    /**
     * FlushAllPages 调用次数
     */
    private final LongAdder flushAllCount = new LongAdder();

    /**
     * Phase 1 总耗时 (纳秒): 收集脏页列表
     */
    private final LongAdder flushPhase1TimeNanos = new LongAdder();

    /**
     * Phase 2 总耗时 (纳秒): 批量 I/O
     */
    private final LongAdder flushPhase2TimeNanos = new LongAdder();

    /**
     * Phase 3 总耗时 (纳秒): 清理元数据
     */
    private final LongAdder flushPhase3TimeNanos = new LongAdder();

    /**
     * Flush I/O 错误次数
     * <p>Phase 2 执行 diskManager.writePage() 失败的次数。</p>
     */
    private final LongAdder flushIoErrors = new LongAdder();

    // ==================== LRU 健康度指标 (LRU Health) ====================

    /**
     * LRU 后台重排次数
     * <p>reorderLruBackground() 执行次数。</p>
     */
    private final LongAdder lruReorderCount = new LongAdder();

    /**
     * LRU 后台重排总耗时 (纳秒)
     */
    private final LongAdder lruReorderTimeNanos = new LongAdder();

    /**
     * LRU 重排调整的页面数
     * <p>每次重排移动了多少页面。</p>
     */
    private final LongAdder lruReorderAdjustedPages = new LongAdder();

    /**
     * 当前 LRU 精度
     * <p>使用 volatile 保证可见性，由后台线程定期更新。</p>
     */
    private volatile double lruPrecision = 1.0;

    // ==================== I/O 重试指标 (I/O Retry) ====================

    /**
     * 磁盘 I/O 重试次数
     * <p>readPage/writePage 发生重试的次数。</p>
     */
    private final LongAdder ioRetries = new LongAdder();

    /**
     * 磁盘 I/O 重试成功次数
     * <p>重试后最终成功的次数。</p>
     */
    private final LongAdder ioRetriesSucceeded = new LongAdder();

    /**
     * 磁盘 I/O 重试失败次数
     * <p>重试后仍然失败的次数。</p>
     */
    private final LongAdder ioRetriesFailed = new LongAdder();

    // ==================== 其他指标 (Miscellaneous) ====================

    /**
     * Buffer 耗尽次数
     * <p>所有页面都被 pin，无法驱逐的次数。</p>
     */
    private final LongAdder bufferExhaustedCount = new LongAdder();

    /**
     * 创建时间戳 (毫秒)
     */
    private final long createdAtMillis = System.currentTimeMillis();

    // ==================== 基础指标记录 ====================

    /**
     * 记录页面缓存命中
     */
    public void recordPageHit() {
        pageHits.increment();
    }

    /**
     * 记录页面缓存未命中
     */
    public void recordPageMiss() {
        pageMisses.increment();
    }

    /**
     * 记录页面驱逐
     */
    public void recordPageEviction() {
        pageEvictions.increment();
    }

    /**
     * 记录页面刷盘
     */
    public void recordPageFlush() {
        pageFlushes.increment();
    }

    // ==================== 锁竞争指标记录 ====================

    /**
     * 记录分段锁等待
     *
     * @param waitTimeNanos 等待时间 (纳秒)
     */
    public void recordSegmentLockWait(long waitTimeNanos) {
        segmentLockWaits.increment();
        segmentLockWaitTimeNanos.add(waitTimeNanos);
    }

    /**
     * 记录 LRU 锁等待
     *
     * @param waitTimeNanos 等待时间 (纳秒)
     */
    public void recordLruLockWait(long waitTimeNanos) {
        lruLockWaits.increment();
        lruLockWaitTimeNanos.add(waitTimeNanos);
    }

    /**
     * 记录 Flush 锁等待
     *
     * @param waitTimeNanos 等待时间 (纳秒)
     */
    public void recordFlushLockWait(long waitTimeNanos) {
        flushLockWaits.increment();
        flushLockWaitTimeNanos.add(waitTimeNanos);
    }

    // ==================== Flush 性能指标记录 ====================

    /**
     * 记录 FlushAllPages 执行
     *
     * @param phase1Nanos Phase 1 耗时 (纳秒)
     * @param phase2Nanos Phase 2 耗时 (纳秒)
     * @param phase3Nanos Phase 3 耗时 (纳秒)
     * @param ioErrors    I/O 错误次数
     */
    public void recordFlushAll(long phase1Nanos, long phase2Nanos, long phase3Nanos, int ioErrors) {
        flushAllCount.increment();
        flushPhase1TimeNanos.add(phase1Nanos);
        flushPhase2TimeNanos.add(phase2Nanos);
        flushPhase3TimeNanos.add(phase3Nanos);
        flushIoErrors.add(ioErrors);
    }

    // ==================== LRU 健康度指标记录 ====================

    /**
     * 记录 LRU 后台重排
     *
     * @param timeNanos      重排耗时 (纳秒)
     * @param adjustedPages  调整的页面数
     */
    public void recordLruReorder(long timeNanos, int adjustedPages) {
        lruReorderCount.increment();
        lruReorderTimeNanos.add(timeNanos);
        lruReorderAdjustedPages.add(adjustedPages);
    }

    /**
     * 更新 LRU 精度
     *
     * @param precision 精度值 (0.0 - 1.0)
     */
    public void updateLruPrecision(double precision) {
        this.lruPrecision = precision;
    }

    // ==================== I/O 重试指标记录 ====================

    /**
     * 记录 I/O 重试成功
     */
    public void recordIoRetrySucceeded() {
        ioRetries.increment();
        ioRetriesSucceeded.increment();
    }

    /**
     * 记录 I/O 重试失败
     */
    public void recordIoRetryFailed() {
        ioRetries.increment();
        ioRetriesFailed.increment();
    }

    // ==================== 其他指标记录 ====================

    /**
     * 记录 Buffer 耗尽事件
     */
    public void recordBufferExhausted() {
        bufferExhaustedCount.increment();
    }

    // ==================== 指标查询 ====================

    /**
     * 获取缓存命中率
     *
     * @return 命中率 (0.0 - 1.0)，无访问时返回 0.0
     */
    public double getHitRate() {
        long hits = pageHits.sum();
        long misses = pageMisses.sum();
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }

    /**
     * 获取页面访问总数
     *
     * @return 命中 + 未命中
     */
    public long getTotalAccesses() {
        return pageHits.sum() + pageMisses.sum();
    }

    /**
     * 获取分段锁等待次数
     *
     * @return 等待次数
     */
    public long getSegmentLockWaits() {
        return segmentLockWaits.sum();
    }

    /**
     * 获取分段锁平均等待时间 (纳秒)
     *
     * @return 平均等待时间，无等待时返回 0
     */
    public long getSegmentLockAvgWaitNanos() {
        long waits = segmentLockWaits.sum();
        return waits == 0 ? 0 : segmentLockWaitTimeNanos.sum() / waits;
    }

    /**
     * 获取 LRU 锁平均等待时间 (纳秒)
     */
    public long getLruLockAvgWaitNanos() {
        long waits = lruLockWaits.sum();
        return waits == 0 ? 0 : lruLockWaitTimeNanos.sum() / waits;
    }

    /**
     * 获取 Flush 平均耗时 (毫秒)
     *
     * @return Phase1 + Phase2 + Phase3 的平均总时长
     */
    public long getFlushAvgTimeMillis() {
        long count = flushAllCount.sum();
        if (count == 0) return 0;

        long totalNanos = flushPhase1TimeNanos.sum() +
                          flushPhase2TimeNanos.sum() +
                          flushPhase3TimeNanos.sum();
        return (totalNanos / count) / 1_000_000;
    }

    /**
     * 获取 Flush Phase 2 平均耗时 (毫秒)
     * <p>Phase 2 是纯 I/O 时间，最耗时。</p>
     */
    public long getFlushPhase2AvgTimeMillis() {
        long count = flushAllCount.sum();
        return count == 0 ? 0 : (flushPhase2TimeNanos.sum() / count) / 1_000_000;
    }

    /**
     * 获取 LRU 后台重排平均耗时 (毫秒)
     */
    public long getLruReorderAvgTimeMillis() {
        long count = lruReorderCount.sum();
        return count == 0 ? 0 : (lruReorderTimeNanos.sum() / count) / 1_000_000;
    }

    /**
     * 获取 I/O 重试成功率
     *
     * @return 成功率 (0.0 - 1.0)
     */
    public double getIoRetrySuccessRate() {
        long total = ioRetries.sum();
        return total == 0 ? 1.0 : (double) ioRetriesSucceeded.sum() / total;
    }

    /**
     * 获取当前 LRU 精度
     *
     * @return 精度 (0.0 - 1.0)
     */
    public double getLruPrecision() {
        return lruPrecision;
    }

    /**
     * 获取运行时长 (秒)
     *
     * @return 从创建到现在的秒数
     */
    public long getUptimeSeconds() {
        return (System.currentTimeMillis() - createdAtMillis) / 1000;
    }

    // ==================== 输出格式化 ====================

    /**
     * 生成简要统计摘要
     *
     * @return 单行摘要字符串
     */
    @Override
    public String toString() {
        return String.format(
            "BufferPoolMetrics{hitRate=%.2f%%, accesses=%d, evictions=%d, flushes=%d, segmentLockWaits=%d}",
            getHitRate() * 100,
            getTotalAccesses(),
            pageEvictions.sum(),
            pageFlushes.sum(),
            segmentLockWaits.sum()
        );
    }

    /**
     * 生成详细统计报告
     * <p>类似 MySQL SHOW ENGINE INNODB STATUS 的格式。</p>
     *
     * @return 多行详细报告
     */
    public String toDetailedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("==================== BufferPool Metrics ====================\n");
        sb.append(String.format("Uptime: %d seconds\n\n", getUptimeSeconds()));

        // Cache Performance
        sb.append("--- Cache Performance ---\n");
        sb.append(String.format("  Hit Rate:        %.2f%% (%d hits / %d total)\n",
            getHitRate() * 100, pageHits.sum(), getTotalAccesses()));
        sb.append(String.format("  Page Hits:       %d\n", pageHits.sum()));
        sb.append(String.format("  Page Misses:     %d\n", pageMisses.sum()));
        sb.append(String.format("  Page Evictions:  %d\n", pageEvictions.sum()));
        sb.append(String.format("  Page Flushes:    %d\n\n", pageFlushes.sum()));

        // Lock Contention
        sb.append("--- Lock Contention ---\n");
        sb.append(String.format("  Segment Lock Waits: %d (avg %.2f μs)\n",
            segmentLockWaits.sum(), getSegmentLockAvgWaitNanos() / 1000.0));
        sb.append(String.format("  LRU Lock Waits:     %d (avg %.2f μs)\n",
            lruLockWaits.sum(), getLruLockAvgWaitNanos() / 1000.0));
        sb.append(String.format("  Flush Lock Waits:   %d\n\n",
            flushLockWaits.sum()));

        // Flush Performance
        sb.append("--- Flush Performance ---\n");
        sb.append(String.format("  FlushAll Count:  %d\n", flushAllCount.sum()));
        sb.append(String.format("  Avg Total Time:  %d ms\n", getFlushAvgTimeMillis()));
        sb.append(String.format("  Avg Phase2 Time: %d ms (I/O)\n", getFlushPhase2AvgTimeMillis()));
        sb.append(String.format("  I/O Errors:      %d\n\n", flushIoErrors.sum()));

        // LRU Health
        sb.append("--- LRU Health ---\n");
        sb.append(String.format("  Precision:       %.2f%%\n", lruPrecision * 100));
        sb.append(String.format("  Reorder Count:   %d\n", lruReorderCount.sum()));
        sb.append(String.format("  Avg Reorder Time: %d ms\n", getLruReorderAvgTimeMillis()));
        sb.append(String.format("  Adjusted Pages:  %d\n\n", lruReorderAdjustedPages.sum()));

        // I/O Retry
        sb.append("--- I/O Retry ---\n");
        sb.append(String.format("  Retry Count:     %d\n", ioRetries.sum()));
        sb.append(String.format("  Success Rate:    %.2f%%\n", getIoRetrySuccessRate() * 100));
        sb.append(String.format("  Succeeded:       %d\n", ioRetriesSucceeded.sum()));
        sb.append(String.format("  Failed:          %d\n\n", ioRetriesFailed.sum()));

        // Miscellaneous
        sb.append("--- Miscellaneous ---\n");
        sb.append(String.format("  Buffer Exhausted: %d\n", bufferExhaustedCount.sum()));

        sb.append("=============================================================");
        return sb.toString();
    }

    /**
     * 重置所有计数器 (用于测试或定期重置)
     * <p><b>警告</b>: 生产环境慎用，会丢失历史统计信息。</p>
     */
    public void reset() {
        pageHits.reset();
        pageMisses.reset();
        pageEvictions.reset();
        pageFlushes.reset();

        segmentLockWaits.reset();
        segmentLockWaitTimeNanos.reset();
        lruLockWaits.reset();
        lruLockWaitTimeNanos.reset();
        flushLockWaits.reset();
        flushLockWaitTimeNanos.reset();

        flushAllCount.reset();
        flushPhase1TimeNanos.reset();
        flushPhase2TimeNanos.reset();
        flushPhase3TimeNanos.reset();
        flushIoErrors.reset();

        lruReorderCount.reset();
        lruReorderTimeNanos.reset();
        lruReorderAdjustedPages.reset();
        lruPrecision = 1.0;

        ioRetries.reset();
        ioRetriesSucceeded.reset();
        ioRetriesFailed.reset();

        bufferExhaustedCount.reset();
    }
}
