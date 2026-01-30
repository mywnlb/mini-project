package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发 B+Tree 测试
 *
 * @author MiniDB
 */
class ConcurrentBTreeTest {

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool bufferPool;
    private RecordComparator comparator;

    private static final int RECORD_SIZE = SimpleRecordBuilder.RECORD_HEADER_SIZE +
            SimpleRecordBuilder.KEY_SIZE + 1;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = tempDir.resolve("test.db");
        diskManager = new DiskManager(dbFile.toString());
        bufferPool = new BufferPool(200, diskManager);
        comparator = new IntKeyComparator();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (bufferPool != null) {
            bufferPool.close();
        }
        if (diskManager != null) {
            diskManager.close();
        }
    }

    // ==================== LatchHolder 测试 ====================

    @Nested
    @DisplayName("LatchHolder 测试")
    class LatchHolderTests {

        @Test
        @DisplayName("获取和释放锁")
        void testAcquireAndRelease() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                var frame = bufferPool.getPage(btree.getMetadata().getRootPageId(),
                        BufferPool.FetchMode.READ_EXISTING);

                LatchHolder holder = new LatchHolder();

                // 获取共享锁
                assertTrue(holder.acquire(frame, LatchMode.SHARED));
                assertEquals(1, holder.getLatchCount());
                assertTrue(holder.isHolding(frame));

                // 释放
                holder.release(frame);
                assertEquals(0, holder.getLatchCount());
                assertFalse(holder.isHolding(frame));

                mtr.commit();
            }
        }

        @Test
        @DisplayName("释放所有锁")
        void testReleaseAll() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BTree btree = BTree.create(1L, 0, bufferPool, comparator, mtr);

                var frame = bufferPool.getPage(btree.getMetadata().getRootPageId(),
                        BufferPool.FetchMode.READ_EXISTING);

                LatchHolder holder = new LatchHolder();
                holder.acquire(frame, LatchMode.SHARED);
                holder.acquire(frame, LatchMode.SHARED); // 可重入

                holder.releaseAll();
                assertEquals(0, holder.getLatchCount());

                mtr.commit();
            }
        }
    }

    // ==================== 并发搜索测试 ====================

    @Nested
    @DisplayName("并发搜索")
    class ConcurrentSearchTests {

        @Test
        @DisplayName("多线程并发搜索")
        void testConcurrentSearch() throws Exception {
            // 准备数据
            BTree btree;
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                btree = BTree.create(1L, 0, bufferPool, comparator, mtr);
                for (int i = 1; i <= 100; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }
                mtr.commit();
            }

            ConcurrentBTree concurrentBTree = ConcurrentBTree.wrap(btree);

            // 并发搜索
            int threadCount = 10;
            int searchesPerThread = 50;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < searchesPerThread; i++) {
                            int key = (i % 100) + 1;
                            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                                BTreeSearchResult result = concurrentBTree.search(
                                        IntKeyComparator.intToBytes(key), mtr);
                                if (result != null && result.isExactMatch()) {
                                    successCount.incrementAndGet();
                                }
                                mtr.commit();
                            }
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                        e.printStackTrace();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(0, errorCount.get(), "不应有错误");
            assertEquals(threadCount * searchesPerThread, successCount.get(), "所有搜索应成功");
        }

        @Test
        @DisplayName("乐观搜索")
        void testOptimisticSearch() throws Exception {
            BTree btree;
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                btree = BTree.create(1L, 0, bufferPool, comparator, mtr);
                for (int i = 1; i <= 50; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }
                mtr.commit();
            }

            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                // 乐观搜索
                BTreeSearchResult result = ConcurrentBTreeOps.optimisticSearch(
                        btree, IntKeyComparator.intToBytes(25), mtr);

                assertNotNull(result);
                assertTrue(result.isExactMatch());

                mtr.commit();
            }
        }
    }

    // ==================== 并发插入测试 ====================

    @Nested
    @DisplayName("并发插入")
    class ConcurrentInsertTests {

        @Test
        @DisplayName("多线程并发插入不同的键")
        void testConcurrentInsertDifferentKeys() throws Exception {
            BTree btree;
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                btree = BTree.create(1L, 0, bufferPool, comparator, mtr);
                mtr.commit();
            }

            ConcurrentBTree concurrentBTree = ConcurrentBTree.wrap(btree);

            int threadCount = 5;
            int insertsPerThread = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errorCount = new AtomicInteger(0);
            ConcurrentHashMap<Integer, Boolean> insertedKeys = new ConcurrentHashMap<>();

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < insertsPerThread; i++) {
                            int key = threadId * 1000 + i; // 每个线程使用不同的键范围
                            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                                byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) key}, 2);
                                boolean inserted = concurrentBTree.insert(record,
                                        IntKeyComparator.intToBytes(key), mtr);
                                if (inserted) {
                                    insertedKeys.put(key, true);
                                }
                                mtr.commit();
                            }
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                        e.printStackTrace();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(0, errorCount.get(), "不应有错误");
            assertEquals(threadCount * insertsPerThread, insertedKeys.size(), "所有键应被插入");

            // 验证所有键都能找到
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                for (int key : insertedKeys.keySet()) {
                    assertTrue(concurrentBTree.containsKey(IntKeyComparator.intToBytes(key), mtr),
                            "应能找到 key=" + key);
                }
                mtr.commit();
            }
        }
    }

    // ==================== 并发删除测试 ====================

    @Nested
    @DisplayName("并发删除")
    class ConcurrentDeleteTests {

        @Test
        @DisplayName("多线程并发删除不同的键")
        void testConcurrentDeleteDifferentKeys() throws Exception {
            BTree btree;
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                btree = BTree.create(1L, 0, bufferPool, comparator, mtr);
                // 插入 100 条记录
                for (int i = 1; i <= 100; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }
                mtr.commit();
            }

            ConcurrentBTree concurrentBTree = ConcurrentBTree.wrap(btree);

            // 准备要删除的键（偶数）
            List<Integer> keysToDelete = new ArrayList<>();
            for (int i = 2; i <= 100; i += 2) {
                keysToDelete.add(i);
            }
            Collections.shuffle(keysToDelete);

            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger deletedCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            int keysPerThread = keysToDelete.size() / threadCount;

            for (int t = 0; t < threadCount; t++) {
                final int start = t * keysPerThread;
                final int end = (t == threadCount - 1) ? keysToDelete.size() : start + keysPerThread;

                executor.submit(() -> {
                    try {
                        for (int i = start; i < end; i++) {
                            int key = keysToDelete.get(i);
                            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                                boolean deleted = concurrentBTree.delete(
                                        IntKeyComparator.intToBytes(key), RECORD_SIZE, mtr);
                                if (deleted) {
                                    deletedCount.incrementAndGet();
                                }
                                mtr.commit();
                            }
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                        e.printStackTrace();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(0, errorCount.get(), "不应有错误");
            assertEquals(50, deletedCount.get(), "应删除 50 条记录");

            // 验证只剩奇数键
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                for (int i = 1; i <= 100; i++) {
                    boolean exists = concurrentBTree.containsKey(IntKeyComparator.intToBytes(i), mtr);
                    if (i % 2 == 0) {
                        assertFalse(exists, "偶数键 " + i + " 应已删除");
                    } else {
                        assertTrue(exists, "奇数键 " + i + " 应存在");
                    }
                }
                mtr.commit();
            }
        }
    }

    // ==================== 混合并发测试 ====================

    @Nested
    @DisplayName("混合并发操作")
    class MixedConcurrentTests {

        @Test
        @DisplayName("并发读写混合")
        void testConcurrentReadWrite() throws Exception {
            BTree btree;
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                btree = BTree.create(1L, 0, bufferPool, comparator, mtr);
                // 初始数据
                for (int i = 1; i <= 50; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i, new byte[]{(byte) i}, 2);
                    btree.insert(record, IntKeyComparator.intToBytes(i), mtr);
                }
                mtr.commit();
            }

            ConcurrentBTree concurrentBTree = ConcurrentBTree.wrap(btree);

            int readerCount = 5;
            int writerCount = 2;
            int operationsPerThread = 30;

            ExecutorService executor = Executors.newFixedThreadPool(readerCount + writerCount);
            CountDownLatch latch = new CountDownLatch(readerCount + writerCount);
            AtomicInteger readSuccess = new AtomicInteger(0);
            AtomicInteger writeSuccess = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            // 读线程
            for (int t = 0; t < readerCount; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < operationsPerThread; i++) {
                            int key = (i % 50) + 1;
                            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                                BTreeSearchResult result = concurrentBTree.search(
                                        IntKeyComparator.intToBytes(key), mtr);
                                if (result != null) {
                                    readSuccess.incrementAndGet();
                                }
                                mtr.commit();
                            }
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                        e.printStackTrace();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // 写线程
            for (int t = 0; t < writerCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < operationsPerThread; i++) {
                            int key = 1000 + threadId * 100 + i;
                            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                                byte[] record = SimpleRecordBuilder.buildRecord(key, new byte[]{(byte) key}, 2);
                                boolean inserted = concurrentBTree.insert(record,
                                        IntKeyComparator.intToBytes(key), mtr);
                                if (inserted) {
                                    writeSuccess.incrementAndGet();
                                }
                                mtr.commit();
                            }
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                        e.printStackTrace();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(60, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(0, errorCount.get(), "不应有错误");
            assertTrue(readSuccess.get() > 0, "应有成功的读操作");
            assertTrue(writeSuccess.get() > 0, "应有成功的写操作");
        }
    }

    // ==================== ConcurrentBTree 包装器测试 ====================

    @Nested
    @DisplayName("ConcurrentBTree 包装器")
    class ConcurrentBTreeWrapperTests {

        @Test
        @DisplayName("创建并发 B+Tree")
        void testCreateConcurrentBTree() throws Exception {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                ConcurrentBTree concurrentBTree = ConcurrentBTree.create(
                        1L, 0, bufferPool, comparator, mtr);

                assertNotNull(concurrentBTree);
                assertEquals(1, concurrentBTree.getTreeHeight());
                assertEquals(0, concurrentBTree.getRecordCount());

                mtr.commit();
            }
        }

        @Test
        @DisplayName("包装现有 B+Tree")
        void testWrapExistingBTree() throws Exception {
            BTree btree;
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                btree = BTree.create(1L, 0, bufferPool, comparator, mtr);
                byte[] record = SimpleRecordBuilder.buildRecord(100, new byte[]{1}, 2);
                btree.insert(record, IntKeyComparator.intToBytes(100), mtr);
                mtr.commit();
            }

            ConcurrentBTree concurrentBTree = ConcurrentBTree.wrap(btree);

            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                assertTrue(concurrentBTree.containsKey(IntKeyComparator.intToBytes(100), mtr));
                assertEquals(1, concurrentBTree.getRecordCount());
                mtr.commit();
            }
        }

        @Test
        @DisplayName("范围扫描")
        void testRangeScan() throws Exception {
            ConcurrentBTree concurrentBTree;
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                concurrentBTree = ConcurrentBTree.create(1L, 0, bufferPool, comparator, mtr);
                for (int i = 1; i <= 10; i++) {
                    byte[] record = SimpleRecordBuilder.buildRecord(i * 10, new byte[]{(byte) i}, 2);
                    concurrentBTree.insert(record, IntKeyComparator.intToBytes(i * 10), mtr);
                }
                mtr.commit();
            }

            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                try (BTreeRangeScanner scanner = concurrentBTree.rangeScan(mtr,
                        RangeBound.inclusive(IntKeyComparator.intToBytes(30)),
                        RangeBound.inclusive(IntKeyComparator.intToBytes(70)))) {
                    List<Integer> keys = scanner.scanKeysAsInt();
                    assertEquals(List.of(30, 40, 50, 60, 70), keys);
                }
                mtr.commit();
            }
        }
    }
}
