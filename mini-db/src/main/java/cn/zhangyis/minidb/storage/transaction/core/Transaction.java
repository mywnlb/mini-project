package cn.zhangyis.minidb.storage.transaction.core;

import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 事务 (Transaction)
 *
 * <p>事务是数据库操作的基本单位，提供 ACID 特性：
 * <ul>
 *   <li><b>Atomicity</b>: 通过 Undo Log 实现</li>
 *   <li><b>Consistency</b>: 通过约束检查实现</li>
 *   <li><b>Isolation</b>: 通过 MVCC + ReadView 实现</li>
 *   <li><b>Durability</b>: 通过 Redo Log (WAL) 实现</li>
 * </ul>
 * </p>
 *
 * <h2>事务生命周期</h2>
 * <pre>
 * TransactionManager.begin()
 *         │
 *         ▼
 *   ┌───────────┐     DML 操作      ┌───────────────┐
 *   │  ACTIVE   │◄────────────────►│ 写入 Undo Log  │
 *   │           │                  │ 修改数据页     │
 *   └─────┬─────┘                  └───────────────┘
 *         │
 *    ┌────┴────┐
 *    │         │
 *    ▼         ▼
 * commit()  rollback()
 *    │         │
 *    ▼         ▼
 * COMMITTED  ROLLED_BACK
 * </pre>
 *
 * <h2>设计约束 (Invariants)</h2>
 * <ul>
 *   <li><b>T1</b>: TRX_ID 全局递增，事务创建时分配</li>
 *   <li><b>T2</b>: 提交前必须写入 Redo Log</li>
 *   <li><b>T3</b>: Undo 必须先于数据修改持久化</li>
 *   <li><b>T4</b>: ReadView 创建后不可变</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>事务对象本身是线程安全的（状态使用 AtomicReference），
 * 但一个事务应该只在单个线程中使用。</p>
 *
 * @author MiniDB
 * @version 1.0
 * @see TransactionManager
 * @see TransactionId
 * @see TransactionState
 */
public class Transaction {

    // ==================== 核心字段 ====================

    /**
     * 事务ID
     */
    private final TransactionId id;

    /**
     * 事务状态
     */
    private final AtomicReference<TransactionState> state;

    // ==================== 时间戳 ====================

    /**
     * 事务开始时间 (毫秒)
     */
    private final long startTime;

    /**
     * 事务提交时间 (毫秒，未提交时为 0)
     */
    private volatile long commitTime;

    // ==================== Undo 相关 ====================

    /**
     * INSERT Undo Segment 的最后一条记录位置
     *
     * <p>用于回滚时逆序遍历 INSERT 操作。</p>
     */
    private volatile RollbackPointer lastInsertUndoPtr;

    /**
     * UPDATE/DELETE Undo Segment 的最后一条记录位置
     *
     * <p>用于回滚时逆序遍历 UPDATE/DELETE 操作，
     * 以及 MVCC 版本链构建。</p>
     */
    private volatile RollbackPointer lastUpdateUndoPtr;

    // ==================== 统计信息 ====================

    /**
     * INSERT 操作计数
     */
    private final AtomicInteger insertCount;

    /**
     * UPDATE 操作计数
     */
    private final AtomicInteger updateCount;

    /**
     * DELETE 操作计数
     */
    private final AtomicInteger deleteCount;

    // ==================== 隔离级别相关 ====================

    /**
     * 隔离级别
     */
    private final IsolationLevel isolationLevel;

    // ==================== 构造函数 ====================

    /**
     * 创建事务（内部使用，由 TransactionManager 调用）
     *
     * @param id             事务ID
     * @param isolationLevel 隔离级别
     */
    public Transaction(TransactionId id, IsolationLevel isolationLevel) {
        this.id = id;
        this.isolationLevel = isolationLevel;
        this.state = new AtomicReference<>(TransactionState.ACTIVE);
        this.startTime = System.currentTimeMillis();
        this.commitTime = 0;
        this.lastInsertUndoPtr = RollbackPointer.NULL;
        this.lastUpdateUndoPtr = RollbackPointer.NULL;
        this.insertCount = new AtomicInteger(0);
        this.updateCount = new AtomicInteger(0);
        this.deleteCount = new AtomicInteger(0);
    }

