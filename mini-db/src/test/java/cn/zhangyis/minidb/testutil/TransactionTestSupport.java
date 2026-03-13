package cn.zhangyis.minidb.testutil;

import cn.zhangyis.minidb.storage.transaction.core.Transaction;
import cn.zhangyis.minidb.storage.transaction.core.TransactionState;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试侧事务辅助工具。
 *
 * <p>仅用于兼容仍在直接操纵事务终态的旧测试。</p>
 */
public final class TransactionTestSupport {

    private static final Field STATE_FIELD = lookupStateField();
    private static final Field COMMIT_TIME_FIELD = lookupCommitTimeField();

    private TransactionTestSupport() {
    }

    private static Field lookupStateField() {
        try {
            Field field = Transaction.class.getDeclaredField("state");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Field lookupCommitTimeField() {
        try {
            Field field = Transaction.class.getDeclaredField("commitTime");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static void forceState(Transaction transaction, TransactionState state) {
        try {
            AtomicReference<TransactionState> ref =
                    (AtomicReference<TransactionState>) STATE_FIELD.get(transaction);
            ref.set(state);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Failed to update transaction state for test", e);
        }
    }

    public static void forceCommitTime(Transaction transaction, long commitTime) {
        try {
            COMMIT_TIME_FIELD.setLong(transaction, commitTime);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Failed to update transaction commit time for test", e);
        }
    }
}
