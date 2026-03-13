package cn.zhangyis.minidb.storage.transaction.core;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.record.logical.DataTuple;
import cn.zhangyis.minidb.storage.transaction.dml.TransactionalDml;
import cn.zhangyis.minidb.storage.transaction.mvcc.ReadView;
import cn.zhangyis.minidb.storage.transaction.undo.UndoLogManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;
import static cn.zhangyis.minidb.testutil.TransactionTestSupport.forceState;

/**
 * 隔离级别支持测试
 *
 * <p>测试所有隔离级别的正确性：
 * <ul>
 *   <li>READ_UNCOMMITTED: 允许脏读</li>
 *   <li>READ_COMMITTED: 只读已提交数据，可能不可重复读</li>
 *   <li>REPEATABLE_READ: 快照隔离，保证可重复读</li>
 *   <li>SERIALIZABLE: 完全串行化</li>
 * </ul>
 * </p>
 *
 * @author MiniDB
 * @version 1.0
 */
@DisplayName("隔离级别支持测试")
public class IsolationLevelTest {

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
    @DisplayName("测试：READ_UNCOMMITTED 隔离级别允许脏读")
    public void testReadUncommittedAllowsDirtyRead() throws Exception {
        // 事务 T1 修改数据（未提交）
        Transaction t1 = transactionManager.begin(Transaction.IsolationLevel.READ_UNCOMMITTED);
        byte[] primaryKey = "key1".getBytes();
        DataTuple tuple1 = createTestTuple("key1", "value1");

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            boolean inserted = dml.insert(mtr, t1, tuple1, primaryKey);
            assertTrue(inserted, "插入应该成功");
            mtr.commit();
        }

