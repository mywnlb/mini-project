package cn.zhangyis.minidb.storage.transaction.lock;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 事务锁上下文
 *
 * <p>跟踪单个事务持有的所有锁请求。提供锁释放和查询功能。</p>
 *
 * <h2>设计约束</h2>
 * <ul>
 *   <li><b>L5</b>: releaseAll 幂等 — 多次调用不会重复释放</li>
 *   <li><b>L1</b>: 锁持续到事务结束 — 只有 releaseAll 和 releaseSharedLocks 两个释放入口</li>
 *   <li><b>L11</b>: Savepoint 回滚不释放锁 — 无 savepointRelease 方法</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>不需要同步 — 一个事务只在一个线程中操作（由 Transaction 约束）。</p>
 */
public class TransactionLockContext {

    private final TransactionId trxId;
    private final Map<LockTarget, LockRequest> heldLocks = new HashMap<>();

    /** 幂等标志 (L5): 防止重复释放 */
    private boolean released = false;

    public TransactionLockContext(TransactionId trxId) {
        this.trxId = trxId;
    }

    /**
     * 添加一个已授予的锁请求
     *
     * @param request 锁请求
     */
    public void addLock(LockRequest request) {
        heldLocks.put(request.getTarget(), request);
    }

    /**
     * 释放所有锁（唯一的全量释放入口）
     *
     * <p>L5 不变量: 幂等操作，多次调用返回空列表。
     * 返回的列表包含所有之前持有的锁请求，调用方负责从 LockTable 中移除。</p>
     *
     * @return 被释放的锁请求列表，如果已释放过则返回空列表
     */
    public List<LockRequest> releaseAll() {
        if (released) {
            return Collections.emptyList();
        }
        released = true;
        List<LockRequest> result = new ArrayList<>(heldLocks.values());
        heldLocks.clear();
        return result;
    }

    /**
     * 释放所有共享锁（READ_COMMITTED 隔离级别使用）
     *
     * <p>L1/L11: RC 隔离级别下，读操作完成后提前释放 S 锁，
     * 但不影响 X 锁的持有（X 锁仍持续到事务结束）。</p>
     *
     * @return 被释放的共享锁请求列表
     */
    public List<LockRequest> releaseSharedLocks() {
        if (released) {
            return Collections.emptyList();
        }
        List<LockRequest> sharedLocks = new ArrayList<>();
        heldLocks.values().removeIf(request -> {
            if (request.getMode() == LockMode.SHARED) {
                sharedLocks.add(request);
                return true;
            }
            return false;
        });
        return sharedLocks;
    }

    /**
     * 移除指定目标的锁请求
     *
     * <p>用于 READ_COMMITTED 隔离级别下，通过 {@code LockManager.unlockRecord}
     * 提前释放特定记录的 S 锁。</p>
     *
     * @param target 锁目标
     * @return 被移除的锁请求，如果未找到返回 null
     */
    public LockRequest removeLock(LockTarget target) {
        return heldLocks.remove(target);
    }

    /**
     * 查询该事务是否已持有某目标的锁
     *
     * @param target 锁目标
     * @return 已持有的锁请求，如果未持有则返回 null
     */
    public LockRequest findLock(LockTarget target) {
        LockRequest request = heldLocks.get(target);
        return (request != null && request.isGranted()) ? request : null;
    }

    /**
     * 获取事务 ID
     *
     * @return 事务 ID
     */
    public TransactionId getTrxId() {
        return trxId;
    }

    /**
     * 获取持有的锁数量
     *
     * @return 锁数量
     */
    public int getLockCount() {
        return heldLocks.size();
    }

    /**
     * 是否已释放
     *
     * @return true 如果 releaseAll 已被调用
     */
    public boolean isReleased() {
        return released;
    }

    @Override
    public String toString() {
        return String.format("TransactionLockContext(trx=%s, locks=%d, released=%s)",
                trxId, heldLocks.size(), released);
    }
}
