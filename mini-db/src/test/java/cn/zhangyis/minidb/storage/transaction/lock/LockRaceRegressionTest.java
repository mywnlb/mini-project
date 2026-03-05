package cn.zhangyis.minidb.storage.transaction.lock;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Lock Race Regression Tests")
class LockRaceRegressionTest {

    @Test
    @DisplayName("LockRequest state transition should be CAS-protected")
    void lockRequestStateTransitionShouldBeCasProtected() {
        LockTarget target = LockTarget.forRecord(1, 1, 1);
        TransactionId trxId = new TransactionId(1);
        LockRequest request = new LockRequest(trxId, target, LockMode.EXCLUSIVE, LockType.RECORD);

        assertTrue(request.markGranted());
        assertFalse(request.markAborted());
        assertTrue(request.isGranted());
        assertFalse(request.isAborted());
    }

    @Test
    @DisplayName("grantWaiters should skip aborted waiter and avoid ghost lock")
    void grantWaitersShouldSkipAbortedWaiter() {
        LockTarget target = LockTarget.forRecord(2, 2, 2);
        TransactionId blockerId = new TransactionId(10);
        TransactionId waiterId = new TransactionId(20);

        LockRequestQueue queue = new LockRequestQueue();
        LockRequest blocker = new LockRequest(blockerId, target, LockMode.EXCLUSIVE, LockType.RECORD);
        LockRequest waiter = new LockRequest(waiterId, target, LockMode.EXCLUSIVE, LockType.RECORD);

        assertEquals(LockResult.GRANTED, queue.tryGrant(blocker));
        assertEquals(LockResult.WAIT, queue.tryGrant(waiter));

        assertTrue(waiter.markAborted());
        queue.release(blockerId);

        assertTrue(queue.getGrantedList().isEmpty());
        assertTrue(queue.getWaitingList().isEmpty());
        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("cancelWait should remove stale granted entry")
    void cancelWaitShouldRemoveStaleGrantedEntry() {
        LockTarget target = LockTarget.forRecord(3, 3, 3);
        TransactionId trxId = new TransactionId(30);

        LockRequestQueue queue = new LockRequestQueue();
        LockRequest request = new LockRequest(trxId, target, LockMode.SHARED, LockType.RECORD);
        assertEquals(LockResult.GRANTED, queue.tryGrant(request));

        queue.cancelWait(trxId);

        assertTrue(queue.getGrantedList().isEmpty());
        assertTrue(queue.getWaitingList().isEmpty());
        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("waitForLock timeout path should recheck granted status")
    void waitForLockTimeoutPathShouldRecheckGrantedStatus() throws Exception {
        LockManagerImpl manager = new LockManagerImpl(64, 0L, false, 1000L);
        LockTarget target = LockTarget.forRecord(4, 4, 4);
        TransactionId trxId = new TransactionId(40);
        LockRequest request = new LockRequest(trxId, target, LockMode.SHARED, LockType.RECORD);
        assertTrue(request.markGranted());

        Method waitForLock = LockManagerImpl.class.getDeclaredMethod(
                "waitForLock", LockRequest.class, LockTarget.class);
        waitForLock.setAccessible(true);

        assertDoesNotThrow(() -> waitForLock.invoke(manager, request, target));
    }
}
