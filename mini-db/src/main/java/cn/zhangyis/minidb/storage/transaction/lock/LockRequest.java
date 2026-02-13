package cn.zhangyis.minidb.storage.transaction.lock;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;

/**
 * 锁请求
 *
 * <p>表示一个事务对某个锁目标的锁请求。维护请求状态、锁模式和等待线程信息。</p>
 *
 * <h2>状态机</h2>
 * <pre>
 *   WAITING → GRANTED
 *   WAITING → ABORTED
 *   状态只能单向转换，不可回退
 * </pre>
 *
 * <h2>JMM 要点</h2>
 * <ul>
 *   <li>{@code status} 使用 volatile，保证跨线程写-读 happens-before</li>
 *   <li>{@code waitingThread} 使用 volatile，供 park/unpark 使用</li>
 *   <li>{@code mode} 使用 volatile，因为升级时会被修改</li>
 *   <li>{@code lockType} 使用 volatile，因为类型合并升级时会被修改</li>
 * </ul>
 */
public class LockRequest {

    /**
     * 锁请求状态
     */
    public enum Status {
        /** 等待授予 */
        WAITING,
        /** 已授予 */
        GRANTED,
        /** 已中止（死锁牺牲等） */
        ABORTED
    }

    private final TransactionId trxId;
    private final LockTarget target;

    /** 锁模式，volatile: 升级时可被修改 */
    private volatile LockMode mode;

    /** 锁类型，volatile: 类型合并升级时可被修改 (L-P5-4) */
    private volatile LockType lockType;

    /** 请求状态，volatile: 跨线程可见 (JMM) */
    private volatile Status status;

    /** 等待线程引用，volatile: park/unpark 用 */
    private volatile Thread waitingThread;

    /** 锁授予时的时间戳 */
    private long grantTime;

    /**
     * 创建锁请求，初始状态为 WAITING，锁类型默认为 RECORD
     *
     * <p>向后兼容: Phase 1-4 的调用方不需要指定 lockType。</p>
     *
     * @param trxId  事务 ID
     * @param target 锁目标
     * @param mode   请求的锁模式
     */
    public LockRequest(TransactionId trxId, LockTarget target, LockMode mode) {
        this(trxId, target, mode, target.getType() == LockType.TABLE ? LockType.TABLE : LockType.RECORD);
    }

    /**
     * 创建锁请求，指定锁类型，初始状态为 WAITING
     *
     * @param trxId    事务 ID
     * @param target   锁目标
     * @param mode     请求的锁模式
     * @param lockType 锁类型 (RECORD / GAP / NEXT_KEY / INSERT_INTENTION / TABLE)
     */
    public LockRequest(TransactionId trxId, LockTarget target, LockMode mode, LockType lockType) {
        this.trxId = trxId;
        this.target = target;
        this.mode = mode;
        this.lockType = lockType;
        this.status = Status.WAITING;
    }

    // ==================== 状态转换方法 ====================

    /**
     * 标记为已授予
     *
     * <p>状态: WAITING → GRANTED，不可回退。</p>
     */
    public void markGranted() {
        this.status = Status.GRANTED;
        this.grantTime = System.currentTimeMillis();
    }

    /**
     * 标记为已中止
     *
     * <p>状态: WAITING → ABORTED，不可回退。</p>
     */
    public void markAborted() {
        this.status = Status.ABORTED;
    }

    // ==================== 状态查询方法 ====================

    public boolean isGranted() {
        return status == Status.GRANTED;
    }

    public boolean isAborted() {
        return status == Status.ABORTED;
    }

    public boolean isWaiting() {
        return status == Status.WAITING;
    }

    // ==================== 访问方法 ====================

    public TransactionId getTrxId() {
        return trxId;
    }

    public LockTarget getTarget() {
        return target;
    }

    public LockMode getMode() {
        return mode;
    }

    /**
     * 升级锁模式
     *
     * @param newMode 新的锁模式（应比当前模式更强）
     */
    public void upgradeMode(LockMode newMode) {
        this.mode = newMode;
    }

    public LockType getLockType() {
        return lockType;
    }

    /**
     * 升级锁类型（类型合并）
     *
     * <p>L-P5-4: 同一事务持有 RECORD 后请求 GAP → 合并为 NEXT_KEY。</p>
     *
     * @param newLockType 新的锁类型
     */
    public void upgradeLockType(LockType newLockType) {
        this.lockType = newLockType;
    }

    /**
     * 检查当前已持有的锁请求是否与另一个请求兼容
     *
     * <p>综合考虑 LockType 和 LockMode 两个维度:</p>
     * <ol>
     *   <li>TABLE 类型: 直接检查 LockMode 兼容性</li>
     *   <li>Record-level 类型: 先查 {@link LockType#recordTypeCompatibility} 类型兼容矩阵</li>
     *   <li>类型矩阵返回 ALWAYS_COMPATIBLE(1) → 兼容</li>
     *   <li>类型矩阵返回 ALWAYS_INCOMPATIBLE(-1) → 不兼容</li>
     *   <li>类型矩阵返回 CHECK_MODE(0) → 进一步检查 LockMode 兼容性</li>
     * </ol>
     *
     * @param requested 请求的锁
     * @return true 如果兼容
     */
    public boolean isCompatibleWith(LockRequest requested) {
        // TABLE 类型: 直接检查 mode 兼容性
        if (this.lockType == LockType.TABLE || requested.lockType == LockType.TABLE) {
            return this.mode.isCompatibleWith(requested.mode);
        }

        // Record-level: 先查类型兼容矩阵 (L-P5-3)
        int typeCompat = LockType.recordTypeCompatibility(this.lockType, requested.lockType);
        if (typeCompat == 1) {
            return true;   // ALWAYS_COMPATIBLE
        }
        if (typeCompat == -1) {
            return false;  // ALWAYS_INCOMPATIBLE
        }
        // CHECK_MODE: 需要检查 LockMode 兼容性
        return this.mode.isCompatibleWith(requested.mode);
    }

    public Status getStatus() {
        return status;
    }

    public Thread getWaitingThread() {
        return waitingThread;
    }

    public void setWaitingThread(Thread thread) {
        this.waitingThread = thread;
    }

    public long getGrantTime() {
        return grantTime;
    }

    @Override
    public String toString() {
        return String.format("LockRequest(trx=%s, target=%s, mode=%s, type=%s, status=%s)",
                trxId, target, mode, lockType, status);
    }
}
