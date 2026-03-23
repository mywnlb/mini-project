package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.mvcc.ReadView;
import cn.zhangyis.minidb.storage.transaction.mvcc.RecordVersion;
import cn.zhangyis.minidb.storage.transaction.mvcc.VersionChainReader;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import cn.zhangyis.minidb.storage.transaction.purge.PurgeCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Undo 压缩并发安全测试
 *
 * <p>验证并发场景下的正确性：
 * <ul>
 *   <li>并发压缩不同链的安全性</li>
 *   <li>并发版本链读取的安全性</li>
 *   <li>压缩与读取并发的安全性</li>
 *   <li>统计信息线程安全</li>
 * </ul>
 * </p>
 */
@DisplayName("Undo Concurrency Safety Tests")
class UndoConcurrencySafetyTest {

    private static final int TABLE_ID = 1;

    private UndoLogManager undoLogManager;
    private PurgeCoordinator purgeCoordinator;
    private UndoCompressionManager compressionManager;
    private MiniTransaction mtr;

    private ConcurrentHashMap<String, UndoRecord> undoStore;
    private AtomicInteger pageCounter;

    @BeforeEach
    void setUp() throws Exception {
        undoLogManager = mock(UndoLogManager.class);
        purgeCoordinator = mock(PurgeCoordinator.class);
        compressionManager = new UndoCompressionManager(undoLogManager, purgeCoordinator);
        mtr = mock(MiniTransaction.class);
        undoStore = new ConcurrentHashMap<>();
        pageCounter = new AtomicInteger(1000);

        when(purgeCoordinator.getPurgeLimit()).thenReturn(new TransactionId(100_000));

        when(undoLogManager.appendMergedUndo(any(UpdateUndoRecord.class), any(MiniTransaction.class)))
                .thenAnswer(invocation -> {
                    UpdateUndoRecord merged = invocation.getArgument(0);
                    int pageNo = pageCounter.getAndIncrement();
                    RollbackPointer ptr = RollbackPointer.forInsert(0, pageNo, 128);
                    undoStore.put(ptrKey(ptr), merged);
                    return ptr;
                });
    }

    // ==================== 3.2 并发压缩安全性 ====================

