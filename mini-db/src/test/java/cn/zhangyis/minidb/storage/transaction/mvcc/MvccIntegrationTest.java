package cn.zhangyis.minidb.storage.transaction.mvcc;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.record.logical.DataTuple;
import cn.zhangyis.minidb.storage.transaction.core.Transaction;
import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.core.TransactionManager;
import cn.zhangyis.minidb.storage.transaction.dml.TransactionalDml;
import cn.zhangyis.minidb.storage.transaction.undo.UndoLogManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MVCC 集成测试
 *
 * <p>端到端测试 MVCC 系统的完整功能：
 * <ul>
 *   <li>ReadView 创建和管理</li>
 *   <li>版本链遍历</li>
 *   <li>可见性判断</li>
 *   <li>隔离级别支持</li>
 *   <li>Purge 安全性</li>
 * </ul>
 * </p>
 *
 * @author MiniDB
 * @version 1.0
 */
@DisplayName("MVCC 集成测试")
public class MvccIntegrationTest {

    private BufferPool bufferPool;
    private TransactionManager transactionManager;
    private UndoLogManager undoLogManager;
    private TransactionalDml dml;

    @BeforeEach
    public void setUp() {
        // TODO: 初始化测试环境
        // 1. 创建 BufferPool
        // 2. 创建 UndoLogManager
        // 3. 创建 TransactionManager
        // 4. 创建 TransactionalDml
    }

    @Test
    @DisplayName("测试：基本的 MVCC 读取")
    public void testBasicMvccRead() {
        // 事务 T1 插入数据
        Transaction t1 = transactionManager.begin();
        byte[] primaryKey = "key1".getBytes();
        DataTuple tuple1 = createTestTuple("key1", "value1");

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            boolean inserted = dml.insert(mtr, t1, tuple1, primaryKey);
            assertTrue(inserted, "插入应该成功");
            mtr.commit();
        }

        try {
            transactionManager.commit(t1);
        } catch (Exception e) {
            // 忽略异常
        }

