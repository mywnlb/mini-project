package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;

/**
 * TableSpace（表空间）逻辑管理
 *
 * <p>TableSpace是InnoDB存储的最顶层抽象，对应一个.ibd文件。
 * 本类封装表空间的逻辑操作：Segment创建/销毁、Extent分配/回收、空间扩展等。</p>
 *
 * <h2>表空间物理结构</h2>
 * <pre>
 * Page 0: FSP_HDR Page - 表空间头 + XDES Array (管理前256个extent)
 * Page 1: IBUF_BITMAP
 * Page 2: INODE Page - 段描述页
 * Page 3+: Data Pages
 * Page 16384: XDES Page - 区描述页 (管理extent 256-511)
 * Page 32768: XDES Page - 区描述页 (管理extent 512-767)
 * ...
 * </pre>
 *
 * <h2>核心链表</h2>
 * <ul>
 *   <li>FSP_FREE: 完全空闲的Extent链表</li>
 *   <li>FSP_FREE_FRAG: 有空闲页的碎片Extent链表（用于单页分配）</li>
 *   <li>FSP_FULL_FRAG: 完全占满的碎片Extent链表</li>
 *   <li>FSP_SEG_INODES_FREE: 有空闲slot的INODE Page链表</li>
 *   <li>FSP_SEG_INODES_FULL: 无空闲slot的INODE Page链表</li>
 * </ul>
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li>逻辑层：负责空间分配决策、链表管理、空间扩展</li>
 *   <li>物理层：FspHeaderPage负责读写FSP Header物理结构</li>
 *   <li>所有操作必须在MTR中完成</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class TableSpace {

    /**
     * 表空间ID
     */
    private final int spaceId;

    /**
     * Buffer Pool引用
     */
    private final BufferPool bufferPool;

    /**
     * 构造函数
     *
     * @param spaceId    表空间ID
     * @param bufferPool Buffer Pool
     */
    public TableSpace(int spaceId, BufferPool bufferPool) {
        if (bufferPool == null) {
            throw new IllegalArgumentException("BufferPool cannot be null");
        }
        if (spaceId < 0) {
            throw new IllegalArgumentException("Invalid spaceId: " + spaceId);
        }

        this.spaceId = spaceId;
        this.bufferPool = bufferPool;
    }

    /**
     * 获取表空间ID
     */
    public int getSpaceId() {
        return spaceId;
    }

    /**
     * 创建新的Segment
     *
     * @param mtr Mini-Transaction
     * @return 新创建的Segment，如果失败返回null
     * @throws MiniDbException 如果操作失败
     */
    public Segment createSegment(MiniTransaction mtr) throws MiniDbException {
        // 1. 分配Segment ID（逻辑层操作）
        FspHeaderPage fspHeader = getFspHeaderPage(mtr);
        long segmentId = allocateNewSegmentId(mtr, fspHeader);

        // 2. 查找或创建INODE Page
        InodePage inodePage = findOrCreateInodePage(mtr);
        if (inodePage == null) {
            return null;
        }

        // 3. 分配INODE Entry（逻辑层操作）
        int entryIndex = allocateInodeEntry(mtr, inodePage, segmentId);
        if (entryIndex == -1) {
            return null;
        }

        // 4. 初始化Segment Descriptor
        SegmentDescriptor descriptor = inodePage.getInodeEntry(entryIndex);
        descriptor.initialize(mtr, segmentId);

        // 5. 返回Segment逻辑对象
        return new Segment(spaceId, segmentId, descriptor, this);
    }

    /**
     * 获取已存在的Segment
     *
     * @param mtr       Mini-Transaction
     * @param segmentId Segment ID
     * @return Segment对象，如果不存在返回null
     * @throws MiniDbException 如果操作失败
     */
    public Segment getSegment(MiniTransaction mtr, long segmentId) throws MiniDbException {
        if (segmentId == 0) {
            throw new IllegalArgumentException("Invalid segment ID: 0");
        }

        FspHeaderPage fspHeader = getFspHeaderPage(mtr);

        // 1. 遍历FSP_SEG_INODES_FREE链表
        Segment segment = searchSegmentInList(mtr, fspHeader.getInodesFreeList(), segmentId);
        if (segment != null) {
            return segment;
        }

        // 2. 遍历FSP_SEG_INODES_FULL链表
        segment = searchSegmentInList(mtr, fspHeader.getInodesFullList(), segmentId);
        if (segment != null) {
            return segment;
        }

        // 3. 未找到
        return null;
    }

    /**
     * 在INODE Page链表中搜索指定的Segment
     *
     * @param mtr       Mini-Transaction
     * @param inodeList INODE Page链表（FREE或FULL）
     * @param segmentId 目标Segment ID
     * @return Segment对象，如果未找到返回null
     * @throws MiniDbException 如果操作失败
     */
    private Segment searchSegmentInList(MiniTransaction mtr, FlstBaseNode inodeList, long segmentId)
            throws MiniDbException {
        if (inodeList.isEmpty()) {
            return null;
        }

        // 遍历链表中的所有INODE Page
        PageId currentPageId = inodeList.getFirstNode();

        while (currentPageId != null) {
            // 加载INODE Page
            Page currentPage = mtr.getPage(currentPageId);
            InodePage inodePage = new InodePage(currentPage);

            // 遍历该页面的85个Entry
            for (int i = 0; i < INODES_PER_PAGE; i++) {
                SegmentDescriptor entry = inodePage.getInodeEntry(i);
                if (entry.getSegmentId() == segmentId) {
                    // 找到目标Segment
                    return new Segment(spaceId, segmentId, entry, this);
                }
            }

            // 移动到下一个INODE Page
            FlstNode listNode = inodePage.getListNode();
            int nextPageNo = listNode.getNextPageNo();
            if (nextPageNo == FIL_NULL) {
                break;  // 已到链表尾
            }
            currentPageId = PageId.of(spaceId, nextPageNo);
        }

        return null;
    }

    /**
     * 为Segment分配一个完整的Extent
     *
     * @param mtr       Mini-Transaction
     * @param segmentId 目标Segment ID
     * @return 分配的Extent，如果失败返回null
     * @throws MiniDbException 如果操作失败
     */
    public Extent allocateExtentForSegment(MiniTransaction mtr, long segmentId) throws MiniDbException {
        FspHeaderPage fspHeader = getFspHeaderPage(mtr);
        FlstBaseNode freeList = fspHeader.getFreeList();

        // 1. 从FREE链表获取空闲Extent
        if (!freeList.isEmpty()) {
            PageId firstExtentPageId = freeList.removeFirst(mtr);
            if (firstExtentPageId != null) {
                int firstExtentOffset = freeList.getFirstNodeOffset();

                // 计算Extent编号
                int extentNo = calculateExtentNo(firstExtentPageId.getPageNo(), firstExtentOffset);

                // 获取ExtentDescriptor
                Page xdesPage = mtr.getPage(firstExtentPageId);
                ExtentDescriptor descriptor = new ExtentDescriptor(xdesPage, firstExtentOffset - 8, extentNo);

                // 设置所属Segment和状态
                descriptor.setSegmentId(mtr, segmentId);
                descriptor.setState(mtr, ExtentState.FSEG);

                return new Extent(spaceId, extentNo, descriptor);
            }
        }

        // 2. 如果FREE链表为空，需要扩展表空间
        expandTablespace(mtr);

        // 3. 再次尝试从FREE链表分配（扩展后应该有空闲Extent了）
        if (!freeList.isEmpty()) {
            PageId firstExtentPageId = freeList.removeFirst(mtr);
            if (firstExtentPageId != null) {
                int firstExtentOffset = freeList.getFirstNodeOffset();
                int extentNo = calculateExtentNo(firstExtentPageId.getPageNo(), firstExtentOffset);

                Page xdesPage = mtr.getPage(firstExtentPageId);
                ExtentDescriptor descriptor = new ExtentDescriptor(xdesPage, firstExtentOffset - 8, extentNo);

                // 设置所属Segment和状态
                descriptor.setSegmentId(mtr, segmentId);
                descriptor.setState(mtr, ExtentState.FSEG);

                return new Extent(spaceId, extentNo, descriptor);
            }
        }

        return null;
    }

    /**
     * 扩展表空间
     *
     * <p>分配新的 Extent 组（256个 extent = 16384页 = 256MB），
     * 初始化 XDES Page 和所有 XDES Entry，并添加到 FSP_FREE 链表。</p>
     *
     * @param mtr Mini-Transaction
     * @throws MiniDbException 如果操作失败
     */
    private void expandTablespace(MiniTransaction mtr) throws MiniDbException {
        FspHeaderPage fspHeader = getFspHeaderPage(mtr);

        // 1. 获取当前表空间大小（以页为单位）
        int currentSize = fspHeader.getSize();

        // 2. 计算下一个 Extent 组的起始页号
        int nextGroupStartPage = ((currentSize / PAGES_PER_EXTENT_GROUP) + 1) * PAGES_PER_EXTENT_GROUP;

        // 3. 创建或获取 XDES Page
        // 新Extent组的第一个页面就是XDES Page（page 16384, 32768, ...）
        PageId xdesPageId = PageId.of(spaceId, nextGroupStartPage);
        Page xdesPageRaw = mtr.newPage(spaceId);  // 分配物理页面

        // 验证分配的页号
        if (xdesPageRaw.getPageNo() != nextGroupStartPage) {
            // 如果分配的页号不匹配，说明中间有空洞，需要补齐
            // 这是一个简化实现，生产环境需要更复杂的逻辑
            throw new MiniDbException(
                    String.format("Page allocation mismatch: expected %d, got %d",
                            nextGroupStartPage, xdesPageRaw.getPageNo()));
        }

        XdesPage xdesPage = new XdesPage(xdesPageRaw.getPageId(), xdesPageRaw.getBuffer());

        // 4. 初始化 XDES Page（设置所有256个XDES Entry为FREE状态）
        xdesPage.initialize(mtr);

        // 5. 获取该XDES Page管理的Extent范围
        int[] extentRange = xdesPage.getLocalExtentRange();
        int startExtentNo = extentRange[0];
        int endExtentNo = extentRange[1];

        // 6. 将所有新Extent添加到FSP_FREE链表
        FlstBaseNode freeList = fspHeader.getFreeList();

        for (int extentNo = startExtentNo; extentNo <= endExtentNo; extentNo++) {
            ExtentDescriptor extentDesc = xdesPage.getXdesEntry(extentNo);
            FlstNode extentNode = extentDesc.getListNode();

            // 添加到FREE链表尾部
            freeList.addLast(mtr, xdesPage, extentNode.getOffset());
        }

        // 7. 更新FSP Header的表空间大小
        int newSize = nextGroupStartPage + PAGES_PER_EXTENT_GROUP;
        fspHeader.setSize(mtr, newSize);

        // 8. 更新FREE_LIMIT（已初始化的页数）
        fspHeader.setFreeLimit(mtr, newSize);
    }

    /**
     * 分配碎片页（Fragment Page）
     *
     * <p>从FREE_FRAG链表的Extent中分配单个页面。</p>
     *
     * @param mtr Mini-Transaction
     * @return 分配的页面，如果失败返回null
     * @throws MiniDbException 如果操作失败
     */
    public Page allocateFragmentPage(MiniTransaction mtr) throws MiniDbException {
        FspHeaderPage fspHeader = getFspHeaderPage(mtr);
        FlstBaseNode freeFragList = fspHeader.getFreeFragList();

        // 1. 从FREE_FRAG链表获取有空闲页的Extent
        if (!freeFragList.isEmpty()) {
            PageId fragExtentPageId = freeFragList.getFirstNode();
            int fragExtentOffset = freeFragList.getFirstNodeOffset();

            if (fragExtentPageId != null) {
                // 获取Extent
                int extentNo = calculateExtentNo(fragExtentPageId.getPageNo(), fragExtentOffset);
                Page xdesPage = mtr.getPage(fragExtentPageId);
                ExtentDescriptor descriptor = new ExtentDescriptor(xdesPage, fragExtentOffset - 8, extentNo);
                Extent extent = new Extent(spaceId, extentNo, descriptor);

                // 分配一个页面
                int pageNo = extent.allocatePage(mtr);
                if (pageNo != -1) {
                    Page page = mtr.newPage(spaceId);  // 实际应该用pageNo

                    // 如果Extent满了，移到FULL_FRAG链表
                    if (extent.isFull()) {
                        moveExtentToFullFrag(mtr, extent);
                    }

                    return page;
                }
            }
        }

        // 2. 如果FREE_FRAG为空，从FREE链表拿一个Extent转为FREE_FRAG
        Extent newFragExtent = allocateExtentFromFree(mtr);
        if (newFragExtent != null) {
            newFragExtent.setState(mtr, ExtentState.FREE_FRAG);
            addExtentToFreeFragList(mtr, newFragExtent);

            int pageNo = newFragExtent.allocatePage(mtr);
            if (pageNo != -1) {
                return mtr.newPage(spaceId);
            }
        }

        return null;
    }

    /**
     * 将Extent归还到FREE链表
     *
     * @param mtr    Mini-Transaction
     * @param extent 要归还的Extent
     * @throws MiniDbException 如果操作失败
     */
    public void returnExtentToFree(MiniTransaction mtr, Extent extent) throws MiniDbException {
        FspHeaderPage fspHeader = getFspHeaderPage(mtr);
        FlstBaseNode freeList = fspHeader.getFreeList();

        // 清空Extent状态
        extent.setSegmentId(mtr, 0);
        extent.setState(mtr, ExtentState.FREE);

        // 添加到FREE链表
        FlstNode extentNode = extent.getListNode();
        Page extentPage = extentNode.getPage();
        int extentNodeOffset = extentNode.getOffset();
        freeList.addLast(mtr, extentPage, extentNodeOffset);
    }

    /**
     * 获取指定Extent
     *
     * @param mtr      Mini-Transaction
     * @param extentNo Extent编号
     * @return Extent对象
     * @throws MiniDbException 如果操作失败
     */
    public Extent getExtent(MiniTransaction mtr, int extentNo) throws MiniDbException {
        // 1. 计算XDES Page号
        int xdesPageNo = XdesPage.getXdesPageNo(extentNo);

        // 2. 获取XDES Page
        PageId xdesPageId = PageId.of(spaceId, xdesPageNo);
        Page xdesPage = mtr.getPage(xdesPageId);

        // 3. 获取XDES Entry
        int xdesEntryOffset = XdesPage.getXdesEntryOffset(extentNo);
        ExtentDescriptor descriptor = new ExtentDescriptor(xdesPage, xdesEntryOffset, extentNo);

        return new Extent(spaceId, extentNo, descriptor);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 获取FSP Header Page
     */
    private FspHeaderPage getFspHeaderPage(MiniTransaction mtr) throws MiniDbException {
        PageId fspPageId = PageId.of(spaceId, 0);
        Page page = mtr.getPage(fspPageId);
        return new FspHeaderPage(page);
    }

    /**
     * 查找或创建INODE Page
     */
    private InodePage findOrCreateInodePage(MiniTransaction mtr) throws MiniDbException {
        FspHeaderPage fspHeader = getFspHeaderPage(mtr);
        FlstBaseNode inodesFreeList = fspHeader.getInodesFreeList();

        // 1. 如果有空闲的INODE Page，直接使用
        if (!inodesFreeList.isEmpty()) {
            PageId inodePageId = inodesFreeList.getFirstNode();
            if (inodePageId != null) {
                Page inodePage = mtr.getPage(inodePageId);
                return new InodePage(inodePage);
            }
        }

        // 2. 创建新的INODE Page
        Page newPage = mtr.newPage(spaceId);
        InodePage newInodePage = new InodePage(newPage.getPageId(), newPage.getBuffer());

        // 3. 初始化INODE Page（设置页面类型、初始化所有Entry）
        newInodePage.initialize(mtr);

        // 4. 添加到FSP_SEG_INODES_FREE链表
        FlstNode inodeListNode = newInodePage.getListNode();
        inodesFreeList.addLast(mtr, newInodePage, inodeListNode.getOffset());

        return newInodePage;
    }

    /**
     * 从FREE链表分配Extent
     */
    private Extent allocateExtentFromFree(MiniTransaction mtr) throws MiniDbException {
        FspHeaderPage fspHeader = getFspHeaderPage(mtr);
        FlstBaseNode freeList = fspHeader.getFreeList();

        if (!freeList.isEmpty()) {
            PageId firstExtentPageId = freeList.removeFirst(mtr);
            if (firstExtentPageId != null) {
                int firstExtentOffset = freeList.getFirstNodeOffset();
                int extentNo = calculateExtentNo(firstExtentPageId.getPageNo(), firstExtentOffset);

                Page xdesPage = mtr.getPage(firstExtentPageId);
                ExtentDescriptor descriptor = new ExtentDescriptor(xdesPage, firstExtentOffset - 8, extentNo);

                return new Extent(spaceId, extentNo, descriptor);
            }
        }

        return null;
    }

    /**
     * 将Extent移到FULL_FRAG链表
     */
    private void moveExtentToFullFrag(MiniTransaction mtr, Extent extent) throws MiniDbException {
        FspHeaderPage fspHeader = getFspHeaderPage(mtr);
        FlstBaseNode freeFragList = fspHeader.getFreeFragList();
        FlstBaseNode fullFragList = fspHeader.getFullFragList();

        // 1. 从FREE_FRAG链表移除
        FlstNode extentNode = extent.getListNode();
        Page extentPage = extentNode.getPage();
        int extentNodeOffset = extentNode.getOffset();
        freeFragList.remove(mtr, extentPage, extentNodeOffset);

        // 2. 更新Extent状态为FULL_FRAG
        extent.setState(mtr, ExtentState.FULL_FRAG);

        // 3. 添加到FULL_FRAG链表
        fullFragList.addLast(mtr, extentPage, extentNodeOffset);
    }

    /**
     * 将Extent添加到FREE_FRAG链表
     */
    private void addExtentToFreeFragList(MiniTransaction mtr, Extent extent) throws MiniDbException {
        FspHeaderPage fspHeader = getFspHeaderPage(mtr);
        FlstBaseNode freeFragList = fspHeader.getFreeFragList();

        FlstNode extentNode = extent.getListNode();
        Page extentPage = extentNode.getPage();
        int extentNodeOffset = extentNode.getOffset();
        freeFragList.addLast(mtr, extentPage, extentNodeOffset);
    }

    /**
     * 分配新的Segment ID（逻辑层操作）
     *
     * @param mtr       Mini-Transaction
     * @param fspHeader FSP Header Page
     * @return 新的Segment ID
     * @throws MiniDbException 如果操作失败
     */
    private long allocateNewSegmentId(MiniTransaction mtr, FspHeaderPage fspHeader)
            throws MiniDbException {
        long currentId = fspHeader.getNextSegmentId();  // 物理层读取
        fspHeader.setNextSegmentId(mtr, currentId + 1);  // 物理层修改
        return currentId;
    }

    /**
     * 分配INODE Entry（逻辑层操作）
     *
     * @param mtr       Mini-Transaction
     * @param inodePage INODE Page
     * @param segmentId Segment ID
     * @return Entry索引（0-84），如果失败返回-1
     * @throws MiniDbException 如果操作失败
     */
    private int allocateInodeEntry(MiniTransaction mtr, InodePage inodePage, long segmentId)
            throws MiniDbException {
        if (segmentId == 0) {
            throw new IllegalArgumentException("Segment ID cannot be 0");
        }

        // 查找空闲Entry（逻辑层搜索）
        int freeIndex = findFreeInodeEntry(inodePage);
        if (freeIndex == -1) {
            return -1;  // 没有空闲Entry
        }

        // 初始化Entry（物理层操作）
        SegmentDescriptor entry = inodePage.getInodeEntry(freeIndex);
        entry.initialize(mtr, segmentId);

        return freeIndex;
    }

    /**
     * 查找空闲的INODE Entry（逻辑层搜索）
     *
     * @param inodePage INODE Page
     * @return Entry索引（0-84），如果没有空闲返回-1
     */
    private int findFreeInodeEntry(InodePage inodePage) {
        for (int i = 0; i < 85; i++) {  // INODES_PER_PAGE = 85
            SegmentDescriptor entry = inodePage.getInodeEntry(i);
            if (entry.getSegmentId() == 0) {  // 调用物理层读取
                return i;
            }
        }
        return -1;
    }

    /**
     * 计算Extent编号
     */
    private int calculateExtentNo(int xdesPageNo, int xdesEntryOffset) {
        int pageGroup = xdesPageNo / 16384;
        int localIndex = (xdesEntryOffset - FIL_HEADER_SIZE) / 40;
        return pageGroup * 256 + localIndex;
    }

    @Override
    public String toString() {
        return String.format("TableSpace{spaceId=%d}", spaceId);
    }
}