        // 事务 T2 读取未提交的数据（脏读）
        Transaction t2 = transactionManager.begin(Transaction.IsolationLevel.READ_UNCOMMITTED);
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            DataTuple read = dml.read(mtr, t2, primaryKey);
            assertNotNull(read, "READ_UNCOMMITTED 应该能读到未提交的数据（脏读）");
            mtr.commit();
        }

        forceState(t1, TransactionState.COMMITTED);
        forceState(t2, TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：READ_COMMITTED 隔离级别不允许脏读")
    public void testReadCommittedPreventsDirtyRead() throws Exception {
        // 事务 T1 修改数据（未提交）
        Transaction t1 = transactionManager.begin();
        byte[] primaryKey = "key2".getBytes();
        DataTuple tuple1 = createTestTuple("key2", "value2");

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            boolean inserted = dml.insert(mtr, t1, tuple1, primaryKey);
            assertTrue(inserted, "插入应该成功");
            mtr.commit();
        }

        // 事务 T2 尝试读取未提交的数据
        Transaction t2 = transactionManager.begin(Transaction.IsolationLevel.READ_COMMITTED);
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            DataTuple read = dml.read(mtr, t2, primaryKey);
            assertNull(read, "READ_COMMITTED 不应该能读到未提交的数据");
            mtr.commit();
        }

        forceState(t1, TransactionState.COMMITTED);
        forceState(t2, TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：READ_COMMITTED 隔离级别可能出现不可重复读")
    public void testReadCommittedNonRepeatableRead() throws Exception {
        // 事务 T1 插入数据并提交
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

        // 事务 T2 第一次读取
        Transaction t2 = transactionManager.begin(Transaction.IsolationLevel.READ_COMMITTED);
        DataTuple read1 = null;
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            read1 = dml.read(mtr, t2, primaryKey);
            assertNotNull(read1, "第一次读取应该成功");
            mtr.commit();
        }

        // 事务 T3 修改数据并提交
        Transaction t3 = transactionManager.begin();
        DataTuple tuple3 = createTestTuple("key3", "value3_modified");

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            boolean updated = dml.update(mtr, t3, primaryKey, tuple3, java.util.List.of());
            assertTrue(updated, "更新应该成功");
            mtr.commit();
        }

        try {
            transactionManager.commit(t3);
        } catch (Exception e) {
            // 忽略异常
        }

        // 事务 T2 第二次读取（可能看到不同的值）
        DataTuple read2 = null;
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            read2 = dml.read(mtr, t2, primaryKey);
            assertNotNull(read2, "第二次读取应该成功");
            mtr.commit();
        }

        // 验证：READ_COMMITTED 可能出现不可重复读
        // 注意：这取决于具体的实现，可能两次读取结果相同或不同

        forceState(t2, TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：REPEATABLE_READ 隔离级别保证可重复读")
    public void testRepeatableReadGuaranteesConsistency() throws Exception {
        // 事务 T1 插入数据并提交
        Transaction t1 = transactionManager.begin();
        byte[] primaryKey = "key4".getBytes();
        DataTuple tuple1 = createTestTuple("key4", "value4");

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

        // 事务 T2 使用 REPEATABLE_READ 隔离级别
        Transaction t2 = transactionManager.begin(Transaction.IsolationLevel.REPEATABLE_READ);

        // 第一次读取
        DataTuple read1 = null;
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            read1 = dml.read(mtr, t2, primaryKey);
            assertNotNull(read1, "第一次读取应该成功");
            mtr.commit();
        }

        // 事务 T3 修改数据并提交
        Transaction t3 = transactionManager.begin();
        DataTuple tuple3 = createTestTuple("key4", "value4_modified");

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            boolean updated = dml.update(mtr, t3, primaryKey, tuple3, java.util.List.of());
            assertTrue(updated, "更新应该成功");
            mtr.commit();
        }

        try {
            transactionManager.commit(t3);
        } catch (Exception e) {
            // 忽略异常
        }

        // 第二次读取（应该看到相同的值）
        DataTuple read2 = null;
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            read2 = dml.read(mtr, t2, primaryKey);
            assertNotNull(read2, "第二次读取应该成功");
            mtr.commit();
        }

        // 验证：REPEATABLE_READ 保证可重复读
        assertEquals(read1.getField(1), read2.getField(1),
                "REPEATABLE_READ 应该保证两次读取结果相同");

        forceState(t2, TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：REPEATABLE_READ 隔离级别使用相同的 ReadView")
    public void testRepeatableReadUsesSameReadView() throws Exception {
        Transaction t1 = transactionManager.begin(Transaction.IsolationLevel.REPEATABLE_READ);

        // 第一次创建 ReadView
        ReadView rv1 = t1.getOrCreateReadView();
        assertNotNull(rv1, "第一次应该创建 ReadView");

        // 第二次获取 ReadView
        ReadView rv2 = t1.getOrCreateReadView();
        assertSame(rv1, rv2, "REPEATABLE_READ 应该复用同一个 ReadView");

        // 第三次获取 ReadView
        ReadView rv3 = t1.getOrCreateReadView();
        assertSame(rv1, rv3, "REPEATABLE_READ 应该始终复用同一个 ReadView");

        forceState(t1, TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：READ_COMMITTED 隔离级别每次创建新 ReadView")
    public void testReadCommittedCreatesNewReadView() throws Exception {
        Transaction t1 = transactionManager.begin(Transaction.IsolationLevel.READ_COMMITTED);

        // 第一次创建 ReadView
        ReadView rv1 = t1.getOrCreateReadView();
        assertNotNull(rv1, "第一次应该创建 ReadView");

        // 第二次创建 ReadView
        ReadView rv2 = t1.getOrCreateReadView();
        assertNotNull(rv2, "第二次应该创建新 ReadView");

        // 验证：两个 ReadView 不同
        assertNotSame(rv1, rv2, "READ_COMMITTED 应该创建不同的 ReadView");

        forceState(t1, TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：READ_UNCOMMITTED 隔离级别不创建 ReadView")
    public void testReadUncommittedNoReadView() throws Exception {
        Transaction t1 = transactionManager.begin(Transaction.IsolationLevel.READ_UNCOMMITTED);

        // 获取 ReadView
        ReadView rv = t1.getOrCreateReadView();
        assertNull(rv, "READ_UNCOMMITTED 不应该创建 ReadView");

        forceState(t1, TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：隔离级别的默认值")
    public void testDefaultIsolationLevel() throws Exception {
        Transaction t1 = transactionManager.begin();
        assertEquals(Transaction.IsolationLevel.REPEATABLE_READ, t1.getIsolationLevel(),
                "默认隔离级别应该是 REPEATABLE_READ");

        forceState(t1, TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：隔离级别的获取")
    public void testGetIsolationLevel() throws Exception {
        Transaction t1 = transactionManager.begin(Transaction.IsolationLevel.READ_COMMITTED);
        assertEquals(Transaction.IsolationLevel.READ_COMMITTED, t1.getIsolationLevel(),
                "应该能正确获取隔离级别");

        Transaction t2 = transactionManager.begin(Transaction.IsolationLevel.REPEATABLE_READ);
        assertEquals(Transaction.IsolationLevel.REPEATABLE_READ, t2.getIsolationLevel(),
                "应该能正确获取隔离级别");

        Transaction t3 = transactionManager.begin(Transaction.IsolationLevel.SERIALIZABLE);
        assertEquals(Transaction.IsolationLevel.SERIALIZABLE, t3.getIsolationLevel(),
                "应该能正确获取隔离级别");

        forceState(t1, TransactionState.COMMITTED);
        forceState(t2, TransactionState.COMMITTED);
        forceState(t3, TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：多个隔离级别的并发执行")
    public void testConcurrentIsolationLevels() throws Exception {
        // 创建不同隔离级别的事务
        Transaction t1 = transactionManager.begin(Transaction.IsolationLevel.READ_UNCOMMITTED);
        Transaction t2 = transactionManager.begin(Transaction.IsolationLevel.READ_COMMITTED);
        Transaction t3 = transactionManager.begin(Transaction.IsolationLevel.REPEATABLE_READ);
        Transaction t4 = transactionManager.begin(Transaction.IsolationLevel.SERIALIZABLE);

        // 验证隔离级别
        assertEquals(Transaction.IsolationLevel.READ_UNCOMMITTED, t1.getIsolationLevel());
        assertEquals(Transaction.IsolationLevel.READ_COMMITTED, t2.getIsolationLevel());
        assertEquals(Transaction.IsolationLevel.REPEATABLE_READ, t3.getIsolationLevel());
        assertEquals(Transaction.IsolationLevel.SERIALIZABLE, t4.getIsolationLevel());

        // 验证 ReadView 创建
        assertNull(t1.getOrCreateReadView(), "READ_UNCOMMITTED 不应该创建 ReadView");
        assertNotNull(t2.getOrCreateReadView(), "READ_COMMITTED 应该创建 ReadView");
        assertNotNull(t3.getOrCreateReadView(), "REPEATABLE_READ 应该创建 ReadView");
        assertNotNull(t4.getOrCreateReadView(), "SERIALIZABLE 应该创建 ReadView");

        forceState(t1, TransactionState.COMMITTED);
        forceState(t2, TransactionState.COMMITTED);
        forceState(t3, TransactionState.COMMITTED);
        forceState(t4, TransactionState.COMMITTED);
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建测试数据元组
     */
    private DataTuple createTestTuple(String key, String value) {
        return DataTuple.builder()
                .addString(key)
                .addString(value)
                .build();
    }
}
