package cn.zhangyis.minidb.storage.transaction.core;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.redo.RedoLogManager;
import cn.zhangyis.minidb.storage.transaction.lock.LockManager;
import cn.zhangyis.minidb.storage.transaction.mvcc.ReadView;
import cn.zhangyis.minidb.storage.transaction.purge.PurgeCoordinator;
import cn.zhangyis.minidb.storage.transaction.undo.UndoLogManager;
import cn.zhangyis.minidb.storage.transaction.undo.UndoRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 事务管理器 (Transaction Manager)
 *
 * <p>事务管理器是事务子系统的核心组件，负责事务的生命周期管理，
 * 包括 begin、commit、rollback 操作，以及 MVCC ReadView 的创建。</p>
 *
 * <h2>核心职责</h2>
 * <ul>
 *   <li><b>事务生命周期</b>: begin() / commit() / rollback()</li>
 *   <li><b>TRX_ID 分配</b>: 全局递增的事务 ID</li>
 *   <li><b>活跃事务管理</b>: 维护当前活跃的事务列表</li>
 *   <li><b>ReadView 创建</b>: 为 MVCC 创建一致性读视图</li>
 *   <li><b>协调器</b>: 协调 UndoLogManager 和 RedoLogManager</li>
 * </ul>
 *
 * <h2>设计约束 (Invariants)</h2>
 * <ul>
 *   <li><b>T1</b>: TRX_ID 全局递增，永不复用</li>
 *   <li><b>T2</b>: 事务提交前必须写入 Redo Log</li>
 *   <li><b>TM1</b>: 活跃事务列表并发安全</li>
 *   <li><b>TM2</b>: 事务状态转换必须合法</li>
 *   <li><b>TM3</b>: ReadView 必须获取一致的活跃列表快照</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>TransactionManager 是线程安全的，支持多线程并发创建和提交事务。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * TransactionManager txnMgr = new TransactionManager(bufferPool, undoLogMgr);
 *
 * // 开始事务
 * Transaction trx = txnMgr.begin();
 *
 * try {
 *     // 执行 DML 操作...
 *
 *     // 创建 ReadView 进行一致性读
 *     ReadView readView = txnMgr.createReadView(trx);
 *
 *     // 提交事务
 *     txnMgr.commit(trx);
 * } catch (Exception e) {
 *     // 回滚事务
 *     txnMgr.rollback(trx);
 * }
 * }</pre>
 *
 * @author MiniDB
 * @version 1.0
 * @see Transaction
 * @see ReadView
 * @see UndoLogManager
 */
public class TransactionManager {

    private static final Logger logger = LoggerFactory.getLogger(TransactionManager.class);

    // ==================== 字段 ====================

    /**
     * Buffer Pool 引用
     */
    private final BufferPool bufferPool;

    /**
     * Undo Log Manager 引用
     */
    private final UndoLogManager undoLogManager;

    /**
     * Redo Log Manager 引用 (可选)
     */
    private final RedoLogManager redoLogManager;

    /**
     * 系统表空间 ID
     */
    private final int spaceId;

    /**
     * 事务系统页 PageId
     */
    private final PageId sysPageId;

    /**
     * 活跃事务映射
     *
     * <p>Key: TransactionId, Value: Transaction</p>
     */
    private final Map<TransactionId, Transaction> activeTransactions;

    /**
     * 活跃事务列表锁
     *
     * <p>用于保护活跃事务列表和 ReadView 创建的一致性：
     * <ul>
     *   <li>读锁: begin() 添加事务</li>
     *   <li>写锁: createReadView() 创建快照</li>
     * </ul>
     * </p>
     */
    private final ReadWriteLock activeTrxLock;

    /**
     * 下一个 TRX_ID (内存缓存)
     *
     * <p>为了避免每次分配都读写系统页，我们在内存中缓存一批 TRX_ID。
     * 当缓存用完时，从系统页批量分配。</p>
     */
    private final AtomicLong nextTrxIdCache;

    /**
     * TRX_ID 缓存上限
     */
    private volatile long trxIdCacheLimit;

    /**
     * TRX_ID 批量分配大小
     */
    private static final int TRX_ID_BATCH_SIZE = 256;

