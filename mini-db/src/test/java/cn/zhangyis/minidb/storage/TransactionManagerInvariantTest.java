package cn.zhangyis.minidb.storage;

import cn.zhangyis.minidb.storage.transaction.core.Transaction;
import cn.zhangyis.minidb.storage.transaction.core.TransactionManager;
import cn.zhangyis.minidb.storage.transaction.lock.LockManagerImpl;
import cn.zhangyis.minidb.storage.transaction.lock.LockMode;
import cn.zhangyis.minidb.storage.transaction.mvcc.ReadView;
import cn.zhangyis.minidb.storage.transaction.mvcc.VisibilityChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransactionManagerInvariantTest extends BaseStorageTest {

    @Test
    void trxIdsAndReadViews_areMonotonicAndSnapshotConsistent() throws Exception {
        TransactionManager txnManager = bootTransactionSubsystem();

        Transaction trx1 = txnManager.begin();
        Transaction trx2 = txnManager.begin();
        Transaction trx3 = txnManager.begin();

        ReadView readView = trx1.getOrCreateReadView();
        Transaction trx4 = txnManager.begin();

        assertTrue(trx1.getId().getValue() < trx2.getId().getValue());
        assertTrue(trx2.getId().getValue() < trx3.getId().getValue());
        assertEquals(trx4.getId(), readView.getLowLimitId(),
                "ReadView.lowLimitId 必须冻结为创建时的下一个 TRX_ID");
        assertEquals(java.util.List.of(trx2.getId(), trx3.getId()), readView.getActiveTrxIds());

        assertTrue(VisibilityChecker.isVisible(trx1.getId(), readView), "自己的修改必须可见");
        assertFalse(VisibilityChecker.isVisible(trx2.getId(), readView), "活跃事务必须不可见");
        assertFalse(VisibilityChecker.isVisible(trx4.getId(), readView), "快照后启动的事务必须不可见");
    }

    @Test
    void commit_releasesLocksAndRemovesTransactionFromActiveSet() throws Exception {
        TransactionManager txnManager = bootTransactionSubsystem();
        LockManagerImpl lockManager = new LockManagerImpl(8, 200, false, 50);
        txnManager.setLockManager(lockManager);

        Transaction trx1 = txnManager.begin();
        lockManager.lockTable(trx1, 42, LockMode.EXCLUSIVE);

        txnManager.commit(trx1);

        assertTrue(trx1.isCommitted());
        assertEquals(0, txnManager.getActiveTransactionCount());

        Transaction trx2 = txnManager.begin();
        assertDoesNotThrow(() -> lockManager.lockTable(trx2, 42, LockMode.EXCLUSIVE),
                "commit 后必须释放事务持有的所有锁");
    }

    @Test
    void rollback_clearsCachedReadViewAndActiveState() throws Exception {
        TransactionManager txnManager = bootTransactionSubsystem();

        Transaction trx = txnManager.begin();
        assertNotNull(trx.getOrCreateReadView());
        assertEquals(1, txnManager.getPurgeCoordinator().getActiveReadViewCount());

        txnManager.rollback(trx);

        assertTrue(trx.isRolledBack());
        assertNull(trx.getCachedReadView());
        assertEquals(0, txnManager.getActiveTransactionCount());
        assertEquals(0, txnManager.getPurgeCoordinator().getActiveReadViewCount());
    }
}
