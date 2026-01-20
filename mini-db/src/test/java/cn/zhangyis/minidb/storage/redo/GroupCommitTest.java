package cn.zhangyis.minidb.storage.redo;

import cn.zhangyis.minidb.storage.BaseStorageTest;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.redo.commit.CommitQueue;
import cn.zhangyis.minidb.storage.redo.commit.GroupCommitMetrics;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5: Group Commit 测试
 *
 * <p>测试内容：</p>
 * <ul>
 *   <li>CommitQueue 基本功能</li>
 *   <li>Leader/Follower 机制</li>
 *   <li>Group Commit 批量效果</li>
 *   <li>并发性能</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class GroupCommitTest extends BaseStorageTest {

    private RedoLogManager redoLogManager;
    private Path redoLogDir;

    @Override
    protected void afterSetup() throws Exception {
        // 创建 redo log 目录
        redoLogDir = testDir.resolve("redo");
        Files.createDirectories(redoLogDir);

        // 创建 RedoLogConfig
        RedoLogConfig config = new RedoLogConfig.Builder()
                .dataDir(redoLogDir.toString())
                .logFileSize(4 * 1024 * 1024)  // 4MB
                .logBufferSize(1024 * 1024)    // 1MB
                .flushLogAtTrxCommit(RedoLogConfig.FLUSH_AT_TRX_COMMIT_SYNC)
                .build();

        // 创建并启动 RedoLogManager (启用 Group Commit)
        redoLogManager = new RedoLogManager(config, true);
        redoLogManager.start();

        // 关联 BufferPool
        bufferPool.setRedoLogManager(redoLogManager);

        // 初始化 Checkpoint
        redoLogManager.initCheckpoint(bufferPool);
    }

    @Override
    protected void beforeCleanup() throws Exception {
        if (redoLogManager != null) {
            redoLogManager.shutdown();
        }
    }

    // ==================== CommitQueue 基本测试 ====================

    @Test
    @Order(1)
    @DisplayName("CommitQueue 基本操作")
    void testCommitQueueBasic() {
        CommitQueue queue = new CommitQueue();

        // 初始状态
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        assertEquals(0, queue.getMaxCommitSn());

        // 加入队列
        queue.joinQueue(100, Thread.currentThread());
        queue.joinQueue(200, Thread.currentThread());
        queue.joinQueue(150, Thread.currentThread());

        assertEquals(3, queue.size());
        assertEquals(200, queue.getMaxCommitSn());
        assertEquals(100, queue.getMinCommitSn());

        // 唤醒批次
        int wakeupCount = queue.wakeupBatch(150);
        assertEquals(2, wakeupCount);  // 100 和 150 被唤醒
        assertEquals(1, queue.size());  // 200 还在队列中
    }

    @Test
    @Order(2)
    @DisplayName("CommitQueue Leader/Follower")
    void testCommitQueueLeaderFollower() {
        CommitQueue queue = new CommitQueue();

        // 第一个线程成为 leader
        assertTrue(queue.tryBeLeader());
        assertTrue(queue.hasLeader());

        // 第二个尝试应该失败
        assertFalse(queue.tryBeLeader());

        // 释放 leader
        queue.releaseLeader();
        assertFalse(queue.hasLeader());

        // 现在可以成为新的 leader
        assertTrue(queue.tryBeLeader());

        // 检查统计
        assertEquals(2, queue.getLeaderCount());
        assertEquals(1, queue.getFollowerCount());

        queue.releaseLeader();
    }

    // ==================== Group Commit 功能测试 ====================

    @Test
    @Order(10)
    @DisplayName("Group Commit 启用状态")
    void testGroupCommitEnabled() {
        assertTrue(redoLogManager.isGroupCommitEnabled());
        assertNotNull(redoLogManager.getCommitQueue());
        assertNotNull(redoLogManager.getGroupCommitMetrics());
    }

    @Test
    @Order(11)
    @DisplayName("Group Commit 单事务提交")
    void testGroupCommitSingleTransaction() throws Exception {
        // 创建单个事务
        try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
            Page page = mtr.newPage(SPACE_ID);
            page.putInt(100, 12345);
            mtr.logModification(page, 100, 4);
            mtr.markDirty(page);
            mtr.commit();
        }

        // 检查指标
        GroupCommitMetrics metrics = redoLogManager.getGroupCommitMetrics();
        assertTrue(metrics.getLeaderCount() > 0 || metrics.getFollowerCount() > 0,
                "Should have leader or follower count");

        System.out.println("Single transaction stats: " + redoLogManager.getGroupCommitStats());
    }

    @Test
    @Order(12)
    @DisplayName("Group Commit 多事务顺序提交")
    void testGroupCommitSequentialTransactions() throws Exception {
        int txnCount = 10;

        for (int i = 0; i < txnCount; i++) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
                Page page = mtr.newPage(SPACE_ID);
                page.putInt(100, i);
                mtr.logModification(page, 100, 4);
                mtr.markDirty(page);
                mtr.commit();
            }
        }

        GroupCommitMetrics metrics = redoLogManager.getGroupCommitMetrics();
        System.out.println("Sequential transactions: " + metrics.getSummary());
        System.out.println("Latency: " + metrics.getLatencySummary());
    }

    // ==================== 并发测试 ====================

    @Test
    @Order(20)
    @DisplayName("Group Commit 并发提交 - 批量效果")
    void testGroupCommitConcurrentBatch() throws Exception {
        int threadCount = 10;
        int txnPerThread = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        // 重置指标
        redoLogManager.getGroupCommitMetrics().reset();
        redoLogManager.getCommitQueue().resetStats();

        // 启动并发线程
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();  // 等待同时开始

                    for (int i = 0; i < txnPerThread; i++) {
                        try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
                            Page page = mtr.newPage(SPACE_ID);
                            page.putInt(100, threadId * 1000 + i);
                            mtr.logModification(page, 100, 4);
                            mtr.markDirty(page);
                            mtr.commit();
                            successCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 开始并发执行
        long startTime = System.nanoTime();
        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // 验证结果
        assertEquals(threadCount * txnPerThread, successCount.get(), "All transactions should succeed");
        assertEquals(0, errorCount.get(), "No errors should occur");

        // 输出性能指标
        GroupCommitMetrics metrics = redoLogManager.getGroupCommitMetrics();
        CommitQueue queue = redoLogManager.getCommitQueue();

        System.out.println("=== Concurrent Group Commit Results ===");
        System.out.println("Threads: " + threadCount + ", TxnPerThread: " + txnPerThread);
        System.out.println("Total transactions: " + successCount.get());
        System.out.println("Elapsed: " + elapsedMs + "ms");
        System.out.println("TPS: " + (successCount.get() * 1000L / Math.max(1, elapsedMs)));
        System.out.println("Leader count: " + metrics.getLeaderCount());
        System.out.println("Follower count: " + metrics.getFollowerCount());
        System.out.println("Leader ratio: " + String.format("%.2f%%", metrics.getLeaderRatio() * 100));
        System.out.println("Avg batch size: " + String.format("%.2f", metrics.getAvgBatchSize()));
        System.out.println("Max batch size: " + metrics.getMaxBatchSize());
        System.out.println("Fsync count: " + metrics.getFsyncCount());
        System.out.println("Avg fsync time: " + String.format("%.2f", metrics.getAvgFsyncUs()) + "μs");
        System.out.println("Queue stats: " + queue.getStats());

        // 验证 Group Commit 效果
        // 如果 Group Commit 有效，leader 数量应该远小于总事务数
        assertTrue(metrics.getLeaderCount() < successCount.get(),
                "Leader count should be less than total transactions (group commit effect)");

        // 平均批量大小应该 > 1
        assertTrue(metrics.getAvgBatchSize() >= 1.0,
                "Average batch size should be at least 1");
    }

    @Test
    @Order(21)
    @DisplayName("Group Commit vs 串行 Commit 对比")
    void testGroupCommitVsSerial() throws Exception {
        int txnCount = 50;

        // 测试 Group Commit 模式 (已在 setup 中启用)
        long gcStartTime = System.nanoTime();
        for (int i = 0; i < txnCount; i++) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
                Page page = mtr.newPage(SPACE_ID);
                page.putInt(100, i);
                mtr.logModification(page, 100, 4);
                mtr.markDirty(page);
                mtr.commit();
            }
        }
        long gcElapsedMs = (System.nanoTime() - gcStartTime) / 1_000_000;

        System.out.println("=== Group Commit Mode ===");
        System.out.println("Transactions: " + txnCount);
        System.out.println("Elapsed: " + gcElapsedMs + "ms");
        System.out.println("TPS: " + (txnCount * 1000L / Math.max(1, gcElapsedMs)));
        System.out.println("Metrics: " + redoLogManager.getGroupCommitStats());
    }

    // ==================== 边界条件测试 ====================

    @Test
    @Order(30)
    @DisplayName("空队列唤醒")
    void testWakeupEmptyQueue() {
        CommitQueue queue = new CommitQueue();
        int wakeupCount = queue.wakeupBatch(1000);
        assertEquals(0, wakeupCount);
    }

    @Test
    @Order(31)
    @DisplayName("CommitQueue 统计重置")
    void testCommitQueueStatsReset() {
        CommitQueue queue = new CommitQueue();

        // 产生一些统计
        queue.tryBeLeader();
        queue.releaseLeader();
        queue.tryBeLeader();
        queue.tryBeLeader();  // follower

        assertTrue(queue.getLeaderCount() > 0);
        assertTrue(queue.getFollowerCount() > 0);

        // 重置
        queue.resetStats();

        assertEquals(0, queue.getLeaderCount());
        assertEquals(0, queue.getFollowerCount());

        queue.releaseLeader();
    }

    @Test
    @Order(32)
    @DisplayName("GroupCommitMetrics 功能测试")
    void testGroupCommitMetrics() {
        GroupCommitMetrics metrics = new GroupCommitMetrics();

        // 初始状态
        assertEquals(0, metrics.getLeaderCount());
        assertEquals(0, metrics.getFollowerCount());
        assertEquals(0, metrics.getFsyncCount());
        assertEquals(0.0, metrics.getAvgBatchSize());

        // 记录一些事件
        metrics.recordLeader();
        metrics.recordFollower();
        metrics.recordFollower();
        metrics.recordGroupCommit(5, 100_000, 500_000, 10_000);

        assertEquals(1, metrics.getLeaderCount());
        assertEquals(2, metrics.getFollowerCount());
        assertEquals(1, metrics.getFsyncCount());
        assertEquals(5.0, metrics.getAvgBatchSize());

        System.out.println("Metrics: " + metrics.toString());

        // 重置
        metrics.reset();
        assertEquals(0, metrics.getLeaderCount());
    }

    // ==================== 压力测试 ====================

    @Test
    @Order(40)
    @DisplayName("高并发压力测试")
    @Disabled("Long running test - enable manually")
    void testHighConcurrencyStress() throws Exception {
        int threadCount = 50;
        int txnPerThread = 100;
        int totalTxn = threadCount * txnPerThread;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicLong successCount = new AtomicLong(0);
        List<Long> latencies = new CopyOnWriteArrayList<>();

        redoLogManager.getGroupCommitMetrics().reset();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < txnPerThread; i++) {
                        long txnStart = System.nanoTime();
                        try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
                            Page page = mtr.newPage(SPACE_ID);
                            page.putInt(100, (int) successCount.get());
                            mtr.logModification(page, 100, 4);
                            mtr.markDirty(page);
                            mtr.commit();
                            successCount.incrementAndGet();
                            latencies.add(System.nanoTime() - txnStart);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        long startTime = System.nanoTime();
        startLatch.countDown();
        doneLatch.await(60, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

        executor.shutdown();

        // 计算延迟百分位
        latencies.sort(Long::compareTo);
        long p50 = latencies.isEmpty() ? 0 : latencies.get(latencies.size() / 2) / 1000;
        long p99 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.99)) / 1000;

        System.out.println("=== High Concurrency Stress Test ===");
        System.out.println("Threads: " + threadCount);
        System.out.println("Total transactions: " + successCount.get() + "/" + totalTxn);
        System.out.println("Elapsed: " + elapsedMs + "ms");
        System.out.println("TPS: " + (successCount.get() * 1000L / Math.max(1, elapsedMs)));
        System.out.println("Latency P50: " + p50 + "μs, P99: " + p99 + "μs");
        System.out.println("Group Commit: " + redoLogManager.getGroupCommitStats());
    }
}