    /**
     * TRX_ID 分配锁
     */
    private final Object trxIdAllocLock = new Object();

    /**
     * 是否已初始化
     */
    private volatile boolean initialized;

    /**
     * Purge 协调器
     *
     * <p>用于跟踪活跃的 ReadView，计算可以安全清理的 TRX_ID 边界。</p>
     */
    private volatile PurgeCoordinator purgeCoordinator;

    /**
     * Lock Manager 引用 (可选)
     *
     * <p>L-P4-4: LockManager 为可选组件。设置后，commit/rollback 会自动调用
     * {@link LockManager#unlockAll} 释放事务持有的所有锁。</p>
     */
    private volatile LockManager lockManager;

    /**
     * Undo 记录应用器 (可选)
     *
     * <p>I-RB2: UndoApplier 为可选组件。设置后，rollback 时会通过此接口
     * 将 Undo 记录应用到数据页，实际恢复物理数据。
     * 未设置时 rollback 仅做 Undo 日志层面的清理，不恢复数据页。</p>
     */
    private volatile UndoApplier undoApplier;

    // ==================== 构造函数 ====================

    /**
     * 创建事务管理器
     *
     * @param bufferPool     Buffer Pool 实例
     * @param undoLogManager Undo Log Manager 实例
     */
    public TransactionManager(BufferPool bufferPool, UndoLogManager undoLogManager) {
        this(bufferPool, undoLogManager, null, 0);
    }

    /**
     * 创建事务管理器
     *
     * @param bufferPool     Buffer Pool 实例
     * @param undoLogManager Undo Log Manager 实例
     * @param redoLogManager Redo Log Manager 实例 (可为 null)
     * @param spaceId        系统表空间 ID
     */
    public TransactionManager(BufferPool bufferPool,
                              UndoLogManager undoLogManager,
                              RedoLogManager redoLogManager,
                              int spaceId) {
        if (bufferPool == null) {
            throw new NullPointerException("bufferPool cannot be null");
        }

        this.bufferPool = bufferPool;
        this.undoLogManager = undoLogManager;
        this.redoLogManager = redoLogManager;
        this.spaceId = spaceId;
        this.sysPageId = TransactionSysPage.getPageId(spaceId);
        this.activeTransactions = new ConcurrentHashMap<>();
        this.activeTrxLock = new ReentrantReadWriteLock();
        this.nextTrxIdCache = new AtomicLong(1);
        this.trxIdCacheLimit = 0;
        this.initialized = false;
    }

    // ==================== 初始化方法 ====================

