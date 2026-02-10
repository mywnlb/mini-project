package cn.zhangyis.minidb.storage.transaction.purge;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.btree.IndexManager;
import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.undo.UndoLogManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Purge 和压缩集成测试
 *
 * <p>测试 PurgeThread、CompressionThread 和 PurgeThreadIntegration 的集成功能。</p>
 */
@DisplayName("Purge and Compression Integration Tests")
class PurgeCompressionIntegrationTest {

    private PurgeCoordinator coordinator;
    private UndoLogManager undoLogManager;
    private BufferPool bufferPool;
    private IndexManager indexManager;
    private PurgeThreadIntegration integration;

    @BeforeEach
    void setUp() {
        coordinator = mock(PurgeCoordinator.class);
        undoLogManager = mock(UndoLogManager.class);
        bufferPool = mock(BufferPool.class);
        indexManager = mock(IndexManager.class);

        integration = new PurgeThreadIntegration(
                coordinator,
                undoLogManager,
                bufferPool,
                indexManager,
                100,  // purgeIntervalMs
                1000, // maxRecordsPerRound
                100,  // compressionIntervalMs
                100   // maxCompressionsPerRound
        );

        when(coordinator.getPurgeLimit()).thenReturn(new TransactionId(1000));
        when(indexManager.getAllTableIds()).thenReturn(Collections.emptySet());
    }

    // ==================== CompressionThread 测试 ====================

    @Test
    @DisplayName("创建压缩线程")
    void testCreateCompressionThread() {
        CompressionThread thread = new CompressionThread(
                coordinator,
                undoLogManager,
                bufferPool,
                indexManager
        );

        assertNotNull(thread);
        assertFalse(thread.isRunning());
        assertFalse(thread.isPaused());
        assertEquals(0, thread.getTotalCompressions());
        assertEquals(0, thread.getCompressionRounds());
        assertEquals(0, thread.getTotalSpaceSavings());
    }

    @Test
    @DisplayName("压缩线程启动和关闭")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCompressionThreadStartAndShutdown() {
        CompressionThread thread = new CompressionThread(
                coordinator,
                undoLogManager,
                bufferPool,
                indexManager,
                100,  // compressionIntervalMs
                100   // maxCompressionsPerRound
        );

        // 启动线程
        thread.start();
        assertTrue(thread.isRunning());

        // 等待一段时间
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 关闭线程
        thread.shutdown();
        assertFalse(thread.isRunning());
    }

    @Test
    @DisplayName("压缩线程暂停和恢复")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCompressionThreadPauseAndResume() {
        CompressionThread thread = new CompressionThread(
                coordinator,
                undoLogManager,
                bufferPool,
                indexManager
        );

        thread.start();
        assertTrue(thread.isRunning());
        assertFalse(thread.isPaused());

        // 暂停
        thread.pause();
        assertTrue(thread.isPaused());

        // 恢复
        thread.resume();
        assertFalse(thread.isPaused());

        thread.shutdown();
    }

    @Test
    @DisplayName("压缩线程统计信息")
    void testCompressionThreadStats() {
        CompressionThread thread = new CompressionThread(
                coordinator,
                undoLogManager,
                bufferPool,
                indexManager
        );

        CompressionThread.CompressionStats stats = thread.getCompressionStats();

        assertNotNull(stats);
        assertEquals(0, stats.totalCompressions);
        assertEquals(0, stats.compressionRounds);
        assertEquals(0, stats.totalSpaceSavings);
    }

    @Test
    @DisplayName("压缩线程无效参数应该抛出异常")
    void testCompressionThreadInvalidArguments() {
        assertThrows(NullPointerException.class, () ->
                new CompressionThread(null, undoLogManager, bufferPool, indexManager)
        );

        assertThrows(NullPointerException.class, () ->
                new CompressionThread(coordinator, null, bufferPool, indexManager)
        );

        assertThrows(NullPointerException.class, () ->
                new CompressionThread(coordinator, undoLogManager, null, indexManager)
        );

        assertThrows(NullPointerException.class, () ->
                new CompressionThread(coordinator, undoLogManager, bufferPool, null)
        );
    }

    // ==================== PurgeThreadIntegration 测试 ====================

    @Test
    @DisplayName("创建集成管理器")
    void testCreateIntegration() {
        assertNotNull(integration);
        assertFalse(integration.isStarted());
        assertFalse(integration.isPurgeRunning());
        assertFalse(integration.isCompressionRunning());
    }

    @Test
    @DisplayName("启动和关闭集成")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testStartAndShutdown() {
        integration.start();
        assertTrue(integration.isStarted());
        assertTrue(integration.isPurgeRunning());
        assertTrue(integration.isCompressionRunning());

        // 等待一段时间
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        integration.shutdown();
        assertFalse(integration.isStarted());
        assertFalse(integration.isPurgeRunning());
        assertFalse(integration.isCompressionRunning());
    }

    @Test
    @DisplayName("暂停和恢复集成")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testPauseAndResume() {
        integration.start();
        assertTrue(integration.isPurgeRunning());
        assertTrue(integration.isCompressionRunning());

        // 暂停
        integration.pause();
        assertTrue(integration.isPurgePaused());
        assertTrue(integration.isCompressionPaused());

        // 恢复
        integration.resume();
        assertFalse(integration.isPurgePaused());
        assertFalse(integration.isCompressionPaused());

        integration.shutdown();
    }

    @Test
    @DisplayName("获取集成统计信息")
    void testGetIntegrationStats() {
        PurgeThreadIntegration.IntegrationStats stats = integration.getStats();

        assertNotNull(stats);
        assertEquals(0, stats.totalPurgedRecords);
        assertEquals(0, stats.purgeRounds);
        assertEquals(0, stats.totalCompressions);
        assertEquals(0, stats.compressionRounds);
        assertEquals(0, stats.totalSpaceSavings);
    }

