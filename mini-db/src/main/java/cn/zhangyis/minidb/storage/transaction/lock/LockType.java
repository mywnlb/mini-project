package cn.zhangyis.minidb.storage.transaction.lock;

/**
 * 锁类型枚举
 *
 * <p>定义锁保护的资源范围和粒度。</p>
 *
 * <h2>Record-Level 锁类型</h2>
 * <ul>
 *   <li>{@link #RECORD} - 行锁，仅锁定记录本身（不含间隙）</li>
 *   <li>{@link #GAP} - 间隙锁，仅锁定记录前面的间隙（不含记录）</li>
 *   <li>{@link #NEXT_KEY} - Next-Key 锁 = Record Lock + Gap Lock（锁定记录及其前面的间隙）</li>
 *   <li>{@link #INSERT_INTENTION} - 插入意向锁，INSERT 操作使用的特殊间隙锁</li>
 * </ul>
 *
 * <h2>Table-Level 锁类型</h2>
 * <ul>
 *   <li>{@link #TABLE} - 表锁，保护整张表</li>
 * </ul>
 *
 * <h2>Record-Level 类型兼容矩阵</h2>
 * <pre>
 *                     RECORD   GAP   NEXT_KEY   INSERT_INTENTION
 *   RECORD             MODE    COMPAT   MODE       COMPAT
 *   GAP               COMPAT  COMPAT  COMPAT     INCOMPAT  ← GAP 阻塞 INSERT_INTENTION
 *   NEXT_KEY            MODE   COMPAT   MODE      INCOMPAT  ← NEXT_KEY 阻塞 INSERT_INTENTION
 *   INSERT_INTENTION   COMPAT  COMPAT  COMPAT      COMPAT   ← INSERT_INTENTION 不阻塞任何锁
 * </pre>
 *
 * <p>MODE = 需要检查 LockMode 兼容性；COMPAT = 始终兼容；INCOMPAT = 始终不兼容</p>
 *
 * <h2>设计约束</h2>
 * <ul>
 *   <li><b>L-P5-1</b>: GAP 锁互不冲突（矩阵中 GAP vs GAP = COMPAT）</li>
 *   <li><b>L-P5-2</b>: INSERT_INTENTION 被 GAP/NEXT_KEY 阻塞（矩阵不对称）</li>
 *   <li><b>L-P5-3</b>: recordTypeCompatibility 通过二维数组查表实现，无条件分支</li>
 *   <li><b>L-P5-5</b>: 所有 record-level 锁类型使用相同的 LockTarget(forRecord)，
 *                       锁类型仅存储在 LockRequest 中</li>
 * </ul>
 */
public enum LockType {

    /** 行锁 — 仅锁定记录本身 */
    RECORD(0),

    /** 表锁 */
    TABLE(-1),

    /** 间隙锁 — 锁定记录前面的间隙，不锁定记录本身 */
    GAP(1),

    /** Next-Key 锁 — Record Lock + Gap Lock，锁定记录及其前面的间隙 */
    NEXT_KEY(2),

    /** 插入意向锁 — INSERT 操作使用的特殊间隙锁，与 GAP/NEXT_KEY 冲突 */
    INSERT_INTENTION(3);

    /** record-level 类型兼容矩阵索引，TABLE 为 -1（不参与 record-level 兼容性检查） */
    private final int recTypeIndex;

