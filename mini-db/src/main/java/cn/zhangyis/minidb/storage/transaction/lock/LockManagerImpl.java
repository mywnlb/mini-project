package cn.zhangyis.minidb.storage.transaction.lock;

import cn.zhangyis.minidb.storage.transaction.core.Transaction;
import cn.zhangyis.minidb.storage.transaction.core.TransactionId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

/**
 * Lock Manager 实现
 *
 * <p>基于分段锁表的事务级逻辑锁管理器。</p>
 *
 * <h2>架构</h2>
 * <pre>
 * LockManagerImpl
 *   ├── LockTableSegment[] segments  (分段锁表，默认 64 段)
 *   ├── contextMap                   (TransactionId → TransactionLockContext)
 *   ├── transactionMap               (TransactionId → Transaction，供死锁检测用)
 *   ├── waitingRequests              (TransactionId → 当前等待的 LockRequest)
 *   └── DeadlockDetector             (可选后台线程)
 * </pre>
 *
 * <h2>等待机制</h2>
 * <ol>
 *   <li>在 segment 锁内: tryAcquire → 如果 WAIT，设置 waitingThread</li>
 *   <li>释放 segment 锁（L6: segment 锁内禁止 park）</li>
 *   <li>LockSupport.parkNanos 等待（带超时）</li>
 *   <li>唤醒后检查 request.isGranted() / isAborted()，处理 spurious wakeup</li>
 * </ol>
 *
 * <h2>设计约束</h2>
 * <ul>
 *   <li><b>L4</b>: 超时必须终止等待</li>
 *   <li><b>L5</b>: unlockAll 幂等</li>
 *   <li><b>L6</b>: segment 锁内禁止 park</li>
 * </ul>
 */
public class LockManagerImpl implements LockManager {

    // ==================== 配置常量 ====================

    /** 默认 segment 数量 (必须是 2 的幂) */
    private static final int DEFAULT_SEGMENT_COUNT = 64;

    /** 默认锁等待超时 (50 秒) */
    private static final long DEFAULT_LOCK_WAIT_TIMEOUT_MS = 50_000L;

    /** 默认死锁检测间隔 (1 秒) */
    private static final long DEFAULT_DEADLOCK_DETECT_INTERVAL_MS = 1_000L;

    // ==================== 分段锁表 ====================

    private final LockTableSegment[] segments;
    private final int segmentMask;
    private final int segmentBits;

    // ==================== 事务状态 ====================

    /** 事务 ID → 锁上下文 */
    private final ConcurrentHashMap<TransactionId, TransactionLockContext> contextMap =
            new ConcurrentHashMap<>();

    /** 事务 ID → Transaction 对象（供死锁检测 victim 选择） */
    private final ConcurrentHashMap<TransactionId, Transaction> transactionMap =
            new ConcurrentHashMap<>();

    /** 事务 ID → 当前等待的 LockRequest（每个事务最多等待一个锁） */
    private final ConcurrentHashMap<TransactionId, LockRequest> waitingRequests =
            new ConcurrentHashMap<>();

    // ==================== 配置 ====================

    private final long lockWaitTimeoutMs;

    // ==================== 死锁检测 ====================

    private final DeadlockDetector deadlockDetector;

    // ==================== 构造函数 ====================

    /**
     * 创建 LockManager（完整配置）
     *
     * @param segmentCount              segment 数量 (必须是 2 的幂)
     * @param lockWaitTimeoutMs         锁等待超时毫秒数
     * @param enableDeadlockDetection   是否启用主动死锁检测
     * @param deadlockDetectIntervalMs  死锁检测间隔毫秒数
     */
    public LockManagerImpl(int segmentCount, long lockWaitTimeoutMs,
                           boolean enableDeadlockDetection, long deadlockDetectIntervalMs) {
        // 校验 segmentCount 是 2 的幂
        if (segmentCount <= 0 || (segmentCount & (segmentCount - 1)) != 0) {
            throw new IllegalArgumentException(
                    "segmentCount must be a power of 2, got: " + segmentCount);
        }

        this.segmentMask = segmentCount - 1;
        this.segmentBits = Integer.numberOfTrailingZeros(segmentCount);
        this.segments = new LockTableSegment[segmentCount];
        for (int i = 0; i < segmentCount; i++) {
            segments[i] = new LockTableSegment();
        }

        this.lockWaitTimeoutMs = lockWaitTimeoutMs;

        // 可选: 启动死锁检测器
        if (enableDeadlockDetection) {
            this.deadlockDetector = new DeadlockDetector(
                    segments, transactionMap, waitingRequests, deadlockDetectIntervalMs);
            this.deadlockDetector.start();
        } else {
            this.deadlockDetector = null;
        }
    }

    /**
     * 创建 LockManager（默认配置: 64 segments, 50s timeout, 无主动死锁检测）
     */
    public LockManagerImpl() {
        this(DEFAULT_SEGMENT_COUNT, DEFAULT_LOCK_WAIT_TIMEOUT_MS,
                false, DEFAULT_DEADLOCK_DETECT_INTERVAL_MS);
    }

