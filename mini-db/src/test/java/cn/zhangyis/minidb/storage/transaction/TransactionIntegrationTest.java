package cn.zhangyis.minidb.storage.transaction;

import cn.zhangyis.minidb.storage.constants.StorageConstants;
import cn.zhangyis.minidb.storage.transaction.core.*;
import cn.zhangyis.minidb.storage.transaction.mvcc.ReadView;
import cn.zhangyis.minidb.storage.transaction.mvcc.VisibilityChecker;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import cn.zhangyis.minidb.storage.transaction.purge.PurgeCoordinator;
import cn.zhangyis.minidb.storage.transaction.purge.PurgeThread;
import cn.zhangyis.minidb.storage.transaction.undo.*;
import org.junit.jupiter.api.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事务子系统端到端集成测试
 *
 * <p>测试事务、MVCC、Undo Log 的完整工作流程</p>
 *
 * @author MiniDB
 * @version 1.0
 */
@DisplayName("Transaction System Integration Tests")
class TransactionIntegrationTest {

    // ==================== MVCC 可见性测试 ====================

    @Nested
    @DisplayName("MVCC Visibility Tests")
    class MvccVisibilityTest {

        private TransactionManager txnMgr;
        private MockBufferPool mockBufferPool;

        @BeforeEach
        void setUp() {
            mockBufferPool = new MockBufferPool();
            txnMgr = new TransactionManager(mockBufferPool, null);
            txnMgr.initializeInMemory();
        }

        @Test
        @DisplayName("REPEATABLE READ: 事务开始后的修改不可见")
        void testRepeatableReadIsolation() throws Exception {
            // T1 开始
            Transaction t1 = txnMgr.begin(Transaction.IsolationLevel.REPEATABLE_READ);

            // T2 开始并提交修改
            Transaction t2 = txnMgr.begin();
            TransactionId t2Id = t2.getId();
            txnMgr.commit(t2);

            // T3 开始（在 T1 的 ReadView 创建后）
            Transaction t3 = txnMgr.begin();
            TransactionId t3Id = t3.getId();

            // T1 创建 ReadView
            ReadView t1ReadView = txnMgr.createReadView(t1);

            // T2 的修改对 T1 可见（因为 T2 在 ReadView 创建前已提交）
            assertTrue(t1ReadView.isVisible(t2Id),
                    "Committed transaction before ReadView should be visible");

            // T3 的修改对 T1 不可见（T3 在 T1 的 ReadView 创建后开始）
            assertFalse(t1ReadView.isVisible(t3Id),
                    "Transaction started after ReadView should not be visible");
        }

        @Test
        @DisplayName("事务可见自己的修改")
        void testTransactionSeesOwnChanges() throws Exception {
            Transaction trx = txnMgr.begin();
            ReadView readView = txnMgr.createReadView(trx);

            // 事务可见自己
            assertTrue(readView.isVisible(trx.getId()),
                    "Transaction should see its own changes");
        }

        @Test
        @DisplayName("VisibilityChecker 可见性分析")
        void testVisibilityCheckerAnalysis() throws Exception {
            Transaction t1 = txnMgr.begin();
            Transaction t2 = txnMgr.begin();
            txnMgr.commit(t2);
            Transaction t3 = txnMgr.begin();

            ReadView readView = txnMgr.createReadView(t1);

            // 分析 T2（已提交）
            VisibilityChecker.VisibilityResult analysisT2 =
                    VisibilityChecker.analyzeVisibility(t2.getId(), readView);
            assertTrue(VisibilityChecker.isVisible(t2.getId(), readView));
            assertNotNull(analysisT2);

            // 分析 T3（ReadView 后开始）
            VisibilityChecker.VisibilityResult analysisT3 =
                    VisibilityChecker.analyzeVisibility(t3.getId(), readView);
            assertFalse(VisibilityChecker.isVisible(t3.getId(), readView));
            assertEquals(VisibilityChecker.VisibilityResult.NOT_VISIBLE_ABOVE_LOW_LIMIT, analysisT3);
        }

