package cn.zhangyis.minidb.storage.btree;

/**
 * 范围查询边界
 *
 * <p>定义范围查询的边界条件。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class RangeBound {

    /** 边界类型 */
    public enum BoundType {
        /** 包含边界值（闭区间） */
        INCLUSIVE,
        /** 不包含边界值（开区间） */
        EXCLUSIVE,
        /** 无边界（无穷） */
        UNBOUNDED
    }

    /** 边界键值 */
    private final byte[] key;

    /** 边界类型 */
    private final BoundType type;

    /**
     * 私有构造函数
     */
    private RangeBound(byte[] key, BoundType type) {
        this.key = key;
        this.type = type;
    }

    /**
     * 创建包含边界（闭区间）
     *
     * @param key 边界键
     * @return 包含边界
     */
    public static RangeBound inclusive(byte[] key) {
        return new RangeBound(key, BoundType.INCLUSIVE);
    }

    /**
     * 创建不包含边界（开区间）
     *
     * @param key 边界键
     * @return 不包含边界
     */
    public static RangeBound exclusive(byte[] key) {
        return new RangeBound(key, BoundType.EXCLUSIVE);
    }

    /**
     * 创建无边界（无穷）
     *
     * @return 无边界
     */
    public static RangeBound unbounded() {
        return new RangeBound(null, BoundType.UNBOUNDED);
    }

    /**
     * 获取边界键
     *
     * @return 边界键，如果是无边界则返回 null
     */
    public byte[] getKey() {
        return key;
    }

    /**
     * 获取边界类型
     *
     * @return 边界类型
     */
    public BoundType getType() {
        return type;
    }

    /**
     * 是否为无边界
     *
     * @return 如果是无边界返回 true
     */
    public boolean isUnbounded() {
        return type == BoundType.UNBOUNDED;
    }

    /**
     * 是否包含边界值
     *
     * @return 如果包含边界值返回 true
     */
    public boolean isInclusive() {
        return type == BoundType.INCLUSIVE;
    }

    /**
     * 是否不包含边界值
     *
     * @return 如果不包含边界值返回 true
     */
    public boolean isExclusive() {
        return type == BoundType.EXCLUSIVE;
    }

    @Override
    public String toString() {
        if (type == BoundType.UNBOUNDED) {
            return "UNBOUNDED";
        }
        String keyStr = key != null ? "key=" + IntKeyComparator.bytesToInt(key) : "null";
        return String.format("%s(%s)", type, keyStr);
    }
}
