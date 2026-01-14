package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;

/**
 * Segment 管理器实现类（逻辑层）
 *
 * <p>SegmentManagerImpl 实现了完整的 Segment 管理逻辑，
 * 包括3阶段页面分配策略和 Extent 链表管理。</p>
 *
 * <h2>核心算法</h2>
 * <ul>
 *   <li>3阶段分配：碎片页（0-31） → Partial Extent → Free Extent</li>
 *   <li>状态转移：FREE → FSEG_FREE → FSEG (NOT_FULL) → FSEG (FULL)</li>
 *   <li>链表迁移：根据 Extent 使用情况自动迁移链表</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class SegmentManagerImpl implements SegmentManager {

    private final BufferPool bufferPool;
    private final ExtentManager extentManager;
    private final SpaceManager spaceManager;

    /**
     * 构造函数
     *
     * @param bufferPool    Buffer Pool 实例
     * @param extentManager Extent Manager 实例
     * @param spaceManager  Space Manager 实例
     */
    public SegmentManagerImpl(BufferPool bufferPool, ExtentManager extentManager, SpaceManager spaceManager) {
        if (bufferPool == null) {
            throw new IllegalArgumentException("BufferPool cannot be null");
        }
        if (extentManager == null) {
            throw new IllegalArgumentException("ExtentManager cannot be null");
        }
        if (spaceManager == null) {
            throw new IllegalArgumentException("SpaceManager cannot be null");
        }
        this.bufferPool = bufferPool;
        this.extentManager = extentManager;
        this.spaceManager = spaceManager;
    }

    @Override
    public long createSegment(MiniTransaction mtr, int spaceId) throws MiniDbException {
        // Step 1: 加载 FSP Header（page 0）
        FspHeaderPage fspHdr = loadFspHeader(mtr, spaceId);

        // Step 2: 分配新的 Segment ID
        long segmentId = fspHdr.getNextSegmentId();
        fspHdr.setNextSegmentId(mtr, segmentId + 1);

        // Step 3: 查找或创建有空闲 Entry 的 INODE Page
        InodePage inodePage = findOrCreateInodePage(mtr, spaceId, fspHdr);

        // Step 4: 在 INODE Page 中分配 Entry
        int entryIndex = inodePage.findFreeEntry();
        if (entryIndex == -1) {
            throw new MiniDbException("INODE Page should have free entry but found none");
        }

        // Step 5: 初始化 INODE Entry
        SegmentDescriptor segment = inodePage.getInodeEntry(entryIndex);
        segment.initialize(mtr, segmentId);

        // Step 6: 检查 INODE Page 是否已满，需要迁移链表
        if (inodePage.isFull()) {
            moveInodePageToFullList(mtr, fspHdr, inodePage);
        }

        return segmentId;
    }

    @Override
    public void dropSegment(MiniTransaction mtr, int spaceId, long segmentId) throws MiniDbException {
        // Step 1: 查找 Segment 的 INODE Entry
        SegmentDescriptor segment = getSegmentDescriptor(mtr, spaceId, segmentId);
        if (segment == null) {
            throw new MiniDbException("Segment not found: " + segmentId);
        }

        FspHeaderPage fspHdr = loadFspHeader(mtr, spaceId);

        // Step 2: 释放碎片页数组中的所有页面
        for (int i = 0; i < INODE_FRAG_ARRAY_PAGES; i++) {
            int pageNo = segment.getFragPageNo(i);
            if (pageNo != FIL_NULL) {
                // 归还到表空间 FREE_FRAG 链表
                spaceManager.freeFragPage(mtr, PageId.of(spaceId, pageNo));
                segment.setFragPageNo(mtr, i, FIL_NULL);
            }
        }

        // Step 3: 释放 FREE 链表中的所有 Extent
        releaseExtentList(mtr, spaceId, fspHdr, segment.getFreeList());

        // Step 4: 释放 NOT_FULL 链表中的所有 Extent
        releaseExtentList(mtr, spaceId, fspHdr, segment.getNotFullList());

        // Step 5: 释放 FULL 链表中的所有 Extent
        releaseExtentList(mtr, spaceId, fspHdr, segment.getFullList());

        // Step 6: 清空 INODE Entry
        segment.clear(mtr);
    }

    @Override
    public PageId allocatePageForSegment(MiniTransaction mtr, int spaceId, long segmentId)
            throws MiniDbException {
        // Step 1: 加载 Segment 描述符
        SegmentDescriptor segment = getSegmentDescriptor(mtr, spaceId, segmentId);
        if (segment == null) {
            throw new MiniDbException("Segment not found: " + segmentId);
        }

        // ===== 阶段1：碎片页分配（前32页）=====
        int fragUsed = segment.getFragUsedCount();
        if (fragUsed < INODE_FRAG_ARRAY_PAGES) {
            PageId fragPage = allocateFragmentPageForSegment(mtr, spaceId, segment);
            if (fragPage != null) {
                return fragPage;
            }
            // 如果碎片页分配失败（表空间 FREE_FRAG 为空），继续尝试 Extent 分配
        }

        // ===== 阶段2：Partial Extent 分配 =====
        if (!segment.getNotFullList().isEmpty()) {
            PageId partialPage = allocateFromPartialExtent(mtr, spaceId, segment);
            if (partialPage != null) {
                return partialPage;
            }
        }

        // ===== 阶段3：Free Extent 分配 =====
        return allocateFromFreeExtent(mtr, spaceId, segment);
    }

    @Override
    public void freePage(MiniTransaction mtr, PageId pageId) throws MiniDbException {
        int spaceId = pageId.getSpaceId();
        int pageNo = pageId.getPageNo();

        // Step 1: 判断页面属于哪个 Extent
        int extentNo = pageNo / EXTENT_SIZE;
        ExtentDescriptor extent = extentManager.getExtentDescriptor(mtr, spaceId, extentNo);

        // Step 2: 获取 Segment ID
        long segmentId = extent.getSegmentId();
        if (segmentId == 0) {
            // 页面属于表空间碎片页，直接归还
            spaceManager.freeFragPage(mtr, pageId);
            return;
        }

        // Step 3: 加载 Segment 描述符
        SegmentDescriptor segment = getSegmentDescriptor(mtr, spaceId, segmentId);
        if (segment == null) {
            throw new MiniDbException("Segment not found: " + segmentId);
        }

        // Step 4: 检查是否是碎片页
        int pageOffset = pageNo % EXTENT_SIZE;
        boolean isFragPage = false;
        for (int i = 0; i < INODE_FRAG_ARRAY_PAGES; i++) {
            if (segment.getFragPageNo(i) == pageNo) {
                // 从碎片页数组移除
                segment.setFragPageNo(mtr, i, FIL_NULL);
                isFragPage = true;
                break;
            }
        }

        if (isFragPage) {
            // 归还到表空间 FREE_FRAG
            spaceManager.freeFragPage(mtr, pageId);
            return;
        }

        // Step 5: 不是碎片页，是 Extent 页
        boolean wasFullBeforeFree = extent.isFull();

        // 释放页面
        extentManager.freePageInExtent(mtr, extent, pageOffset);

        // Step 6: 检查状态转移（FULL → NOT_FULL）
        if (wasFullBeforeFree && !extent.isFull()) {
            moveExtentFromFullToNotFull(mtr, segment, extent);
        }
    }

    @Override
    public ExtentDescriptor allocateExtentForSegment(MiniTransaction mtr, int spaceId, long segmentId)
            throws MiniDbException {
        // Step 1: 加载 FSP Header
        FspHeaderPage fspHdr = loadFspHeader(mtr, spaceId);

        // Step 2: 检查表空间 FREE 链表是否为空
        if (fspHdr.getFreeList().isEmpty()) {
            // 自动扩展表空间（扩展1个 Extent）
            spaceManager.extendTablespace(mtr, spaceId, 1);

            // 扩展后重新检查
            if (fspHdr.getFreeList().isEmpty()) {
                throw new MiniDbException("Failed to extend tablespace, FREE list still empty");
            }
        }

        // Step 3: 从 FREE 链表移除第一个 Extent
        FilAddr removedAddr = fspHdr.getFreeList().removeFirstAndGetAddr(mtr);
        if (removedAddr.isNull()) {
            return null;
        }

        // Step 4: 加载 Extent 描述符
        Page extentPage = mtr.getPage(PageId.of(spaceId, removedAddr.getPageNo()));
        int extentOffset = removedAddr.getOffset() - XDES_FLST_NODE;
        int extentNo = calculateExtentNo(removedAddr.getPageNo(), extentOffset);
        ExtentDescriptor extent = new ExtentDescriptor(extentPage, extentOffset, extentNo);

        // Step 5: 设置 Extent 归属
        extent.setSegmentId(mtr, segmentId);
        extent.setState(mtr, ExtentState.FSEG_FREE);

        // Step 6: 加载 Segment 并加入其 FREE 链表
        SegmentDescriptor segment = getSegmentDescriptor(mtr, spaceId, segmentId);
        if (segment == null) {
            throw new MiniDbException("Segment not found: " + segmentId);
        }

        segment.getFreeList().addLast(mtr, extentPage, extent.getListNode().getOffset());

        return extent;
    }

    @Override
    public SegmentDescriptor getSegmentDescriptor(MiniTransaction mtr, int spaceId, long segmentId)
            throws MiniDbException {
        FspHeaderPage fspHdr = loadFspHeader(mtr, spaceId);

        // Step 1: 在 INODES_FREE 链表中查找
        SegmentDescriptor segment = scanInodeListForSegment(mtr, spaceId,
                fspHdr.getInodesFreeList(), segmentId);
        if (segment != null) {
            return segment;
        }

        // Step 2: 在 INODES_FULL 链表中查找
        return scanInodeListForSegment(mtr, spaceId, fspHdr.getInodesFullList(), segmentId);
    }

    @Override
    public SegmentStatistics getStatistics(MiniTransaction mtr, int spaceId, long segmentId)
            throws MiniDbException {
        SegmentDescriptor segment = getSegmentDescriptor(mtr, spaceId, segmentId);
        if (segment == null) {
            throw new MiniDbException("Segment not found: " + segmentId);
        }

        return new SegmentStatistics(
                segmentId,
                segment.getFragUsedCount(),
                segment.getFreeList().getLength(),
                segment.getNotFullList().getLength(),
                segment.getFullList().getLength()
        );
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 加载 FSP Header（page 0）
     */
    private FspHeaderPage loadFspHeader(MiniTransaction mtr, int spaceId) throws MiniDbException {
        Page p0 = mtr.getPage(PageId.of(spaceId, 0));
        return new FspHeaderPage(p0);
    }

    /**
     * 查找或创建有空闲 Entry 的 INODE Page
     */
    private InodePage findOrCreateInodePage(MiniTransaction mtr, int spaceId, FspHeaderPage fspHdr)
            throws MiniDbException {
        // Step 1: 检查 INODES_FREE 链表是否有页面
        FlstBaseNode inodesFreeList = fspHdr.getInodesFreeList();
        if (!inodesFreeList.isEmpty()) {
            // 返回链表中的第一个 INODE Page
            PageId firstInodePageId = inodesFreeList.getFirstNode();
            Page inodePageRaw = mtr.getPage(firstInodePageId);
            return new InodePage(inodePageRaw);
        }

        // Step 2: INODES_FREE 为空，需要分配新的 INODE Page
        // TODO: 从表空间分配新页面（需要 SpaceManager）
        // 暂时使用 mtr.newPage() 模拟
        Page newInodePageRaw = mtr.newPage(spaceId);
        InodePage newInodePage = new InodePage(newInodePageRaw);
        newInodePage.initialize(mtr);

        // Step 3: 将新 INODE Page 加入 INODES_FREE 链表
        inodesFreeList.addLast(mtr, newInodePageRaw, newInodePage.getListNode().getOffset());

        return newInodePage;
    }

    /**
     * 将 INODE Page 从 FREE 链表移动到 FULL 链表
     */
    private void moveInodePageToFullList(MiniTransaction mtr, FspHeaderPage fspHdr, InodePage inodePage)
            throws MiniDbException {
        // Step 1: 从 FREE 链表移除
        fspHdr.getInodesFreeList().remove(mtr, inodePage, inodePage.getListNode().getOffset());

        // Step 2: 加入 FULL 链表
        fspHdr.getInodesFullList().addLast(mtr, inodePage, inodePage.getListNode().getOffset());
    }

    /**
     * 释放 Extent 链表中的所有 Extent（归还到表空间 FREE 链表）
     */
    private void releaseExtentList(MiniTransaction mtr, int spaceId, FspHeaderPage fspHdr,
                                    FlstBaseNode extentList) throws MiniDbException {
        while (!extentList.isEmpty()) {
            FilAddr removedAddr = extentList.removeFirstAndGetAddr(mtr);
            if (removedAddr.isNull()) {
                break;
            }

            // 加载 Extent 并重置状态
            Page extentPage = mtr.getPage(PageId.of(spaceId, removedAddr.getPageNo()));
            int extentOffset = removedAddr.getOffset() - XDES_FLST_NODE;
            int extentNo = calculateExtentNo(removedAddr.getPageNo(), extentOffset);
            ExtentDescriptor extent = new ExtentDescriptor(extentPage, extentOffset, extentNo);

            // 重新初始化为 FREE 状态
            extentManager.initializeExtent(mtr, extent);

            // 加入表空间 FREE 链表
            fspHdr.getFreeList().addLast(mtr, extentPage, extent.getListNode().getOffset());
        }
    }

    /**
     * 在 INODE 链表中扫描查找 Segment
     */
    private SegmentDescriptor scanInodeListForSegment(MiniTransaction mtr, int spaceId,
                                                       FlstBaseNode inodeList, long segmentId)
            throws MiniDbException {
        if (inodeList.isEmpty()) {
            return null;
        }

        PageId curPageId = inodeList.getFirstNode();
        while (curPageId != null) {
            Page inodePageRaw = mtr.getPage(curPageId);
            InodePage inodePage = new InodePage(inodePageRaw);

            // 遍历该 INODE Page 的所有 Entry
            for (int i = 0; i < INODES_PER_PAGE; i++) {
                SegmentDescriptor entry = inodePage.getInodeEntry(i);
                if (entry.getSegmentId() == segmentId) {
                    return entry;
                }
            }

            // 移动到下一个 INODE Page
            FlstNode listNode = inodePage.getListNode();
            int nextPageNo = listNode.getNextPageNo();
            if (nextPageNo == FIL_NULL) {
                break;
            }
            curPageId = PageId.of(spaceId, nextPageNo);
        }

        return null;
    }

    /**
     * 为 Segment 分配碎片页（阶段1）
     */
    private PageId allocateFragmentPageForSegment(MiniTransaction mtr, int spaceId,
                                                   SegmentDescriptor segment) throws MiniDbException {
        // 从表空间 FREE_FRAG 链表分配页面
        PageId fragPage = spaceManager.allocateFragPage(mtr, spaceId);
        if (fragPage == null) {
            return null;  // 表空间 FREE_FRAG 为空，无法分配碎片页
        }

        // 将页面记录到 Segment 的碎片页数组
        boolean added = false;
        for (int i = 0; i < INODE_FRAG_ARRAY_PAGES; i++) {
            if (segment.getFragPageNo(i) == FIL_NULL) {
                segment.setFragPageNo(mtr, i, fragPage.getPageNo());
                added = true;
                break;
            }
        }

        if (!added) {
            // 碎片页数组已满（理论上不应该发生，因为调用者会检查 fragUsed < 32）
            throw new MiniDbException("Fragment array is full but allocateFragmentPageForSegment was called");
        }

        return fragPage;
    }

    /**
     * 从 Partial Extent 分配页面（阶段2）
     */
    private PageId allocateFromPartialExtent(MiniTransaction mtr, int spaceId,
                                             SegmentDescriptor segment) throws MiniDbException {
        FlstBaseNode notFullList = segment.getNotFullList();
        if (notFullList.isEmpty()) {
            return null;
        }

        // 获取链表首个 Extent
        FilAddr firstAddr = notFullList.getFirstNodeAddr();
        Page extentPage = mtr.getPage(PageId.of(spaceId, firstAddr.getPageNo()));
        int extentOffset = firstAddr.getOffset() - XDES_FLST_NODE;
        int extentNo = calculateExtentNo(firstAddr.getPageNo(), extentOffset);
        ExtentDescriptor extent = new ExtentDescriptor(extentPage, extentOffset, extentNo);

        // 分配页面
        int pageOffset = extentManager.allocatePageInExtent(mtr, extent);
        if (pageOffset == -1) {
            throw new MiniDbException("NOT_FULL extent should have free pages");
        }

        // 检查是否变满，需要迁移到 FULL 链表
        if (extent.isFull()) {
            moveExtentFromNotFullToFull(mtr, segment, extent);
        }

        return PageId.of(spaceId, extent.getStartPageNo() + pageOffset);
    }

    /**
     * 从 Free Extent 分配页面（阶段3）
     */
    private PageId allocateFromFreeExtent(MiniTransaction mtr, int spaceId,
                                          SegmentDescriptor segment) throws MiniDbException {
        FlstBaseNode freeList = segment.getFreeList();

        // 如果 FREE 链表为空，尝试分配新 Extent
        if (freeList.isEmpty()) {
            ExtentDescriptor newExtent = allocateExtentForSegment(mtr, spaceId, segment.getSegmentId());
            if (newExtent == null) {
                throw new MiniDbException("Failed to allocate extent for segment");
            }
        }

        // 获取链表首个 Extent
        FilAddr firstAddr = freeList.getFirstNodeAddr();
        Page extentPage = mtr.getPage(PageId.of(spaceId, firstAddr.getPageNo()));
        int extentOffset = firstAddr.getOffset() - XDES_FLST_NODE;
        int extentNo = calculateExtentNo(firstAddr.getPageNo(), extentOffset);
        ExtentDescriptor extent = new ExtentDescriptor(extentPage, extentOffset, extentNo);

        // 分配第一个页面
        int pageOffset = extentManager.allocatePageInExtent(mtr, extent);
        if (pageOffset == -1) {
            throw new MiniDbException("FREE extent should have all pages free");
        }

        // 移动到 NOT_FULL 链表
        moveExtentFromFreeToNotFull(mtr, segment, extent);

        return PageId.of(spaceId, extent.getStartPageNo() + pageOffset);
    }

    /**
     * 移动 Extent：FREE → NOT_FULL
     */
    private void moveExtentFromFreeToNotFull(MiniTransaction mtr, SegmentDescriptor segment,
                                             ExtentDescriptor extent) throws MiniDbException {
        // 从 FREE 链表移除
        segment.getFreeList().remove(mtr, extent.getListNode().getPage(),
                extent.getListNode().getOffset());

        // 修改状态
        extent.setState(mtr, ExtentState.FSEG);

        // 加入 NOT_FULL 链表
        segment.getNotFullList().addLast(mtr, extent.getListNode().getPage(),
                extent.getListNode().getOffset());
    }

    /**
     * 移动 Extent：NOT_FULL → FULL
     */
    private void moveExtentFromNotFullToFull(MiniTransaction mtr, SegmentDescriptor segment,
                                             ExtentDescriptor extent) throws MiniDbException {
        // 从 NOT_FULL 链表移除
        segment.getNotFullList().remove(mtr, extent.getListNode().getPage(),
                extent.getListNode().getOffset());

        // 加入 FULL 链表
        segment.getFullList().addLast(mtr, extent.getListNode().getPage(),
                extent.getListNode().getOffset());
    }

    /**
     * 移动 Extent：FULL → NOT_FULL
     */
    private void moveExtentFromFullToNotFull(MiniTransaction mtr, SegmentDescriptor segment,
                                             ExtentDescriptor extent) throws MiniDbException {
        // 从 FULL 链表移除
        segment.getFullList().remove(mtr, extent.getListNode().getPage(),
                extent.getListNode().getOffset());

        // 加入 NOT_FULL 链表
        segment.getNotFullList().addLast(mtr, extent.getListNode().getPage(),
                extent.getListNode().getOffset());
    }

    /**
     * 根据页号和偏移计算 Extent 编号
     */
    private int calculateExtentNo(int pageNo, int extentOffset) {
        if (pageNo == 0) {
            // FSP_HDR: XDES Array 从 XDES_ARR_OFFSET(150) 开始
            return (extentOffset - XDES_ARR_OFFSET) / XDES_ENTRY_SIZE;
        } else {
            // XDES Page: XDES Array 从 FIL_HEADER_SIZE(38) 开始
            int groupNo = pageNo / PAGES_PER_EXTENT_GROUP;
            int localIndex = (extentOffset - FIL_HEADER_SIZE) / XDES_ENTRY_SIZE;
            return groupNo * EXTENTS_PER_GROUP + localIndex;
        }
    }

    @Override
    public String toString() {
        return String.format("SegmentManagerImpl{bufferPool=%s, extentManager=%s, spaceManager=%s}",
                bufferPool, extentManager, spaceManager);
    }
}
