package cn.zhangyis.minidb.storage.transaction.mvcc;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 读视图 (ReadView) - MVCC 核心数据结构
 *
 * <p>ReadView 用于判断某个记录版本对当前事务是否可见。
 * 在 REPEATABLE READ 隔离级别下，事务首次快照读时创建 ReadView，之后复用。</p>
 *
 * <h2>可见性判断算法</h2>
 * <pre>
 * isVisible(trx_id):
 *   1. trx_id == creator_trx_id       → 可见（自己的修改）
 *   2. trx_id &lt; up_limit_id           → 可见（已提交的旧事务）
 *   3. trx_id &gt;= low_limit_id         → 不可见（创建 ReadView 后开始的事务）
 *   4. trx_id in active_list          → 不可见（创建时正在执行的事务）
 *   5. else                           → 可见（已提交的事务）
 * </pre>
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li><b>creatorTrxId</b>: 创建此 ReadView 的事务 ID</li>
 *   <li><b>upLimitId</b>: 活跃事务列表中最小的 ID（小于此值的事务都已提交）</li>
 *   <li><b>lowLimitId</b>: 下一个要分配的事务 ID（大于等于此值的事务对本 ReadView 不可见）</li>
 *   <li><b>activeTrxIds</b>: 创建 ReadView 时正在执行的事务 ID 列表（有序）</li>
 * </ul>
 *
 * <h2>设计约束 (Invariants)</h2>
 * <ul>
 *   <li><b>T4</b>: ReadView 创建后不可变</li>
 *   <li><b>活跃列表有序</b>: 便于二分查找</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 创建 ReadView
 * ReadView readView = transactionManager.createReadView(currentTrx);
 *
 * // 判断记录可见性
 * TransactionId recordTrxId = getRecordTrxId(record);
 * if (readView.isVisible(recordTrxId)) {
 *     // 当前版本可见，返回
 *     return record;
 * } else {
 *     // 沿版本链查找可见版本
 *     return findVisibleVersion(record.getRollPtr(), readView);
 * }
 * }</pre>
 *
 * <h2>InnoDB 对应</h2>
 * <p>对应 InnoDB 的 ReadView 结构 (read0types.h)</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class ReadView {

    // ==================== 字段 ====================

    /**
     * 创建此 ReadView 的事务 ID
     */
    private final TransactionId creatorTrxId;

    /**
     * 高水位：所有 >= 此 ID 的事务对本 ReadView 不可见
     *
     * <p>创建 ReadView 时的下一个要分配的事务 ID</p>
     */
    private final TransactionId lowLimitId;

    /**
     * 低水位：所有 < 此 ID 的已提交事务对本 ReadView 可见
     *
     * <p>创建 ReadView 时最小的活跃事务 ID。
     * 如果没有活跃事务，等于 lowLimitId。</p>
     */
    private final TransactionId upLimitId;

    /**
     * 活跃事务 ID 列表（有序）
     *
     * <p>创建 ReadView 时正在执行的事务 ID。
     * 这些事务虽然 ID 可能 < lowLimitId，但因为还未提交，对本 ReadView 不可见。</p>
     */
    private final List<TransactionId> activeTrxIds;

    /**
     * 创建时间戳（用于调试和统计）
     */
    private final long createTime;

    // ==================== 构造函数 ====================

    /**
     * 创建 ReadView
     *
     * <p>通常由 TransactionManager.createReadView() 调用</p>
     *
     * @param creatorTrxId 创建者事务 ID
     * @param lowLimitId   高水位 (下一个要分配的事务 ID)
     * @param upLimitId    低水位 (最小活跃事务 ID)
     * @param activeTrxIds 活跃事务 ID 列表
     */
    public ReadView(TransactionId creatorTrxId,
                    TransactionId lowLimitId,
                    TransactionId upLimitId,
                    List<TransactionId> activeTrxIds) {
        this.creatorTrxId = creatorTrxId;
        this.lowLimitId = lowLimitId;
        this.upLimitId = upLimitId;
        // 创建不可变副本
        this.activeTrxIds = activeTrxIds != null
                ? Collections.unmodifiableList(new ArrayList<>(activeTrxIds))
                : Collections.emptyList();
        this.createTime = System.currentTimeMillis();
    }

    // ==================== 可见性判断 ====================

    /**
     * 判断某个事务 ID 对本 ReadView 是否可见
     *
     * <h3>算法</h3>
     * <pre>
     * 1. trx_id == creator_trx_id → 可见（自己的修改总是可见）
     * 2. trx_id &lt; up_limit_id     → 可见（该事务在创建 ReadView 前已提交）
     * 3. trx_id &gt;= low_limit_id   → 不可见（该事务在创建 ReadView 后开始）
     * 4. trx_id in active_list    → 不可见（该事务在创建时还未提交）
     * 5. else                     → 可见（该事务在创建前已提交）
     * </pre>
     *
     * @param trxId 记录的事务 ID
     * @return true 如果该版本对当前事务可见
     */
    public boolean isVisible(TransactionId trxId) {
        // 无效事务 ID 视为可见（系统记录等）
        if (!trxId.isValid()) {
            return true;
        }

        // 1. 自己的修改总是可见
        if (trxId.equals(creatorTrxId)) {
            return true;
        }

        // 2. trx_id < up_limit_id: 该事务在创建 ReadView 前已提交，可见
        if (trxId.isBefore(upLimitId)) {
            return true;
        }

        // 3. trx_id >= low_limit_id: 该事务在创建 ReadView 后开始，不可见
        if (trxId.isAfterOrEqual(lowLimitId)) {
            return false;
        }

        // 4. up_limit_id <= trx_id < low_limit_id: 需要检查活跃列表
        //    如果在活跃列表中，说明该事务在创建 ReadView 时还未提交，不可见
        //    如果不在活跃列表中，说明该事务在创建 ReadView 前已提交，可见
        return !isInActiveList(trxId);
    }

    /**
     * 二分查找检查是否在活跃列表中
     *
     * @param trxId 事务 ID
     * @return true 如果在活跃列表中
     */
    private boolean isInActiveList(TransactionId trxId) {
        if (activeTrxIds.isEmpty()) {
            return false;
        }
        int idx = Collections.binarySearch(activeTrxIds, trxId);
        return idx >= 0;
    }

    // ==================== 访问方法 ====================

    /**
     * 获取创建者事务 ID
     *
     * @return 创建者事务 ID
     */
    public TransactionId getCreatorTrxId() {
        return creatorTrxId;
    }

    /**
     * 获取高水位 (low_limit_id)
     *
     * <p>所有 >= 此 ID 的事务对本 ReadView 不可见</p>
     *
     * @return 高水位事务 ID
     */
    public TransactionId getLowLimitId() {
        return lowLimitId;
    }

    /**
     * 获取低水位 (up_limit_id)
     *
     * <p>所有 < 此 ID 的已提交事务对本 ReadView 可见</p>
     *
     * @return 低水位事务 ID
     */
    public TransactionId getUpLimitId() {
        return upLimitId;
    }

    /**
     * 获取活跃事务列表
     *
     * @return 活跃事务 ID 列表（不可变）
     */
    public List<TransactionId> getActiveTrxIds() {
        return activeTrxIds;
    }

    /**
     * 获取活跃事务数量
     *
     * @return 活跃事务数量
     */
    public int getActiveCount() {
        return activeTrxIds.size();
    }

    /**
     * 获取创建时间
     *
     * @return 创建时间戳（毫秒）
     */
    public long getCreateTime() {
        return createTime;
    }

    /**
     * 检查是否有活跃事务
     *
     * @return true 如果有活跃事务
     */
    public boolean hasActiveTransactions() {
        return !activeTrxIds.isEmpty();
    }

    // ==================== Object 方法 ====================

    @Override
    public String toString() {
        return String.format(
                "ReadView{creator=%s, upLimit=%s, lowLimit=%s, activeCount=%d}",
                creatorTrxId, upLimitId, lowLimitId, activeTrxIds.size());
    }

    /**
     * 详细信息（用于调试）
     *
     * @return 详细信息字符串
     */
    public String toDetailString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ReadView{\n");
        sb.append("  creatorTrxId: ").append(creatorTrxId).append("\n");
        sb.append("  upLimitId: ").append(upLimitId).append("\n");
        sb.append("  lowLimitId: ").append(lowLimitId).append("\n");
        sb.append("  createTime: ").append(createTime).append("\n");
        sb.append("  activeTrxIds: [");
        for (int i = 0; i < activeTrxIds.size(); i++) {
            if (i > 0) sb.append(", ");
            if (i >= 10) {
                sb.append("... and ").append(activeTrxIds.size() - 10).append(" more");
                break;
            }
            sb.append(activeTrxIds.get(i));
        }
        sb.append("]\n}");
        return sb.toString();
    }
}
