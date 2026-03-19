package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.common.exception.PageNotManagedByMtrException;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;

/**
 * Segment 描述符（段描述符）- 物理层
 *
 * <p>SegmentDescriptor 封装了 INODE Entry (192字节) 的物理读写操作。
 * 这是纯物理层类，只负责字节级别的 get/put 操作，不包含分配逻辑。</p>
 *
 * <h2>INODE Entry 物理结构（192字节）</h2>
 * <pre>
 * Offset  Size  Field
 * ------  ----  -----
 * 0       8     INODE_SEGMENT_ID (0=未分配)
 * 8       4     INODE_NOT_FULL_N_USED (NOT_FULL链表中已使用页数)
 * 12      16    INODE_FREE (FLST_BASE_NODE) - Free Extent 链表
 * 28      16    INODE_NOT_FULL (FLST_BASE_NODE) - Partial Extent 链表
 * 44      16    INODE_FULL (FLST_BASE_NODE) - Full Extent 链表
 * 60      4     INODE_MAGIC_N (97937874)
 * 64      128   INODE_FRAG_ARRAY (32 × 4B) - 碎片页数组
 * </pre>
 *
 * <h2>3个 Extent 链表</h2>
 * <ul>
 *   <li>FREE: 完全空闲的 Extent（64页都未分配）</li>
 *   <li>NOT_FULL: 部分分配的 Extent（1-63页已分配）</li>
 *   <li>FULL: 完全占满的 Extent（64页都已分配）</li>
 * </ul>
 *
 * <h2>碎片页数组</h2>
 * <p>Segment 的前32个页面单独分配（不占用完整 Extent），避免小表浪费空间。
 * PageNo = FIL_NULL (0xFFFFFFFF) 表示未分配。</p>
 *
 * <h2>分层设计</h2>
 * <ul>
 *   <li>物理层（本类）：负责 get/put/getFragPageNo/setFragPageNo 等底层字段操作</li>
 *   <li>逻辑层（Segment类）：负责页面分配决策、碎片页搜索算法、32页策略</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 物理层操作示例
 * SegmentDescriptor descriptor = new SegmentDescriptor(inodePage, offset);
 * long segId = descriptor.getSegmentId();  // 读取字段
 * descriptor.setSegmentId(mtr, 123);       // 修改字段
 * int pageNo = descriptor.getFragPageNo(5); // 读取碎片页数组
 * descriptor.setFragPageNo(mtr, 5, 100);   // 修改碎片页数组
 *
 * // 逻辑层操作应该使用 Segment 类
 * Segment segment = new Segment(spaceId, segmentId, descriptor, tableSpace);
 * Page page = segment.allocatePage(mtr);  // 自动选择碎片页或extent
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class SegmentDescriptor {

    /**
     * INODE Entry 所在的页面（INODE Page）
     */
    private final Page page;

    /**
     * INODE Entry 在页面中的偏移
     */
    private final int offset;

    /**
     * 构造函数
     *
     * @param page   INODE Entry 所在的页面
     * @param offset INODE Entry 在页面中的偏移
     */
    public SegmentDescriptor(Page page, int offset) {
        if (page == null) {
            throw new IllegalArgumentException("Page cannot be null");
        }
        if (offset < 0 || offset > PAGE_SIZE - INODE_ENTRY_SIZE) {
            throw new IllegalArgumentException("Invalid offset: " + offset);
        }

        this.page = page;
        this.offset = offset;
    }

    /**
     * 获取 INODE Entry 所在页面的 PageId
     *
     * @return INODE Page 的 PageId
     */
    public PageId getInodePageId() {
        return page.getPageId();
    }

    // ==================== 基本字段访问 ====================

    /**
     * 获取 Segment ID
     *
     * @return Segment ID，0 表示此 Entry 未分配
     */
    public long getSegmentId() {
        return page.getLong(offset + INODE_SEGMENT_ID);
    }

    /**
     * 设置 Segment ID
     *
     * @param mtr       Mini-Transaction
     * @param segmentId Segment ID
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void setSegmentId(MiniTransaction mtr, long segmentId)
            throws PageNotManagedByMtrException, MtrStateException {
        page.putLong(offset + INODE_SEGMENT_ID, segmentId);
        mtr.markDirty(page);
    }

    /**
     * 获取 NOT_FULL 链表中已使用的页数
     *
     * @return 已使用页数
     */
    public int getNotFullNUsed() {
        return page.getInt(offset + INODE_NOT_FULL_N_USED);
    }

    /**
     * 设置 NOT_FULL 链表中已使用的页数
     *
     * @param mtr   Mini-Transaction
     * @param count 已使用页数
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void setNotFullNUsed(MiniTransaction mtr, int count)
            throws PageNotManagedByMtrException, MtrStateException {
        page.putInt(offset + INODE_NOT_FULL_N_USED, count);
        mtr.markDirty(page);
    }

    /**
     * 获取魔数
     *
     * @return 魔数（应为 97937874）
     */
    public int getMagicNumber() {
        return page.getInt(offset + INODE_MAGIC_N);
    }

    /**
     * 设置魔数
     *
     * @param mtr   Mini-Transaction
     * @param magic 魔数
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void setMagicNumber(MiniTransaction mtr, int magic)
            throws PageNotManagedByMtrException, MtrStateException {
        page.putInt(offset + INODE_MAGIC_N, magic);
        mtr.markDirty(page);
    }

    /**
     * 判断此 Entry 是否已分配
     *
     * @return true 如果已分配（Segment ID 非 0）
     */
    public boolean isAllocated() {
        return getSegmentId() != 0;
    }

    // ==================== 链表访问 ====================

    /**
     * 获取 Free Extent 链表基节点
     *
     * @return FlstBaseNode 对象
     */
    public FlstBaseNode getFreeList() {
        return new FlstBaseNode(page, offset + INODE_FREE);
    }

    /**
     * 获取 Partial Extent 链表基节点
     *
     * @return FlstBaseNode 对象
     */
    public FlstBaseNode getNotFullList() {
        return new FlstBaseNode(page, offset + INODE_NOT_FULL);
    }

    /**
     * 获取 Full Extent 链表基节点
     *
     * @return FlstBaseNode 对象
     */
    public FlstBaseNode getFullList() {
        return new FlstBaseNode(page, offset + INODE_FULL);
    }

    // ==================== 碎片页数组操作 ====================

    /**
     * 获取碎片页数组中指定位置的页号
     *
     * @param index 索引（0-31）
     * @return 页号，FIL_NULL 表示未分配
     */
    public int getFragPageNo(int index) {
        validateFragIndex(index);
        return page.getInt(offset + INODE_FRAG_ARRAY + index * 4);
    }

    /**
     * 设置碎片页数组中指定位置的页号
     *
     * @param mtr    Mini-Transaction
     * @param index  索引（0-31）
     * @param pageNo 页号（FIL_NULL 表示清空）
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void setFragPageNo(MiniTransaction mtr, int index, int pageNo)
            throws PageNotManagedByMtrException, MtrStateException {
        validateFragIndex(index);
        page.putInt(offset + INODE_FRAG_ARRAY + index * 4, pageNo);
        mtr.markDirty(page);
    }

    /**
     * 获取碎片页数组中已使用的槽位数量
     *
     * @return 已使用槽位数（0-32）
     */
    public int getFragUsedCount() {
        int count = 0;
        for (int i = 0; i < INODE_FRAG_ARRAY_PAGES; i++) {
            if (getFragPageNo(i) != FIL_NULL) {
                count++;
            }
        }
        return count;
    }

    /**
     * 判断碎片页数组是否已满
     *
     * @return true 如果32个槽位都已分配
     */
    public boolean isFragArrayFull() {
        return getFragUsedCount() == INODE_FRAG_ARRAY_PAGES;
    }

    /**
     * 清空碎片页数组（设置所有槽位为 FIL_NULL）
     *
     * @param mtr Mini-Transaction
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void clearFragArray(MiniTransaction mtr)
            throws PageNotManagedByMtrException, MtrStateException {
        for (int i = 0; i < INODE_FRAG_ARRAY_PAGES; i++) {
            setFragPageNo(mtr, i, FIL_NULL);
        }
    }

    // ==================== 初始化和清除 ====================

    /**
     * 初始化 INODE Entry
     *
     * @param mtr       Mini-Transaction
     * @param segmentId Segment ID
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void initialize(MiniTransaction mtr, long segmentId)
            throws PageNotManagedByMtrException, MtrStateException {
        if (segmentId == 0) {
            throw new IllegalArgumentException("Segment ID cannot be 0");
        }

        // 设置 Segment ID
        setSegmentId(mtr, segmentId);

        // 设置 NOT_FULL 计数为 0
        setNotFullNUsed(mtr, 0);

        // 设置魔数
        setMagicNumber(mtr, INODE_MAGIC_NUMBER);

        // 初始化三个链表为空
        getFreeList().initialize(mtr);
        getNotFullList().initialize(mtr);
        getFullList().initialize(mtr);

        // 清空碎片页数组
        clearFragArray(mtr);
    }

    /**
     * 清除 INODE Entry（设置为未分配状态）
     *
     * @param mtr Mini-Transaction
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void clear(MiniTransaction mtr)
            throws PageNotManagedByMtrException, MtrStateException {
        // 设置 Segment ID 为 0（表示未分配）
        setSegmentId(mtr, 0);

        // 清空其他字段
        setNotFullNUsed(mtr, 0);
        setMagicNumber(mtr, INODE_MAGIC_NUMBER);

        // 清空链表
        getFreeList().initialize(mtr);
        getNotFullList().initialize(mtr);
        getFullList().initialize(mtr);

        // 清空碎片页数组
        clearFragArray(mtr);
    }

    // ==================== 统计信息 ====================

    /**
     * 获取 Segment 拥有的 Extent 总数
     *
     * @return Extent 数量（Free + NotFull + Full）
     */
    public int getTotalExtentCount() {
        return getFreeList().getLength() +
               getNotFullList().getLength() +
               getFullList().getLength();
    }

    /**
     * 获取 Segment 拥有的页面总数（估算）
     *
     * <p>计算公式：碎片页数 + Extent数 × 64。
     * 注意：这是估算值，实际使用页数可能少于此值。</p>
     *
     * @return 页面总数
     */
    public int getEstimatedPageCount() {
        return getFragUsedCount() + getTotalExtentCount() * EXTENT_SIZE;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 验证碎片页数组索引的有效性
     *
     * @param index 索引
     */
    private void validateFragIndex(int index) {
        if (index < 0 || index >= INODE_FRAG_ARRAY_PAGES) {
            throw new IllegalArgumentException(
                    String.format("Frag index must be 0-31, got: %d", index));
        }
    }

    // ==================== 调试信息 ====================

    @Override
    public String toString() {
        if (!isAllocated()) {
            return "SegmentDescriptor{unallocated}";
        }

        return String.format("SegmentDescriptor{segmentId=%d, freeExtents=%d, " +
                        "partialExtents=%d, fullExtents=%d, fragPages=%d/32, " +
                        "estimatedPages=%d}",
                getSegmentId(),
                getFreeList().getLength(),
                getNotFullList().getLength(),
                getFullList().getLength(),
                getFragUsedCount(),
                getEstimatedPageCount());
    }

    /**
     * 获取详细的调试信息
     *
     * @return 多行调试信息
     */
    public String toDetailedString() {
        if (!isAllocated()) {
            return "SegmentDescriptor: UNALLOCATED";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("SegmentDescriptor Details:\n"));
        sb.append(String.format("  Segment ID: %d\n", getSegmentId()));
        sb.append(String.format("  Magic Number: %d (expected: %d)\n",
                getMagicNumber(), INODE_MAGIC_NUMBER));
        sb.append(String.format("  Free Extents: %d\n", getFreeList().getLength()));
        sb.append(String.format("  Partial Extents: %d (used pages: %d)\n",
                getNotFullList().getLength(), getNotFullNUsed()));
        sb.append(String.format("  Full Extents: %d\n", getFullList().getLength()));
        sb.append(String.format("  Frag Pages: %d/32\n", getFragUsedCount()));
        sb.append(String.format("  Total Extents: %d\n", getTotalExtentCount()));
        sb.append(String.format("  Estimated Pages: %d\n", getEstimatedPageCount()));

        return sb.toString();
    }
}
