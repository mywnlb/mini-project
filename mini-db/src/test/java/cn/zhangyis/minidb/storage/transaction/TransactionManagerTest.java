package cn.zhangyis.minidb.storage.transaction;

import cn.zhangyis.minidb.storage.constants.StorageConstants;
import cn.zhangyis.minidb.storage.transaction.core.*;
import cn.zhangyis.minidb.storage.transaction.mvcc.ReadView;
import cn.zhangyis.minidb.storage.transaction.undo.UndoLogManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static cn.zhangyis.minidb.testutil.TransactionTestSupport.forceCommitTime;

/**
 * TransactionManager 单元测试
 *
 * <p>测试 TransactionSysPage 和 TransactionManager 的正确性</p>
 *
 * @author MiniDB
 * @version 1.0
 */
@DisplayName("Transaction Manager Tests")
class TransactionManagerTest {

    // ==================== TransactionSysPage 测试 ====================

    @Nested
    @DisplayName("TransactionSysPage Tests")
    class TransactionSysPageTest {

        private ByteBuffer pageBuffer;

        @BeforeEach
        void setUp() {
            pageBuffer = ByteBuffer.allocate(StorageConstants.PAGE_SIZE);
            pageBuffer.order(ByteOrder.LITTLE_ENDIAN);
        }

        @Test
        @DisplayName("初始化事务系统页")
        void testInit() {
            int spaceId = 0;
            TransactionSysPage.init(pageBuffer, spaceId);

            // 验证页面类型
            assertTrue(TransactionSysPage.isTrxSysPage(pageBuffer));

            // 验证初始 TRX_ID
            assertEquals(TransactionSysPage.INITIAL_TRX_ID, TransactionSysPage.getNextTrxId(pageBuffer));

            // 验证所有 Rseg 槽位为无效
            assertEquals(0, TransactionSysPage.getValidRsegCount(pageBuffer));
        }

        @Test
        @DisplayName("分配单个 TRX_ID")
        void testAllocateTrxId() {
            TransactionSysPage.init(pageBuffer, 0);

            // 分配第一个 TRX_ID
            TransactionId trxId1 = TransactionSysPage.allocateTrxId(pageBuffer);
            assertEquals(1L, trxId1.getValue());

            // 分配第二个 TRX_ID
            TransactionId trxId2 = TransactionSysPage.allocateTrxId(pageBuffer);
            assertEquals(2L, trxId2.getValue());

            // 验证下一个 TRX_ID
            assertEquals(3L, TransactionSysPage.getNextTrxId(pageBuffer));
        }

        @Test
        @DisplayName("批量分配 TRX_ID")
        void testAllocateTrxIdBatch() {
            TransactionSysPage.init(pageBuffer, 0);

            // 批量分配 100 个 TRX_ID
            TransactionId firstId = TransactionSysPage.allocateTrxIdBatch(pageBuffer, 100);
            assertEquals(1L, firstId.getValue());

            // 验证下一个 TRX_ID
            assertEquals(101L, TransactionSysPage.getNextTrxId(pageBuffer));

            // 继续分配
            TransactionId nextId = TransactionSysPage.allocateTrxId(pageBuffer);
            assertEquals(101L, nextId.getValue());
        }

        @Test
        @DisplayName("批量分配数量必须为正数")
        void testAllocateTrxIdBatchInvalidCount() {
            TransactionSysPage.init(pageBuffer, 0);

            assertThrows(IllegalArgumentException.class, () ->
                    TransactionSysPage.allocateTrxIdBatch(pageBuffer, 0));

            assertThrows(IllegalArgumentException.class, () ->
                    TransactionSysPage.allocateTrxIdBatch(pageBuffer, -1));
        }