    /**
     * Record-Level 类型兼容矩阵
     *
     * <p>REC_TYPE_COMPAT[held][requested]:</p>
     * <ul>
     *   <li>0 = CHECK_MODE（需要进一步检查 LockMode 兼容性）</li>
     *   <li>1 = ALWAYS_COMPATIBLE（无论 LockMode 都兼容）</li>
     *   <li>-1 = ALWAYS_INCOMPATIBLE（无论 LockMode 都不兼容）</li>
     * </ul>
     *
     * <p>L-P5-3: 纯查表操作，无条件分支。</p>
     *
     * <p>索引映射: RECORD=0, GAP=1, NEXT_KEY=2, INSERT_INTENTION=3</p>
     */
    private static final int[][] REC_TYPE_COMPAT = {
        //                  RECORD  GAP  NEXT_KEY  INSERT_INTENTION
        /* RECORD */      {   0,     1,     0,         1    },
        /* GAP */         {   1,     1,     1,        -1    },
        /* NEXT_KEY */    {   0,     1,     0,        -1    },
        /* INSERT_INT */  {   1,     1,     1,         1    },
    };

    LockType(int recTypeIndex) {
        this.recTypeIndex = recTypeIndex;
    }

    /**
     * 查询两个 record-level 锁类型的兼容性
     *
     * <p>L-P5-3: 纯查表操作，无条件分支。</p>
     *
     * @param held      已持有的锁类型
     * @param requested 请求的锁类型
     * @return 0 = 需要检查 LockMode 兼容性，1 = 始终兼容，-1 = 始终不兼容
     * @throws IllegalArgumentException 如果任一参数不是 record-level 类型
     */
    public static int recordTypeCompatibility(LockType held, LockType requested) {
        return REC_TYPE_COMPAT[held.recTypeIndex][requested.recTypeIndex];
    }

    /**
     * 是否为 record-level 锁类型
     *
     * @return true 如果是 RECORD, GAP, NEXT_KEY 或 INSERT_INTENTION
     */
    public boolean isRecordLevel() {
        return recTypeIndex >= 0;
    }

    /**
     * 当前锁类型是否覆盖请求的锁类型
     *
     * <p>L-P5-4: NEXT_KEY 覆盖 RECORD 和 GAP。
     * 同类型总是覆盖自身。其他锁类型只覆盖自身。</p>
     *
     * <p>注意: NEXT_KEY 不覆盖 INSERT_INTENTION。虽然持有 NEXT_KEY 已完全锁住该区域，
     * 但 INSERT_INTENTION 的语义是"允许多事务并行插入同一间隙"，与 NEXT_KEY 的保护语义不同。
     * 不覆盖可保证在未来支持锁降级/部分释放时，INSERT_INTENTION 不会被意外跳过。</p>
     *
     * <p>用于锁重入检查：如果已持有覆盖请求类型的锁，无需再次加锁。</p>
     *
     * @param requested 请求的锁类型
     * @return true 如果当前类型覆盖请求类型
     */
    public boolean covers(LockType requested) {
        if (this == requested) {
            return true;
        }
        if (this == NEXT_KEY) {
            return requested == RECORD || requested == GAP;
        }
        return false;
    }

    /**
     * 合并两个锁类型（用于同一事务的锁升级）
     *
     * <p>L-P5-4: RECORD + GAP → NEXT_KEY。</p>
     *
     * <p>合并规则:</p>
     * <ul>
     *   <li>相同类型 → 返回自身</li>
     *   <li>任一方为 NEXT_KEY → NEXT_KEY</li>
     *   <li>RECORD + GAP → NEXT_KEY</li>
     *   <li>其他组合 → NEXT_KEY（保守合并）</li>
     * </ul>
     *
     * @param other 另一个锁类型
     * @return 合并后的锁类型
     */
    public LockType mergeWith(LockType other) {
        if (this == other) {
            return this;
        }
        if (this == NEXT_KEY || other == NEXT_KEY) {
            return NEXT_KEY;
        }
        if ((this == RECORD && other == GAP) || (this == GAP && other == RECORD)) {
            return NEXT_KEY;
        }
        // 其他组合（如 RECORD + INSERT_INTENTION）→ NEXT_KEY（保守）
        return NEXT_KEY;
    }

    /**
     * 获取 record-level 类型兼容矩阵索引
     *
     * @return 索引值，TABLE 返回 -1
     */
    public int getRecTypeIndex() {
        return recTypeIndex;
    }
}
