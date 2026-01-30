package cn.zhangyis.minidb.storage.btree;

/**
 * 锁模式
 *
 * <p>定义 B+Tree 操作中使用的锁模式。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public enum LatchMode {

    /**
     * 共享锁（S-latch）
     *
     * <p>用于读操作，多个读操作可以同时持有。</p>
     */
    SHARED,

    /**
     * 排他锁（X-latch）
     *
     * <p>用于写操作，独占访问。</p>
     */
    EXCLUSIVE,

    /**
     * 无锁
     *
     * <p>用于乐观读取。</p>
     */
    NONE;

    /**
     * 是否与另一个锁模式兼容
     *
     * @param other 另一个锁模式
     * @return 如果兼容返回 true
     */
    public boolean isCompatibleWith(LatchMode other) {
        if (this == NONE || other == NONE) {
            return true;
        }
        if (this == SHARED && other == SHARED) {
            return true;
        }
        return false;
    }

    /**
     * 是否为写锁
     *
     * @return 如果是排他锁返回 true
     */
    public boolean isExclusive() {
        return this == EXCLUSIVE;
    }

    /**
     * 是否为读锁
     *
     * @return 如果是共享锁返回 true
     */
    public boolean isShared() {
        return this == SHARED;
    }
}