        @Test
        @DisplayName("活跃事务在 ReadView 中不可见")
        void testActiveTransactionNotVisible() throws Exception {
            Transaction t1 = txnMgr.begin();
            Transaction t2 = txnMgr.begin(); // 不提交

            // 为 T1 创建 ReadView
            ReadView readView = txnMgr.createReadView(t1);

            // T2 未提交，对 T1 不可见
            assertFalse(readView.isVisible(t2.getId()),
                    "Uncommitted active transaction should not be visible");

            // 验证 T2 在活跃列表中
            assertTrue(readView.getActiveTrxIds().contains(t2.getId()));
        }
    }

    // ==================== 事务生命周期测试 ====================

    @Nested
    @DisplayName("Transaction Lifecycle Tests")
    class TransactionLifecycleTest {

        private TransactionManager txnMgr;
        private MockBufferPool mockBufferPool;

        @BeforeEach
        void setUp() {
            mockBufferPool = new MockBufferPool();
            txnMgr = new TransactionManager(mockBufferPool, null);
            txnMgr.initializeInMemory();
        }

        @Test
        @DisplayName("完整事务生命周期: begin -> commit")
        void testCommitLifecycle() throws Exception {
            // Begin
            Transaction trx = txnMgr.begin();
            assertEquals(TransactionState.ACTIVE, trx.getState());
            assertTrue(txnMgr.isTransactionActive(trx.getId()));

            // Do some work (simulated)
            trx.incrementInsertCount();
            trx.incrementUpdateCount();
            assertEquals(1, trx.getInsertCount());
            assertEquals(1, trx.getUpdateCount());

            // Commit
            txnMgr.commit(trx);
            assertEquals(TransactionState.COMMITTED, trx.getState());
            assertFalse(txnMgr.isTransactionActive(trx.getId()));
            assertTrue(trx.getCommitTime() > 0);
        }

        @Test
        @DisplayName("完整事务生命周期: begin -> rollback")
        void testRollbackLifecycle() throws Exception {
            // Begin
            Transaction trx = txnMgr.begin();
            assertEquals(TransactionState.ACTIVE, trx.getState());

            // Do some work
            trx.incrementDeleteCount();

            // Rollback
            txnMgr.rollback(trx);
            assertEquals(TransactionState.ROLLED_BACK, trx.getState());
            assertFalse(txnMgr.isTransactionActive(trx.getId()));
        }

        @Test
        @DisplayName("多事务交错执行")
        void testInterleavedTransactions() throws Exception {
            // T1 开始
            Transaction t1 = txnMgr.begin();

            // T2 开始
            Transaction t2 = txnMgr.begin();

            // T3 开始
            Transaction t3 = txnMgr.begin();

            assertEquals(3, txnMgr.getActiveTransactionCount());

            // T2 提交
            txnMgr.commit(t2);
            assertEquals(2, txnMgr.getActiveTransactionCount());
            assertTrue(t2.isCommitted());

            // T1 回滚
            txnMgr.rollback(t1);
            assertEquals(1, txnMgr.getActiveTransactionCount());
            assertTrue(t1.isRolledBack());

            // T3 提交
            txnMgr.commit(t3);
            assertEquals(0, txnMgr.getActiveTransactionCount());
        }
    }

    // ==================== Undo Log 测试 ====================

    @Nested
    @DisplayName("Undo Log Integration Tests")
    class UndoLogIntegrationTest {