    @Test
    @DisplayName("重复启动应该被忽略")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testDoubleStart() {
        integration.start();
        assertTrue(integration.isStarted());

        // 再次启动应该被忽略
        integration.start();
        assertTrue(integration.isStarted());

        integration.shutdown();
    }

    @Test
    @DisplayName("重复关闭应该被忽略")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testDoubleShutdown() {
        integration.start();
        assertTrue(integration.isStarted());

        integration.shutdown();
        assertFalse(integration.isStarted());

        // 再次关闭应该被忽略
        integration.shutdown();
        assertFalse(integration.isStarted());
    }

    @Test
    @DisplayName("集成管理器无效参数应该抛出异常")
    void testIntegrationInvalidArguments() {
        assertThrows(NullPointerException.class, () ->
                new PurgeThreadIntegration(null, undoLogManager, bufferPool, indexManager)
        );

        assertThrows(NullPointerException.class, () ->
                new PurgeThreadIntegration(coordinator, null, bufferPool, indexManager)
        );

        assertThrows(NullPointerException.class, () ->
                new PurgeThreadIntegration(coordinator, undoLogManager, null, indexManager)
        );

        assertThrows(NullPointerException.class, () ->
                new PurgeThreadIntegration(coordinator, undoLogManager, bufferPool, null)
        );
    }

    // ==================== 并发测试 ====================

    @Test
    @DisplayName("并发暂停和恢复")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testConcurrentPauseAndResume() throws InterruptedException {
        integration.start();

        // 创建多个线程并发暂停/恢复
        Thread[] threads = new Thread[5];
        for (int i = 0; i < 5; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10; j++) {
                    integration.pause();
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    integration.resume();
                }
            });
            threads[i].start();
        }

        // 等待所有线程完成
        for (Thread thread : threads) {
            thread.join();
        }

        integration.shutdown();
    }

    @Test
    @DisplayName("并发获取统计信息")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testConcurrentGetStats() throws InterruptedException {
        integration.start();

        // 创建多个线程并发获取统计信息
        Thread[] threads = new Thread[5];
        for (int i = 0; i < 5; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    PurgeThreadIntegration.IntegrationStats stats = integration.getStats();
                    assertNotNull(stats);
                }
            });
            threads[i].start();
        }

        // 等待所有线程完成
        for (Thread thread : threads) {
            thread.join();
        }

        integration.shutdown();
    }

    // ==================== 生命周期测试 ====================

    @Test
    @DisplayName("启动后立即关闭")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testStartAndImmediateShutdown() {
        integration.start();
        assertTrue(integration.isStarted());

        integration.shutdown();
        assertFalse(integration.isStarted());
    }

    @Test
    @DisplayName("多次启动和关闭")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testMultipleStartAndShutdown() {
        for (int i = 0; i < 3; i++) {
            integration.start();
            assertTrue(integration.isStarted());

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            integration.shutdown();
            assertFalse(integration.isStarted());

            // 创建新的集成管理器用于下一轮
            if (i < 2) {
                integration = new PurgeThreadIntegration(
                        coordinator,
                        undoLogManager,
                        bufferPool,
                        indexManager
                );
            }
        }
    }

    // ==================== 状态查询测试 ====================

    @Test
    @DisplayName("查询 Purge 线程状态")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testQueryPurgeThreadState() {
        assertFalse(integration.isPurgeRunning());
        assertFalse(integration.isPurgePaused());

        integration.start();
        assertTrue(integration.isPurgeRunning());
        assertFalse(integration.isPurgePaused());

        integration.pause();
        assertTrue(integration.isPurgeRunning());
        assertTrue(integration.isPurgePaused());

        integration.resume();
        assertTrue(integration.isPurgeRunning());
        assertFalse(integration.isPurgePaused());

        integration.shutdown();
        assertFalse(integration.isPurgeRunning());
    }

    @Test
    @DisplayName("查询压缩线程状态")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testQueryCompressionThreadState() {
        assertFalse(integration.isCompressionRunning());
        assertFalse(integration.isCompressionPaused());

        integration.start();
        assertTrue(integration.isCompressionRunning());
        assertFalse(integration.isCompressionPaused());

        integration.pause();
        assertTrue(integration.isCompressionRunning());
        assertTrue(integration.isCompressionPaused());

        integration.resume();
        assertTrue(integration.isCompressionRunning());
        assertFalse(integration.isCompressionPaused());

        integration.shutdown();
        assertFalse(integration.isCompressionRunning());
    }

    // ==================== 字符串表示测试 ====================

    @Test
    @DisplayName("CompressionThread toString")
    void testCompressionThreadToString() {
        CompressionThread thread = new CompressionThread(
                coordinator,
                undoLogManager,
                bufferPool,
                indexManager
        );

        String str = thread.toString();
        assertNotNull(str);
        assertTrue(str.contains("CompressionThread"));
        assertTrue(str.contains("running=false"));
    }

    @Test
    @DisplayName("PurgeThreadIntegration toString")
    void testIntegrationToString() {
        String str = integration.toString();
        assertNotNull(str);
        assertTrue(str.contains("PurgeThreadIntegration"));
        assertTrue(str.contains("started=false"));
    }

    @Test
    @DisplayName("IntegrationStats toString")
    void testIntegrationStatsToString() {
        PurgeThreadIntegration.IntegrationStats stats = integration.getStats();
        String str = stats.toString();
        assertNotNull(str);
        assertTrue(str.contains("IntegrationStats"));
        assertTrue(str.contains("purged=0"));
        assertTrue(str.contains("compressed=0"));
    }
}
