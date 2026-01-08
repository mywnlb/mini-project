package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.common.exception.PageNotManagedByMtrException;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.page.PageType;

import java.nio.ByteBuffer;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;

/**
 * FSP Header Page（表空间头页）- 物理层
 *
 * <p>FSP_HDR Page 是每个表空间的第一个页面（page 0），包含表空间的元信息
 * 和前256个 Extent 的 XDES Entry。
 * 这是纯物理层类，只负责 FSP Header 字段的读写，不包含分配逻辑。</p>
 *
 * <h2>物理布局（16KB）</h2>
 * <pre>
 * [FIL Header 38B]
 * [FSP Header 112B]
 *   ├─ FSP_SPACE_ID (4B)
 *   ├─ FSP_SIZE (4B) - 当前页数
 *   ├─ FSP_FREE_LIMIT (4B) - 已初始化页数
 *   ├─ FSP_SEG_ID (8B) - 下一个Segment ID
 *   ├─ FSP_FREE (16B) - 空闲Extent链表
 *   ├─ FSP_FREE_FRAG (16B) - 碎片Extent链表
 *   ├─ FSP_FULL_FRAG (16B) - 全满碎片Extent链表
 *   ├─ FSP_SEG_INODES_FREE (16B) - 有空闲的INODE页链表
 *   └─ FSP_SEG_INODES_FULL (16B) - 全满的INODE页链表
 * [XDES Array 10240B] - 256个XDES Entry (256 × 40B)
 * [Unused Space]
 * [FIL Trailer 8B]
 * </pre>
 *
 * <h2>分层设计</h2>
 * <ul>
 *   <li>物理层（本类）：负责 get/set FSP Header 字段、链表访问、XDES Entry 访问</li>
 *   <li>逻辑层（TableSpace类）：负责 Segment ID 分配、Extent 管理、空间扩展</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 物理层操作示例
 * FspHeaderPage fspHdr = new FspHeaderPage(page);
 * long nextSegId = fspHdr.getNextSegmentId();  // 读取字段
 * fspHdr.setNextSegmentId(mtr, nextSegId + 1); // 修改字段
 * FlstBaseNode freeList = fspHdr.getFreeList(); // 访问链表
 *
 * // 逻辑层操作应该使用 TableSpace 类
 * TableSpace tableSpace = new TableSpace(spaceId, bufferPool);
 * Segment segment = tableSpace.createSegment(mtr);  // 自动分配Segment ID
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class FspHeaderPage extends Page {

    /**
     * 构造函数：从 PageId 创建新页面
     *
     * @param pageId 页面ID（必须是page 0）
     */
    public FspHeaderPage(PageId pageId) {
        super(pageId);

        if (pageId.getPageNo() != 0) {
            throw new IllegalArgumentException("FSP_HDR must be page 0, got: " + pageId.getPageNo());
        }

        // 设置页面类型为 FSP_HDR
        setPageType(PageType.FIL_PAGE_TYPE_FSP_HDR);
    }

    /**
     * 构造函数：从现有 Page 对象创建
     *
     * <p>此构造函数接受任何页面类型，因为：</p>
     * <ul>
     *   <li>新分配的页面可能有默认类型（如 FIL_PAGE_INDEX）</li>
     *   <li>调用者应随后调用 {@link #initialize(MiniTransaction, int)} 设置正确的页面类型</li>
     *   <li>对于已初始化的页面，建议使用验证版本 {@link #fromExistingPage(Page)}</li>
     * </ul>
     *
     * @param page 现有页面（必须是 page 0）
     */
    public FspHeaderPage(Page page) {
        super(page.getPageId(), page.getBuffer());

        if (page.getPageNo() != 0) {
            throw new IllegalArgumentException("FSP_HDR must be page 0, got: " + page.getPageNo());
        }
        // 不验证页面类型，允许任何类型的页面（将由 initialize() 设置正确类型）
    }

    /**
     * 从已存在的 FSP_HDR 页面创建（带类型验证）
     *
     * <p>用于从磁盘读取已初始化的 FSP_HDR 页面时，验证页面类型正确。</p>
     *
     * @param page 已初始化的 FSP_HDR 页面
     * @return FspHeaderPage 对象
     * @throws IllegalArgumentException 如果页面类型不是 FSP_HDR
     */
    public static FspHeaderPage fromExistingPage(Page page) {
        if (page.getPageNo() != 0) {
            throw new IllegalArgumentException("FSP_HDR must be page 0, got: " + page.getPageNo());
        }

        PageType actualType = page.getPageType();
        if (actualType != PageType.FIL_PAGE_TYPE_FSP_HDR) {
            throw new IllegalArgumentException(
                    "Invalid page type for FSP_HDR: " + actualType + " (expected FSP_HDR)");
        }

        return new FspHeaderPage(page);
    }

    /**
     * 构造函数：从 ByteBuffer 创建
     *
     * @param pageId 页面ID
     * @param buffer 页面数据
     */
    public FspHeaderPage(PageId pageId, ByteBuffer buffer) {
        super(pageId, buffer);

        if (pageId.getPageNo() != 0) {
            throw new IllegalArgumentException("FSP_HDR must be page 0");
        }
    }

    // ==================== FSP Header 字段访问 ====================

    /**
     * 获取表空间ID
     *
     * @return 表空间ID
     */
    public int getFspSpaceId() {
        return getInt(FSP_SPACE_ID);
    }

    /**
     * 设置表空间ID
     *
     * @param mtr     Mini-Transaction
     * @param spaceId 表空间ID
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void setFspSpaceId(MiniTransaction mtr, int spaceId)
            throws PageNotManagedByMtrException, MtrStateException {
        putInt(FSP_SPACE_ID, spaceId);
        mtr.markDirty(this);
    }

    /**
     * 获取当前表空间总页数
     *
     * @return 总页数
     */
    public int getSize() {
        return getInt(FSP_SIZE);
    }

    /**
     * 设置表空间总页数
     *
     * @param mtr  Mini-Transaction
     * @param size 总页数
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void setSize(MiniTransaction mtr, int size)
            throws PageNotManagedByMtrException, MtrStateException {
        putInt(FSP_SIZE, size);
        mtr.markDirty(this);
    }

    /**
     * 获取已初始化的页数
     *
     * @return 已初始化页数
     */
    public int getFreeLimit() {
        return getInt(FSP_FREE_LIMIT);
    }

    /**
     * 设置已初始化的页数
     *
     * @param mtr       Mini-Transaction
     * @param freeLimit 已初始化页数
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void setFreeLimit(MiniTransaction mtr, int freeLimit)
            throws PageNotManagedByMtrException, MtrStateException {
        putInt(FSP_FREE_LIMIT, freeLimit);
        mtr.markDirty(this);
    }

    /**
     * 获取表空间标志位
     *
     * @return 标志位
     */
    public int getSpaceFlags() {
        return getInt(FSP_SPACE_FLAGS);
    }

    /**
     * 设置表空间标志位
     *
     * @param mtr   Mini-Transaction
     * @param flags 标志位
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void setSpaceFlags(MiniTransaction mtr, int flags)
            throws PageNotManagedByMtrException, MtrStateException {
        putInt(FSP_SPACE_FLAGS, flags);
        mtr.markDirty(this);
    }

    /**
     * 获取碎片区已使用页数
     *
     * @return 已使用页数
     */
    public int getFragNUsed() {
        return getInt(FSP_FRAG_N_USED);
    }

    /**
     * 设置碎片区已使用页数
     *
     * @param mtr   Mini-Transaction
     * @param count 已使用页数
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void setFragNUsed(MiniTransaction mtr, int count)
            throws PageNotManagedByMtrException, MtrStateException {
        putInt(FSP_FRAG_N_USED, count);
        mtr.markDirty(this);
    }

    /**
     * 获取下一个可分配的 Segment ID
     *
     * @return Segment ID
     */
    public long getNextSegmentId() {
        return getLong(FSP_SEG_ID);
    }

    /**
     * 设置下一个 Segment ID
     *
     * @param mtr       Mini-Transaction
     * @param segmentId Segment ID
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void setNextSegmentId(MiniTransaction mtr, long segmentId)
            throws PageNotManagedByMtrException, MtrStateException {
        putLong(FSP_SEG_ID, segmentId);
        mtr.markDirty(this);
    }

    // ==================== 链表访问 ====================

    /**
     * 获取空闲 Extent 链表基节点
     *
     * @return FlstBaseNode 对象
     */
    public FlstBaseNode getFreeList() {
        return new FlstBaseNode(this, FSP_FREE);
    }

    /**
     * 获取碎片 Extent 链表基节点（部分空闲）
     *
     * @return FlstBaseNode 对象
     */
    public FlstBaseNode getFreeFragList() {
        return new FlstBaseNode(this, FSP_FREE_FRAG);
    }

    /**
     * 获取碎片 Extent 链表基节点（全满）
     *
     * @return FlstBaseNode 对象
     */
    public FlstBaseNode getFullFragList() {
        return new FlstBaseNode(this, FSP_FULL_FRAG);
    }

    /**
     * 获取有空闲的 INODE Page 链表基节点
     *
     * @return FlstBaseNode 对象
     */
    public FlstBaseNode getInodesFreeList() {
        return new FlstBaseNode(this, FSP_SEG_INODES_FREE);
    }

    /**
     * 获取全满的 INODE Page 链表基节点
     *
     * @return FlstBaseNode 对象
     */
    public FlstBaseNode getInodesFullList() {
        return new FlstBaseNode(this, FSP_SEG_INODES_FULL);
    }

    // ==================== XDES Array 访问 ====================

    /**
     * 计算 XDES Entry 在页面中的偏移
     *
     * @param extentNo Extent 编号（0-255）
     * @return 偏移量
     */
    public int getXdesEntryOffset(int extentNo) {
        if (extentNo < 0 || extentNo >= EXTENTS_PER_GROUP) {
            throw new IllegalArgumentException(
                    "ExtentNo must be 0-255 for FSP_HDR, got: " + extentNo);
        }
        return XDES_ARR_OFFSET + extentNo * XDES_ENTRY_SIZE;
    }

    /**
     * 获取 XDES Entry 描述符
     *
     * @param extentNo Extent 编号（0-255）
     * @return ExtentDescriptor 对象
     */
    public ExtentDescriptor getXdesEntry(int extentNo) {
        int offset = getXdesEntryOffset(extentNo);
        return new ExtentDescriptor(this, offset, extentNo);
    }

    // ==================== 初始化和高级操作 ====================

    /**
     * 初始化 FSP_HDR 页面
     *
     * @param mtr     Mini-Transaction
     * @param spaceId 表空间ID
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void initialize(MiniTransaction mtr, int spaceId)
            throws PageNotManagedByMtrException, MtrStateException {
        // 设置页面类型
        setPageType(PageType.FIL_PAGE_TYPE_FSP_HDR);

        // 初始化 FSP Header 字段
        setFspSpaceId(mtr, spaceId);
        setSize(mtr, 1);
        setFreeLimit(mtr, 1);
        setSpaceFlags(mtr, 0);
        setFragNUsed(mtr, 0);
        setNextSegmentId(mtr, 1);  // Segment ID 从1开始

        // 初始化所有链表为空
        initExtentLists(mtr);

        // 页面已通过各个 setter 方法标记为脏页，此处无需重复标记
    }

    /**
     * 初始化所有 Extent 链表为空
     *
     * @param mtr Mini-Transaction
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void initExtentLists(MiniTransaction mtr)
            throws PageNotManagedByMtrException, MtrStateException {
        getFreeList().initialize(mtr);
        getFreeFragList().initialize(mtr);
        getFullFragList().initialize(mtr);
        getInodesFreeList().initialize(mtr);
        getInodesFullList().initialize(mtr);
    }

    /**
     * 将 Extent 添加到 FSP_FREE 链表
     *
     * @param mtr    Mini-Transaction
     * @param extent Extent 描述符
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void addToFreeList(MiniTransaction mtr, ExtentDescriptor extent)
            throws MiniDbException {
        FlstBaseNode freeList = getFreeList();
        Page extentPage = extent.getListNode().getPage();
        int extentOffset = extent.getListNode().getOffset();
        freeList.addLast(mtr, extentPage, extentOffset);
    }

    /**
     * 从 FSP_FREE 链表中移除第一个 Extent
     *
     * @param mtr Mini-Transaction
     * @return 被移除的 Extent 的 PageId，如果链表为空返回 null
     * @throws MiniDbException 如果操作失败
     */
    public PageId removeFirstFromFreeList(MiniTransaction mtr) throws MiniDbException {
        FlstBaseNode freeList = getFreeList();
        return freeList.removeFirst(mtr);
    }

    /**
     * 将 Extent 添加到 FSP_FREE_FRAG 链表
     *
     * @param mtr    Mini-Transaction
     * @param extent Extent 描述符
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void addToFreeFragList(MiniTransaction mtr, ExtentDescriptor extent)
            throws MiniDbException {
        FlstBaseNode freeFragList = getFreeFragList();
        Page extentPage = extent.getListNode().getPage();
        int extentOffset = extent.getListNode().getOffset();
        freeFragList.addLast(mtr, extentPage, extentOffset);
    }

    /**
     * 将 Extent 添加到 FSP_FULL_FRAG 链表
     *
     * @param mtr    Mini-Transaction
     * @param extent Extent 描述符
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void addToFullFragList(MiniTransaction mtr, ExtentDescriptor extent)
            throws MiniDbException {
        FlstBaseNode fullFragList = getFullFragList();
        Page extentPage = extent.getListNode().getPage();
        int extentOffset = extent.getListNode().getOffset();
        fullFragList.addLast(mtr, extentPage, extentOffset);
    }

    /**
     * 将 INODE Page 添加到 FSP_SEG_INODES_FREE 链表
     *
     * @param mtr       Mini-Transaction
     * @param inodePage INODE Page
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void addToInodesFreeList(MiniTransaction mtr, Page inodePage)
            throws MiniDbException {
        FlstBaseNode inodesFreeList = getInodesFreeList();
        // INODE Page 的链表节点位于 FIL_HEADER 之后
        int nodeOffset = FIL_HEADER_SIZE;
        inodesFreeList.addLast(mtr, inodePage, nodeOffset);
    }

    /**
     * 将 INODE Page 添加到 FSP_SEG_INODES_FULL 链表
     *
     * @param mtr       Mini-Transaction
     * @param inodePage INODE Page
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void addToInodesFullList(MiniTransaction mtr, Page inodePage)
            throws MiniDbException {
        FlstBaseNode inodesFullList = getInodesFullList();
        int nodeOffset = FIL_HEADER_SIZE;
        inodesFullList.addLast(mtr, inodePage, nodeOffset);
    }

    // ==================== 统计信息 ====================

    /**
     * 获取空闲 Extent 数量
     *
     * @return 空闲 Extent 数量
     */
    public int getFreeExtentCount() {
        return getFreeList().getLength();
    }

    /**
     * 获取碎片 Extent 数量
     *
     * @return 碎片 Extent 数量（部分空闲 + 全满）
     */
    public int getFragExtentCount() {
        return getFreeFragList().getLength() + getFullFragList().getLength();
    }

    /**
     * 获取 INODE Page 数量
     *
     * @return INODE Page 数量（有空闲 + 全满）
     */
    public int getInodePageCount() {
        return getInodesFreeList().getLength() + getInodesFullList().getLength();
    }

    @Override
    public String toString() {
        return String.format("FspHeaderPage{spaceId=%d, size=%d, freeLimit=%d, " +
                        "nextSegId=%d, freeExtents=%d, fragExtents=%d, inodePages=%d}",
                getFspSpaceId(), getSize(), getFreeLimit(), getNextSegmentId(),
                getFreeExtentCount(), getFragExtentCount(), getInodePageCount());
    }
}