        @Test
        @DisplayName("INSERT Undo 记录写入和读取")
        void testInsertUndoRecord() {
            TransactionId trxId = new TransactionId(100);
            byte[] pk = new byte[]{0x01, 0x02, 0x03, 0x04};
            int tableId = 5;

            // 创建 INSERT Undo
            InsertUndoRecord insertUndo = new InsertUndoRecord(trxId, tableId, pk);

            // 验证属性
            assertEquals(UndoRecordType.INSERT, insertUndo.getType());
            assertEquals(trxId, insertUndo.getTrxId());
            assertEquals(tableId, insertUndo.getTableId());
            assertArrayEquals(pk, insertUndo.getPrimaryKeyData());

            // 序列化和反序列化
            int size = insertUndo.calculateSize();
            ByteBuffer buf = ByteBuffer.allocate(size);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            insertUndo.writeTo(buf, 0);

            UndoRecord restored = UndoRecord.readFrom(buf, 0);
            assertInstanceOf(InsertUndoRecord.class, restored);
            assertEquals(trxId, restored.getTrxId());
        }

        @Test
        @DisplayName("UPDATE Undo 记录带版本链")
        void testUpdateUndoWithVersionChain() {
            TransactionId trxId = new TransactionId(200);
            byte[] pk = new byte[]{0x10, 0x20};
            RollbackPointer prevPtr = RollbackPointer.forUpdate(5, 100, 50);

            List<UpdateUndoRecord.OldColumnValue> oldCols = new ArrayList<>();
            oldCols.add(new UpdateUndoRecord.OldColumnValue(1, new byte[]{0x01}));
            oldCols.add(new UpdateUndoRecord.OldColumnValue(3, new byte[]{0x03, 0x04}));

            UpdateUndoRecord updateUndo = new UpdateUndoRecord(trxId, 10, prevPtr, pk, oldCols);

            // 验证版本链指针
            assertEquals(prevPtr, updateUndo.getPrevUndoPtr());
            assertFalse(updateUndo.getPrevUndoPtr().isNull());

            // 验证旧列值
            assertEquals(2, updateUndo.getColumnCount());
        }

        @Test
        @DisplayName("UndoPage 写入多条记录")
        void testUndoPageMultipleRecords() {
            ByteBuffer pageBuffer = ByteBuffer.allocate(StorageConstants.PAGE_SIZE);
            pageBuffer.order(ByteOrder.LITTLE_ENDIAN);

            TransactionId trxId = new TransactionId(100);
            UndoPage.init(pageBuffer, UndoPageHeader.UNDO_INSERT, trxId, 0);

            // 写入多条记录
            List<Integer> offsets = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                byte[] pk = new byte[]{(byte) i, (byte) (i + 1)};
                InsertUndoRecord record = new InsertUndoRecord(trxId, i, pk);
                int offset = UndoPage.writeUndoRecord(pageBuffer, record);
                if (offset > 0) {
                    offsets.add(offset);
                }
            }

            // 验证写入数量
            assertTrue(offsets.size() > 0);
            assertEquals(offsets.size(), UndoPage.getRecordCount(pageBuffer));

            // 读取验证
            for (int i = 0; i < offsets.size(); i++) {
                UndoRecord record = UndoPage.readUndoRecord(pageBuffer, offsets.get(i));
                assertEquals(i, record.getTableId());
            }
        }