    /**
     * 创建 LockManager（启用死锁检测）
     *
     * @param enableDeadlockDetection 是否启用主动死锁检测
     */
    public LockManagerImpl(boolean enableDeadlockDetection) {
        this(DEFAULT_SEGMENT_COUNT, DEFAULT_LOCK_WAIT_TIMEOUT_MS,
                enableDeadlockDetection, DEFAULT_DEADLOCK_DETECT_INTERVAL_MS);
    }

    // ==================== LockManager 接口实现 ====================

    @Override
    public void lockRecord(Transaction trx, int spaceId, int pageNo, int heapNo, LockMode mode)
            throws DeadlockException, LockWaitTimeoutException {
        LockTarget target = LockTarget.forRecord(spaceId, pageNo, heapNo);
        acquireLock(trx, target, mode, LockType.RECORD);
    }

    @Override
    public void lockGap(Transaction trx, int spaceId, int pageNo, int heapNo, LockMode mode)
            throws DeadlockException, LockWaitTimeoutException {
        LockTarget target = LockTarget.forRecord(spaceId, pageNo, heapNo);
        acquireLock(trx, target, mode, LockType.GAP);
    }

    @Override
    public void lockNextKey(Transaction trx, int spaceId, int pageNo, int heapNo, LockMode mode)
            throws DeadlockException, LockWaitTimeoutException {
        LockTarget target = LockTarget.forRecord(spaceId, pageNo, heapNo);
        acquireLock(trx, target, mode, LockType.NEXT_KEY);
    }

    @Override
    public void lockInsertIntention(Transaction trx, int spaceId, int pageNo, int heapNo)
            throws DeadlockException, LockWaitTimeoutException {
        LockTarget target = LockTarget.forRecord(spaceId, pageNo, heapNo);
        acquireLock(trx, target, LockMode.EXCLUSIVE, LockType.INSERT_INTENTION);
    }

    @Override
    public void lockTable(Transaction trx, int tableId, LockMode mode)
            throws DeadlockException, LockWaitTimeoutException {
        LockTarget target = LockTarget.forTable(tableId);
        acquireLock(trx, target, mode, LockType.TABLE);
    }

    @Override
    public void unlockAll(Transaction trx) {
        TransactionId trxId = trx.getId();

        // 移除事务状态
        transactionMap.remove(trxId);

        // 取消等待中的锁请求: 标记 ABORTED + unpark + 从队列移除
        // 防止等待线程无谓 park 到超时，同时避免 FIFO-strict 下阻塞后续等待者
        LockRequest waitingRequest = waitingRequests.remove(trxId);
        if (waitingRequest != null) {
            waitingRequest.markAborted();
            Thread t = waitingRequest.getWaitingThread();
            if (t != null) {
                LockSupport.unpark(t);
            }
            getSegment(waitingRequest.getTarget()).cancelWait(trxId, waitingRequest.getTarget());
        }

        TransactionLockContext ctx = contextMap.remove(trxId);
        if (ctx == null) {
            return; // L5: 幂等，没有锁上下文则无需释放
        }

        List<LockRequest> releasedLocks = ctx.releaseAll();
        if (releasedLocks.isEmpty()) {
            return; // L5: 已释放过
        }

        // 按 segment 分组释放，减少锁竞争
        Map<Integer, List<LockRequest>> bySegment = new HashMap<>();
        for (LockRequest request : releasedLocks) {
            int segIdx = getSegmentIndex(request.getTarget());
            bySegment.computeIfAbsent(segIdx, k -> new ArrayList<>()).add(request);
        }
        for (Map.Entry<Integer, List<LockRequest>> entry : bySegment.entrySet()) {
            segments[entry.getKey()].releaseAll(trxId, entry.getValue());
        }
    }

    @Override
    public void unlockRecord(Transaction trx, LockTarget target) {
        TransactionId trxId = trx.getId();
        TransactionLockContext ctx = contextMap.get(trxId);
        if (ctx == null) {
            return;
        }

        LockRequest removed = ctx.removeLock(target);
        if (removed == null) {
            return;
        }

        LockTableSegment segment = getSegment(target);
        segment.release(trxId, target);
    }

    @Override
    public void shutdown() {
        if (deadlockDetector != null) {
            deadlockDetector.stop();
        }
    }

    // ==================== 核心加锁逻辑 ====================

