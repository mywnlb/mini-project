package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.transaction.core.Transaction;
import cn.zhangyis.minidb.storage.transaction.core.TransactionManager;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 执行上下文：持有当前事务和事务管理器引用。
 *
 * <p>事务生命周期：
 * <ul>
 *   <li>BEGIN → 创建新事务，存入 currentTxn</li>
 *   <li>COMMIT/ROLLBACK → 结束事务，清空 currentTxn</li>
 *   <li>无事务时执行 DML → auto-commit 语义（每条语句独立事务）</li>
 * </ul>
 */
public class ExecutionContext {

    private final TransactionManager txnManager;
    private final List<TransactionLifecycleParticipant> participants = new ArrayList<>();
    private Transaction currentTxn;
    private int parallelism = 1;
    private QueryThreadPool queryThreadPool;  // 新增：查询级共享线程池

    public ExecutionContext(TransactionManager txnManager) {
        this.txnManager = txnManager;
    }

    public TransactionManager txnManager() { return txnManager; }

    public int parallelism() { return parallelism; }
    public void setParallelism(int p) {
        this.parallelism = Math.max(1, p);
        if (queryThreadPool != null) {
            queryThreadPool.close();
        }
        queryThreadPool = new QueryThreadPool(this.parallelism);
    }

    public QueryThreadPool queryThreadPool() {
        if (queryThreadPool == null) {
            queryThreadPool = new QueryThreadPool(parallelism);
        }
        return queryThreadPool;
    }

    public Transaction currentTxn() { return currentTxn; }

    public boolean inTransaction() { return currentTxn != null; }

    public Transaction begin() {
        if (currentTxn != null) {
            throw new IllegalStateException("Transaction already active (trxId=" + currentTxn.getId() + ")");
        }
        try {
            currentTxn = txnManager.begin();
        } catch (MiniDbException e) {
            throw new RuntimeException("Failed to begin transaction", e);
        }
        return currentTxn;
    }

    public void commit() {
        if (currentTxn == null) {
            throw new IllegalStateException("No active transaction to commit");
        }
        try {
            notifyBeforeCommit(currentTxn);
            txnManager.commit(currentTxn);
            notifyAfterCommit(currentTxn);
        } catch (MiniDbException e) {
            throw new RuntimeException("Failed to commit transaction", e);
        }
        currentTxn = null;
    }

    public void rollback() {
        if (currentTxn == null) {
            throw new IllegalStateException("No active transaction to rollback");
        }
        try {
            notifyBeforeRollback(currentTxn);
            txnManager.rollback(currentTxn);
        } catch (MiniDbException e) {
            throw new RuntimeException("Failed to rollback transaction", e);
        }
        currentTxn = null;
    }

    // ==================== DML 事务解析 ====================

    /**
     * 为 DML 操作解析事务。
     *
     * <p>显式事务模式（inTransaction == true）：返回 currentTxn。
     * auto-commit 模式（inTransaction == false）：创建新事务。
     * 调用方负责在 auto-commit 场景调用 autoCommitIfNeeded / autoRollbackIfNeeded。</p>
     */
    public Transaction resolveTransactionForDml() {
        if (currentTxn != null) {
            return currentTxn;
        }
        try {
            return txnManager.begin();
        } catch (MiniDbException e) {
            throw new RuntimeException("Failed to begin auto-commit transaction", e);
        }
    }

    /**
     * auto-commit 模式下提交事务。
     * 显式事务模式下（txn == currentTxn）为 no-op。
     */
    public void autoCommitIfNeeded(Transaction txn) {
        if (txn == currentTxn) {
            return;
        }
        try {
            notifyBeforeCommit(txn);
            txnManager.commit(txn);
            notifyAfterCommit(txn);
        } catch (MiniDbException e) {
            throw new RuntimeException("Failed to auto-commit transaction", e);
        }
    }

    /**
     * auto-commit 模式下回滚事务。
     * 显式事务模式下（txn == currentTxn）为 no-op，由用户决定 ROLLBACK。
     */
    public void autoRollbackIfNeeded(Transaction txn) {
        if (txn == currentTxn) {
            return;
        }
        try {
            notifyBeforeRollback(txn);
            txnManager.rollback(txn);
        } catch (MiniDbException e) {
            // rollback 失败记录但不吞掉，上层需要知道
            throw new RuntimeException("Failed to auto-rollback transaction", e);
        }
    }

    public void registerParticipant(TransactionLifecycleParticipant participant) {
        if (participant != null && !participants.contains(participant)) {
            participants.add(participant);
        }
    }

    private void notifyBeforeCommit(Transaction txn) {
        for (TransactionLifecycleParticipant participant : participants) {
            participant.beforeCommit(txn);
        }
    }

    private void notifyAfterCommit(Transaction txn) {
        for (TransactionLifecycleParticipant participant : participants) {
            participant.afterCommit(txn);
        }
    }

    private void notifyBeforeRollback(Transaction txn) {
        for (TransactionLifecycleParticipant participant : participants) {
            participant.beforeRollback(txn);
        }
    }
}
