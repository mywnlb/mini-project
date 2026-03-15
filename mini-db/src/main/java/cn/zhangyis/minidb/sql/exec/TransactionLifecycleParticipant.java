package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.storage.transaction.core.Transaction;

/**
 * SQL 事务生命周期参与者。
 *
 * <p>用于在 SQL 层显式事务和 auto-commit 事务的提交/回滚边界上
 * 同步 flush 或 discard 会话内状态。</p>
 */
public interface TransactionLifecycleParticipant {

    /**
     * 在事务提交到底层 TransactionManager 之前调用。
     */
    void beforeCommit(Transaction txn);

    /**
     * 在事务成功提交后调用。
     */
    void afterCommit(Transaction txn);

    /**
     * 在事务回滚前调用，用于丢弃会话内暂存状态。
     */
    void beforeRollback(Transaction txn);
}