        @Test
        @DisplayName("设置和获取 Rseg 槽位")
        void testRsegSlot() {
            TransactionSysPage.init(pageBuffer, 0);

            // 设置 Rseg 槽位
            TransactionSysPage.setRsegSlot(pageBuffer, 0, 0, 64);
            TransactionSysPage.setRsegSlot(pageBuffer, 5, 1, 100);
            TransactionSysPage.setRsegSlot(pageBuffer, 127, 2, 200);

            // 验证槽位
            assertTrue(TransactionSysPage.isRsegSlotValid(pageBuffer, 0));
            assertTrue(TransactionSysPage.isRsegSlotValid(pageBuffer, 5));
            assertTrue(TransactionSysPage.isRsegSlotValid(pageBuffer, 127));
            assertFalse(TransactionSysPage.isRsegSlotValid(pageBuffer, 1));

            // 验证槽位内容
            int[] slot0 = TransactionSysPage.getRsegSlot(pageBuffer, 0);
            assertEquals(0, slot0[0]); // spaceId
            assertEquals(64, slot0[1]); // pageNo

            int[] slot5 = TransactionSysPage.getRsegSlot(pageBuffer, 5);
            assertEquals(1, slot5[0]);
            assertEquals(100, slot5[1]);

            // 验证有效 Rseg 数量
            assertEquals(3, TransactionSysPage.getValidRsegCount(pageBuffer));
        }

        @Test
        @DisplayName("Rseg ID 边界检查")
        void testRsegIdBoundary() {
            TransactionSysPage.init(pageBuffer, 0);

            // 有效范围: 0-127
            assertDoesNotThrow(() -> TransactionSysPage.getRsegSlot(pageBuffer, 0));
            assertDoesNotThrow(() -> TransactionSysPage.getRsegSlot(pageBuffer, 127));

            // 无效范围
            assertThrows(IllegalArgumentException.class, () ->
                    TransactionSysPage.getRsegSlot(pageBuffer, -1));
            assertThrows(IllegalArgumentException.class, () ->
                    TransactionSysPage.getRsegSlot(pageBuffer, 128));
        }

        @Test
        @DisplayName("PageId 工具方法")
        void testPageIdMethods() {
            var pageId = TransactionSysPage.getPageId(0);
            assertEquals(0, pageId.getSpaceId());
            assertEquals(TransactionSysPage.DEFAULT_PAGE_NO, pageId.getPageNo());

            var defaultPageId = TransactionSysPage.getDefaultPageId();
            assertEquals(pageId, defaultPageId);
        }

        @Test
        @DisplayName("dump 方法")
        void testDump() {
            TransactionSysPage.init(pageBuffer, 0);
            TransactionSysPage.setRsegSlot(pageBuffer, 0, 0, 64);
            TransactionSysPage.allocateTrxIdBatch(pageBuffer, 10);

            String dump = TransactionSysPage.dump(pageBuffer);

            assertNotNull(dump);
            assertTrue(dump.contains("nextTrxId: 11"));
            assertTrue(dump.contains("validRsegs: 1/128"));
            assertTrue(dump.contains("rseg[0]"));
        }
    }

    // ==================== TransactionManager 测试 (使用内存模式) ====================

    @Nested
    @DisplayName("TransactionManager Tests (Memory Mode)")
    class TransactionManagerMemoryModeTest {

        private TransactionManager txnMgr;
        private MockBufferPool mockBufferPool;

        @BeforeEach
        void setUp() {
            mockBufferPool = new MockBufferPool();
            txnMgr = new TransactionManager(mockBufferPool, null);
            txnMgr.initializeInMemory();
        }

        @Test
        @DisplayName("开始事务")
        void testBegin() throws Exception {
            Transaction trx = txnMgr.begin();

            assertNotNull(trx);
            assertNotNull(trx.getId());
            assertEquals(TransactionState.ACTIVE, trx.getState());
            assertTrue(trx.isActive());
            assertEquals(1, txnMgr.getActiveTransactionCount());
        }

        @Test
        @DisplayName("开始事务 - 指定隔离级别")
        void testBeginWithIsolationLevel() throws Exception {
            Transaction trxRR = txnMgr.begin(Transaction.IsolationLevel.REPEATABLE_READ);
            assertEquals(Transaction.IsolationLevel.REPEATABLE_READ, trxRR.getIsolationLevel());

            Transaction trxRC = txnMgr.begin(Transaction.IsolationLevel.READ_COMMITTED);
            assertEquals(Transaction.IsolationLevel.READ_COMMITTED, trxRC.getIsolationLevel());
        }

