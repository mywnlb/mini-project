package cn.zhangyis.minidb.storage.transaction.lock;

/**
 * 锁等待超时异常
 *
 * <p>当事务等待锁超过配置的超时时间时抛出。</p>
 *
 * <p>错误码: 401002 (模块40 Transaction System, 类型10, 序号02)</p>
 */
public class LockWaitTimeoutException extends LockException {

    private static final int ERROR_CODE = 401002;

    private final long waitedMs;
    private final LockTarget target;

    /**
     * 创建锁等待超时异常
     *
     * @param waitedMs 实际等待的毫秒数
     * @param target   等待的锁目标
     */
    public LockWaitTimeoutException(long waitedMs, LockTarget target) {
        super(ERROR_CODE, String.format("Lock wait timeout after %d ms on %s", waitedMs, target));
        this.waitedMs = waitedMs;
        this.target = target;
    }

    /**
     * 获取实际等待时间
     *
     * @return 等待的毫秒数
     */
    public long getWaitedMs() {
        return waitedMs;
    }

    /**
     * 获取等待的锁目标
     *
     * @return 锁目标
     */
    public LockTarget getTarget() {
        return target;
    }
}
