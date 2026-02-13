package cn.zhangyis.minidb.storage.transaction.lock;

import cn.zhangyis.minidb.storage.transaction.core.TransactionId;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * 单个锁目标的请求队列
 *
 * <p>维护已授予和等待中的锁请求列表，实现带 FIFO 公平性的授予逻辑。</p>
 *
 * <h2>线程安全</h2>
 * <p>本类自身不做同步，由 {@link LockTableSegment} 的 segment 锁保护。
 * 所有方法的调用方必须持有所属 segment 的锁。</p>
 *
 * <h2>授予规则</h2>
 * <ol>
 *   <li>锁重入/升级 — 允许插队（同一事务已持有锁）</li>
 *   <li>FIFO 公平性 — waitingList 非空时，新请求必须排队（防止写饥饿, L10）</li>
 *   <li>waitingList 为空，检查与所有已授予锁的兼容性</li>
 *   <li>不兼容 → 排队等待</li>
 * </ol>
 *
 * <h2>设计约束</h2>
 * <ul>
 *   <li><b>L7</b>: grantedList 和 waitingList 同时为空时标记 empty，由 LockTableSegment 清理</li>
 *   <li><b>L9</b>: 升级请求存在不兼容持有者时立即返回 DEADLOCK，禁止进入等待队列</li>
 *   <li><b>L10</b>: waitingList 非空时，除锁重入外，新请求必须排队</li>
 *   <li><b>FIFO-strict grantWaiters</b>: 遇到第一个不兼容等待者即停止，不可跳过</li>
 * </ul>
 */
public class LockRequestQueue {

    /** 已授予的锁请求 */
    private final List<LockRequest> grantedList = new ArrayList<>();

    /** 等待中的锁请求（FIFO 顺序） */
    private final List<LockRequest> waitingList = new ArrayList<>();

    /** 空队列标志 (L7): grantedList 和 waitingList 均为空时置 true */
    private boolean empty = false;

    // ==================== 核心授予逻辑 ====================

    /**
     * 尝试授予锁请求
     *
     * <p>授予规则:</p>
     * <ol>
     *   <li>锁重入: 同一事务已持有的锁覆盖请求的 lockType 且 mode 更强 → 直接返回 GRANTED</li>
     *   <li>锁升级: 同一事务已持有锁但需要升级 mode 或合并 lockType → 尝试升级（L9）</li>
     *   <li>FIFO 公平性: waitingList 非空 → 排队等待（L10）</li>
     *   <li>兼容性检查: 与所有已授予锁兼容（考虑 LockType + LockMode）→ 授予</li>
     *   <li>不兼容 → 排队等待</li>
     * </ol>
     *
     * @param newRequest 新的锁请求
     * @return GRANTED / WAIT / DEADLOCK
     */
    public LockResult tryGrant(LockRequest newRequest) {
        // 规则 1 & 2: 锁重入/升级 — 允许插队
        LockRequest existingGrant = findGrant(newRequest.getTrxId());
        if (existingGrant != null) {
            // L-P5-4: 检查 lockType 覆盖 + mode 强度
            if (existingGrant.getLockType().covers(newRequest.getLockType())
                    && existingGrant.getMode().isStrongerOrEqual(newRequest.getMode())) {
                return LockResult.GRANTED;  // 已持有覆盖的锁，无需操作
            }
            return tryUpgrade(existingGrant, newRequest);
        }

        // 规则 3: FIFO 公平性 — waitingList 非空时，新请求必须排队 (L10)
        if (!waitingList.isEmpty()) {
            addToWait(newRequest);
            return LockResult.WAIT;
        }

        // 规则 4: waitingList 为空，检查与所有已授予锁的兼容性
        if (isCompatibleWithAllGranted(newRequest)) {
            addToGranted(newRequest);
            return LockResult.GRANTED;
        }

        // 规则 5: 不兼容，排队
        addToWait(newRequest);
        return LockResult.WAIT;
    }

    /**
     * 尝试锁升级（mode 升级 + lockType 合并）
     *
     * <p>L9 不变量: 如果存在其他事务的已授予锁与升级后的目标不兼容，
     * 立即返回 DEADLOCK，禁止进入等待队列。</p>
     *
     * <p>L-P5-4: 同一事务持有 RECORD S 后请求 GAP S → 合并为 NEXT_KEY S。</p>
     *
     * @param existingGrant 已持有的锁请求（在 grantedList 中）
     * @param newRequest    新的锁请求（包含目标 mode 和 lockType）
     * @return GRANTED（升级成功）或 DEADLOCK
     */
    private LockResult tryUpgrade(LockRequest existingGrant, LockRequest newRequest) {
        TransactionId myTrxId = existingGrant.getTrxId();
        LockMode targetMode = existingGrant.getMode().isStrongerOrEqual(newRequest.getMode())
                ? existingGrant.getMode() : newRequest.getMode();
        LockType targetType = existingGrant.getLockType().mergeWith(newRequest.getLockType());

        // 构造一个虚拟请求用于兼容性检查
        // 检查所有其他持有者是否与升级后的目标兼容
        for (LockRequest r : grantedList) {
            if (!r.getTrxId().equals(myTrxId)) {
                // 使用类型兼容矩阵 + mode 兼容矩阵
                if (r.getLockType().isRecordLevel() && targetType.isRecordLevel()) {
                    int typeCompat = LockType.recordTypeCompatibility(r.getLockType(), targetType);
                    if (typeCompat == -1) {
                        return LockResult.DEADLOCK;
                    }
                    if (typeCompat == 0 && !r.getMode().isCompatibleWith(targetMode)) {
                        return LockResult.DEADLOCK;
                    }
                } else {
                    if (!r.getMode().isCompatibleWith(targetMode)) {
                        return LockResult.DEADLOCK;
                    }
                }
            }
        }

        // 所有其他持有者兼容 → 原地升级 mode 和 lockType
        if (!existingGrant.getMode().isStrongerOrEqual(newRequest.getMode())) {
            existingGrant.upgradeMode(targetMode);
        }
        if (existingGrant.getLockType() != targetType) {
            existingGrant.upgradeLockType(targetType);
        }
        return LockResult.GRANTED;
    }