        @Test
        @DisplayName("TRX_ID 递增分配")
        void testTrxIdIncrement() throws Exception {
            Transaction trx1 = txnMgr.begin();
            Transaction trx2 = txnMgr.begin();
            Transaction trx3 = txnMgr.begin();

            assertTrue(trx2.getId().getValue() > trx1.getId().getValue());
            assertTrue(trx3.getId().getValue() > trx2.getId().getValue());
        }

        @Test
        @DisplayName("提交事务")
        void testCommit() throws Exception {
            Transaction trx = txnMgr.begin();
            assertEquals(1, txnMgr.getActiveTransactionCount());

            txnMgr.commit(trx);

            assertEquals(TransactionState.COMMITTED, trx.getState());
            assertEquals(0, txnMgr.getActiveTransactionCount());
            assertFalse(txnMgr.isTransactionActive(trx.getId()));
        }

        @Test
        @DisplayName("提交已提交事务抛出异常")
        void testCommitCommittedTransaction() throws Exception {
            Transaction trx = txnMgr.begin();
            txnMgr.commit(trx);

            assertThrows(IllegalStateException.class, () -> txnMgr.commit(trx));
        }

        @Test
        @DisplayName("回滚事务")
        void testRollback() throws Exception {
            Transaction trx = txnMgr.begin();
            assertEquals(1, txnMgr.getActiveTransactionCount());

            txnMgr.rollback(trx);

            assertEquals(TransactionState.ROLLED_BACK, trx.getState());
            assertEquals(0, txnMgr.getActiveTransactionCount());
        }

        @Test
        @DisplayName("回滚已提交事务抛出异常")
        void testRollbackCommittedTransaction() throws Exception {
            Transaction trx = txnMgr.begin();
            txnMgr.commit(trx);

            assertThrows(IllegalStateException.class, () -> txnMgr.rollback(trx));
        }

        @Test
        @DisplayName("重复回滚不抛出异常")
        void testDoubleRollback() throws Exception {
            Transaction trx = txnMgr.begin();
            txnMgr.rollback(trx);

            // 再次回滚应该安静地返回
            assertDoesNotThrow(() -> txnMgr.rollback(trx));
        }

        @Test
        @DisplayName("创建 ReadView")
        void testCreateReadView() throws Exception {
            // 创建多个活跃事务
            Transaction trx1 = txnMgr.begin();
            Transaction trx2 = txnMgr.begin();
            Transaction trx3 = txnMgr.begin();

            // 为 trx2 创建 ReadView
            ReadView readView = txnMgr.createReadView(trx2);

            assertNotNull(readView);
            assertEquals(trx2.getId(), readView.getCreatorTrxId());

            // ReadView 应该包含 trx1 和 trx3（排除创建者 trx2）
            List<TransactionId> activeList = readView.getActiveTrxIds();
            assertEquals(2, activeList.size());
            assertTrue(activeList.contains(trx1.getId()));
            assertTrue(activeList.contains(trx3.getId()));
            assertFalse(activeList.contains(trx2.getId()));
        }

        @Test
        @DisplayName("ReadView - 可见性检查")
        void testReadViewVisibility() throws Exception {
            Transaction trx1 = txnMgr.begin();
            Transaction trx2 = txnMgr.begin();

            // trx1 提交
            txnMgr.commit(trx1);

            // trx3 在 trx1 提交后开始
            Transaction trx3 = txnMgr.begin();

            // 为 trx2 创建 ReadView
            ReadView readView = txnMgr.createReadView(trx2);

            // trx1 已提交，在 ReadView 创建前，应该可见
            assertTrue(readView.isVisible(trx1.getId()));

            // trx2 是创建者自己，可见
            assertTrue(readView.isVisible(trx2.getId()));

            // trx3 在 ReadView 创建后开始，不可见
            assertFalse(readView.isVisible(trx3.getId()));
        }

