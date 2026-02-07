package cn.zhangyis.minidb.storage.transaction.dml;

import cn.zhangyis.minidb.storage.btree.BTree;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.record.logical.DataTuple;
import cn.zhangyis.minidb.storage.record.schema.RecordSchema;
import cn.zhangyis.minidb.storage.record.physical.SystemLayout;
import cn.zhangyis.minidb.storage.transaction.core.Transaction;
import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.core.TransactionManager;
import cn.zhangyis.minidb.storage.transaction.mvcc.ReadView;
import cn.zhangyis.minidb.storage.transaction.undo.UndoLogManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TransactionalDml 读路径测试
 *
 * <p>测试 MVCC 支持的读操作：
 * <ul>
 *   <li>单行读取</li>
 *   <li>范围扫描</li>
 *   <li>可见性判断</li>
 *   <li>版本链遍历</li>
 * </ul>
 * </p>
 *
 * @author MiniDB
 * @version 1.0
 */
@DisplayName("TransactionalDml 读路径测试")
public class TransactionalDmlReadTest {

    private BufferPool bufferPool;
    private BTree btree;
    private UndoLogManager undoLogManager;
    private TransactionManager transactionManager;
    private TransactionalDml dml;
    private RecordSchema schema;
    private SystemLayout layout;

    @BeforeEach
    public void setUp() {
        // TODO: 初始化测试环境
        // 1. 创建 BufferPool
        // 2. 创建 BTree
        // 3. 创建 UndoLogManager
        // 4. 创建 TransactionManager
        // 5. 创建 TransactionalDml
        // 6. 创建 RecordSchema 和 SystemLayout
    }

    @Test
    @DisplayName("测试：事务可以读取自己的修改")
    public void testReadOwnModification() {
        // 事务 T1 插入记录
        Transaction t1 = transactionManager.begin();
        DataTuple tuple = createTestTuple("key1", "value1");
        byte[] primaryKey = "key1".getBytes();

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            boolean inserted = dml.insert(mtr, t1, tuple, primaryKey);
            assertTrue(inserted, "插入应该成功");
            mtr.commit();
        }

        // 事务 T1 读取记录
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            DataTuple read = dml.read(mtr, t1, primaryKey);
            assertNotNull(read, "T1 应该能读到自己插入的记录");
            assertEquals("value1", read.getColumnValue("col1"), "读取的值应该正确");
            mtr.commit();
        }

        t1.setState(Transaction.TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：事务不能读取未提交的修改")
    public void testCannotReadUncommittedModification() {
        // 事务 T1 插入记录（未提交）
        Transaction t1 = transactionManager.begin();
        DataTuple tuple = createTestTuple("key2", "value2");
        byte[] primaryKey = "key2".getBytes();

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            boolean inserted = dml.insert(mtr, t1, tuple, primaryKey);
            assertTrue(inserted, "插入应该成功");
            mtr.commit();
        }

        // 事务 T2 尝试读取（T1 未提交）
        Transaction t2 = transactionManager.begin();
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            DataTuple read = dml.read(mtr, t2, primaryKey);
            assertNull(read, "T2 不应该能读到 T1 未提交的记录");
            mtr.commit();
        }

        t1.setState(Transaction.TransactionState.COMMITTED);
        t2.setState(Transaction.TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：事务可以读取已提交的修改")
    public void testReadCommittedModification() {
        // 事务 T1 插入记录并提交
        Transaction t1 = transactionManager.begin();
        DataTuple tuple = createTestTuple("key3", "value3");
        byte[] primaryKey = "key3".getBytes();

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            boolean inserted = dml.insert(mtr, t1, tuple, primaryKey);
            assertTrue(inserted, "插入应该成功");
            mtr.commit();
        }

        t1.setState(Transaction.TransactionState.COMMITTED);

        // 事务 T2 读取记录
        Transaction t2 = transactionManager.begin();
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            DataTuple read = dml.read(mtr, t2, primaryKey);
            assertNotNull(read, "T2 应该能读到 T1 已提交的记录");
            assertEquals("value3", read.getColumnValue("col1"), "读取的值应该正确");
            mtr.commit();
        }

        t2.setState(Transaction.TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：REPEATABLE_READ 隔离级别")
    public void testRepeatableRead() {
        // 事务 T1 开始（创建 ReadView）
        Transaction t1 = transactionManager.begin(Transaction.IsolationLevel.REPEATABLE_READ);
        ReadView readView1 = t1.getOrCreateReadView();
        assertNotNull(readView1, "REPEATABLE_READ 应该创建 ReadView");

        // 事务 T2 插入记录并提交
        Transaction t2 = transactionManager.begin();
        DataTuple tuple = createTestTuple("key4", "value4");
        byte[] primaryKey = "key4".getBytes();

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            boolean inserted = dml.insert(mtr, t2, tuple, primaryKey);
            assertTrue(inserted, "插入应该成功");
            mtr.commit();
        }

        t2.setState(Transaction.TransactionState.COMMITTED);

        // 事务 T1 读取（第一次）
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            DataTuple read1 = dml.read(mtr, t1, primaryKey);
            assertNull(read1, "T1 第一次读取不应该看到 T2 的记录");
            mtr.commit();
        }

        // 事务 T3 插入记录并提交
        Transaction t3 = transactionManager.begin();
        DataTuple tuple3 = createTestTuple("key5", "value5");
        byte[] primaryKey3 = "key5".getBytes();

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            boolean inserted = dml.insert(mtr, t3, tuple3, primaryKey3);
            assertTrue(inserted, "插入应该成功");
            mtr.commit();
        }

        t3.setState(Transaction.TransactionState.COMMITTED);

        // 事务 T1 读取（第二次）
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            DataTuple read2 = dml.read(mtr, t1, primaryKey3);
            assertNull(read2, "T1 第二次读取也不应该看到 T3 的记录（快照隔离）");
            mtr.commit();
        }

        // 验证 T1 使用的是同一个 ReadView
        ReadView readView2 = t1.getCachedReadView();
        assertSame(readView1, readView2, "REPEATABLE_READ 应该复用同一个 ReadView");

        t1.setState(Transaction.TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：范围扫描的可见性过滤")
    public void testRangeScanVisibility() {
        // 插入多条记录
        Transaction t0 = transactionManager.begin();
        byte[][] keys = {"k1".getBytes(), "k2".getBytes(), "k3".getBytes(), "k4".getBytes(), "k5".getBytes()};

        for (int i = 0; i < keys.length; i++) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                DataTuple tuple = createTestTuple("k" + (i + 1), "v" + (i + 1));
                boolean inserted = dml.insert(mtr, t0, tuple, keys[i]);
                assertTrue(inserted, "插入应该成功");
                mtr.commit();
            }
        }

        t0.setState(Transaction.TransactionState.COMMITTED);

        // 事务 T1 删除部分记录
        Transaction t1 = transactionManager.begin();
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            boolean deleted = dml.delete(mtr, t1, keys[1]); // 删除 k2
            assertTrue(deleted, "删除应该成功");
            mtr.commit();
        }

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            boolean deleted = dml.delete(mtr, t1, keys[3]); // 删除 k4
            assertTrue(deleted, "删除应该成功");
            mtr.commit();
        }

        t1.setState(Transaction.TransactionState.COMMITTED);

        // 事务 T2 范围扫描
        Transaction t2 = transactionManager.begin();
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // TODO: 实现范围扫描测试
            // 验证：T2 只看到 k1, k3, k5（k2, k4 被删除）
            mtr.commit();
        }

        t2.setState(Transaction.TransactionState.COMMITTED);
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
