package cn.zhangyis.minidb.storage.transaction.purge;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.core.TransactionManager;
import cn.zhangyis.minidb.storage.transaction.mvcc.ReadView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Purge 协调器
 *
 * <p>跟踪活跃的 ReadView，计算可以安全清理的 TRX_ID 边界。</p>
 *
 * <h2>核心概念</h2>
 * <p>在 MVCC 中，UPDATE/DELETE Undo 记录需要保留，直到没有任何活跃的
 * ReadView 需要它们。Purge 协调器维护所有活跃 ReadView 的最小 up_limit_id，
 * 只有 TRX_ID 小于此边界的记录才能被安全清理。</p>
 *
 * <h2>设计约束 (Invariants)</h2>
 * <ul>
 *   <li><b>P1</b>: 不能清理任何活跃 ReadView 可能需要的 Undo 记录</li>
 *   <li><b>P2</b>: purge_limit 是所有活跃 ReadView 的 up_limit_id 的最小值</li>
 *   <li><b>P3</b>: ReadView 关闭时必须从跟踪列表中移除</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * PurgeCoordinator coordinator = new PurgeCoordinator();
 *
 * // 创建 ReadView 时注册
 * ReadView rv = txnMgr.createReadView(trx);
 * coordinator.registerReadView(rv);
 *
 * // ReadView 关闭时注销
 * coordinator.unregisterReadView(rv);
 *
 * // 获取可清理边界
 * TransactionId purgeLimit = coordinator.getPurgeLimit();
 * }</pre>
 *
 * @author MiniDB
 * @version 1.0
 * @see ReadView
 * @see PurgeThread
 */
public class PurgeCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(PurgeCoordinator.class);

    // ==================== 字段 ====================

    /**
     * 活跃的 ReadView 列表
     *
     * <p>使用 CopyOnWriteArrayList 保证线程安全的遍历。</p>
     */
    private final CopyOnWriteArrayList<ReadView> activeReadViews;

    /**
     * 事务管理器引用
     */
    private final TransactionManager transactionManager;

    /**
     * 缓存的 Purge 边界
     *
     * <p>为了避免频繁计算，缓存最近计算的边界。</p>
     */
    private final AtomicReference<TransactionId> cachedPurgeLimit;

    /**
     * 缓存失效标记
     */
    private volatile boolean cacheInvalid;

    // ==================== 构造函数 ====================

    /**
     * 创建 Purge 协调器
     *
     * @param transactionManager 事务管理器
     */
    public PurgeCoordinator(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
        this.activeReadViews = new CopyOnWriteArrayList<>();
        this.cachedPurgeLimit = new AtomicReference<>(new TransactionId(0));
        this.cacheInvalid = true;
    }

    // ==================== ReadView 管理 ====================

    /**
     * 注册 ReadView
     *
     * <p>当创建新的 ReadView 时调用。</p>
     *
     * @param readView 新创建的 ReadView
     */
    public void registerReadView(ReadView readView) {
        if (readView == null) {
            throw new NullPointerException("readView cannot be null");
        }

        activeReadViews.add(readView);
        cacheInvalid = true;

        logger.trace("Registered ReadView: creator={}, upLimit={}",
                readView.getCreatorTrxId(), readView.getUpLimitId());
    }

    /**
     * 注销 ReadView
     *
     * <p>当 ReadView 不再需要时调用（事务提交/回滚后）。</p>
     *
     * @param readView 要注销的 ReadView
     */
    public void unregisterReadView(ReadView readView) {
        if (readView == null) {
            return;
        }

        boolean removed = activeReadViews.remove(readView);
        if (removed) {
            cacheInvalid = true;
            logger.trace("Unregistered ReadView: creator={}", readView.getCreatorTrxId());
        }
    }

    // ==================== Purge 边界计算 ====================

    /**
     * 获取 Purge 边界
     *
     * <p>返回可以安全清理的最大 TRX_ID。所有 TRX_ID 小于此值的
     * UPDATE/DELETE Undo 记录都可以被清理。</p>
     *
     * <p>计算方法：</p>
     * <ol>
     *   <li>如果没有活跃的 ReadView，返回当前最大 TRX_ID</li>
     *   <li>否则，返回所有活跃 ReadView 的 up_limit_id 的最小值</li>
     * </ol>
     *
     * @return Purge 边界 TRX_ID
     */
    public TransactionId getPurgeLimit() {
        if (!cacheInvalid) {
            return cachedPurgeLimit.get();
        }

        TransactionId limit = calculatePurgeLimit();
        cachedPurgeLimit.set(limit);
        cacheInvalid = false;

        return limit;
    }

    /**
     * 计算 Purge 边界
     */
    private TransactionId calculatePurgeLimit() {
        if (activeReadViews.isEmpty()) {
            // 没有活跃的 ReadView，可以清理到当前最大 TRX_ID
            long nextTrxId = transactionManager.getNextTrxId();
            return new TransactionId(nextTrxId - 1);
        }

        // 找最小的 up_limit_id
        TransactionId minUpLimit = null;

        for (ReadView rv : activeReadViews) {
            TransactionId upLimit = rv.getUpLimitId();
            if (minUpLimit == null || upLimit.isBefore(minUpLimit)) {
                minUpLimit = upLimit;
            }
        }

        return minUpLimit != null ? minUpLimit : new TransactionId(0);
    }

    /**
     * 检查 TRX_ID 是否可以被清理
     *
     * @param trxId 要检查的 TRX_ID
     * @return 如果可以清理返回 true
     */
    public boolean canPurge(TransactionId trxId) {
        TransactionId limit = getPurgeLimit();
        return trxId.isBefore(limit);
    }

    // ==================== 统计信息 ====================

    /**
     * 获取活跃 ReadView 数量
     *
     * @return 活跃 ReadView 数量
     */
    public int getActiveReadViewCount() {
        return activeReadViews.size();
    }

    /**
     * 获取活跃 ReadView 列表（快照）
     *
     * @return ReadView 列表
     */
    public List<ReadView> getActiveReadViews() {
        return List.copyOf(activeReadViews);
    }

    /**
     * 强制使缓存失效
     */
    public void invalidateCache() {
        cacheInvalid = true;
    }

    @Override
    public String toString() {
        return String.format("PurgeCoordinator{activeReadViews=%d, purgeLimit=%s}",
                activeReadViews.size(), getPurgeLimit());
    }
}