        @Test
        @DisplayName("获取活跃事务 ID 列表")
        void testGetActiveTransactionIds() throws Exception {
            Transaction trx1 = txnMgr.begin();
            Transaction trx2 = txnMgr.begin();
            Transaction trx3 = txnMgr.begin();

            txnMgr.commit(trx2);

            List<TransactionId> activeIds = txnMgr.getActiveTransactionIds();
            assertEquals(2, activeIds.size());
            assertTrue(activeIds.contains(trx1.getId()));
            assertFalse(activeIds.contains(trx2.getId()));
            assertTrue(activeIds.contains(trx3.getId()));
        }

        @Test
        @DisplayName("获取事务")
        void testGetTransaction() throws Exception {
            Transaction trx = txnMgr.begin();

            Transaction found = txnMgr.getTransaction(trx.getId());
            assertEquals(trx, found);

            txnMgr.commit(trx);

            // 提交后不在活跃列表中
            assertNull(txnMgr.getTransaction(trx.getId()));
        }

        @Test
        @DisplayName("未初始化时操作抛出异常")
        void testOperationsBeforeInit() {
            TransactionManager uninitMgr = new TransactionManager(mockBufferPool, null);

            assertThrows(IllegalStateException.class, uninitMgr::begin);
        }

        @Test
        @DisplayName("关闭时回滚所有活跃事务")
        void testCloseRollbacksActiveTransactions() throws Exception {
            Transaction trx1 = txnMgr.begin();
            Transaction trx2 = txnMgr.begin();

            assertEquals(2, txnMgr.getActiveTransactionCount());

            txnMgr.close();

            assertEquals(0, txnMgr.getActiveTransactionCount());
            assertEquals(TransactionState.ROLLED_BACK, trx1.getState());
            assertEquals(TransactionState.ROLLED_BACK, trx2.getState());
        }

        @Test
        @DisplayName("toString 方法")
        void testToString() throws Exception {
            txnMgr.begin();
            txnMgr.begin();

            String str = txnMgr.toString();
            assertNotNull(str);
            assertTrue(str.contains("activeCount=2"));
        }
    }

    // ==================== 并发测试 ====================

    @Nested
    @DisplayName("Concurrency Tests")
    class ConcurrencyTest {

        private TransactionManager txnMgr;
        private MockBufferPool mockBufferPool;

        @BeforeEach
        void setUp() {
            mockBufferPool = new MockBufferPool();
            txnMgr = new TransactionManager(mockBufferPool, null);
            txnMgr.initializeInMemory();
        }

