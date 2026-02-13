package cn.zhangyis.minidb.storage.transaction.lock;

/**
 * 锁目标标识
 *
 * <p>不可变值对象，通过 (type, spaceId, pageNo, heapNo) 四元组唯一标识一个锁目标。</p>
 *
 * <h2>设计约束</h2>
 * <ul>
 *   <li>equals/hashCode 必须完全基于四元组，不可遗漏任何字段</li>
 *   <li>hashCode 使用位混合（31 乘法 + 高位异或），保证 segment 分布均匀</li>
 *   <li>表锁时 pageNo=0, heapNo=0</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * LockTarget recordLock = LockTarget.forRecord(1, 5, 3);
 * LockTarget tableLock = LockTarget.forTable(1);
 * }</pre>
 */
public final class LockTarget {

    private final LockType type;
    private final int spaceId;
    private final int pageNo;
    private final int heapNo;

    private LockTarget(LockType type, int spaceId, int pageNo, int heapNo) {
        this.type = type;
        this.spaceId = spaceId;
        this.pageNo = pageNo;
        this.heapNo = heapNo;
    }

    /**
     * 创建行锁目标
     *
     * @param spaceId 表空间 ID
     * @param pageNo  页号
     * @param heapNo  记录在页内的 heap 编号
     * @return 行锁目标
     */
    public static LockTarget forRecord(int spaceId, int pageNo, int heapNo) {
        return new LockTarget(LockType.RECORD, spaceId, pageNo, heapNo);
    }

    /**
     * 创建表锁目标
     *
     * @param tableId 表 ID（使用 spaceId 字段存储）
     * @return 表锁目标
     */
    public static LockTarget forTable(int tableId) {
        return new LockTarget(LockType.TABLE, tableId, 0, 0);
    }

    public LockType getType() {
        return type;
    }

    public int getSpaceId() {
        return spaceId;
    }

    public int getPageNo() {
        return pageNo;
    }

    public int getHeapNo() {
        return heapNo;
    }

    /**
     * 基于四元组的 hashCode
     *
     * <p>使用 31 乘法链 + 高位异或（h ^ (h >>> 16)）做位混合，
     * 避免 (1,2,3) 和 (3,2,1) 碰撞到同一 segment。</p>
     */
    @Override
    public int hashCode() {
        int h = type.hashCode();
        h = 31 * h + spaceId;
        h = 31 * h + pageNo;
        h = 31 * h + heapNo;
        // 高位异或，改善低位分布
        return h ^ (h >>> 16);
    }

    /**
     * 基于四元组的 equals
     *
     * <p>四字段完全比较，保证锁目标唯一性。</p>
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LockTarget that = (LockTarget) o;
        return spaceId == that.spaceId
            && pageNo == that.pageNo
            && heapNo == that.heapNo
            && type == that.type;
    }

    @Override
    public String toString() {
        if (type == LockType.TABLE) {
            return String.format("LockTarget(TABLE, spaceId=%d)", spaceId);
        }
        return String.format("LockTarget(RECORD, spaceId=%d, pageNo=%d, heapNo=%d)",
                spaceId, pageNo, heapNo);
    }
}
