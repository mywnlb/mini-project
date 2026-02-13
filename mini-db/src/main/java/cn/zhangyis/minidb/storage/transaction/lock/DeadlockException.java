package cn.zhangyis.minidb.storage.transaction.lock;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;

/**
 * 死锁异常
 *
 * <p>当检测到死锁时，选定的牺牲事务会收到此异常。</p>
 *
 * <p>错误码: 401001 (模块40 Transaction System, 类型10, 序号01)</p>
 */
public class DeadlockException extends LockException {

    private static final int ERROR_CODE = 401001;

    private final TransactionId victimTrxId;

    /**
     * 创建死锁异常
     *
     * @param victimTrxId 被选为牺牲者的事务 ID
     */
    public DeadlockException(TransactionId victimTrxId) {
        super(ERROR_CODE, String.format("Deadlock detected, victim transaction: %s", victimTrxId));
        this.victimTrxId = victimTrxId;
    }

    /**
     * 获取牺牲事务 ID
     *
     * @return 被选为牺牲者的事务 ID
     */
    public TransactionId getVictimTrxId() {
        return victimTrxId;
    }
}