    /**
     * 创建事务（默认 REPEATABLE_READ 隔离级别）
     *
     * @param id 事务ID
     */
    public Transaction(TransactionId id) {
        this(id, IsolationLevel.REPEATABLE_READ);
    }

    // ==================== 状态管理 ====================

    /**
     * 获取事务ID
     *
     * @return 事务ID
     */
    public TransactionId getId() {
        return id;
    }

    /**
     * 获取当前状态
     *
     * @return 事务状态
     */
    public TransactionState getState() {
        return state.get();
    }

    /**
     * 是否处于活跃状态
     *
     * @return true 如果事务正在执行中
     */
    public boolean isActive() {
        return state.get() == TransactionState.ACTIVE;
    }

    /**
     * 是否已提交
     *
     * @return true 如果事务已成功提交
     */
    public boolean isCommitted() {
        return state.get() == TransactionState.COMMITTED;
    }

    /**
     * 是否已回滚
     *
     * @return true 如果事务已回滚
     */
    public boolean isRolledBack() {
        return state.get() == TransactionState.ROLLED_BACK;
    }

    /**
     * 是否已结束（提交或回滚）
     *
     * @return true 如果事务已结束
     */
    public boolean isFinished() {
        TransactionState s = state.get();
        return s == TransactionState.COMMITTED || s == TransactionState.ROLLED_BACK;
    }

    /**
     * 尝试转换到新状态
     *
     * @param expected 预期的当前状态
     * @param newState 目标状态
     * @return true 如果成功转换
     * @throws IllegalStateException 如果状态转换不合法
     */
    public boolean compareAndSetState(TransactionState expected, TransactionState newState) {
        expected.validateTransition(newState);
        return state.compareAndSet(expected, newState);
    }

    /**
     * 设置状态（强制）
     *
     * <p>仅供 TransactionManager 内部使用。</p>
     *
     * @param newState 目标状态
     */
    void setState(TransactionState newState) {
        TransactionState current = state.get();
        current.validateTransition(newState);
        state.set(newState);
    }

    // ==================== 时间戳 ====================

    /**
     * 获取事务开始时间
     *
     * @return 开始时间（毫秒）
     */
    public long getStartTime() {
        return startTime;
    }

    /**
     * 获取事务提交时间
     *
     * @return 提交时间（毫秒），未提交返回 0
     */
    public long getCommitTime() {
        return commitTime;
    }

    /**
     * 设置提交时间
     *
     * <p>仅供 TransactionManager 内部使用。</p>
     *
     * @param commitTime 提交时间
     */
    void setCommitTime(long commitTime) {
        this.commitTime = commitTime;
    }

    /**
     * 获取事务运行时长
     *
     * @return 运行时长（毫秒）
     */
    public long getDuration() {
        long endTime = commitTime > 0 ? commitTime : System.currentTimeMillis();
        return endTime - startTime;
    }

    // ==================== Undo 指针管理 ====================

    /**
     * 获取最后一条 INSERT Undo 记录位置
     *
     * @return 回滚指针
     */
    public RollbackPointer getLastInsertUndoPtr() {
        return lastInsertUndoPtr;
    }

    /**
     * 设置最后一条 INSERT Undo 记录位置
     *
     * @param ptr 回滚指针
     */
    public void setLastInsertUndoPtr(RollbackPointer ptr) {
        this.lastInsertUndoPtr = ptr;
    }

    /**
     * 获取最后一条 UPDATE/DELETE Undo 记录位置
     *
     * @return 回滚指针
     */
    public RollbackPointer getLastUpdateUndoPtr() {
        return lastUpdateUndoPtr;
    }

