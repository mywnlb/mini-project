package cn.zhangyis.minidb.storage.transaction.lock;

import cn.zhangyis.minidb.storage.transaction.core.Transaction;

/**
 * Lock Manager 对外接口
 *
 * <p>提供事务级逻辑锁的获取和释放。与 MVCC ReadView 配合，
 * 共同实现完整的事务隔离（READ_COMMITTED / REPEATABLE_READ 等）。</p>
 *
 * <h2>锁模式</h2>
 * <ul>
 *   <li>{@link LockMode#SHARED} (S) — 当前读（SELECT ... LOCK IN SHARE MODE）</li>
 *   <li>{@link LockMode#EXCLUSIVE} (X) — 写操作（INSERT/UPDATE/DELETE）</li>
 *   <li>{@link LockMode#INTENTION_SHARED} (IS) — 表级意向共享锁</li>
 *   <li>{@link LockMode#INTENTION_EXCLUSIVE} (IX) — 表级意向排他锁</li>
 * </ul>
 *
 * <h2>锁生命周期</h2>
 * <p>锁持续到事务结束（commit/rollback），由 {@link #unlockAll} 释放。
 * RC 隔离级别下，S 锁可通过 {@link #unlockRecord} 提前释放。</p>
 *
 * <h2>异常处理</h2>
 * <ul>
 *   <li>{@link DeadlockException} — 检测到死锁，事务被选为牺牲者</li>
 *   <li>{@link LockWaitTimeoutException} — 等待锁超过超时时间</li>
 * </ul>
 *
 * @see LockMode
 * @see LockTarget
 */
public interface LockManager {

    /**
     * 获取行级锁
     *
     * <p>如果锁不兼容，阻塞当前线程直到锁被授予、超时或检测到死锁。</p>
     *
     * <p>锁重入: 如果事务已持有相同或更强的锁，直接返回。
     * 锁升级: S→X 时，如果存在其他 S 持有者，抛出 DeadlockException (L9)。</p>
     *
     * @param trx     事务对象
     * @param spaceId 表空间 ID
     * @param pageNo  页号
     * @param heapNo  记录在页内的 heap 编号
     * @param mode    锁模式 (S/X)
     * @throws DeadlockException         检测到死锁，事务被选为牺牲者
     * @throws LockWaitTimeoutException  等待锁超过超时时间
     */
    void lockRecord(Transaction trx, int spaceId, int pageNo, int heapNo, LockMode mode)
            throws DeadlockException, LockWaitTimeoutException;

    /**
     * 获取间隙锁 (Gap Lock)
     *
     * <p>锁定记录前面的间隙，不锁定记录本身。用于 REPEATABLE_READ 隔离级别下
     * 防止其他事务在间隙中插入新记录（防止幻读）。</p>
     *
     * <p>L-P5-1: GAP 锁之间互不冲突（两个 GAP 锁可以同时持有）。
     * GAP 锁只阻塞 INSERT_INTENTION 锁。</p>
     *
     * @param trx     事务对象
     * @param spaceId 表空间 ID
     * @param pageNo  页号
     * @param heapNo  记录在页内的 heap 编号（间隙在此记录之前）
     * @param mode    锁模式 (S/X)
     * @throws DeadlockException         检测到死锁
     * @throws LockWaitTimeoutException  等待锁超过超时时间
     */
    void lockGap(Transaction trx, int spaceId, int pageNo, int heapNo, LockMode mode)
            throws DeadlockException, LockWaitTimeoutException;

    /**
     * 获取 Next-Key 锁
     *
     * <p>锁定记录本身及其前面的间隙（= Record Lock + Gap Lock）。
     * InnoDB 在 REPEATABLE_READ 隔离级别下的默认行锁类型。</p>
     *
     * <p>L-P5-4: 如果事务已持有 RECORD 锁，请求 GAP 锁时会自动合并为 NEXT_KEY 锁。</p>
     *
     * @param trx     事务对象
     * @param spaceId 表空间 ID
     * @param pageNo  页号
     * @param heapNo  记录在页内的 heap 编号
     * @param mode    锁模式 (S/X)
     * @throws DeadlockException         检测到死锁
     * @throws LockWaitTimeoutException  等待锁超过超时时间
     */
    void lockNextKey(Transaction trx, int spaceId, int pageNo, int heapNo, LockMode mode)
            throws DeadlockException, LockWaitTimeoutException;

    /**
     * 获取插入意向锁 (Insert Intention Lock)
     *
     * <p>INSERT 操作在插入记录前获取的特殊间隙锁。
     * 多个事务向同一间隙的不同位置插入时，INSERT_INTENTION 锁之间互不冲突。</p>
     *
     * <p>L-P5-2: INSERT_INTENTION 被已持有的 GAP/NEXT_KEY 锁阻塞；
     * 反之 INSERT_INTENTION 不阻塞任何锁。</p>
     *
     * @param trx     事务对象
     * @param spaceId 表空间 ID
     * @param pageNo  页号
     * @param heapNo  记录在页内的 heap 编号（插入位置的下一条记录）
     * @throws DeadlockException         检测到死锁
     * @throws LockWaitTimeoutException  等待锁超过超时时间
     */
    void lockInsertIntention(Transaction trx, int spaceId, int pageNo, int heapNo)
            throws DeadlockException, LockWaitTimeoutException;

    /**
     * 获取表级锁
     *
     * <p>DML 操作前应先获取表级意向锁 (IS/IX)，再获取行级锁 (L2)。</p>
     *
     * @param trx     事务对象
     * @param tableId 表 ID
     * @param mode    锁模式 (IS/IX/S/X)
     * @throws DeadlockException         检测到死锁
     * @throws LockWaitTimeoutException  等待锁超过超时时间
     */
    void lockTable(Transaction trx, int tableId, LockMode mode)
            throws DeadlockException, LockWaitTimeoutException;

    /**
     * 释放事务持有的所有锁
     *
     * <p>在事务 commit/rollback 时调用。L5 不变量: 幂等操作，多次调用安全。</p>
     *
     * @param trx 事务对象
     */
    void unlockAll(Transaction trx);

    /**
     * 释放指定记录上的锁
     *
     * <p>用于 READ_COMMITTED 隔离级别下，读操作完成后提前释放 S 锁。</p>
     *
     * @param trx    事务对象
     * @param target 锁目标
     */
    void unlockRecord(Transaction trx, LockTarget target);

    /**
     * 关闭 Lock Manager
     *
     * <p>停止后台线程（如 DeadlockDetector）。</p>
     */
    void shutdown();
}
