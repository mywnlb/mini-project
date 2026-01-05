package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.page.PageType;

import java.nio.ByteBuffer;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;

/**
 * INODE Page（段目录页）
 *
 * <p>INODE Page 存储 Segment 的元数据（INODE Entry）。
 * 每个 INODE Page 包含85个 INODE Entry，每个 Entry 描述一个 Segment。</p>
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
 * <h2>使用示例</h2>
 * <pre>
 * // 查找空闲的 INODE Entry
 * InodePage inodePage = ...;
 * int freeIndex = inodePage.findFreeEntry();
 * if (freeIndex != -1) {
 *     SegmentDescriptor segment = inodePage.getInodeEntry(freeIndex);
 *     segment.initialize(mtr, newSegmentId);
 * }
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
        super(pageId, PageType.FIL_PAGE_INODE);
    }

    /**
     * 构造函数：从现有 Page 对象创建
     *
     * @param page 现有页面
     */
    public InodePage(Page page) {
        super(page.getBuffer(), page.getPageId());

        // 验证页面类型
        PageType actualType = page.getPageType();
        if (actualType != PageType.FIL_PAGE_INODE &&
            actualType != PageType.FIL_PAGE_TYPE_ALLOCATED) {
            throw new IllegalArgumentException(
                    "Invalid page type for INODE: " + actualType);
        }
    }

    /**
     * 构造函数：从 ByteBuffer 创建
     *
     * @param buffer 页面数据
     * @param pageId 页面ID
     */
    public InodePage(ByteBuffer buffer, PageId pageId) {
        super(buffer, pageId);
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
     * 查找第一个空闲的 INODE Entry
     *
     * <p>空闲 Entry 的特征：INODE_SEGMENT_ID = 0。</p>
     *
     * @return Entry 索引（0-84），如果没有空闲 Entry 返回 -1
     */
    public int findFreeEntry() {
        for (int i = 0; i < INODES_PER_PAGE; i++) {
            SegmentDescriptor entry = getInodeEntry(i);
            if (entry.getSegmentId() == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 查找指定 Segment ID 的 INODE Entry
     *
     * @param segmentId Segment ID
     * @return Entry 索引（0-84），如果未找到返回 -1
     */
    public int findEntryBySegmentId(long segmentId) {
        if (segmentId == 0) {
            throw new IllegalArgumentException("Segment ID cannot be 0");
        }

        for (int i = 0; i < INODES_PER_PAGE; i++) {
            SegmentDescriptor entry = getInodeEntry(i);
            if (entry.getSegmentId() == segmentId) {
                return i;
            }
        }
        return -1;
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
     */
    public void initialize(MiniTransaction mtr) {
        // 设置页面类型
        setPageType(mtr, PageType.FIL_PAGE_INODE);

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
     */
    public void initAllEntries(MiniTransaction mtr) {
        for (int i = 0; i < INODES_PER_PAGE; i++) {
            int offset = getInodeEntryOffset(i);

            // 设置 Segment ID 为 0（表示未分配）
            putLong(offset + INODE_SEGMENT_ID, 0);

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
     */
    public void initEntry(MiniTransaction mtr, int entryIndex) {
        SegmentDescriptor entry = getInodeEntry(entryIndex);
        entry.clear(mtr);
    }

    /**
     * 分配一个新的 INODE Entry
     *
     * @param mtr       Mini-Transaction
     * @param segmentId Segment ID
     * @return Entry 索引（0-84），如果没有空闲 Entry 返回 -1
     */
    public int allocateEntry(MiniTransaction mtr, long segmentId) {
        if (segmentId == 0) {
            throw new IllegalArgumentException("Segment ID cannot be 0");
        }

        int freeIndex = findFreeEntry();
        if (freeIndex == -1) {
            return -1;  // 没有空闲 Entry
        }

        SegmentDescriptor entry = getInodeEntry(freeIndex);
        entry.initialize(mtr, segmentId);

        return freeIndex;
    }

    /**
     * 释放一个 INODE Entry
     *
     * @param mtr        Mini-Transaction
     * @param entryIndex Entry 索引（0-84）
     */
    public void freeEntry(MiniTransaction mtr, int entryIndex) {
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
}
