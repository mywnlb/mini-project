package cn.zhangyis.minidb.storage.transaction.core;

/**
 * 事务状态枚举
 *
 * <h2>状态机</h2>
 * <pre>
 *      ┌─────────────┐
 *      │   ACTIVE    │◄────── begin()
 *      └──────┬──────┘
 *             │
 *     ┌───────┴───────┐
 *     │               │
 *     ▼               ▼
 * ┌───────────┐  ┌──────────────┐
 * │  COMMIT   │  │   ROLLBACK   │
 * │  PENDING  │  │   PENDING    │
 * └─────┬─────┘  └──────┬───────┘
 *       │               │
 *       ▼               ▼
 * ┌───────────┐  ┌──────────────┐
 * │ COMMITTED │  │ ROLLED_BACK  │
 * └───────────┘  └──────────────┘
 * </pre>
 *
 * <h2>状态转换规则</h2>
 * <ul>
 *   <li>{@code ACTIVE} → {@code COMMIT_PENDING}: 调用 commit()</li>
 *   <li>{@code COMMIT_PENDING} → {@code COMMITTED}: Redo Log 持久化完成</li>
 *   <li>{@code ACTIVE} → {@code ROLLBACK_PENDING}: 调用 rollback()</li>
 *   <li>{@code ROLLBACK_PENDING} → {@code ROLLED_BACK}: Undo 应用完成</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public enum TransactionState {

    /**
     * 活跃状态
     *
     * <p>事务正在执行中，可以进行读写操作。
     * 这是事务的初始状态。</p>
     */
    ACTIVE("活跃中", true, false),

    /**
     * 提交中
     *
     * <p>事务正在提交过程中，Redo Log 已写入 buffer，
     * 等待 fsync 持久化完成。</p>
     *
     * <p>在此状态下：
     * <ul>
     *   <li>不允许新的读写操作</li>
     *   <li>Redo Log 正在刷盘</li>
     *   <li>事务对外可见（其他事务可以看到修改）</li>
     * </ul>
     * </p>
     */
    COMMIT_PENDING("提交中", false, false),

    /**
     * 已提交
     *
     * <p>事务已成功提交，所有修改已持久化。
     * 这是事务的最终状态之一。</p>
     */
    COMMITTED("已提交", false, true),

    /**
     * 回滚中
     *
     * <p>事务正在回滚过程中，正在逆序应用 Undo Log
     * 恢复数据到事务开始前的状态。</p>
     *
     * <p>在此状态下：
     * <ul>
     *   <li>不允许新的读写操作</li>
     *   <li>Undo Log 正在被应用</li>
     *   <li>数据正在被还原</li>
     * </ul>
     * </p>
     */
    ROLLBACK_PENDING("回滚中", false, false),

    /**
     * 已回滚
     *
     * <p>事务已成功回滚，所有修改已撤销。
     * 这是事务的最终状态之一。</p>
     */
    ROLLED_BACK("已回滚", false, true);

    // ==================== 字段 ====================

    /**
     * 状态描述
     */
    private final String description;

    /**
     * 是否允许执行新操作
     */
    private final boolean allowOperations;

    /**
     * 是否是终态
     */
    private final boolean terminal;

    // ==================== 构造函数 ====================

    TransactionState(String description, boolean allowOperations, boolean terminal) {
        this.description = description;
        this.allowOperations = allowOperations;
        this.terminal = terminal;
    }

    // ==================== 访问方法 ====================

    /**
     * 获取状态描述
     *
     * @return 描述字符串
     */
    public String getDescription() {
        return description;
    }

    /**
     * 是否允许执行新的读写操作
     *
     * <p>只有 ACTIVE 状态允许执行操作。</p>
     *
     * @return true 如果允许
     */
    public boolean allowsOperations() {
        return allowOperations;
    }

    /**
     * 是否是终态
     *
     * <p>COMMITTED 和 ROLLED_BACK 是终态，
     * 事务不能再进行任何状态转换。</p>
     *
     * @return true 如果是终态
     */
    public boolean isTerminal() {
        return terminal;
    }

    /**
     * 是否是活跃状态
     *
     * @return true 如果是 ACTIVE
     */
    public boolean isActive() {
        return this == ACTIVE;
    }

    /**
     * 是否已提交
     *
     * @return true 如果是 COMMITTED
     */
    public boolean isCommitted() {
        return this == COMMITTED;
    }

    /**
     * 是否已回滚
     *
     * @return true 如果是 ROLLED_BACK
     */
    public boolean isRolledBack() {
        return this == ROLLED_BACK;
    }

    /**
     * 是否正在处理中（提交或回滚）
     *
     * @return true 如果是 COMMIT_PENDING 或 ROLLBACK_PENDING
     */
    public boolean isPending() {
        return this == COMMIT_PENDING || this == ROLLBACK_PENDING;
    }

    // ==================== 状态转换验证 ====================

    /**
     * 检查是否可以转换到目标状态
     *
     * @param target 目标状态
     * @return true 如果允许转换
     */
    public boolean canTransitionTo(TransactionState target) {
        return switch (this) {
            case ACTIVE -> target == COMMIT_PENDING || target == ROLLBACK_PENDING;
            case COMMIT_PENDING -> target == COMMITTED;
            case ROLLBACK_PENDING -> target == ROLLED_BACK;
            case COMMITTED, ROLLED_BACK -> false; // 终态不能转换
        };
    }

    /**
     * 验证状态转换是否合法
     *
     * @param target 目标状态
     * @throws IllegalStateException 如果不允许转换
     */
    public void validateTransition(TransactionState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException(
                    String.format("Cannot transition from %s to %s", this, target));
        }
    }

    @Override
    public String toString() {
        return name() + "(" + description + ")";
    }
}
