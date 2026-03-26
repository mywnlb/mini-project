package cn.zhangyis.minidb.storage;

import cn.zhangyis.minidb.storage.transaction.core.Transaction;
import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.lock.DeadlockException;
import cn.zhangyis.minidb.storage.transaction.lock.LockManagerImpl;
import cn.zhangyis.minidb.storage.transaction.lock.LockMode;
import cn.zhangyis.minidb.storage.transaction.lock.LockWaitTimeoutException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LockManagerInvariantTest {

    @Test
    void unlockAll_isIdempotentAndAllowsRelock() throws Exception {
        LockManagerImpl lockManager = new LockManagerImpl(8, 200, false, 50);
        Transaction trx1 = new Transaction(new TransactionId(1));
        Transaction trx2 = new Transaction(new TransactionId(2));

        try {
            lockManager.lockTable(trx1, 7, LockMode.EXCLUSIVE);

            assertDoesNotThrow(() -> lockManager.unlockAll(trx1));
            assertDoesNotThrow(() -> lockManager.unlockAll(trx1));
            assertDoesNotThrow(() -> lockManager.lockTable(trx2, 7, LockMode.EXCLUSIVE));
        } finally {
            lockManager.unlockAll(trx1);
            lockManager.unlockAll(trx2);
            lockManager.shutdown();
        }
    }

    @Test
    void conflictingRecordLocks_timeoutDeterministically() throws Exception {
        LockManagerImpl lockManager = new LockManagerImpl(8, 100, false, 50);
        Transaction trx1 = new Transaction(new TransactionId(1));
        Transaction trx2 = new Transaction(new TransactionId(2));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            lockManager.lockRecord(trx1, 0, 11, 3, LockMode.EXCLUSIVE);

            Future<Throwable> blocked = executor.submit(
                    () -> captureFailure(() -> lockManager.lockRecord(trx2, 0, 11, 3, LockMode.EXCLUSIVE)));

            Throwable failure = blocked.get(1, TimeUnit.SECONDS);
            assertInstanceOf(LockWaitTimeoutException.class, failure);
        } finally {
            lockManager.unlockAll(trx1);
            lockManager.unlockAll(trx2);
            lockManager.shutdown();
        }
    }

    @Test
    void deadlockDetection_breaksWaitCycle() throws Exception {
        LockManagerImpl lockManager = new LockManagerImpl(8, 1_000, true, 10);
        Transaction trx1 = new Transaction(new TransactionId(1));
        Transaction trx2 = new Transaction(new TransactionId(2));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            lockManager.lockTable(trx1, 11, LockMode.EXCLUSIVE);
            lockManager.lockTable(trx2, 22, LockMode.EXCLUSIVE);

            Future<Throwable> wait1 = executor.submit(
                    () -> captureFailure(() -> lockManager.lockTable(trx1, 22, LockMode.EXCLUSIVE)));
            Future<Throwable> wait2 = executor.submit(
                    () -> captureFailure(() -> lockManager.lockTable(trx2, 11, LockMode.EXCLUSIVE)));

            Thread.sleep(200);
            lockManager.unlockAll(trx1);
            lockManager.unlockAll(trx2);

            Throwable failure1 = wait1.get(1, TimeUnit.SECONDS);
            Throwable failure2 = wait2.get(1, TimeUnit.SECONDS);

            assertTrue(failure1 instanceof DeadlockException || failure2 instanceof DeadlockException,
                    "启用 deadlock detector 后至少一个等待者必须被终止");
        } finally {
            lockManager.unlockAll(trx1);
            lockManager.unlockAll(trx2);
            lockManager.shutdown();
        }
    }

    private Throwable captureFailure(ThrowingAction action) {
        try {
            action.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
