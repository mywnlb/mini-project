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
 * INODE Page（段目录页）- 物理层
 *
 * <p>INODE Page 存储 Segment 的元数据（INODE Entry）。
 * 每个 INODE Page 包含85个 INODE Entry，每个 Entry 描述一个 Segment。
 * 这是纯物理层类，只负责页面访问和Entry访问，不包含分配逻辑。</p>
 *
 * <h2>物理布局（16KB）</h2>
 * <pre>
 * [FIL Header 38B]
 * [INODE Page Header 12B]
 *   └─ FLST_NODE (12B) - 链表节点，链接到 FSP_SEG_INODES_FREE/FULL
 * [INODE Entry #0 192B]
 * [INODE Entry #1 192B]
 * ...
 * [INODE Entry #84 192B]  - 共85个Entry
 * [Unused 6B]
 * [FIL Trailer 8B]
 *
 * 计算：38 + 12 + 192×85 + 6 + 8 = 16384
 * </pre>
 *
 * <h2>INODE Entry 结构（192字节）</h2>
 * <pre>
 * Offset  Size  Field
 * ------  ----  -----
 * 0       8     INODE_SEGMENT_ID (0=未分配)
 * 8       4     INODE_NOT_FULL_N_USED
 * 12      16    INODE_FREE (FLST_BASE_NODE)
 * 28      16    INODE_NOT_FULL (FLST_BASE_NODE)
 * 44      16    INODE_FULL (FLST_BASE_NODE)
 * 60      4     INODE_MAGIC_N (97937874)
 * 64      128   INODE_FRAG_ARRAY (32 × 4B)
 * </pre>
 *
 * <h2>INODE Page 链表</h2>
 * <ul>
 *   <li>FSP_SEG_INODES_FREE: 有空闲 Entry 的 INODE Page</li>
 *   <li>FSP_SEG_INODES_FULL: 全满的 INODE Page（85个Entry都已分配）</li>
 * </ul>
 *
 * <h2>分层设计</h2>
 * <ul>
 *   <li>物理层（本类）：负责 getInodeEntry/getListNode/initialize 等底层页面操作</li>
 *   <li>逻辑层（TableSpace类）：负责Entry分配逻辑、空闲Entry搜索</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 物理层操作示例
 * InodePage inodePage = new InodePage(page);
 * SegmentDescriptor entry = inodePage.getInodeEntry(5);  // 访问Entry 5
 * long segId = entry.getSegmentId();  // 读取字段
 * int usedCount = inodePage.getUsedEntryCount();  // 统计
 *
 * // 逻辑层操作应该使用 TableSpace 类
 * TableSpace tableSpace = new TableSpace(spaceId, bufferPool);
 * Segment segment = tableSpace.createSegment(mtr);  // 自动分配Entry
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class InodePage extends Page {

    /**
     * 构造函数：从 PageId 创建新页面
     *
     * @param pageId 页面ID
     */
    public InodePage(PageId pageId) {
        super(pageId);
        setPageType(PageType.FIL_PAGE_INODE);
    }

    /**
     * 构造函数：从现有 Page 对象创建
     *
     * <p>此构造函数接受任何页面类型，因为：</p>
     * <ul>
     *   <li>新分配的页面可能有默认类型（如 FIL_PAGE_INDEX）</li>
     *   <li>调用者应随后调用 {@link #initialize(MiniTransaction)} 设置正确的页面类型</li>
     *   <li>对于已初始化的页面，建议使用验证版本 {@link #fromExistingPage(Page)}</li>
     * </ul>
     *
     * @param page 现有页面
     */
    public InodePage(Page page) {
        super(page.getPageId(), page.getBuffer());
        // 不验证页面类型，允许任何类型的页面（将由 initialize() 设置正确类型）
    }

    /**
     * 从已存在的 INODE 页面创建（带类型验证）
     *
     * <p>用于从磁盘读取已初始化的 INODE 页面时，验证页面类型正确。</p>
     *
     * @param page 已初始化的 INODE 页面
     * @return InodePage 对象
     * @throws IllegalArgumentException 如果页面类型不是 INODE
     */
    public static InodePage fromExistingPage(Page page) {
        PageType actualType = page.getPageType();
        if (actualType != PageType.FIL_PAGE_INODE) {
            throw new IllegalArgumentException(
                    "Invalid page type for INODE: " + actualType + " (expected FIL_PAGE_INODE)");
        }

        return new InodePage(page);
    }

    /**
     * 构造函数：从 ByteBuffer 创建
     *
     * @param pageId 页面ID
     * @param buffer 页面数据
     */
    public InodePage(PageId pageId, ByteBuffer buffer) {
        super(pageId, buffer);
    }

    // ==================== INODE Page Header ====================

    /**
     * 获取链表节点（用于链接到 FSP_SEG_INODES_FREE/FULL）
     *
     * @return FlstNode 对象
     */
    public FlstNode getListNode() {
        // INODE Page Header 从 FIL_HEADER 之后开始
        return new FlstNode(this, FIL_HEADER_SIZE);
    }

    // ==================== INODE Entry 访问 ====================

    /**
     * 计算 INODE Entry 在页面中的偏移
     *
     * @param entryIndex Entry 索引（0-84）
     * @return 偏移量
     */
    public int getInodeEntryOffset(int entryIndex) {
        if (entryIndex < 0 || entryIndex >= INODES_PER_PAGE) {
            throw new IllegalArgumentException(
                    String.format("Entry index must be 0-%d, got: %d",
                            INODES_PER_PAGE - 1, entryIndex));
        }

        // INODE Entry 从 FIL_HEADER + INODE_PAGE_HEADER 之后开始
        return FIL_HEADER_SIZE + INODE_PAGE_HEADER_SIZE + entryIndex * INODE_ENTRY_SIZE;
    }

    /**
     * 获取 INODE Entry 描述符
     *
     * @param entryIndex Entry 索引（0-84）
     * @return SegmentDescriptor 对象
     */
    public SegmentDescriptor getInodeEntry(int entryIndex) {
        int offset = getInodeEntryOffset(entryIndex);
        return new SegmentDescriptor(this, offset);
    }

    /**
     * 获取已分配的 Entry 数量
     *
     * @return 已分配的 Entry 数量（0-85）
     */
    public int getUsedEntryCount() {
        int count = 0;
        for (int i = 0; i < INODES_PER_PAGE; i++) {
            SegmentDescriptor entry = getInodeEntry(i);
            if (entry.getSegmentId() != 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * 判断是否已满（所有85个Entry都已分配）
     *
     * @return true 如果已满
     */
    public boolean isFull() {
        return getUsedEntryCount() == INODES_PER_PAGE;
    }

    /**
     * 判断是否为空（所有Entry都未分配）
     *
     * @return true 如果为空
     */
    public boolean isEmpty() {
        return getUsedEntryCount() == 0;
    }

    // ==================== 初始化 ====================

    /**
     * 初始化 INODE Page
     *
     * @param mtr Mini-Transaction
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void initialize(MiniTransaction mtr)
            throws PageNotManagedByMtrException, MtrStateException {
        // 设置页面类型
        setPageType(PageType.FIL_PAGE_INODE);

        // 初始化链表节点为孤立状态
        getListNode().initialize(mtr);

        // 初始化所有 Entry
        initAllEntries(mtr);

        mtr.markDirty(this);
    }

    /**
     * 初始化所有 INODE Entry（设置 Segment ID 为 0）
     *
     * @param mtr Mini-Transaction
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void initAllEntries(MiniTransaction mtr)
            throws PageNotManagedByMtrException, MtrStateException {
        for (int i = 0; i < INODES_PER_PAGE; i++) {
            int offset = getInodeEntryOffset(i);

            // 设置 Segment ID 为 0（表示未分配）
            putLong(offset + INODE_SEGMENT_ID, 0);

            // 设置 NOT_FULL_N_USED 为 0（NOT_FULL链表中已使用的页数）
            putInt(offset + INODE_NOT_FULL_N_USED, 0);

            // 设置魔数
            putInt(offset + INODE_MAGIC_N, INODE_MAGIC_NUMBER);

            // 初始化碎片页数组为 FIL_NULL
            for (int j = 0; j < INODE_FRAG_ARRAY_PAGES; j++) {
                putInt(offset + INODE_FRAG_ARRAY + j * 4, FIL_NULL);
            }

            // 初始化三个链表为空
            FlstBaseNode freeList = new FlstBaseNode(this, offset + INODE_FREE);
            FlstBaseNode notFullList = new FlstBaseNode(this, offset + INODE_NOT_FULL);
            FlstBaseNode fullList = new FlstBaseNode(this, offset + INODE_FULL);

            freeList.initialize(mtr);
            notFullList.initialize(mtr);
            fullList.initialize(mtr);
        }

        mtr.markDirty(this);
    }

    /**
     * 初始化单个 INODE Entry
     *
     * @param mtr        Mini-Transaction
     * @param entryIndex Entry 索引（0-84）
     * @throws MiniDbException 如果操作失败
     */
    public void initEntry(MiniTransaction mtr, int entryIndex)
            throws MiniDbException {
        SegmentDescriptor entry = getInodeEntry(entryIndex);
        entry.clear(mtr);
    }

    // ==================== 调试和统计 ====================

    /**
     * 获取所有已分配的 Segment ID 列表
     *
     * @return Segment ID 数组
     */
    public long[] getAllSegmentIds() {
        long[] segmentIds = new long[INODES_PER_PAGE];
        int count = 0;

        for (int i = 0; i < INODES_PER_PAGE; i++) {
            SegmentDescriptor entry = getInodeEntry(i);
            long segmentId = entry.getSegmentId();
            if (segmentId != 0) {
                segmentIds[count++] = segmentId;
            }
        }

        // 截断数组
        long[] result = new long[count];
        System.arraycopy(segmentIds, 0, result, 0, count);
        return result;
    }

    @Override
    public String toString() {
        return String.format("InodePage{pageNo=%d, used=%d/%d, full=%b}",
                getPageNo(), getUsedEntryCount(), INODES_PER_PAGE, isFull());
    }

    /**
     *
     * @return
     */
    public int findFreeEntry() {
        return 0;
    }
}