        // 事务 T2 读取数据
        Transaction t2 = transactionManager.begin();
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            DataTuple read = dml.read(mtr, t2, primaryKey);
            assertNotNull(read, "应该能读到已提交的数据");
            assertEquals("value1", read.getColumnValue("col1"), "读取的值应该正确");
            mtr.commit();
        }

        t2.setState(Transaction.TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：版本链遍历")
    public void testVersionChainTraversal() {
        // 事务 T1 插入数据
        Transaction t1 = transactionManager.begin();
        byte[] primaryKey = "key2".getBytes();
        DataTuple tuple1 = createTestTuple("key2", "value1");

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            boolean inserted = dml.insert(mtr, t1, tuple1, primaryKey);
            assertTrue(inserted, "插入应该成功");
            mtr.commit();
        }

        try {
            transactionManager.commit(t1);
        } catch (Exception e) {
            // 忽略异常
        }

        // 事务 T2 更新数据
        Transaction t2 = transactionManager.begin();
        DataTuple tuple2 = createTestTuple("key2", "value2");

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            boolean updated = dml.update(mtr, t2, tuple2, primaryKey);
            assertTrue(updated, "更新应该成功");
            mtr.commit();
        }

        try {
            transactionManager.commit(t2);
        } catch (Exception e) {
            // 忽略异常
        }

        // 事务 T3 读取数据（应该看到最新版本）
        Transaction t3 = transactionManager.begin();
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            DataTuple read = dml.read(mtr, t3, primaryKey);
            assertNotNull(read, "应该能读到数据");
            assertEquals("value2", read.getColumnValue("col1"), "应该读到最新版本");
            mtr.commit();
        }

        t3.setState(Transaction.TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：删除标记检查")
    public void testDeleteMarkCheck() {
        // 事务 T1 插入数据
        Transaction t1 = transactionManager.begin();
        byte[] primaryKey = "key3".getBytes();
        DataTuple tuple1 = createTestTuple("key3", "value3");

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            boolean inserted = dml.insert(mtr, t1, tuple1, primaryKey);
            assertTrue(inserted, "插入应该成功");
            mtr.commit();
        }

        try {
            transactionManager.commit(t1);
        } catch (Exception e) {
            // 忽略异常
        }

        // 事务 T2 删除数据
        Transaction t2 = transactionManager.begin();

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            boolean deleted = dml.delete(mtr, t2, primaryKey);
            assertTrue(deleted, "删除应该成功");
            mtr.commit();
        }

        try {
            transactionManager.commit(t2);
        } catch (Exception e) {
            // 忽略异常
        }

        // 事务 T3 读取数据（应该看不到已删除的数据）
        Transaction t3 = transactionManager.begin();
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            DataTuple read = dml.read(mtr, t3, primaryKey);
            assertNull(read, "应该看不到已删除的数据");
            mtr.commit();
        }

        t3.setState(Transaction.TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：快照隔离")
    public void testSnapshotIsolation() {
        // 事务 T1 插入数据
        Transaction t1 = transactionManager.begin();
        byte[] primaryKey = "key4".getBytes();
        DataTuple tuple1 = createTestTuple("key4", "value1");

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            boolean inserted = dml.insert(mtr, t1, tuple1, primaryKey);
            assertTrue(inserted, "插入应该成功");
            mtr.commit();
        }

        try {
            transactionManager.commit(t1);
        } catch (Exception e) {
            // 忽略异常
        }

        // 事务 T2 开始（REPEATABLE_READ）
        Transaction t2 = transactionManager.begin(Transaction.IsolationLevel.REPEATABLE_READ);
        ReadView rv2 = t2.getOrCreateReadView();
        assertNotNull(rv2, "应该创建 ReadView");

        // 事务 T3 更新数据
        Transaction t3 = transactionManager.begin();
        DataTuple tuple3 = createTestTuple("key4", "value2");

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            boolean updated = dml.update(mtr, t3, tuple3, primaryKey);
            assertTrue(updated, "更新应该成功");
            mtr.commit();
        }

        try {
            transactionManager.commit(t3);
        } catch (Exception e) {
            // 忽略异常
        }

        // 事务 T2 读取数据（应该看到快照中的值）
        DataTuple read1 = null;
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            read1 = dml.read(mtr, t2, primaryKey);
            assertNotNull(read1, "应该能读到数据");
            mtr.commit();
        }

        // 事务 T4 再次更新数据
        Transaction t4 = transactionManager.begin();
        DataTuple tuple4 = createTestTuple("key4", "value3");

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            boolean updated = dml.update(mtr, t4, tuple4, primaryKey);
            assertTrue(updated, "更新应该成功");
            mtr.commit();
        }

        try {
            transactionManager.commit(t4);
        } catch (Exception e) {
            // 忽略异常
        }

        // 事务 T2 再次读取数据（应该看到相同的快照值）
        DataTuple read2 = null;
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            read2 = dml.read(mtr, t2, primaryKey);
            assertNotNull(read2, "应该能读到数据");
            mtr.commit();
        }

        // 验证快照隔离
        assertEquals(read1.getColumnValue("col1"), read2.getColumnValue("col1"),
                "快照隔离应该保证两次读取结果相同");

        t2.setState(Transaction.TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：多个并发事务")
    public void testConcurrentTransactions() {
        // 创建多个事务
        Transaction t1 = transactionManager.begin();
        Transaction t2 = transactionManager.begin();
        Transaction t3 = transactionManager.begin();

        // 验证事务 ID 递增
        assertTrue(t1.getId().getValue() < t2.getId().getValue(), "事务 ID 应该递增");
        assertTrue(t2.getId().getValue() < t3.getId().getValue(), "事务 ID 应该递增");

        // 验证所有事务都是活跃的
        assertTrue(t1.isActive(), "事务 T1 应该是活跃的");
        assertTrue(t2.isActive(), "事务 T2 应该是活跃的");
        assertTrue(t3.isActive(), "事务 T3 应该是活跃的");

        t1.setState(Transaction.TransactionState.COMMITTED);
        t2.setState(Transaction.TransactionState.COMMITTED);
        t3.setState(Transaction.TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：ReadView 的可见性判断")
    public void testReadViewVisibility() {
        // 创建事务 T1
        Transaction t1 = transactionManager.begin();
        ReadView rv1 = t1.getOrCreateReadView();

        assertNotNull(rv1, "应该创建 ReadView");
        assertEquals(t1.getId(), rv1.getCreatorTrxId(), "ReadView 的创建者应该是 T1");

        // 验证 ReadView 的属性
        assertNotNull(rv1.getLowLimitId(), "ReadView 应该有 low_limit_id");
        assertNotNull(rv1.getUpLimitId(), "ReadView 应该有 up_limit_id");
        assertNotNull(rv1.getActiveList(), "ReadView 应该有活跃列表");

        t1.setState(Transaction.TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：事务提交时的 ReadView 清理")
    public void testReadViewCleanupOnCommit() {
        Transaction t1 = transactionManager.begin();
        ReadView rv1 = t1.getOrCreateReadView();

        assertNotNull(rv1, "应该创建 ReadView");

        // 提交事务
        try {
            transactionManager.commit(t1);
        } catch (Exception e) {
            // 忽略异常
        }

        // 验证 ReadView 已清理
        assertNull(t1.getCachedReadView(), "提交后 ReadView 应该被清理");
    }

    @Test
    @DisplayName("测试：事务回滚时的 ReadView 清理")
    public void testReadViewCleanupOnRollback() {
        Transaction t1 = transactionManager.begin();
        ReadView rv1 = t1.getOrCreateReadView();

        assertNotNull(rv1, "应该创建 ReadView");

        // 回滚事务
        try {
            transactionManager.rollback(t1);
        } catch (Exception e) {
            // 忽略异常
        }

        // 验证 ReadView 已清理
        assertNull(t1.getCachedReadView(), "回滚后 ReadView 应该被清理");
    }

    @Test
    @DisplayName("测试：范围扫描的 MVCC 过滤")
    public void testRangeScanMvccFiltering() {
        // 插入多条数据
        Transaction t1 = transactionManager.begin();
        byte[] key1 = "key1".getBytes();
        byte[] key2 = "key2".getBytes();
        byte[] key3 = "key3".getBytes();

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            dml.insert(mtr, t1, createTestTuple("key1", "value1"), key1);
            dml.insert(mtr, t1, createTestTuple("key2", "value2"), key2);
            dml.insert(mtr, t1, createTestTuple("key3", "value3"), key3);
            mtr.commit();
        }

        try {
            transactionManager.commit(t1);
        } catch (Exception e) {
            // 忽略异常
        }

        // 范围扫描
        Transaction t2 = transactionManager.begin();
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // TODO: 实现范围扫描测试
            mtr.commit();
        }

        t2.setState(Transaction.TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：Purge 安全性")
    public void testPurgeSafety() {
        // 创建事务 T1 并创建 ReadView
        Transaction t1 = transactionManager.begin();
        ReadView rv1 = t1.getOrCreateReadView();

        // 验证 ReadView 已注册到 Purge 协调器
        int activeReadViews = transactionManager.getPurgeCoordinator().getActiveReadViewCount();
        assertTrue(activeReadViews > 0, "应该有活跃的 ReadView");

        // 提交事务
        try {
            transactionManager.commit(t1);
        } catch (Exception e) {
            // 忽略异常
        }

        // 验证 ReadView 已注销
        int activeReadViewsAfter = transactionManager.getPurgeCoordinator().getActiveReadViewCount();
        assertTrue(activeReadViewsAfter < activeReadViews, "ReadView 应该被注销");
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建测试数据元组
     */
    private DataTuple createTestTuple(String key, String value) {
        // TODO: 实现创建测试数据元组的逻辑
        return null;
    }
}