        @Test
        @DisplayName("UndoSegment 管理多个页面")
        void testUndoSegmentMultiplePages() {
            TransactionId trxId = new TransactionId(100);
            int rsegId = 5;
            UndoSegment segment = new UndoSegment(trxId, rsegId, true);

            // Mock 页面提供器
            MockUndoPageSupplier supplier = new MockUndoPageSupplier();

            // 写入足够多的记录以触发页面分配
            int recordCount = 100;
            for (int i = 0; i < recordCount; i++) {
                byte[] pk = new byte[50]; // 较大的主键
                InsertUndoRecord record = new InsertUndoRecord(trxId, i, pk);
                RollbackPointer ptr = segment.writeUndoRecord(record, supplier);
                assertNotNull(ptr);
                assertFalse(ptr.isNull());
            }

            assertEquals(recordCount, segment.getRecordCount());
            assertTrue(segment.getPageCount() > 0);
        }
    }

    // ==================== Purge 协调器测试 ====================

    @Nested
    @DisplayName("Purge Coordinator Tests")
    class PurgeCoordinatorTest {

        private TransactionManager txnMgr;
        private MockBufferPool mockBufferPool;
        private PurgeCoordinator purgeCoordinator;

        @BeforeEach
        void setUp() {
            mockBufferPool = new MockBufferPool();
            txnMgr = new TransactionManager(mockBufferPool, null);
            txnMgr.initializeInMemory();
            purgeCoordinator = new PurgeCoordinator(txnMgr);
        }

        @Test
        @DisplayName("无活跃 ReadView 时 Purge 边界最大")
        void testPurgeLimitWithNoReadViews() throws Exception {
            // 创建一些事务
            Transaction t1 = txnMgr.begin();
            Transaction t2 = txnMgr.begin();
            txnMgr.commit(t1);
            txnMgr.commit(t2);

            // 没有注册 ReadView
            TransactionId purgeLimit = purgeCoordinator.getPurgeLimit();

            // Purge 边界应该接近当前最大 TRX_ID
            assertTrue(purgeLimit.getValue() > 0);
        }

        @Test
        @DisplayName("有活跃 ReadView 时 Purge 边界受限")
        void testPurgeLimitWithActiveReadViews() throws Exception {
            Transaction t1 = txnMgr.begin();
            Transaction t2 = txnMgr.begin();
            Transaction t3 = txnMgr.begin();

            // 为 T1 创建 ReadView 并注册
            ReadView rv1 = txnMgr.createReadView(t1);
            purgeCoordinator.registerReadView(rv1);

            // Purge 边界应该不超过 rv1 的 up_limit_id
            TransactionId purgeLimit = purgeCoordinator.getPurgeLimit();
            TransactionId rv1UpLimit = rv1.getUpLimitId();

            assertTrue(purgeLimit.getValue() <= rv1UpLimit.getValue(),
                    "Purge limit should not exceed minimum ReadView up_limit_id");

            // 注销后边界应该更新
            purgeCoordinator.unregisterReadView(rv1);
            purgeCoordinator.invalidateCache();
            TransactionId newPurgeLimit = purgeCoordinator.getPurgeLimit();

            assertTrue(newPurgeLimit.getValue() >= purgeLimit.getValue());
        }

        @Test
        @DisplayName("canPurge 检查")
        void testCanPurge() throws Exception {
            Transaction t1 = txnMgr.begin();
            ReadView rv = txnMgr.createReadView(t1);
            purgeCoordinator.registerReadView(rv);

            // 早期事务可以清理
            TransactionId oldTrx = new TransactionId(1);
            // 这取决于 up_limit_id 的值

            // 当前事务不能清理
            TransactionId currentTrx = t1.getId();
            assertFalse(purgeCoordinator.canPurge(currentTrx),
                    "Current transaction should not be purgeable");
        }
    }

    // ==================== 并发事务测试 ====================

    @Nested
    @DisplayName("Concurrent Transaction Tests")
    class ConcurrentTransactionTest {

        private TransactionManager txnMgr;
        private MockBufferPool mockBufferPool;

        @BeforeEach
        void setUp() {
            mockBufferPool = new MockBufferPool();
            txnMgr = new TransactionManager(mockBufferPool, null);
            txnMgr.initializeInMemory();
        }

        @Test
        @DisplayName("并发事务 TRX_ID 唯一性")
        void testConcurrentTrxIdUniqueness() throws Exception {
            int threadCount = 8;
            int txnPerThread = 200;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            ConcurrentHashMap<Long, Boolean> trxIds = new ConcurrentHashMap<>();
            AtomicInteger duplicateCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < txnPerThread; i++) {
                            Transaction trx = txnMgr.begin();
                            if (trxIds.putIfAbsent(trx.getId().getValue(), true) != null) {
                                duplicateCount.incrementAndGet();
                            }
                            // 随机提交或回滚
                            if (i % 2 == 0) {
                                txnMgr.commit(trx);
                            } else {
                                txnMgr.rollback(trx);
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(0, duplicateCount.get(), "No duplicate TRX_IDs should exist");
            assertEquals(threadCount * txnPerThread, trxIds.size());
        }

        @Test
        @DisplayName("并发 ReadView 创建")
        void testConcurrentReadViewCreation() throws Exception {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            // 先创建一些活跃事务
            List<Transaction> activeTrxs = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                activeTrxs.add(txnMgr.begin());
            }

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        Transaction trx = txnMgr.begin();
                        ReadView rv = txnMgr.createReadView(trx);
                        if (rv != null && rv.getCreatorTrxId().equals(trx.getId())) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount, successCount.get());
        }
    }

    // ==================== RollbackPointer 测试 ====================

    @Nested
    @DisplayName("RollbackPointer Tests")
    class RollbackPointerTest {

        @Test
        @DisplayName("INSERT 指针编解码")
        void testInsertPointerEncoding() {
            RollbackPointer ptr = RollbackPointer.forInsert(63, 1000, 500);

            assertTrue(ptr.isInsert());
            assertEquals(63, ptr.getRsegId());
            assertEquals(1000, ptr.getPageNo());
            assertEquals(500, ptr.getOffset());

            // 编码后解码
            long encoded = ptr.encode();
            RollbackPointer decoded = RollbackPointer.decode(encoded);

            assertEquals(ptr.isInsert(), decoded.isInsert());
            assertEquals(ptr.getRsegId(), decoded.getRsegId());
            assertEquals(ptr.getPageNo(), decoded.getPageNo());
            assertEquals(ptr.getOffset(), decoded.getOffset());
        }

        @Test
        @DisplayName("UPDATE 指针编解码")
        void testUpdatePointerEncoding() {
            RollbackPointer ptr = RollbackPointer.forUpdate(100, 50000, 30000);

            assertFalse(ptr.isInsert());
            assertEquals(100, ptr.getRsegId());
            assertEquals(50000, ptr.getPageNo());
            assertEquals(30000, ptr.getOffset());

            // ByteBuffer 编解码
            ByteBuffer buf = ByteBuffer.allocate(8);
            ptr.writeTo(buf, 0);

            RollbackPointer restored = RollbackPointer.readFrom(buf, 0);
            assertEquals(ptr, restored);
        }

        @Test
        @DisplayName("NULL 指针")
        void testNullPointer() {
            RollbackPointer nullPtr = RollbackPointer.NULL;

            assertTrue(nullPtr.isNull());
            assertEquals(0, nullPtr.getPageNo());
            assertEquals(0, nullPtr.getOffset());
        }
    }

    // ==================== Mock 类 ====================

    private static class MockBufferPool extends cn.zhangyis.minidb.storage.buffer.BufferPool {
        public MockBufferPool() {
            super(100, null);
        }
    }

    private static class MockUndoPageSupplier implements UndoSegment.UndoPageSupplier {
        private int nextPageNo = 100;
        private final java.util.Map<Integer, ByteBuffer> pages = new java.util.HashMap<>();

        @Override
        public UndoSegment.UndoPageInfo allocateUndoPage(int undoType,
                                                          TransactionId trxId,
                                                          int rsegId) {
            int pageNo = nextPageNo++;
            ByteBuffer buf = ByteBuffer.allocate(StorageConstants.PAGE_SIZE);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            UndoPage.init(buf, undoType, trxId, rsegId);
            pages.put(pageNo, buf);
            return new UndoSegment.UndoPageInfo(pageNo, buf);
        }

        public ByteBuffer readPage(int pageNo) {
            return pages.get(pageNo);
        }
    }
}