    // ==================== 释放逻辑 ====================

    /**
     * 释放指定事务在此队列中的所有锁
     *
     * <p>从 grantedList 和 waitingList 中移除该事务的请求，
     * 然后尝试唤醒等待者。最后检查空队列标志 (L7)。</p>
     *
     * @param trxId 要释放的事务 ID
     */
    public void release(TransactionId trxId) {
        // 从 grantedList 移除
        grantedList.removeIf(r -> r.getTrxId().equals(trxId));

        // 从 waitingList 移除（处理事务中止时尚未授予的请求）
        waitingList.removeIf(r -> r.getTrxId().equals(trxId));

        // 授予等待者
        grantWaiters();

        // L7: 检查空队列
        if (grantedList.isEmpty() && waitingList.isEmpty()) {
            this.empty = true;
        }
    }

    /**
     * 取消指定事务的等待请求
     *
     * <p>用于超时或死锁检测中止等待。</p>
     *
     * @param trxId 要取消等待的事务 ID
     */
    public void cancelWait(TransactionId trxId) {
        waitingList.removeIf(r -> r.getTrxId().equals(trxId));

        // 取消等待后，后续等待者可能可以被授予
        grantWaiters();

        // L7: 检查空队列
        if (grantedList.isEmpty() && waitingList.isEmpty()) {
            this.empty = true;
        }
    }

    // ==================== 等待者唤醒 ====================

    /**
     * 尝试按 FIFO 顺序授予等待中的请求
     *
     * <p>FIFO-strict: 从队列头部开始，遇到第一个与当前已授予锁不兼容的等待者即停止。
     * 不可跳过不兼容等待者去授予后续兼容者，否则会导致写饥饿。</p>
     *
     * <p>已授予的等待者会被 markGranted (volatile write) 并 unpark，
     * 等待线程的 park 循环会检测到状态变更。</p>
     */
    private void grantWaiters() {
        Iterator<LockRequest> it = waitingList.iterator();
        while (it.hasNext()) {
            LockRequest waiter = it.next();

            if (isCompatibleWithAllGranted(waiter)) {
                it.remove();
                addToGranted(waiter);

                // 唤醒等待线程
                Thread t = waiter.getWaitingThread();
                if (t != null) {
                    LockSupport.unpark(t);
                }
            } else {
                // FIFO-strict: 遇到不兼容即停止，防止写饥饿
                break;
            }
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 查找指定事务在 grantedList 中的锁请求
     *
     * <p>每个事务对同一目标最多持有一个锁（重入/升级时原地修改）。</p>
     *
     * @param trxId 事务 ID
     * @return 已授予的锁请求，未找到返回 null
     */
    private LockRequest findGrant(TransactionId trxId) {
        for (LockRequest r : grantedList) {
            if (r.getTrxId().equals(trxId)) {
                return r;
            }
        }
        return null;
    }

    /**
     * 检查请求是否与所有已授予锁兼容（考虑 LockType + LockMode）
     *
     * <p>遍历 grantedList 逐项检查，使用 {@link LockRequest#isCompatibleWith} 综合判断
     * LockType 类型兼容矩阵和 LockMode 兼容矩阵。</p>
     *
     * @param requested 请求的锁
     * @return true 如果与所有已授予锁兼容（空列表返回 true）
     */
    private boolean isCompatibleWithAllGranted(LockRequest requested) {
        for (LockRequest granted : grantedList) {
            if (!granted.isCompatibleWith(requested)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将请求加入已授予列表并标记为 GRANTED
     */
    private void addToGranted(LockRequest request) {
        request.markGranted();
        grantedList.add(request);
    }

    /**
     * 将请求加入等待列表（FIFO 尾部）
     */
    private void addToWait(LockRequest request) {
        waitingList.add(request);
    }

    // ==================== 状态查询 ====================

    /**
     * 队列是否为空 (L7)
     *
     * @return true 如果 grantedList 和 waitingList 均为空
     */
    public boolean isEmpty() {
        return empty;
    }

    /**
     * 获取已授予列表（供 Phase 3 死锁检测器遍历 wait-for 关系）
     *
     * @return 已授予的锁请求列表（直接引用，调用方必须持有 segment 锁）
     */
    public List<LockRequest> getGrantedList() {
        return grantedList;
    }

    /**
     * 获取等待列表（供 Phase 3 死锁检测器遍历 wait-for 关系）
     *
     * @return 等待中的锁请求列表（直接引用，调用方必须持有 segment 锁）
     */
    public List<LockRequest> getWaitingList() {
        return waitingList;
    }

    @Override
    public String toString() {
        return String.format("LockRequestQueue(granted=%d, waiting=%d, empty=%s)",
                grantedList.size(), waitingList.size(), empty);
    }
}