        @Test
        @DisplayName("并发开始事务 - TRX_ID 唯一性")
        void testConcurrentBeginTrxIdUniqueness() throws Exception {
            int threadCount = 10;
            int txnPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            Set<Long> trxIds = java.util.Collections.synchronizedSet(new HashSet<>());
            AtomicInteger errorCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < txnPerThread; j++) {
                            Transaction trx = txnMgr.begin();
                            boolean added = trxIds.add(trx.getId().getValue());
                            if (!added) {
                                errorCount.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(0, errorCount.get(), "Should have no duplicate TRX_IDs");
            assertEquals(threadCount * txnPerThread, trxIds.size(), "All TRX_IDs should be unique");
        }

        @Test
        @DisplayName("并发提交和回滚")
        void testConcurrentCommitAndRollback() throws Exception {
            int txnCount = 100;
            List<Transaction> transactions = new ArrayList<>();

            // 创建事务
            for (int i = 0; i < txnCount; i++) {
                transactions.add(txnMgr.begin());
            }

            assertEquals(txnCount, txnMgr.getActiveTransactionCount());

            // 并发提交/回滚
            ExecutorService executor = Executors.newFixedThreadPool(10);
            CountDownLatch doneLatch = new CountDownLatch(txnCount);
            AtomicInteger errorCount = new AtomicInteger(0);

            for (int i = 0; i < txnCount; i++) {
                final Transaction trx = transactions.get(i);
                final boolean shouldCommit = (i % 2 == 0);

                executor.submit(() -> {
                    try {
                        if (shouldCommit) {
                            txnMgr.commit(trx);
                        } else {
                            txnMgr.rollback(trx);
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(0, errorCount.get());
            assertEquals(0, txnMgr.getActiveTransactionCount());

            // 验证状态
            int committedCount = 0;
            int rolledBackCount = 0;
            for (Transaction trx : transactions) {
                if (trx.getState() == TransactionState.COMMITTED) {
                    committedCount++;
                } else if (trx.getState() == TransactionState.ROLLED_BACK) {
                    rolledBackCount++;
                }
            }
            assertEquals(txnCount / 2, committedCount);
            assertEquals(txnCount / 2, rolledBackCount);
        }

        @Test
        @DisplayName("并发创建 ReadView")
        void testConcurrentCreateReadView() throws Exception {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger errorCount = new AtomicInteger(0);

            // 创建一些初始事务
            List<Transaction> initialTrx = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                initialTrx.add(txnMgr.begin());
            }

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();

                        // 每个线程创建新事务并创建 ReadView
                        Transaction trx = txnMgr.begin();
                        ReadView readView = txnMgr.createReadView(trx);

                        if (readView == null) {
                            errorCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(0, errorCount.get());
        }
    }

    // ==================== Transaction 状态测试 ====================

    @Nested
    @DisplayName("Transaction State Tests")
    class TransactionStateTest {

        @Test
        @DisplayName("事务状态转换")
        void testStateTransitions() {
            TransactionId trxId = new TransactionId(1);
            Transaction trx = new Transaction(trxId, Transaction.IsolationLevel.REPEATABLE_READ);

            assertEquals(TransactionState.ACTIVE, trx.getState());
            assertTrue(trx.isActive());

            // ACTIVE -> COMMIT_PENDING
            assertTrue(trx.compareAndSetState(TransactionState.ACTIVE, TransactionState.COMMIT_PENDING));
            assertEquals(TransactionState.COMMIT_PENDING, trx.getState());
            assertFalse(trx.isActive());

            // COMMIT_PENDING -> COMMITTED
            assertTrue(trx.compareAndSetState(TransactionState.COMMIT_PENDING, TransactionState.COMMITTED));
            assertEquals(TransactionState.COMMITTED, trx.getState());
        }

        @Test
        @DisplayName("事务时间戳")
        void testTransactionTimestamps() throws Exception {
            TransactionId trxId = new TransactionId(1);
            Transaction trx = new Transaction(trxId, Transaction.IsolationLevel.REPEATABLE_READ);

            assertTrue(trx.getStartTime() > 0);

            Thread.sleep(10);

            forceCommitTime(trx, System.currentTimeMillis());
            assertTrue(trx.getCommitTime() > trx.getStartTime());
            assertTrue(trx.getDuration() >= 10);
        }

        @Test
        @DisplayName("compareAndSetState")
        void testCompareAndSetState() {
            TransactionId trxId = new TransactionId(1);
            Transaction trx = new Transaction(trxId, Transaction.IsolationLevel.REPEATABLE_READ);

            // 成功的 CAS
            assertTrue(trx.compareAndSetState(TransactionState.ACTIVE, TransactionState.COMMIT_PENDING));
            assertEquals(TransactionState.COMMIT_PENDING, trx.getState());

            // 失败的 CAS (期望 ACTIVE 但实际是 COMMIT_PENDING)
            assertFalse(trx.compareAndSetState(TransactionState.ACTIVE, TransactionState.COMMITTED));
            assertEquals(TransactionState.COMMIT_PENDING, trx.getState());
        }

        @Test
        @DisplayName("事务 toString")
        void testTransactionToString() {
            TransactionId trxId = new TransactionId(100);
            Transaction trx = new Transaction(trxId, Transaction.IsolationLevel.REPEATABLE_READ);

            String str = trx.toString();
            assertNotNull(str);
            assertTrue(str.contains("100"));
            assertTrue(str.contains("ACTIVE"));
        }
    }

    // ==================== Mock 类 ====================

    /**
     * 模拟 BufferPool
     */
    private static class MockBufferPool extends cn.zhangyis.minidb.storage.buffer.BufferPool {

        public MockBufferPool() {
            super(100, null);
        }

        // 不需要实际实现，因为使用 initializeInMemory() 模式
    }
}
