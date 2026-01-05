package cn.zhangyis.minidb.storage.space;

/**
 * Extent 状态枚举
 *
 * <p>定义了 Extent (区) 在表空间中的五种状态。
 * Extent 的状态决定了它的所有权和使用方式。</p>
 *
 * <h2>状态转移</h2>
 * <pre>
 * FREE (表空间所有) → FSEG_FREE (Segment分配，但完全空闲)
 *                  ↓
 *               FSEG (Segment使用中)
 *
 * FREE → FREE_FRAG (碎片区，部分分配) → FULL_FRAG (碎片区，全满)
 * </pre>
 *
 * <h2>InnoDB 对应关系</h2>
 * <ul>
 *   <li>对应 InnoDB 源码: storage/innobase/include/fsp0types.h</li>
 *   <li>宏定义: XDES_FREE, XDES_FREE_FRAG, XDES_FULL_FRAG, XDES_FSEG</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public enum ExtentState {

    /**
     * 完全空闲 (属于表空间)
     *
     * <p>Extent 的所有 64 个页面都未分配，且不属于任何 Segment。
     * 位于 FSP_FREE 链表中，可以被分配给任何 Segment。</p>
     */
    FREE(1),

    /**
     * 碎片区，部分分配 (属于表空间)
     *
     * <p>Extent 的部分页面已作为碎片页分配，但不属于任何 Segment。
     * 位于 FSP_FREE_FRAG 链表中，用于为 Segment 的 FRAG_ARRAY 分配单个页面。</p>
     *
     * <p>碎片页机制：
     * <ul>
     *   <li>每个 Segment 的前 32 个页面单独分配（不占用完整 Extent）</li>
     *   <li>避免小表浪费空间（32 页 = 512KB）</li>
     *   <li>当 Segment 增长超过 32 页后，开始分配完整 Extent</li>
     * </ul>
     * </p>
     */
    FREE_FRAG(2),

    /**
     * 碎片区，全满 (属于表空间)
     *
     * <p>Extent 的所有 64 个页面都已作为碎片页分配，但不属于任何 Segment。
     * 位于 FSP_FULL_FRAG 链表中，不能再分配新页面。</p>
     */
    FULL_FRAG(3),

    /**
     * 属于 Segment (部分或全满)
     *
     * <p>Extent 完全属于某个 Segment (XDES_ID 非 0)。
     * 根据使用情况位于 Segment 的三个链表之一：
     * <ul>
     *   <li>INODE_FREE: 完全空闲，未分配任何页面</li>
     *   <li>INODE_NOT_FULL: 部分分配，1-63 页已使用</li>
     *   <li>INODE_FULL: 全满，64 页都已分配</li>
     * </ul>
     * </p>
     */
    FSEG(4),

    /**
     * 属于 Segment，但完全空闲
     *
     * <p>Extent 属于某个 Segment (XDES_ID 非 0)，但所有 64 个页面都未分配。
     * 位于 Segment 的 INODE_FREE 链表中。</p>
     *
     * <p>注意：此状态在实际实现中通常使用 FSEG 状态代替，
     * 通过检查 Bitmap 判断是否完全空闲。某些 InnoDB 版本定义了此状态但未使用。</p>
     */
    FSEG_FREE(5);

    /**
     * 状态值 (对应 XDES_STATE 字段的值)
     */
    private final int value;

    /**
     * 构造函数
     *
     * @param value 状态值
     */
    ExtentState(int value) {
        this.value = value;
    }

    /**
     * 获取状态值
     *
     * @return 状态值 (1-5)
     */
    public int getValue() {
        return value;
    }

    /**
     * 从状态值获取枚举
     *
     * @param value 状态值 (1-5)
     * @return 对应的 ExtentState 枚举
     * @throws IllegalArgumentException 如果状态值无效
     */
    public static ExtentState fromValue(int value) {
        for (ExtentState state : values()) {
            if (state.value == value) {
                return state;
            }
        }
        throw new IllegalArgumentException("Invalid ExtentState value: " + value);
    }

    /**
     * 判断是否属于表空间 (非 Segment 所有)
     *
     * @return true 如果是 FREE, FREE_FRAG, FULL_FRAG
     */
    public boolean isTablespaceOwned() {
        return this == FREE || this == FREE_FRAG || this == FULL_FRAG;
    }

    /**
     * 判断是否属于 Segment
     *
     * @return true 如果是 FSEG 或 FSEG_FREE
     */
    public boolean isSegmentOwned() {
        return this == FSEG || this == FSEG_FREE;
    }

    /**
     * 判断是否可以分配页面
     *
     * <p>只有 FREE_FRAG 和 FSEG 状态的 Extent 可以分配页面
     * (需要进一步检查 Bitmap 是否有空闲页)。</p>
     *
     * @return true 如果可以尝试分配页面
     */
    public boolean canAllocatePage() {
        return this == FREE_FRAG || this == FSEG;
    }
}
