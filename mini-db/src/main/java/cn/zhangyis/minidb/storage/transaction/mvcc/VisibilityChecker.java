package cn.zhangyis.minidb.storage.transaction.mvcc;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;

/**
 * MVCC 可见性检查器
 *
 * <p>封装 MVCC 可见性判断逻辑，判断记录版本对给定 ReadView 是否可见。</p>
 *
 * <h2>可见性算法</h2>
 * <pre>
 * isVisible(trx_id, readView):
 *   1. trx_id 无效 (== 0)           → 可见 (系统记录)
 *   2. trx_id == creator_trx_id     → 可见 (自己的修改)
 *   3. trx_id < up_limit_id         → 可见 (已提交的旧事务)
 *   4. trx_id >= low_limit_id       → 不可见 (创建 ReadView 后开始的事务)
 *   5. trx_id in active_list        → 不可见 (创建时正在执行的事务)
 *   6. else                         → 可见 (已提交的事务)
 * </pre>
 *
 * <h2>设计约束 (Invariants)</h2>
 * <ul>
 *   <li><b>V1</b>: 可见性判断必须严格遵循算法，不可简化步骤</li>
 *   <li><b>V2</b>: 无效 trxId (0) 视为系统记录，始终可见</li>
 *   <li><b>V3</b>: ReadView 不可为 null</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * ReadView readView = transactionManager.createReadView(currentTrx);
 * TransactionId recordTrxId = record.getTrxId();
 *
 * if (VisibilityChecker.isVisible(recordTrxId, readView)) {
 *     // 当前版本可见
 *     return record;
 * } else {
 *     // 需要沿版本链查找可见版本
 *     return versionChainReader.findVisibleVersion(record, readView);
 * }
 * }</pre>
 *
 * <h2>InnoDB 对应</h2>
 * <p>对应 InnoDB 的 changes_visible() 函数 (read0types.cc)</p>
 *
 * @author MiniDB
 * @version 1.0
 * @see ReadView
 */
public final class VisibilityChecker {

    // ==================== 私有构造函数 ====================

    /**
     * 工具类，禁止实例化
     */
    private VisibilityChecker() {
        throw new UnsupportedOperationException("Utility class");
    }

    // ==================== 核心方法 ====================

    /**
     * 判断记录版本是否对 ReadView 可见
     *
     * <h3>算法步骤</h3>
     * <ol>
     *   <li>无效 trxId (0) 视为系统记录，始终可见</li>
     *   <li>自己的修改总是可见</li>
     *   <li>trx_id < up_limit_id: 该事务在创建 ReadView 前已提交，可见</li>
     *   <li>trx_id >= low_limit_id: 该事务在创建 ReadView 后开始，不可见</li>
     *   <li>检查活跃列表，在其中则不可见，否则可见</li>
     * </ol>
     *
     * @param trxId    记录的事务 ID
     * @param readView 读视图
     * @return true 如果该版本可见
     * @throws NullPointerException 如果 trxId 或 readView 为 null
     */
    public static boolean isVisible(TransactionId trxId, ReadView readView) {
        if (trxId == null) {
            throw new NullPointerException("trxId cannot be null");
        }
        if (readView == null) {
            throw new NullPointerException("readView cannot be null");
        }

        // 直接委托给 ReadView 的 isVisible 方法
        // ReadView 已经实现了完整的可见性算法
        return readView.isVisible(trxId);
    }

    /**
     * 判断记录版本是否对 ReadView 可见 (原始值版本)
     *
     * <p>用于不需要创建 TransactionId 对象的场景，提升性能。</p>
     *
     * @param trxIdValue 事务 ID 值
     * @param readView   读视图
     * @return true 如果该版本可见
     */
    public static boolean isVisible(long trxIdValue, ReadView readView) {
        if (readView == null) {
            throw new NullPointerException("readView cannot be null");
        }

        // 无效 trxId 视为系统记录，始终可见
        if (trxIdValue <= 0) {
            return true;
        }

        TransactionId trxId = new TransactionId(trxIdValue);
        return readView.isVisible(trxId);
    }

