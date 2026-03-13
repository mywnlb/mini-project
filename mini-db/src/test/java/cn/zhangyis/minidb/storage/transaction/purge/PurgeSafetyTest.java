package cn.zhangyis.minidb.storage.transaction.purge;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.transaction.core.Transaction;
import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.core.TransactionManager;
import cn.zhangyis.minidb.storage.transaction.core.TransactionState;
import cn.zhangyis.minidb.storage.transaction.mvcc.ReadView;
import cn.zhangyis.minidb.storage.transaction.undo.UndoLogManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;
import static cn.zhangyis.minidb.testutil.TransactionTestSupport.forceState;

/**
 * Purge 安全门控测试
 *
 * <p>测试 Purge 系统的安全性：
 * <ul>
 *   <li>ReadView 注册和注销</li>
 *   <li>Purge 边界计算</li>
 *   <li>活跃 ReadView 保护</li>
 *   <li>事务提交时的 ReadView 清理</li>
 * </ul>
 * </p>
 *
 * @author MiniDB
 * @version 1.0
 */
@DisplayName("Purge 安全门控测试")
public class PurgeSafetyTest {

    private BufferPool bufferPool;
    private TransactionManager transactionManager;
    private UndoLogManager undoLogManager;
    private PurgeCoordinator purgeCoordinator;

    @BeforeEach
    public void setUp() {
        // TODO: 初始化测试环境
        // 1. 创建 BufferPool
        // 2. 创建 UndoLogManager
        // 3. 创建 TransactionManager
        // 4. 获取 PurgeCoordinator
    }

