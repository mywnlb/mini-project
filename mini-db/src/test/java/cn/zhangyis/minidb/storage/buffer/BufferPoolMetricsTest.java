package cn.zhangyis.minidb.storage.buffer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BufferPoolMetrics 单元测试
 *
 * @author MiniDB
 * @version 1.0
 */
class BufferPoolMetricsTest {

    private BufferPoolMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new BufferPoolMetrics();
    }

    // ==================== 基础指标测试 ====================

    @Test
    void testInitialState() {
        assertEquals(0.0, metrics.getHitRate());
        assertEquals(0, metrics.getTotalAccesses());
        assertEquals(0, metrics.getSegmentLockWaits());
        assertEquals(1.0, metrics.getLruPrecision());
        assertTrue(metrics.getUptimeSeconds() >= 0);
    }

    @Test
    void testPageHitAndMiss() {
        // 记录命中和未命中
        metrics.recordPageHit();
        metrics.recordPageHit();
        metrics.recordPageMiss();

        // 验证统计
        assertEquals(3, metrics.getTotalAccesses());
        assertEquals(2.0 / 3.0, metrics.getHitRate(), 0.01);
    }

    @Test
    void testPageEvictionAndFlush() {
        metrics.recordPageEviction();
        metrics.recordPageEviction();
        metrics.recordPageFlush();
        metrics.recordPageFlush();
        metrics.recordPageFlush();

        // BufferPoolMetrics 内部使用 LongAdder，直接检查行为
        // 由于没有 getter，我们通过 toString() 或详细报告验证
        String report = metrics.toString();
        assertTrue(report.contains("evictions=2"));
        assertTrue(report.contains("flushes=3"));
    }

    @Test
    void testHitRate_ZeroAccesses() {
        // 无访问时命中率应为 0
        assertEquals(0.0, metrics.getHitRate());
    }

    @Test
    void testHitRate_AllHits() {
        metrics.recordPageHit();
        metrics.recordPageHit();
        metrics.recordPageHit();

        assertEquals(1.0, metrics.getHitRate());
    }

    @Test
    void testHitRate_AllMisses() {
        metrics.recordPageMiss();
        metrics.recordPageMiss();

        assertEquals(0.0, metrics.getHitRate());
    }

    // ==================== 锁竞争指标测试 ====================

    @Test
    void testSegmentLockWait() {
        metrics.recordSegmentLockWait(100_000); // 100μs
        metrics.recordSegmentLockWait(200_000); // 200μs

        assertEquals(2, metrics.getSegmentLockWaits());
        assertEquals(150_000, metrics.getSegmentLockAvgWaitNanos()); // (100k + 200k) / 2
    }

    @Test
    void testSegmentLockWait_NoWaits() {
        // 无等待时平均时间应为 0
        assertEquals(0, metrics.getSegmentLockAvgWaitNanos());
    }

    @Test
    void testLruLockWait() {
        metrics.recordLruLockWait(50_000);  // 50μs
        metrics.recordLruLockWait(150_000); // 150μs

        assertEquals(100_000, metrics.getLruLockAvgWaitNanos());
    }

    @Test
    void testFlushLockWait() {
        metrics.recordFlushLockWait(1_000_000); // 1ms

        String report = metrics.toDetailedString();
        assertTrue(report.contains("Flush Lock Waits"));
    }

    // ==================== Flush 性能指标测试 ====================

    @Test
    void testFlushAll() {
        long phase1 = 1_000_000;   // 1ms
        long phase2 = 5_000_000;   // 5ms
        long phase3 = 100_000;     // 0.1ms
        int ioErrors = 2;

        metrics.recordFlushAll(phase1, phase2, phase3, ioErrors);

        // 总时长 = 6.1ms
        assertEquals(6, metrics.getFlushAvgTimeMillis());

        // Phase 2 时长 = 5ms
        assertEquals(5, metrics.getFlushPhase2AvgTimeMillis());
    }

    @Test
    void testFlushAll_MultipleFlushes() {
        // 第1次: 总 10ms
        metrics.recordFlushAll(1_000_000, 8_000_000, 1_000_000, 0);

        // 第2次: 总 20ms
        metrics.recordFlushAll(2_000_000, 16_000_000, 2_000_000, 1);

        // 平均: (10 + 20) / 2 = 15ms
        assertEquals(15, metrics.getFlushAvgTimeMillis());

        // Phase 2 平均: (8 + 16) / 2 = 12ms
        assertEquals(12, metrics.getFlushPhase2AvgTimeMillis());
    }

    @Test
    void testFlushAll_NoFlushes() {
        // 无 flush 操作时平均时间为 0
        assertEquals(0, metrics.getFlushAvgTimeMillis());
        assertEquals(0, metrics.getFlushPhase2AvgTimeMillis());
    }

    // ==================== LRU 健康度指标测试 ====================

    @Test
    void testLruReorder() {
        metrics.recordLruReorder(10_000_000, 5);  // 10ms, 5 pages
        metrics.recordLruReorder(20_000_000, 10); // 20ms, 10 pages

        // 平均: (10 + 20) / 2 = 15ms
        assertEquals(15, metrics.getLruReorderAvgTimeMillis());
    }

    @Test
    void testLruReorder_NoReorders() {
        assertEquals(0, metrics.getLruReorderAvgTimeMillis());
    }

    @Test
    void testLruPrecision() {
        // 初始精度为 1.0
        assertEquals(1.0, metrics.getLruPrecision());

        // 更新精度
        metrics.updateLruPrecision(0.95);
        assertEquals(0.95, metrics.getLruPrecision());

        metrics.updateLruPrecision(0.85);
        assertEquals(0.85, metrics.getLruPrecision());
    }

    // ==================== I/O 重试指标测试 ====================

    @Test
    void testIoRetry_AllSucceeded() {
        metrics.recordIoRetrySucceeded();
        metrics.recordIoRetrySucceeded();
        metrics.recordIoRetrySucceeded();

        assertEquals(1.0, metrics.getIoRetrySuccessRate());
    }

    @Test
    void testIoRetry_AllFailed() {
        metrics.recordIoRetryFailed();
        metrics.recordIoRetryFailed();

        assertEquals(0.0, metrics.getIoRetrySuccessRate());
    }

    @Test
    void testIoRetry_Mixed() {
        metrics.recordIoRetrySucceeded();
        metrics.recordIoRetrySucceeded();
        metrics.recordIoRetrySucceeded();
        metrics.recordIoRetryFailed();

        // 成功率: 3 / 4 = 0.75
        assertEquals(0.75, metrics.getIoRetrySuccessRate());
    }

    @Test
    void testIoRetry_NoRetries() {
        // 无重试时成功率为 1.0
        assertEquals(1.0, metrics.getIoRetrySuccessRate());
    }

    // ==================== 其他指标测试 ====================

    @Test
    void testBufferExhausted() {
        metrics.recordBufferExhausted();
        metrics.recordBufferExhausted();

        String report = metrics.toDetailedString();
        assertTrue(report.contains("Buffer Exhausted: 2"));
    }

    @Test
    void testUptime() throws InterruptedException {
        long uptime1 = metrics.getUptimeSeconds();
        Thread.sleep(1100); // 睡眠 1.1 秒
        long uptime2 = metrics.getUptimeSeconds();

        // 运行时间应该增加至少 1 秒
        assertTrue(uptime2 >= uptime1 + 1);
    }

    // ==================== 输出格式化测试 ====================

    @Test
    void testToString() {
        metrics.recordPageHit();
        metrics.recordPageHit();
        metrics.recordPageMiss();
        metrics.recordPageEviction();
        metrics.recordPageFlush();
        metrics.recordSegmentLockWait(100_000);

        String str = metrics.toString();

        // 验证包含关键信息
        assertTrue(str.contains("hitRate"));
        assertTrue(str.contains("accesses=3"));
        assertTrue(str.contains("evictions=1"));
        assertTrue(str.contains("flushes=1"));
        assertTrue(str.contains("segmentLockWaits=1"));
    }

    @Test
    void testToDetailedString() {
        // 添加各种指标
        metrics.recordPageHit();
        metrics.recordPageMiss();
        metrics.recordPageEviction();
        metrics.recordPageFlush();
        metrics.recordSegmentLockWait(50_000);
        metrics.recordLruLockWait(100_000);
        metrics.recordFlushAll(1_000_000, 5_000_000, 100_000, 0);
        metrics.recordLruReorder(10_000_000, 5);
        metrics.updateLruPrecision(0.98);
        metrics.recordIoRetrySucceeded();
        metrics.recordBufferExhausted();

        String report = metrics.toDetailedString();

        // 验证包含所有主要部分
        assertTrue(report.contains("BufferPool Metrics"));
        assertTrue(report.contains("Cache Performance"));
        assertTrue(report.contains("Lock Contention"));
        assertTrue(report.contains("Flush Performance"));
        assertTrue(report.contains("LRU Health"));
        assertTrue(report.contains("I/O Retry"));
        assertTrue(report.contains("Miscellaneous"));

        // 验证具体数值
        assertTrue(report.contains("Hit Rate"));
        assertTrue(report.contains("Segment Lock Waits: 1"));
        assertTrue(report.contains("FlushAll Count:  1"));
        assertTrue(report.contains("Precision:       98"));
        assertTrue(report.contains("Buffer Exhausted: 1"));
    }

    // ==================== 重置测试 ====================

    @Test
    void testReset() {
        // 添加一些数据
        metrics.recordPageHit();
        metrics.recordPageHit();
        metrics.recordPageMiss();
        metrics.recordSegmentLockWait(100_000);
        metrics.recordFlushAll(1_000_000, 5_000_000, 100_000, 0);
        metrics.updateLruPrecision(0.85);

        // 重置
        metrics.reset();

        // 验证所有计数器归零
        assertEquals(0, metrics.getTotalAccesses());
        assertEquals(0.0, metrics.getHitRate());
        assertEquals(0, metrics.getSegmentLockWaits());
        assertEquals(0, metrics.getFlushAvgTimeMillis());
        assertEquals(1.0, metrics.getLruPrecision()); // 重置为默认值
    }

    // ==================== 并发测试 ====================

    @Test
    void testConcurrentUpdates() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 1000;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    metrics.recordPageHit();
                    metrics.recordPageMiss();
                    metrics.recordSegmentLockWait(100);
                }
            });
            threads[i].start();
        }

        // 等待所有线程完成
        for (Thread thread : threads) {
            thread.join();
        }

        // 验证总访问次数
        int expectedAccesses = threadCount * operationsPerThread * 2; // hit + miss
        assertEquals(expectedAccesses, metrics.getTotalAccesses());

        // 命中率应该是 50%
        assertEquals(0.5, metrics.getHitRate(), 0.01);

        // 锁等待次数
        assertEquals(threadCount * operationsPerThread, metrics.getSegmentLockWaits());
    }
}
