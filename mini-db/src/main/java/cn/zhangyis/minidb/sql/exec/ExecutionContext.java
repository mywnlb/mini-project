package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.transaction.core.Transaction;
import cn.zhangyis.minidb.storage.transaction.core.TransactionManager;

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
    private Transaction currentTxn;

    public ExecutionContext(TransactionManager txnManager) {
        this.txnManager = txnManager;
    }

    public TransactionManager txnManager() { return txnManager; }

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
            txnManager.commit(currentTxn);
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
            txnManager.rollback(currentTxn);
        } catch (MiniDbException e) {
            throw new RuntimeException("Failed to rollback transaction", e);
        }
        currentTxn = null;
    }
}