    @Test
    @DisplayName("测试：ReadView 自动注册到 Purge 协调器")
    public void testReadViewAutoRegistration() throws Exception {
        // 事务 T1 创建 ReadView
        Transaction t1 = transactionManager.begin();
        ReadView readView = t1.getOrCreateReadView();

        assertNotNull(readView, "ReadView 应该被创建");

        // 验证 ReadView 已注册到 Purge 协调器
        PurgeCoordinator coordinator = transactionManager.getPurgeCoordinator();
        assertEquals(1, coordinator.getActiveReadViewCount(), "应该有 1 个活跃 ReadView");

        forceState(t1, TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：事务提交时 ReadView 自动注销")
    public void testReadViewAutoUnregistration() throws Exception {
        // 事务 T1 创建 ReadView
        Transaction t1 = transactionManager.begin();
        ReadView readView = t1.getOrCreateReadView();

        PurgeCoordinator coordinator = transactionManager.getPurgeCoordinator();
        assertEquals(1, coordinator.getActiveReadViewCount(), "应该有 1 个活跃 ReadView");

        // 提交事务
        try {
            transactionManager.commit(t1);
        } catch (Exception e) {
            // 忽略异常
        }

        // 验证 ReadView 已注销
        assertEquals(0, coordinator.getActiveReadViewCount(), "应该没有活跃 ReadView");
    }

    @Test
    @DisplayName("测试：Purge 边界计算（无活跃 ReadView）")
    public void testPurgeLimitWithoutActiveReadViews() throws Exception {
        // 创建并提交事务 T1
        Transaction t1 = transactionManager.begin();
        try {
            transactionManager.commit(t1);
        } catch (Exception e) {
            // 忽略异常
        }

        // 创建并提交事务 T2
        Transaction t2 = transactionManager.begin();
        try {
            transactionManager.commit(t2);
        } catch (Exception e) {
            // 忽略异常
        }

        // 获取 Purge 边界
        PurgeCoordinator coordinator = transactionManager.getPurgeCoordinator();
        TransactionId purgeLimit = coordinator.getPurgeLimit();

        // 验证：Purge 边界应该是当前最大 TRX_ID
        assertNotNull(purgeLimit, "Purge 边界不应该为 null");
        assertTrue(purgeLimit.getValue() > 0, "Purge 边界应该大于 0");
    }

    @Test
    @DisplayName("测试：Purge 边界计算（有活跃 ReadView）")
    public void testPurgeLimitWithActiveReadViews() throws Exception {
        // 事务 T1 创建 ReadView
        Transaction t1 = transactionManager.begin();
        ReadView readView1 = t1.getOrCreateReadView();
        long t1TrxId = t1.getId().getValue();

        // 事务 T2 创建 ReadView
        Transaction t2 = transactionManager.begin();
        ReadView readView2 = t2.getOrCreateReadView();
        long t2TrxId = t2.getId().getValue();

        // 获取 Purge 边界
        PurgeCoordinator coordinator = transactionManager.getPurgeCoordinator();
        TransactionId purgeLimit = coordinator.getPurgeLimit();

        // 验证：Purge 边界应该是最小活跃 ReadView 的 up_limit_id
        assertNotNull(purgeLimit, "Purge 边界不应该为 null");
        assertTrue(purgeLimit.getValue() <= Math.min(t1TrxId, t2TrxId),
                "Purge 边界应该小于等于最小活跃 TRX_ID");

        // 清理
        forceState(t1, TransactionState.COMMITTED);
        forceState(t2, TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：多个 ReadView 的 Purge 边界")
    public void testPurgeLimitWithMultipleReadViews() throws Exception {
        // 创建 3 个事务，每个都有 ReadView
        Transaction t1 = transactionManager.begin();
        ReadView rv1 = t1.getOrCreateReadView();

        Transaction t2 = transactionManager.begin();
        ReadView rv2 = t2.getOrCreateReadView();

        Transaction t3 = transactionManager.begin();
        ReadView rv3 = t3.getOrCreateReadView();

        PurgeCoordinator coordinator = transactionManager.getPurgeCoordinator();

        // 验证：有 3 个活跃 ReadView
        assertEquals(3, coordinator.getActiveReadViewCount(), "应该有 3 个活跃 ReadView");

        // 提交 T1
        try {
            transactionManager.commit(t1);
        } catch (Exception e) {
            // 忽略异常
        }

        // 验证：还有 2 个活跃 ReadView
        assertEquals(2, coordinator.getActiveReadViewCount(), "应该有 2 个活跃 ReadView");

        // 提交 T2
        try {
            transactionManager.commit(t2);
        } catch (Exception e) {
            // 忽略异常
        }

        // 验证：还有 1 个活跃 ReadView
        assertEquals(1, coordinator.getActiveReadViewCount(), "应该有 1 个活跃 ReadView");

        // 提交 T3
        try {
            transactionManager.commit(t3);
        } catch (Exception e) {
            // 忽略异常
        }

        // 验证：没有活跃 ReadView
        assertEquals(0, coordinator.getActiveReadViewCount(), "应该没有活跃 ReadView");
    }

    @Test
    @DisplayName("测试：canPurge() 方法")
    public void testCanPurgeMethod() throws Exception {
        // 事务 T1 创建 ReadView
        Transaction t1 = transactionManager.begin();
        ReadView readView = t1.getOrCreateReadView();

        PurgeCoordinator coordinator = transactionManager.getPurgeCoordinator();
        TransactionId purgeLimit = coordinator.getPurgeLimit();

        // 验证：小于 Purge 边界的 TRX_ID 可以被清理
        TransactionId smallerId = new TransactionId(Math.max(0, purgeLimit.getValue() - 1));
        assertTrue(coordinator.canPurge(smallerId), "小于 Purge 边界的 TRX_ID 应该可以被清理");

        // 验证：大于等于 Purge 边界的 TRX_ID 不能被清理
        TransactionId largerId = new TransactionId(purgeLimit.getValue() + 1);
        assertFalse(coordinator.canPurge(largerId), "大于等于 Purge 边界的 TRX_ID 不应该被清理");

        forceState(t1, TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：REPEATABLE_READ 隔离级别的 ReadView 缓存")
    public void testRepeatableReadViewCaching() throws Exception {
        // 事务 T1 使用 REPEATABLE_READ 隔离级别
        Transaction t1 = transactionManager.begin(Transaction.IsolationLevel.REPEATABLE_READ);

        // 第一次创建 ReadView
        ReadView rv1 = t1.getOrCreateReadView();
        assertNotNull(rv1, "第一次应该创建 ReadView");

        // 第二次获取 ReadView
        ReadView rv2 = t1.getOrCreateReadView();
        assertSame(rv1, rv2, "REPEATABLE_READ 应该复用同一个 ReadView");

        // 验证：只有 1 个活跃 ReadView
        PurgeCoordinator coordinator = transactionManager.getPurgeCoordinator();
        assertEquals(1, coordinator.getActiveReadViewCount(), "应该只有 1 个活跃 ReadView");

        forceState(t1, TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：READ_COMMITTED 隔离级别的 ReadView 创建")
    public void testReadCommittedReadViewCreation() throws Exception {
        // 事务 T1 使用 READ_COMMITTED 隔离级别
        Transaction t1 = transactionManager.begin(Transaction.IsolationLevel.READ_COMMITTED);

        // 第一次创建 ReadView
        ReadView rv1 = t1.getOrCreateReadView();
        assertNotNull(rv1, "第一次应该创建 ReadView");

        // 第二次创建 ReadView
        ReadView rv2 = t1.getOrCreateReadView();
        assertNotSame(rv1, rv2, "READ_COMMITTED 应该创建新的 ReadView");

        // 验证：有 2 个活跃 ReadView
        PurgeCoordinator coordinator = transactionManager.getPurgeCoordinator();
        assertEquals(2, coordinator.getActiveReadViewCount(), "应该有 2 个活跃 ReadView");

        forceState(t1, TransactionState.COMMITTED);
    }

    @Test
    @DisplayName("测试：事务回滚时的 ReadView 清理")
    public void testReadViewCleanupOnRollback() throws Exception {
        // 事务 T1 创建 ReadView
        Transaction t1 = transactionManager.begin();
        ReadView readView = t1.getOrCreateReadView();

        PurgeCoordinator coordinator = transactionManager.getPurgeCoordinator();
        assertEquals(1, coordinator.getActiveReadViewCount(), "应该有 1 个活跃 ReadView");

        // 回滚事务
        try {
            transactionManager.rollback(t1);
        } catch (Exception e) {
            // 忽略异常
        }

        // 验证：ReadView 已清理
        assertEquals(0, coordinator.getActiveReadViewCount(), "应该没有活跃 ReadView");
    }

    @Test
    @DisplayName("测试：Purge 协调器缓存失效")
    public void testPurgeCoordinatorCacheInvalidation() throws Exception {
        PurgeCoordinator coordinator = transactionManager.getPurgeCoordinator();

        // 获取初始 Purge 边界
        TransactionId limit1 = coordinator.getPurgeLimit();

        // 创建新事务
        Transaction t1 = transactionManager.begin();
        ReadView rv1 = t1.getOrCreateReadView();

        // 获取新的 Purge 边界（应该不同）
        TransactionId limit2 = coordinator.getPurgeLimit();

        // 验证：Purge 边界应该改变
        assertNotEquals(limit1.getValue(), limit2.getValue(),
                "添加新 ReadView 后 Purge 边界应该改变");

        forceState(t1, TransactionState.COMMITTED);
    }
}