    /**
     * 通用加锁逻辑
     *
     * <p>流程:</p>
     * <ol>
     *   <li>快速路径: 检查 TransactionLockContext 是否已持有覆盖的锁（lockType + mode）</li>
     *   <li>创建 LockRequest（含 lockType），路由到 segment，调用 tryAcquire</li>
     *   <li>GRANTED → 如果是新锁，添加到 context</li>
     *   <li>WAIT → 注册等待，park 循环等待（L6: park 在 segment 锁外）</li>
     *   <li>DEADLOCK → 抛出 DeadlockException</li>
     * </ol>
     */
    private void acquireLock(Transaction trx, LockTarget target, LockMode mode, LockType lockType)
            throws DeadlockException, LockWaitTimeoutException {
        TransactionId trxId = trx.getId();
        transactionMap.putIfAbsent(trxId, trx);
        TransactionLockContext ctx = getOrCreateContext(trxId);

        // 快速路径: 已持有覆盖的锁（lockType 覆盖 + mode 更强），无需获取 segment 锁
        LockRequest existing = ctx.findLock(target);
        if (existing != null
                && existing.getLockType().covers(lockType)
                && existing.getMode().isStrongerOrEqual(mode)) {
            return;
        }

        LockRequest request = new LockRequest(trxId, target, mode, lockType);
        LockTableSegment segment = getSegment(target);
        LockResult result = segment.tryAcquire(request);

        switch (result) {
            case GRANTED:
                // request.isGranted() == true: 新锁已加入 queue 的 grantedList
                // request.isGranted() == false: 重入/升级，已有 LockRequest 被原地修改
                if (request.isGranted()) {
                    ctx.addLock(request);
                }
                break;

            case WAIT:
                // 等待授予（park 在 segment 锁外，L6）
                waitingRequests.put(trxId, request);
                try {
                    waitForLock(request, target);
                    // 授予成功，添加到 context
                    ctx.addLock(request);
                } finally {
                    waitingRequests.remove(trxId);
                }
                break;

            case DEADLOCK:
                throw new DeadlockException(trxId);
        }
    }

    /**
     * 等待锁授予
     *
     * <p>L6: park 在 segment 锁外执行。
     * L4: 超时后取消等待并抛出异常。</p>
     *
     * <p>JMM: request.status 使用 CAS/volatile 语义，
     * grantWaiters 中的 markGranted() 对此处 isGranted() 可见。</p>
     *
     * <p>LockSupport.unpark 在 park 之前调用也有效（设置 permit），
     * 所以不会丢失唤醒信号。</p>
     *
     * @param request 等待中的锁请求
     * @param target  锁目标
     * @throws DeadlockException         被死锁检测选为牺牲者
     * @throws LockWaitTimeoutException  等待超时
     */
    private void waitForLock(LockRequest request, LockTarget target)
            throws DeadlockException, LockWaitTimeoutException {
        long startTime = System.currentTimeMillis();
        long deadline = startTime + lockWaitTimeoutMs;

        while (true) {
            // 检查超时
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                // 边界竞态: 超时瞬间可能刚被授予，二次确认后再取消
                if (request.isGranted()) {
                    return;
                }
                // L4: 超时终止等待
                getSegment(target).cancelWait(request.getTrxId(), target);
                throw new LockWaitTimeoutException(
                        System.currentTimeMillis() - startTime, target);
            }

            // L6: park 在 segment 锁外
            LockSupport.parkNanos(remaining * 1_000_000L);

            // 检查状态（CAS/volatile 读语义）
            if (request.isGranted()) {
                return; // 锁已授予
            }
            if (request.isAborted()) {
                // 被死锁检测选为牺牲者
                getSegment(target).cancelWait(request.getTrxId(), target);
                throw new DeadlockException(request.getTrxId());
            }

            // spurious wakeup → 继续循环
        }
    }

    // ==================== segment 路由 ====================

    /**
     * 通过 LockTarget 的 hashCode 路由到 segment
     *
     * <p>参考 BufferPool 的 PageHashSegment 路由算法:
     * 取 hash 值的高位作为 segment 索引，保证分布均匀。</p>
     */
    private LockTableSegment getSegment(LockTarget target) {
        return segments[getSegmentIndex(target)];
    }

    private int getSegmentIndex(LockTarget target) {
        int hash = target.hashCode();
        return (hash >>> (32 - segmentBits)) & segmentMask;
    }

    // ==================== 事务上下文管理 ====================

    private TransactionLockContext getOrCreateContext(TransactionId trxId) {
        return contextMap.computeIfAbsent(trxId, TransactionLockContext::new);
    }

    // ==================== 内部访问（供 DeadlockDetector 使用） ====================

    /**
     * 获取 segment 数组（package-private，供 DeadlockDetector 遍历）
     */
    LockTableSegment[] getSegments() {
        return segments;
    }

    // ==================== 监控 ====================

    /**
     * 获取当前活跃的事务锁上下文数量
     */
    public int getActiveContextCount() {
        return contextMap.size();
    }

    /**
     * 获取当前等待锁的事务数量
     */
    public int getWaitingCount() {
        return waitingRequests.size();
    }

    /**
     * 获取锁等待超时配置
     */
    public long getLockWaitTimeoutMs() {
        return lockWaitTimeoutMs;
    }
}