    /**
     * 判断是否需要遍历版本链
     *
     * <p>如果当前版本不可见，且存在版本链 (rollPtr 非空)，则需要遍历。</p>
     *
     * @param trxId     记录的事务 ID
     * @param readView  读视图
     * @param hasOldVersion 是否存在旧版本 (rollPtr 非空)
     * @return true 如果需要遍历版本链
     */
    public static boolean needsVersionChainTraversal(TransactionId trxId,
                                                     ReadView readView,
                                                     boolean hasOldVersion) {
        if (!hasOldVersion) {
            return false;
        }
        return !isVisible(trxId, readView);
    }

    // ==================== 可见性分析 (调试用) ====================

    /**
     * 可见性判断结果
     */
    public enum VisibilityResult {
        /**
         * 可见：无效的事务 ID (系统记录)
         */
        VISIBLE_INVALID_TRX_ID,

        /**
         * 可见：创建者自己的修改
         */
        VISIBLE_CREATOR,

        /**
         * 可见：事务 ID 小于低水位，已提交的旧事务
         */
        VISIBLE_BELOW_UP_LIMIT,

        /**
         * 可见：不在活跃列表中，已提交的事务
         */
        VISIBLE_COMMITTED,

        /**
         * 不可见：事务 ID 大于等于高水位，创建 ReadView 后开始
         */
        NOT_VISIBLE_ABOVE_LOW_LIMIT,

        /**
         * 不可见：在活跃列表中，事务还未提交
         */
        NOT_VISIBLE_IN_ACTIVE_LIST
    }

    /**
     * 分析可见性判断结果 (用于调试和测试)
     *
     * <p>返回详细的可见性判断原因，便于调试和理解 MVCC 行为。</p>
     *
     * @param trxId    记录的事务 ID
     * @param readView 读视图
     * @return 可见性判断结果
     */
    public static VisibilityResult analyzeVisibility(TransactionId trxId, ReadView readView) {
        if (trxId == null || readView == null) {
            throw new NullPointerException("Arguments cannot be null");
        }

        // 1. 无效事务 ID
        if (!trxId.isValid()) {
            return VisibilityResult.VISIBLE_INVALID_TRX_ID;
        }

        // 2. 创建者自己的修改
        if (trxId.equals(readView.getCreatorTrxId())) {
            return VisibilityResult.VISIBLE_CREATOR;
        }

        // 3. 小于低水位
        if (trxId.isBefore(readView.getUpLimitId())) {
            return VisibilityResult.VISIBLE_BELOW_UP_LIMIT;
        }

        // 4. 大于等于高水位
        if (trxId.isAfterOrEqual(readView.getLowLimitId())) {
            return VisibilityResult.NOT_VISIBLE_ABOVE_LOW_LIMIT;
        }

        // 5. 检查活跃列表
        if (isInActiveList(trxId, readView)) {
            return VisibilityResult.NOT_VISIBLE_IN_ACTIVE_LIST;
        }

        // 6. 已提交的事务
        return VisibilityResult.VISIBLE_COMMITTED;
    }

    /**
     * 检查事务 ID 是否在活跃列表中
     *
     * @param trxId    事务 ID
     * @param readView 读视图
     * @return true 如果在活跃列表中
     */
    private static boolean isInActiveList(TransactionId trxId, ReadView readView) {
        // 使用二分查找 (ReadView 中的列表是有序的)
        return java.util.Collections.binarySearch(
                readView.getActiveTrxIds(), trxId) >= 0;
    }

    /**
     * 获取可见性判断的调试信息
     *
     * @param trxId    记录的事务 ID
     * @param readView 读视图
     * @return 调试信息字符串
     */
    public static String getDebugInfo(TransactionId trxId, ReadView readView) {
        VisibilityResult result = analyzeVisibility(trxId, readView);
        boolean visible = isVisible(trxId, readView);

        return String.format(
                "Visibility{trxId=%s, readView=%s, result=%s, visible=%s}",
                trxId, readView, result, visible);
    }
}