    /**
     * 初始化事务管理器
     *
     * <p>从系统页加载下一个 TRX_ID，或者创建新的系统页。</p>
     *
     * @throws MiniDbException 如果初始化失败
     */
    public void initialize() throws MiniDbException {
        if (initialized) {
            return;
        }

        synchronized (trxIdAllocLock) {
            if (initialized) {
                return;
            }

            try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
                Page sysPage;

                try {
                    // 尝试读取现有系统页
                    sysPage = mtr.getPage(sysPageId, BufferPool.FetchMode.READ_EXISTING);
                    ByteBuffer buf = sysPage.getBuffer();

                    if (TransactionSysPage.isTrxSysPage(buf)) {
                        // 从系统页恢复 TRX_ID
                        long nextTrxId = TransactionSysPage.getNextTrxId(buf);
                        nextTrxIdCache.set(nextTrxId);
                        trxIdCacheLimit = nextTrxId;
                        logger.info("Recovered next TRX_ID from system page: {}", nextTrxId);
                    } else {
                        // 页面存在但不是系统页，初始化它
                        TransactionSysPage.init(buf, spaceId);
                        mtr.markDirty(sysPage);
                        nextTrxIdCache.set(TransactionSysPage.INITIAL_TRX_ID);
                        trxIdCacheLimit = TransactionSysPage.INITIAL_TRX_ID;
                        logger.info("Initialized TRX_SYS page");
                    }
                } catch (MiniDbException e) {
                    // 系统页不存在，创建新的
                    sysPage = mtr.getPage(sysPageId, BufferPool.FetchMode.NEW_PAGE);
                    TransactionSysPage.init(sysPage.getBuffer(), spaceId);
                    mtr.markDirty(sysPage);
                    nextTrxIdCache.set(TransactionSysPage.INITIAL_TRX_ID);
                    trxIdCacheLimit = TransactionSysPage.INITIAL_TRX_ID;
                    logger.info("Created new TRX_SYS page");
                }

                mtr.commit();
            }

            initialized = true;
        }
    }

    /**
     * 初始化（简化版，使用内存 TRX_ID，不持久化）
     *
     * <p>用于测试或不需要持久化的场景。</p>
     */
    public void initializeInMemory() {
        if (initialized) {
            return;
        }

        synchronized (trxIdAllocLock) {
            if (initialized) {
                return;
            }

            nextTrxIdCache.set(1);
            trxIdCacheLimit = Long.MAX_VALUE;  // 不需要从系统页分配

            // 初始化 Purge 协调器
            if (purgeCoordinator == null) {
                purgeCoordinator = new PurgeCoordinator(this);
            }

            initialized = true;
            logger.info("Initialized TransactionManager in memory mode");
        }
    }

    // ==================== 事务生命周期方法 ====================

    /**
     * 开始一个新事务
     *
     * @return 新创建的事务
     * @throws MiniDbException 如果创建失败
     */
    public Transaction begin() throws MiniDbException {
        return begin(Transaction.IsolationLevel.REPEATABLE_READ);
    }

    /**
     * 开始一个新事务 (指定隔离级别)
     *
     * @param isolationLevel 隔离级别
     * @return 新创建的事务
     * @throws MiniDbException 如果创建失败
     */
    public Transaction begin(Transaction.IsolationLevel isolationLevel) throws MiniDbException {
        ensureInitialized();

        // 分配 TRX_ID
        TransactionId trxId = allocateTrxId();

        // 创建事务对象
        Transaction trx = new Transaction(trxId, isolationLevel);

        // 设置 TransactionManager 引用
        trx.setTransactionManager(this);

        // 添加到活跃事务列表
        activeTrxLock.readLock().lock();
        try {
            activeTransactions.put(trxId, trx);
        } finally {
            activeTrxLock.readLock().unlock();
        }

        logger.debug("Transaction started: trxId={}, isolationLevel={}", trxId, isolationLevel);
        return trx;
    }

    /**
     * 提交事务
     *
     * <p>提交步骤：
     * <ol>
     *   <li>检查事务状态</li>
     *   <li>设置状态为 COMMIT_PENDING</li>
     *   <li>通知 UndoLogManager 事务提交</li>
     *   <li>设置状态为 COMMITTED</li>
     *   <li>注销 ReadView</li>
     *   <li>从活跃列表移除</li>
     * </ol>
     * </p>
     *
     * @param trx 事务
     * @throws MiniDbException 如果提交失败
     */
    public void commit(Transaction trx) throws MiniDbException {
        if (trx == null) {
            throw new NullPointerException("Transaction cannot be null");
        }

        // 检查状态
        if (!trx.isActive()) {
            throw new IllegalStateException("Cannot commit non-active transaction: " + trx.getState());
        }

        try {
            // 状态转换: ACTIVE -> COMMIT_PENDING
            trx.setState(TransactionState.COMMIT_PENDING);

            // 通知 UndoLogManager
            if (undoLogManager != null) {
                undoLogManager.commitTransaction(trx);
            }

            // 设置提交时间
            trx.setCommitTime(System.currentTimeMillis());

            // 状态转换: COMMIT_PENDING -> COMMITTED
            trx.setState(TransactionState.COMMITTED);

            // 注销 ReadView（Purge 安全门控）
            ReadView cachedReadView = trx.getCachedReadView();
            if (cachedReadView != null) {
                trx.unregisterReadView(cachedReadView);
            }

            // 清除缓存的 ReadView
            trx.clearCachedReadView();

            // L-P4-1: 释放所有锁（在 Undo 提交之后、activeTransactions.remove 之前）
            // L5: unlockAll 幂等，commit 失败后 rollback 重复调用也安全
            if (lockManager != null) {
                lockManager.unlockAll(trx);
            }

            // 从活跃列表移除
            activeTransactions.remove(trx.getId());

            logger.debug("Transaction committed: trxId={}, duration={}ms",
                    trx.getId(), trx.getDuration());

        } catch (Exception e) {
            // 提交失败，尝试回滚
            logger.error("Commit failed, attempting rollback: trxId={}", trx.getId(), e);
            try {
                rollback(trx);
            } catch (Exception rollbackEx) {
                logger.error("Rollback after failed commit also failed", rollbackEx);
            }
            throw new MiniDbException("Transaction commit failed", e);
        }
    }

    /**
     * 回滚事务
     *
     * <p>回滚步骤：
     * <ol>
     *   <li>检查事务状态</li>
     *   <li>设置状态为 ROLLBACK_PENDING</li>
     *   <li>从 UndoLogManager 获取 Undo 记录并应用</li>
     *   <li>设置状态为 ROLLED_BACK</li>
     *   <li>注销 ReadView</li>
     *   <li>从活跃列表移除</li>
     * </ol>
     * </p>
     *
     * @param trx 事务
     * @throws MiniDbException 如果回滚失败
     */
    public void rollback(Transaction trx) throws MiniDbException {
        if (trx == null) {
            throw new NullPointerException("Transaction cannot be null");
        }

        TransactionState currentState = trx.getState();

        // 已经回滚或已提交，无需操作
        if (currentState == TransactionState.ROLLED_BACK) {
            return;
        }
        if (currentState == TransactionState.COMMITTED) {
            throw new IllegalStateException("Cannot rollback committed transaction");
        }

        try {
            // 状态转换: ACTIVE/COMMIT_PENDING -> ROLLBACK_PENDING
            if (currentState == TransactionState.ACTIVE ||
                currentState == TransactionState.COMMIT_PENDING) {
                // 直接设置状态（跳过验证，因为 COMMIT_PENDING -> ROLLBACK_PENDING 是特殊情况）
                trx.compareAndSetState(currentState, TransactionState.ROLLBACK_PENDING);
            }

            // 执行 Undo 操作
            if (undoLogManager != null) {
                Iterable<UndoRecord> undoRecords = undoLogManager.rollbackTransaction(trx);

                // R1: Undo 记录按逆序遍历（UndoLogManager 已保证顺序）
                int undoCount = 0;
                UndoApplier applier = this.undoApplier;

                for (UndoRecord record : undoRecords) {
                    // R3: 通过 UndoApplier 将 Undo 应用到数据页
                    if (applier != null) {
                        try {
                            applier.applyUndo(trx, record);
                        } catch (MiniDbException e) {
                            // R3: 应用失败必须传播，部分回滚 = 数据损坏
                            logger.error("Failed to apply undo record during rollback: trx={}, record={}",
                                    trx.getId(), record.getRollbackDescription(), e);
                            throw new RuntimeException(
                                    "Undo apply failed during rollback of trx " + trx.getId(), e);
                        }
                    }
                    undoCount++;
                    logger.trace("Applied undo record: {}", record.getRollbackDescription());
                }
                logger.debug("Applied {} undo records for rollback (applier={})",
                        undoCount, applier != null ? "active" : "none");
            }

            // 状态转换: ROLLBACK_PENDING -> ROLLED_BACK
            trx.setState(TransactionState.ROLLED_BACK);

            // 注销 ReadView（Purge 安全门控）
            ReadView cachedReadView = trx.getCachedReadView();
            if (cachedReadView != null) {
                trx.unregisterReadView(cachedReadView);
            }

            // 清除缓存的 ReadView
            trx.clearCachedReadView();

            // L-P4-1: 释放所有锁（在 Undo 回滚之后、activeTransactions.remove 之前）
            // L5: unlockAll 幂等
            if (lockManager != null) {
                lockManager.unlockAll(trx);
            }

            // 从活跃列表移除
            activeTransactions.remove(trx.getId());

            logger.debug("Transaction rolled back: trxId={}, duration={}ms",
                    trx.getId(), trx.getDuration());

        } catch (Exception e) {
            logger.error("Rollback failed: trxId={}", trx.getId(), e);
            throw new MiniDbException("Transaction rollback failed", e);
        }
    }

    // ==================== ReadView 方法 ====================

    /**
     * 为事务创建 ReadView
     *
     * <p>在 REPEATABLE READ 隔离级别下，事务首次快照读时创建 ReadView，
     * 之后复用同一个 ReadView。</p>
     *
     * <p>在 READ COMMITTED 隔离级别下，每次快照读都创建新的 ReadView。</p>
     *
     * @param trx 事务
     * @return ReadView
     */
    public ReadView createReadView(Transaction trx) {
        ensureInitialized();

        if (trx == null) {
            throw new NullPointerException("Transaction cannot be null");
        }
        if (!trx.isActive()) {
            throw new IllegalStateException("Cannot create ReadView for non-active transaction");
        }

        // 获取写锁以创建一致的快照
        activeTrxLock.writeLock().lock();
        try {
            // 获取活跃事务列表快照
            List<TransactionId> activeList = new ArrayList<>();
            TransactionId minActive = null;

            for (TransactionId id : activeTransactions.keySet()) {
                // 排除自己
                if (!id.equals(trx.getId())) {
                    activeList.add(id);
                    if (minActive == null || id.isBefore(minActive)) {
                        minActive = id;
                    }
                }
            }

            // 排序（ReadView 要求有序列表以支持二分查找）
            Collections.sort(activeList);

            // 计算 low_limit_id (下一个要分配的 TRX_ID)
            TransactionId lowLimitId = new TransactionId(nextTrxIdCache.get());

            // 计算 up_limit_id (最小活跃事务 ID)
            TransactionId upLimitId = minActive != null ? minActive : lowLimitId;

            return new ReadView(trx.getId(), lowLimitId, upLimitId, activeList);

        } finally {
            activeTrxLock.writeLock().unlock();
        }
    }

    // ==================== TRX_ID 分配 ====================

    /**
     * 分配一个新的 TRX_ID
     *
     * @return 新分配的 TRX_ID
     * @throws MiniDbException 如果分配失败
     */
    private TransactionId allocateTrxId() throws MiniDbException {
        // 快速路径：从缓存分配
        long id = nextTrxIdCache.getAndIncrement();
        if (id < trxIdCacheLimit) {
            return new TransactionId(id);
        }

        // 慢速路径：从系统页批量分配
        synchronized (trxIdAllocLock) {
            // Double-check
            id = nextTrxIdCache.get();
            if (id < trxIdCacheLimit) {
                return new TransactionId(nextTrxIdCache.getAndIncrement());
            }

            // 检查是否是内存模式
            if (trxIdCacheLimit == Long.MAX_VALUE) {
                return new TransactionId(nextTrxIdCache.getAndIncrement());
            }

            // 从系统页批量分配
            try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
                Page sysPage = mtr.getPage(sysPageId, BufferPool.FetchMode.READ_EXISTING);
                ByteBuffer buf = sysPage.getBuffer();

                TransactionId firstId = TransactionSysPage.allocateTrxIdBatch(buf, TRX_ID_BATCH_SIZE);
                mtr.markDirty(sysPage);
                mtr.commit();

                // 更新缓存
                nextTrxIdCache.set(firstId.getValue() + 1);
                trxIdCacheLimit = firstId.getValue() + TRX_ID_BATCH_SIZE;

                logger.debug("Allocated TRX_ID batch: start={}, limit={}",
                        firstId.getValue(), trxIdCacheLimit);

                return firstId;
            }
        }
    }

    // ==================== 查询方法 ====================

    /**
     * 获取事务
     *
     * @param trxId 事务 ID
     * @return 事务，如果不存在返回 null
     */
    public Transaction getTransaction(TransactionId trxId) {
        return activeTransactions.get(trxId);
    }

    /**
     * 获取活跃事务数量
     *
     * @return 活跃事务数量
     */
    public int getActiveTransactionCount() {
        return activeTransactions.size();
    }

    /**
     * 获取活跃事务 ID 列表
     *
     * @return 活跃事务 ID 列表（快照）
     */
    public List<TransactionId> getActiveTransactionIds() {
        return new ArrayList<>(activeTransactions.keySet());
    }

    /**
     * 检查事务是否活跃
     *
     * @param trxId 事务 ID
     * @return true 如果事务活跃
     */
    public boolean isTransactionActive(TransactionId trxId) {
        return activeTransactions.containsKey(trxId);
    }

    /**
     * 获取下一个 TRX_ID (不分配)
     *
     * @return 下一个 TRX_ID
     */
    public long getNextTrxId() {
        return nextTrxIdCache.get();
    }

    // ==================== Purge 安全门控方法 ====================

    /**
     * 获取 Purge 协调器
     *
     * @return Purge 协调器
     */
    public PurgeCoordinator getPurgeCoordinator() {
        ensureInitialized();
        if (purgeCoordinator == null) {
            synchronized (trxIdAllocLock) {
                if (purgeCoordinator == null) {
                    purgeCoordinator = new PurgeCoordinator(this);
                }
            }
        }
        return purgeCoordinator;
    }

    /**
     * 注册 ReadView 到 Purge 协调器
     *
     * <p>当创建 ReadView 时调用，确保 Purge 线程不会清理活跃 ReadView 需要的版本。</p>
     *
     * @param readView ReadView 对象
     */
    public void registerReadView(ReadView readView) {
        if (readView != null) {
            PurgeCoordinator coordinator = getPurgeCoordinator();
            coordinator.registerReadView(readView);
        }
    }

    /**
     * 注销 ReadView 从 Purge 协调器
     *
     * <p>当 ReadView 不再需要时调用（事务提交/回滚后）。</p>
     *
     * @param readView ReadView 对象
     */
    public void unregisterReadView(ReadView readView) {
        if (readView != null && purgeCoordinator != null) {
            purgeCoordinator.unregisterReadView(readView);
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 确保已初始化
     */
    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("TransactionManager not initialized");
        }
    }

    // ==================== Lock Manager 集成 ====================

    /**
     * 设置 Lock Manager
     *
     * <p>L-P4-4: LockManager 为可选组件，设置后 commit/rollback 自动释放锁。
     * 应在 TransactionManager 初始化后、处理事务之前调用。</p>
     *
     * @param lockManager Lock Manager 实例
     */
    public void setLockManager(LockManager lockManager) {
        this.lockManager = lockManager;
    }

    /**
     * 获取 Lock Manager
     *
     * @return Lock Manager，未设置时返回 null
     */
    public LockManager getLockManager() {
        return lockManager;
    }

    /**
     * 获取 Undo Log Manager
     *
     * @return UndoLogManager 实例，可能为 null
     */
    public UndoLogManager getUndoLogManager() {
        return undoLogManager;
    }

    // ==================== Undo Applier 集成 ====================

    /**
     * 设置 Undo 记录应用器
     *
     * <p>I-RB2: UndoApplier 为可选组件，设置后 rollback 会通过此接口
     * 将 Undo 记录实际应用到数据页。应在 TransactionManager 初始化后、
     * 处理事务之前调用。</p>
     *
     * @param undoApplier Undo 应用器实例
     */
    public void setUndoApplier(UndoApplier undoApplier) {
        this.undoApplier = undoApplier;
    }

    /**
     * 获取 Undo 记录应用器
     *
     * @return UndoApplier，未设置时返回 null
     */
    public UndoApplier getUndoApplier() {
        return undoApplier;
    }

    // ==================== 关闭方法 ====================

    /**
     * 关闭事务管理器
     *
     * <p>回滚所有活跃事务并释放资源。</p>
     */
    public void close() {
        int activeCount = activeTransactions.size();

        if (activeCount > 0) {
            logger.warn("Closing TransactionManager with {} active transactions", activeCount);

            // 回滚所有活跃事务
            for (Transaction trx : activeTransactions.values()) {
                try {
                    rollback(trx);
                } catch (Exception e) {
                    logger.error("Failed to rollback transaction during shutdown: {}", trx.getId(), e);
                }
            }
        }

        activeTransactions.clear();

        // 关闭 LockManager（停止 DeadlockDetector 后台线程等）
        if (lockManager != null) {
            lockManager.shutdown();
        }

        initialized = false;

        logger.info("TransactionManager closed");
    }

    @Override
    public String toString() {
        return String.format("TransactionManager{spaceId=%d, activeCount=%d, nextTrxId=%d}",
                spaceId, activeTransactions.size(), nextTrxIdCache.get());
    }
}
