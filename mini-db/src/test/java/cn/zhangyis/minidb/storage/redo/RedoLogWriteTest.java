package cn.zhangyis.minidb.storage.redo;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.BaseStorageTest;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.redo.buffer.RedoLogBuffer;
import cn.zhangyis.minidb.storage.redo.record.RedoRecord;
import cn.zhangyis.minidb.storage.redo.record.WriteBytesRecord;
import cn.zhangyis.minidb.storage.redo.record.MultiRecEndRecord;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2: Redo Log 写路径集成测试
 *
 * <p>测试内容：</p>
 * <ul>
 *   <li>RedoLogManager 基本功能</li>
 *   <li>MTR 与 RedoLogManager 集成</li>
 *   <li>WAL 规则验证</li>
 *   <li>并发写入测试</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RedoLogWriteTest extends BaseStorageTest {

    private RedoLogManager redoLogManager;
    private Path redoLogDir;

    @Override
    protected void afterSetup() throws Exception {
        // 创建 redo log 目录
        redoLogDir = testDir.resolve("redo");
        java.nio.file.Files.createDirectories(redoLogDir);

        // 创建 RedoLogConfig
        RedoLogConfig config = new RedoLogConfig.Builder()
                .dataDir(redoLogDir.toString())
                .logFileSize(4 * 1024 * 1024)  // 4MB (最小值)
                .logBufferSize(1024 * 1024)    // 1MB
                .flushLogAtTrxCommit(RedoLogConfig.FLUSH_AT_TRX_COMMIT_SYNC)
                .build();

        // 创建并启动 RedoLogManager
        redoLogManager = new RedoLogManager(config);
        redoLogManager.start();

        // 关联 BufferPool (WAL 规则)
        bufferPool.setRedoLogManager(redoLogManager);
    }

    @Override
    protected void beforeCleanup() throws Exception {
        if (redoLogManager != null) {
            redoLogManager.shutdown();
        }
    }

    // ==================== RedoLogManager 基本功能测试 ====================

    @Test
    @Order(1)
    @DisplayName("RedoLogManager 启动和状态检查")
    void testRedoLogManagerStartup() {
        assertTrue(redoLogManager.isRunning(), "RedoLogManager should be running");
        assertEquals(0, redoLogManager.getCurrentSn(), "Initial currentSn should be 0");
        assertEquals(0, redoLogManager.getFlushedSn(), "Initial flushedSn should be 0");
    }

    @Test
    @Order(2)
    @DisplayName("直接写入 redo records")
    void testDirectWrite() throws Exception {
        // 创建测试 redo records
        PageId pageId = new PageId(SPACE_ID, 100);
        byte[] data = new byte[]{0x01, 0x02, 0x03, 0x04};

        List<RedoRecord> records = new ArrayList<>();
        records.add(new WriteBytesRecord(pageId, 100, data));
        records.add(new MultiRecEndRecord());

        // 获取 commit lock 并写入
        redoLogManager.getCommitLock().lock();
        try {
            long endSn = redoLogManager.write(records);
            assertTrue(endSn > 0, "End SN should be > 0 after write");

            // 等待 flush
            redoLogManager.waitForFlush(endSn);

            // 验证 flushedSn 已推进
            assertTrue(redoLogManager.getFlushedSn() >= endSn,
                    "FlushedSn should be >= endSn after waitForFlush");
        } finally {
            redoLogManager.getCommitLock().unlock();
        }
    }

    @Test
    @Order(3)
    @DisplayName("多次写入 redo records")
    void testMultipleWrites() throws Exception {
        int writeCount = 10;
        long lastSn = 0;

        for (int i = 0; i < writeCount; i++) {
            PageId pageId = new PageId(SPACE_ID, 100 + i);
            byte[] data = ("data_" + i).getBytes();

            List<RedoRecord> records = new ArrayList<>();
            records.add(new WriteBytesRecord(pageId, 50, data));
            records.add(new MultiRecEndRecord());

            redoLogManager.getCommitLock().lock();
            try {
                long endSn = redoLogManager.write(records);
                assertTrue(endSn > lastSn, "SN should be monotonically increasing");
                lastSn = endSn;
            } finally {
                redoLogManager.getCommitLock().unlock();
            }
        }

        // 等待所有写入 flush
        redoLogManager.waitForFlush(lastSn);
        assertTrue(redoLogManager.getFlushedSn() >= lastSn);

        System.out.println("Final currentSn: " + redoLogManager.getCurrentSn());
        System.out.println("Final flushedSn: " + redoLogManager.getFlushedSn());
        System.out.println("Writer stats: " + redoLogManager.getWriterStats());
        System.out.println("Flusher stats: " + redoLogManager.getFlusherStats());
    }

    // ==================== MTR 集成测试 ====================

    @Test
    @Order(10)
    @DisplayName("MTR 提交时生成 redo log")
    void testMtrCommitWithRedoLog() throws Exception {
        long snBefore = redoLogManager.getCurrentSn();

        // 使用带 RedoLogManager 的 MTR
        try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
            // 分配新页面
            Page page = mtr.newPage(SPACE_ID);
            assertNotNull(page);

            // 修改页面
            int testOffset = 100;
            int testValue = 12345;
            page.putInt(testOffset, testValue);

            // 记录修改 (生成 redo log)
            mtr.logModification(page, testOffset, 4);

            // 标记脏页
            mtr.markDirty(page);

            // 提交
            mtr.commit();
        }

        long snAfter = redoLogManager.getCurrentSn();
        assertTrue(snAfter > snBefore, "CurrentSn should increase after MTR commit");

        System.out.println("SN before: " + snBefore + ", after: " + snAfter);
    }

    @Test
    @Order(11)
    @DisplayName("MTR 多页面修改生成 redo log")
    void testMtrMultiPageModification() throws Exception {
        long snBefore = redoLogManager.getCurrentSn();

        try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
            // 分配多个页面
            Page page1 = mtr.newPage(SPACE_ID);
            Page page2 = mtr.newPage(SPACE_ID);
            Page page3 = mtr.newPage(SPACE_ID);

            // 修改所有页面
            page1.putInt(100, 111);
            mtr.logModification(page1, 100, 4);
            mtr.markDirty(page1);

            page2.putInt(200, 222);
            mtr.logModification(page2, 200, 4);
            mtr.markDirty(page2);

            page3.putLong(300, 333333L);
            mtr.logModification(page3, 300, 8);
            mtr.markDirty(page3);

            // 提交
            mtr.commit();
        }

        long snAfter = redoLogManager.getCurrentSn();
        assertTrue(snAfter > snBefore, "CurrentSn should increase significantly after multi-page MTR");

        System.out.println("Multi-page MTR: SN " + snBefore + " -> " + snAfter +
                " (delta: " + (snAfter - snBefore) + " bytes)");
    }

    @Test
    @Order(12)
    @DisplayName("MTR 回滚不生成 redo log")
    void testMtrRollbackNoRedoLog() throws Exception {
        long snBefore = redoLogManager.getCurrentSn();

        try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
            Page page = mtr.newPage(SPACE_ID);
            page.putInt(100, 999);
            mtr.logModification(page, 100, 4);
            mtr.markDirty(page);

            // 不调用 commit，让 close() 触发 rollback
        }

        long snAfter = redoLogManager.getCurrentSn();
        assertEquals(snBefore, snAfter, "CurrentSn should not change after MTR rollback");
    }

    @Test
    @Order(13)
    @DisplayName("MTR 无修改不生成 redo log")
    void testMtrNoModificationNoRedoLog() throws Exception {
        long snBefore = redoLogManager.getCurrentSn();

        try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
            // 只读取页面，不修改
            Page page = mtr.newPage(SPACE_ID);
            int value = page.getInt(100);  // 只读

            mtr.commit();
        }

        long snAfter = redoLogManager.getCurrentSn();
        assertEquals(snBefore, snAfter, "CurrentSn should not change for read-only MTR");
    }

    // ==================== WAL 规则测试 ====================

    @Test
    @Order(20)
    @DisplayName("WAL 规则: page LSN 更新")
    void testWalPageLsnUpdate() throws Exception {
        Page page;
        long pageLsn;

        try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
            page = mtr.newPage(SPACE_ID);
            page.putInt(100, 12345);
            mtr.logModification(page, 100, 4);
            mtr.markDirty(page);

            // 提交前 LSN 应该是 0 或很小
            long lsnBefore = page.getLsn();

            mtr.commit();

            // 提交后 LSN 应该更新
            pageLsn = page.getLsn();
        }

        assertTrue(pageLsn > 0, "Page LSN should be updated after commit");
        System.out.println("Page LSN after commit: " + pageLsn);
    }

    @Test
    @Order(21)
    @DisplayName("WAL 规则: BufferPool 关联 RedoLogManager")
    void testWalBufferPoolIntegration() {
        assertNotNull(bufferPool.getRedoLogManager(),
                "BufferPool should have RedoLogManager set");
        assertSame(redoLogManager, bufferPool.getRedoLogManager(),
                "BufferPool's RedoLogManager should match");
    }

    // ==================== 并发测试 ====================

    @Test
    @Order(30)
    @DisplayName("并发 MTR 提交")
    void testConcurrentMtrCommit() throws Exception {
        int threadCount = 4;
        int operationsPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        long snBefore = redoLogManager.getCurrentSn();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();  // 等待所有线程就绪

                    for (int i = 0; i < operationsPerThread; i++) {
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

        // 启动所有线程
        startLatch.countDown();

        // 等待完成
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Should complete within timeout");
        executor.shutdown();

        long snAfter = redoLogManager.getCurrentSn();

        System.out.println("Concurrent test results:");
        System.out.println("  Success: " + successCount.get());
        System.out.println("  Errors: " + errorCount.get());
        System.out.println("  SN delta: " + (snAfter - snBefore));
        System.out.println("  Writer stats: " + redoLogManager.getWriterStats());
        System.out.println("  Flusher stats: " + redoLogManager.getFlusherStats());

        assertEquals(threadCount * operationsPerThread, successCount.get(),
                "All operations should succeed");
        assertEquals(0, errorCount.get(), "No errors should occur");
        assertTrue(snAfter > snBefore, "SN should increase after concurrent operations");
    }

    // ==================== 性能测试 ====================

    @Test
    @Order(40)
    @DisplayName("写入吞吐量测试")
    void testWriteThroughput() throws Exception {
        int iterations = 100;
        long snBefore = redoLogManager.getCurrentSn();
        long startTime = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
                Page page = mtr.newPage(SPACE_ID);

                // 模拟多个修改
                for (int j = 0; j < 5; j++) {
                    int offset = 100 + j * 10;
                    page.putInt(offset, i * 1000 + j);
                    mtr.logModification(page, offset, 4);
                }
                mtr.markDirty(page);
                mtr.commit();
            }
        }

        long endTime = System.nanoTime();
        long snAfter = redoLogManager.getCurrentSn();
        long durationMs = (endTime - startTime) / 1_000_000;
        double throughput = (double) iterations / durationMs * 1000;

        System.out.println("Throughput test results:");
        System.out.println("  Iterations: " + iterations);
        System.out.println("  Duration: " + durationMs + " ms");
        System.out.println("  Throughput: " + String.format("%.2f", throughput) + " tx/sec");
        System.out.println("  Bytes written: " + (snAfter - snBefore));
        System.out.println("  Writer stats: " + redoLogManager.getWriterStats());
        System.out.println("  Flusher stats: " + redoLogManager.getFlusherStats());

        // 基本性能断言 (至少 100 tx/sec)
        assertTrue(throughput > 100, "Throughput should be > 100 tx/sec");
    }

    // ==================== 边界条件测试 ====================

    @Test
    @Order(50)
    @DisplayName("空 records 列表写入")
    void testEmptyRecordsWrite() throws Exception {
        long snBefore = redoLogManager.getCurrentSn();

        redoLogManager.getCommitLock().lock();
        try {
            long endSn = redoLogManager.write(new ArrayList<>());
            assertEquals(snBefore, endSn, "Empty write should return current SN");
        } finally {
            redoLogManager.getCommitLock().unlock();
        }
    }

    @Test
    @Order(51)
    @DisplayName("大数据量写入")
    void testLargeDataWrite() throws Exception {
        PageId pageId = new PageId(SPACE_ID, 200);
        byte[] largeData = new byte[8000];  // 8KB (接近半页)
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }

        List<RedoRecord> records = new ArrayList<>();
        records.add(new WriteBytesRecord(pageId, 100, largeData));
        records.add(new MultiRecEndRecord());

        redoLogManager.getCommitLock().lock();
        try {
            long endSn = redoLogManager.write(records);
            redoLogManager.waitForFlush(endSn);
            assertTrue(endSn > 8000, "Large write should advance SN significantly");
        } finally {
            redoLogManager.getCommitLock().unlock();
        }
    }
}
