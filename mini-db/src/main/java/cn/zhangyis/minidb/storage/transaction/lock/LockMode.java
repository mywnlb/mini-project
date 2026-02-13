package cn.zhangyis.minidb.storage.transaction.lock;

/**
 * 锁模式枚举 + 兼容矩阵
 *
 * <p>定义事务级逻辑锁的四种模式，并通过二维数组实现兼容矩阵。</p>
 *
 * <h2>兼容矩阵</h2>
 * <pre>
 *          S      X      IS     IX
 *   S    true   false   true   false
 *   X    false  false   false  false
 *   IS   true   false   true   true
 *   IX   false  false   true   true
 * </pre>
 *
 * <h2>设计约束</h2>
 * <ul>
 *   <li><b>L3</b>: isCompatibleWith 是纯查表操作，不含任何条件分支</li>
 *   <li>ordinal 顺序必须和 COMPAT_MATRIX 索引对齐</li>
 *   <li>添加新枚举值时必须同步扩展 COMPAT_MATRIX</li>
 * </ul>
 *
 * <h2>锁强度偏序</h2>
 * <pre>
 *   X > IX > IS
 *   X > S  > IS
 * </pre>
 */
public enum LockMode {

    SHARED(0),
    EXCLUSIVE(1),
    INTENTION_SHARED(2),
    INTENTION_EXCLUSIVE(3);

    private final int index;

    /**
     * 二维数组兼容矩阵，COMPAT_MATRIX[held][requested]
     *
     * <p>L3 不变量: isCompatibleWith 通过查表实现，禁止 if-else 链。</p>
     */
    private static final boolean[][] COMPAT_MATRIX = {
        //             S      X      IS     IX
        /* S  */  { true,  false, true,  false },
        /* X  */  { false, false, false, false },
        /* IS */  { true,  false, true,  true  },
        /* IX */  { false, false, true,  true  },
    };

    /**
     * 锁强度矩阵，STRENGTH[a][b] = true 表示 a 强于或等于 b
     *
     * <pre>
     * X >= X, S, IS, IX  (X 最强)
     * S >= S, IS
     * IX >= IX, IS
     * IS >= IS
     * </pre>
     */
    private static final boolean[][] STRENGTH = {
        //             S      X      IS     IX
        /* S  */  { true,  false, true,  false },
        /* X  */  { true,  true,  true,  true  },
        /* IS */  { false, false, true,  false },
        /* IX */  { false, false, true,  true  },
    };

    LockMode(int index) {
        this.index = index;
    }

    /**
     * 检查当前持有的锁模式是否与请求的锁模式兼容
     *
     * <p>L3 不变量: 纯查表操作，无条件分支。</p>
     *
     * @param requested 请求的锁模式
     * @return true 如果兼容（可以同时授予）
     */
    public boolean isCompatibleWith(LockMode requested) {
        return COMPAT_MATRIX[this.index][requested.index];
    }

    /**
     * 检查当前锁模式是否强于或等于另一个锁模式
     *
     * <p>用于锁升级判断：如果已持有更强的锁，无需再次加锁。</p>
     *
     * @param other 另一个锁模式
     * @return true 如果当前模式强于或等于 other
     */
    public boolean isStrongerOrEqual(LockMode other) {
        return STRENGTH[this.index][other.index];
    }

    /**
     * 获取矩阵索引
     *
     * @return 兼容矩阵中的索引值
     */
    public int getIndex() {
        return index;
    }
}