    /**
     * 设置最后一条 UPDATE/DELETE Undo 记录位置
     *
     * @param ptr 回滚指针
     */
    public void setLastUpdateUndoPtr(RollbackPointer ptr) {
        this.lastUpdateUndoPtr = ptr;
    }

    // ==================== 操作计数 ====================

    /**
     * 增加 INSERT 计数
     *
     * @return 新的计数值
     */
    public int incrementInsertCount() {
        return insertCount.incrementAndGet();
    }

    /**
     * 增加 UPDATE 计数
     *
     * @return 新的计数值
     */
    public int incrementUpdateCount() {
        return updateCount.incrementAndGet();
    }

    /**
     * 增加 DELETE 计数
     *
     * @return 新的计数值
     */
    public int incrementDeleteCount() {
        return deleteCount.incrementAndGet();
    }

    /**
     * 获取 INSERT 计数
     *
     * @return INSERT 操作数量
     */
    public int getInsertCount() {
        return insertCount.get();
    }

    /**
     * 获取 UPDATE 计数
     *
     * @return UPDATE 操作数量
     */
    public int getUpdateCount() {
        return updateCount.get();
    }

    /**
     * 获取 DELETE 计数
     *
     * @return DELETE 操作数量
     */
    public int getDeleteCount() {
        return deleteCount.get();
    }

    /**
     * 获取总修改计数
     *
     * @return INSERT + UPDATE + DELETE 总数
     */
    public int getTotalModifications() {
        return insertCount.get() + updateCount.get() + deleteCount.get();
    }

    /**
     * 是否是只读事务（没有修改操作）
     *
     * @return true 如果没有任何修改
     */
    public boolean isReadOnly() {
        return getTotalModifications() == 0;
    }

    // ==================== 隔离级别 ====================

    /**
     * 获取隔离级别
     *
     * @return 隔离级别
     */
    public IsolationLevel getIsolationLevel() {
        return isolationLevel;
    }

    // ==================== 活跃状态检查 ====================

    /**
     * 检查事务是否处于活跃状态
     *
     * @throws IllegalStateException 如果事务不在活跃状态
     */
    public void checkActive() {
        TransactionState s = state.get();
        if (s != TransactionState.ACTIVE) {
            throw new IllegalStateException(
                    String.format("Transaction %s is not active: %s", id, s));
        }
    }

    // ==================== Object 方法 ====================

    @Override
    public String toString() {
        return String.format("Transaction{id=%s, state=%s, duration=%dms, " +
                        "inserts=%d, updates=%d, deletes=%d}",
                id, state.get(), getDuration(),
                insertCount.get(), updateCount.get(), deleteCount.get());
    }

    // ==================== 隔离级别枚举 ====================

    /**
     * 事务隔离级别
     */
    public enum IsolationLevel {
        /**
         * 读未提交
         *
         * <p>允许脏读，实际很少使用。</p>
         */
        READ_UNCOMMITTED(0),

        /**
         * 读已提交
         *
         * <p>每次读取创建新的 ReadView，只能看到已提交的数据。
         * 可能出现不可重复读。</p>
         */
        READ_COMMITTED(1),

        /**
         * 可重复读 (默认)
         *
         * <p>事务首次读取时创建 ReadView，之后复用。
         * 保证同一事务内多次读取结果一致。</p>
         *
         * <p>InnoDB 通过 MVCC 实现，避免幻读。</p>
         */
        REPEATABLE_READ(2),

        /**
         * 串行化
         *
         * <p>最高隔离级别，通过锁实现完全串行。
         * 性能最差，但保证完全一致性。</p>
         */
        SERIALIZABLE(3);

        private final int level;

        IsolationLevel(int level) {
            this.level = level;
        }

        public int getLevel() {
            return level;
        }

        /**
         * 从级别值获取枚举
         *
         * @param level 级别值
         * @return 隔离级别
         */
        public static IsolationLevel fromLevel(int level) {
            for (IsolationLevel il : values()) {
                if (il.level == level) {
                    return il;
                }
            }
            throw new IllegalArgumentException("Unknown isolation level: " + level);
        }
    }
}