    @Test
    @DisplayName("并发：多线程压缩不同链")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testConcurrentCompressionDifferentChains() throws InterruptedException {
        int threadCount = 8;
        int tasksPerThread = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicLong totalSuccess = new AtomicLong(0);
        AtomicLong totalFailed = new AtomicLong(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await(); // 所有线程同时启动
                    for (int i = 0; i < tasksPerThread; i++) {
                        byte[] pk = new byte[]{(byte) threadId, (byte) i};
                        List<UpdateUndoRecord> chain = buildChain(pk, 5, 2, 32);
                        RollbackPointer ptr = RollbackPointer.forInsert(0, 50 + threadId * 100 + i, 100);

                        UndoCompressionManager.CompressionResult result =
                                compressionManager.compressUndoChain(pk, TABLE_ID, chain, ptr, mtr);

                        if (result.isSuccessful()) {
                            totalSuccess.incrementAndGet();
                        } else {
                            totalFailed.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    fail("并发压缩不应抛异常: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown(); // GO!
        doneLatch.await();

        long total = totalSuccess.get() + totalFailed.get();
        assertEquals((long) threadCount * tasksPerThread, total,
                "所有任务应完成");

        // 验证统计信息一致
        UndoCompressionManager.CompressionStats stats = compressionManager.getCompressionStats();
        assertEquals(totalSuccess.get(), stats.successfulCompressions,
                "统计成功数应匹配");
        assertEquals(totalFailed.get(), stats.failedCompressions,
                "统计失败数应匹配");

        System.out.printf("并发压缩: threads=%d, total=%d, success=%d, failed=%d%n",
                threadCount, total, totalSuccess.get(), totalFailed.get());
    }

    @Test
    @DisplayName("并发：压缩与版本链读取并行")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testConcurrentCompressionAndRead() throws InterruptedException {
        // 预先构建一批版本链
        int chainCount = 20;
        List<ChainInfo> chains = new ArrayList<>();
        for (int i = 0; i < chainCount; i++) {
            chains.add(buildVersionChain(10, 3, 32));
        }

        int readerThreads = 4;
        int compressorThreads = 2;
        int readIterations = 1000;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(readerThreads + compressorThreads);
        AtomicLong readOps = new AtomicLong(0);
        AtomicLong readErrors = new AtomicLong(0);

        // 读取线程：持续遍历版本链
        for (int t = 0; t < readerThreads; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    VersionChainReader reader = createChainReader();
                    ReadView readView = createReadViewSeeingAll();

                    for (int i = 0; i < readIterations; i++) {
                        ChainInfo chain = chains.get(i % chains.size());
                        try {
                            // 版本链读取不应因并发压缩而失败
                            reader.findVisibleVersion(chain.headPtr, readView);
                            readOps.incrementAndGet();
                        } catch (Exception e) {
                            readErrors.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        // 压缩线程：对链执行压缩
        for (int t = 0; t < compressorThreads; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = threadId; i < chains.size(); i += compressorThreads) {
                        ChainInfo chain = chains.get(i);
                        compressionManager.compressUndoChain(
                                chain.primaryKey, TABLE_ID, chain.undoChain, chain.headPtr, mtr);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await();

        System.out.printf("并发读取+压缩: reads=%d, readErrors=%d%n",
                readOps.get(), readErrors.get());

        // 并发场景下不应有读取错误
        assertEquals(0, readErrors.get(), "版本链读取不应因并发压缩而出错");
        assertTrue(readOps.get() > 0, "应完成读取操作");
    }

    @Test
    @DisplayName("并发：统计信息线程安全")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testConcurrentStatsAccess() throws InterruptedException {
        int writerThreads = 4;
        int readerThreads = 4;
        int opsPerThread = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(writerThreads + readerThreads);

        AtomicLong statsReadCount = new AtomicLong(0);

        // 写入线程：执行压缩修改统计
        for (int t = 0; t < writerThreads; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        byte[] pk = new byte[]{(byte) threadId, (byte) i};
                        List<UpdateUndoRecord> chain = buildChain(pk, 4, 2, 16);
                        RollbackPointer ptr = RollbackPointer.forInsert(0, 50 + threadId * 200 + i, 64);
                        compressionManager.compressUndoChain(pk, TABLE_ID, chain, ptr, mtr);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        // 读取线程：并发读取统计信息
        for (int t = 0; t < readerThreads; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < opsPerThread * 10; i++) {
                        UndoCompressionManager.CompressionStats stats = compressionManager.getCompressionStats();
                        assertNotNull(stats, "统计信息不应为 null");
                        assertTrue(stats.successfulCompressions >= 0, "成功数不应为负");
                        assertTrue(stats.failedCompressions >= 0, "失败数不应为负");
                        assertTrue(stats.totalSpaceSavings >= 0, "节省空间不应为负");
                        statsReadCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await();

        System.out.printf("并发统计访问: statsReads=%d%n", statsReadCount.get());
        assertTrue(statsReadCount.get() > 0, "应完成统计读取");

        // 最终统计应一致
        UndoCompressionManager.CompressionStats stats = compressionManager.getCompressionStats();
        long total = stats.successfulCompressions + stats.failedCompressions;
        assertEquals((long) writerThreads * opsPerThread, total,
                "最终统计总数应等于总任务数");
    }

    // ==================== 辅助方法 ====================

    private List<UpdateUndoRecord> buildChain(byte[] pk, int chainLength,
                                               int columnCount, int valueLen) {
        List<UpdateUndoRecord> chain = new ArrayList<>();
        for (int i = chainLength; i >= 1; i--) {
            List<UpdateUndoRecord.OldColumnValue> cols = new ArrayList<>();
            for (int c = 0; c < columnCount; c++) {
                byte[] val = new byte[valueLen];
                val[0] = (byte) (i + c);
                cols.add(new UpdateUndoRecord.OldColumnValue(c, val));
            }
            RollbackPointer prevPtr = i > 1
                    ? RollbackPointer.forInsert(0, 60 + i, 300 + i)
                    : RollbackPointer.NULL;
            chain.add(new UpdateUndoRecord(
                    new TransactionId(i), TABLE_ID, prevPtr, pk, cols));
        }
        return chain;
    }

    private ChainInfo buildVersionChain(int depth, int modifiedColumns, int valueLen) {
        byte[] pk = new byte[]{
                (byte) (pageCounter.get() & 0xFF),
                (byte) ((pageCounter.get() >> 8) & 0xFF),
                0x01, 0x02
        };
        List<UpdateUndoRecord> chain = new ArrayList<>();
        RollbackPointer prevPtr = RollbackPointer.NULL;

        for (int i = 1; i <= depth; i++) {
            List<UpdateUndoRecord.OldColumnValue> cols = new ArrayList<>();
            for (int c = 0; c < modifiedColumns; c++) {
                byte[] val = new byte[valueLen];
                val[0] = (byte) (i + c);
                cols.add(new UpdateUndoRecord.OldColumnValue(c, val));
            }

            UpdateUndoRecord record = new UpdateUndoRecord(
                    new TransactionId(i), TABLE_ID, prevPtr, pk, cols);

            int pageNo = pageCounter.getAndIncrement();
            RollbackPointer thisPtr = RollbackPointer.forInsert(0, pageNo, 64);
            undoStore.put(ptrKey(thisPtr), record);

            chain.add(0, record);
            prevPtr = thisPtr;
        }

        return new ChainInfo(pk, chain, prevPtr);
    }

    private VersionChainReader createChainReader() {
        return new VersionChainReader(rollPtr -> undoStore.get(ptrKey(rollPtr)));
    }

    private ReadView createReadViewSeeingAll() {
        return new ReadView(
                new TransactionId(999_999),
                new TransactionId(999_999),
                new TransactionId(1),
                Collections.emptyList()
        );
    }

    private String ptrKey(RollbackPointer ptr) {
        return ptr.getRsegId() + ":" + ptr.getPageNo() + ":" + ptr.getOffset();
    }

    private static class ChainInfo {
        final byte[] primaryKey;
        final List<UpdateUndoRecord> undoChain;
        final RollbackPointer headPtr;

        ChainInfo(byte[] pk, List<UpdateUndoRecord> chain, RollbackPointer headPtr) {
            this.primaryKey = pk;
            this.undoChain = chain;
            this.headPtr = headPtr;
        }
    }
}
