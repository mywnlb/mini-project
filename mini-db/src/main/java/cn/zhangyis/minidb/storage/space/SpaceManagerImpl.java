package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.page.PageType;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;

/**
 * Space Manager 实现类
 *
 * <p>SpaceManagerImpl 实现了完整的表空间管理逻辑，
 * 包括表空间初始化、Extent分配、碎片页管理和表空间扩展。</p>
 *
 * <h2>核心算法</h2>
 * <ul>
 *   <li>碎片页分配：从 FREE_FRAG 链表分配，Extent 满时移到 FULL_FRAG</li>
 *   <li>Extent 分配：从 FREE 链表分配，必要时自动扩展表空间</li>
 *   <li>表空间扩展：每次扩展4个Extent（4MB），自动处理 XDES Page 创建</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class SpaceManagerImpl implements SpaceManager {

    private final BufferPool bufferPool;
    private final ExtentManager extentManager;

    /**
     * 构造函数
     *
     * @param bufferPool    Buffer Pool 实例
     * @param extentManager Extent Manager 实例
     */
    public SpaceManagerImpl(BufferPool bufferPool, ExtentManager extentManager) {
        if (bufferPool == null) {
            throw new IllegalArgumentException("BufferPool cannot be null");
        }
        if (extentManager == null) {
            throw new IllegalArgumentException("ExtentManager cannot be null");
        }
        this.bufferPool = bufferPool;
        this.extentManager = extentManager;
    }

    @Override
    public void initializeTablespace(MiniTransaction mtr, int spaceId) throws MiniDbException {
        // Step 1: 初始化 Page 0 (FSP_HDR)
        Page p0 = mtr.newPage(spaceId);
        if (p0.getPageNo() != 0) {
            throw new MiniDbException("First page must be page 0, got: " + p0.getPageNo());
        }

        FspHeaderPage fsp = new FspHeaderPage(p0);
        fsp.initialize(mtr, spaceId);

        // Step 2: 初始化前256个 Extent 的 XDES Entry（位于 page 0）
        // 此时表空间还没有扩展，所以只初始化 Extent 0
        ExtentDescriptor ext0 = fsp.getXdesEntry(0);
        ext0.initialize(mtr);
        ext0.setState(mtr, ExtentState.FSEG);  // Extent 0 用于系统页，不加入 FREE 链表

        // Step 3: 扩展表空间到第一个完整 Extent（64页）
        // 这会将 Extent 1 加入 FREE 链表
        extendTablespace(mtr, spaceId, 1);

        // Step 4: 创建第一个 INODE Page（Page 2）
        // 注意：Page 0 是 FSP_HDR，Page 1 是 IBUF_BITMAP（暂未实现，保留），Page 2 是第一个 INODE Page
        Page p1 = mtr.newPage(spaceId);  // Page 1 (占位，暂时未使用)
        p1.setPageType(PageType.FIL_PAGE_TYPE_ALLOCATED);
        mtr.markDirty(p1);

        Page p2 = mtr.newPage(spaceId);  // Page 2 (INODE Page)
        InodePage inodePage = new InodePage(p2);
        inodePage.initialize(mtr);

        // Step 5: 将 INODE Page 加入 INODES_FREE 链表
        fsp.getInodesFreeList().addLast(mtr, inodePage, inodePage.getListNode().getOffset());

        mtr.markDirty(fsp);
    }

    @Override
    public ExtentDescriptor allocateExtent(MiniTransaction mtr, int spaceId) throws MiniDbException {
        // Step 1: 加载 FSP Header
        FspHeaderPage fsp = loadFspHeader(mtr, spaceId);

        // Step 2: 检查 FREE 链表是否为空
        FlstBaseNode freeList = fsp.getFreeList();
        if (freeList.isEmpty()) {
            // 扩展表空间（1个Extent = 1MB，避免 Buffer Pool 耗尽）
            extendTablespace(mtr, spaceId, 1);

            // 重新检查
            if (freeList.isEmpty()) {
                throw new MiniDbException("Failed to extend tablespace");
            }
        }

        // Step 3: 从 FREE 链表移除第一个 Extent
        FilAddr removedAddr = freeList.removeFirstAndGetAddr(mtr);
        if (removedAddr.isNull()) {
            return null;
        }

        // Step 4: 加载 Extent 描述符
        Page extentPage = mtr.getPage(PageId.of(spaceId, removedAddr.getPageNo()));
        int extentOffset = removedAddr.getOffset() - XDES_FLST_NODE;
        int extentNo = calculateExtentNo(removedAddr.getPageNo(), extentOffset);
        ExtentDescriptor extent = new ExtentDescriptor(extentPage, extentOffset, extentNo);

        return extent;
    }

    @Override
    public void freeExtent(MiniTransaction mtr, ExtentDescriptor extent) throws MiniDbException {
        // Step 1: 获取 Extent 所在的表空间
        int spaceId = extent.getListNode().getPage().getSpaceId();
        FspHeaderPage fsp = loadFspHeader(mtr, spaceId);

        // Step 2: 重新初始化 Extent（清空状态）
        extentManager.initializeExtent(mtr, extent);

        // Step 3: 加入表空间 FREE 链表
        fsp.getFreeList().addLast(mtr, extent.getListNode().getPage(),
                extent.getListNode().getOffset());

        mtr.markDirty(fsp);
    }

    @Override
    public PageId allocateFragPage(MiniTransaction mtr, int spaceId) throws MiniDbException {
        // Step 1: 加载 FSP Header
        FspHeaderPage fsp = loadFspHeader(mtr, spaceId);

        // Step 2: 检查 FREE_FRAG 链表
        FlstBaseNode freeFragList = fsp.getFreeFragList();

        ExtentDescriptor fragExtent;
        if (freeFragList.isEmpty()) {
            // Step 3: FREE_FRAG 为空，从 FREE 链表分配一个 Extent
            fragExtent = allocateExtent(mtr, spaceId);
            if (fragExtent == null) {
                throw new MiniDbException("Failed to allocate extent for fragment page");
            }

            // 设置为 FREE_FRAG 状态
            fragExtent.setState(mtr, ExtentState.FREE_FRAG);
            fragExtent.setSegmentId(mtr, 0);  // 碎片 Extent 不属于任何 Segment

            // 加入 FREE_FRAG 链表
            freeFragList.addLast(mtr, fragExtent.getListNode().getPage(),
                    fragExtent.getListNode().getOffset());
        } else {
            // Step 4: 从 FREE_FRAG 链表获取第一个 Extent
            FilAddr firstAddr = freeFragList.getFirstNodeAddr();
            Page extentPage = mtr.getPage(PageId.of(spaceId, firstAddr.getPageNo()));
            int extentOffset = firstAddr.getOffset() - XDES_FLST_NODE;
            int extentNo = calculateExtentNo(firstAddr.getPageNo(), extentOffset);
            fragExtent = new ExtentDescriptor(extentPage, extentOffset, extentNo);
        }

        // Step 5: 在 Extent 中分配页面
        int pageOffset = extentManager.allocatePageInExtent(mtr, fragExtent);
        if (pageOffset == -1) {
            throw new MiniDbException("FREE_FRAG extent should have free pages");
        }

        // Step 6: 检查 Extent 是否变满
        if (extentManager.isFull(fragExtent)) {
            // 从 FREE_FRAG 移到 FULL_FRAG
            freeFragList.remove(mtr, fragExtent.getListNode().getPage(),
                    fragExtent.getListNode().getOffset());

            fragExtent.setState(mtr, ExtentState.FULL_FRAG);

            fsp.getFullFragList().addLast(mtr, fragExtent.getListNode().getPage(),
                    fragExtent.getListNode().getOffset());
        }

        mtr.markDirty(fsp);

        return PageId.of(spaceId, fragExtent.getStartPageNo() + pageOffset);
    }

    @Override
    public void freeFragPage(MiniTransaction mtr, PageId pageId) throws MiniDbException {
        int spaceId = pageId.getSpaceId();
        int pageNo = pageId.getPageNo();

        // Step 1: 确定页面所属的 Extent
        int extentNo = pageNo / EXTENT_SIZE;
        ExtentDescriptor extent = extentManager.getExtentDescriptor(mtr, spaceId, extentNo);

        ExtentState state = extent.getState();
        if (state != ExtentState.FREE_FRAG && state != ExtentState.FULL_FRAG) {
            throw new MiniDbException("Page does not belong to a fragment extent: " + pageId);
        }

        // Step 2: 释放页面
        int pageOffset = pageNo % EXTENT_SIZE;
        boolean wasFullBefore = extentManager.isFull(extent);

        extentManager.freePageInExtent(mtr, extent, pageOffset);

        // Step 3: 检查状态转移
        FspHeaderPage fsp = loadFspHeader(mtr, spaceId);

        if (state == ExtentState.FULL_FRAG && wasFullBefore && !extentManager.isFull(extent)) {
            // FULL_FRAG → FREE_FRAG
            fsp.getFullFragList().remove(mtr, extent.getListNode().getPage(),
                    extent.getListNode().getOffset());

            extent.setState(mtr, ExtentState.FREE_FRAG);

            fsp.getFreeFragList().addLast(mtr, extent.getListNode().getPage(),
                    extent.getListNode().getOffset());
        }

        // Step 4: 如果 Extent 变空，移回 FREE 链表
        if (extentManager.isEmpty(extent)) {
            if (state == ExtentState.FREE_FRAG) {
                fsp.getFreeFragList().remove(mtr, extent.getListNode().getPage(),
                        extent.getListNode().getOffset());
            } else {
                fsp.getFullFragList().remove(mtr, extent.getListNode().getPage(),
                        extent.getListNode().getOffset());
            }

            extent.setState(mtr, ExtentState.FREE);

            fsp.getFreeList().addLast(mtr, extent.getListNode().getPage(),
                    extent.getListNode().getOffset());
        }

        mtr.markDirty(fsp);
    }

    @Override
    public void extendTablespace(MiniTransaction mtr, int spaceId, int extentCount)
            throws MiniDbException {
        FspHeaderPage fsp = loadFspHeader(mtr, spaceId);

        int currentSize = fsp.getSize();
        int newSize = currentSize + extentCount * EXTENT_SIZE;

        // Step 1: 创建必需的 XDES Page（如果跨越 XDES Page 边界）
        // 其他页面采用延迟分配（lazy allocation），在实际使用时才创建
        for (int i = currentSize; i < newSize; i++) {
            // 只创建 XDES Page，其他页面延迟分配
            if (i > 0 && i % PAGES_PER_EXTENT_GROUP == 0) {
                // 创建新的 XDES Page
                Page xdesPageRaw = mtr.newPage(spaceId);
                if (xdesPageRaw.getPageNo() != i) {
                    throw new MiniDbException("Expected page " + i + ", got " + xdesPageRaw.getPageNo());
                }

                xdesPageRaw.setPageType(PageType.FIL_PAGE_TYPE_XDES);
                mtr.markDirty(xdesPageRaw);

                XdesPage xdesPage = new XdesPage(xdesPageRaw);
                xdesPage.initialize(mtr);
            }
        }

        // Step 2: 初始化新 Extent 并加入 FREE 链表
        int startExtentNo = currentSize / EXTENT_SIZE;
        int endExtentNo = (newSize - 1) / EXTENT_SIZE;

        for (int extentNo = startExtentNo; extentNo <= endExtentNo; extentNo++) {
            // 跳过 Extent 0（系统保留）
            if (extentNo == 0) {
                continue;
            }

            // 跳过包含 XDES Page 的 Extent
            int extentStartPage = extentNo * EXTENT_SIZE;
            if (extentStartPage > 0 && extentStartPage % PAGES_PER_EXTENT_GROUP == 0) {
                continue;
            }

            ExtentDescriptor extent = extentManager.getExtentDescriptor(mtr, spaceId, extentNo);

            // 检查 Extent 是否已经被分配（避免重新初始化已分配的 Extent）
            ExtentState currentState = extent.getState();
            long currentSegmentId = extent.getSegmentId();

            // 如果 Extent 已经被初始化且不是 FREE 状态，或者属于某个 Segment，跳过
            if (currentState != null && currentState != ExtentState.FREE && currentState != ExtentState.FREE_FRAG && currentState != ExtentState.FULL_FRAG) {
                continue;  // Extent 已经属于某个 Segment，跳过
            }

            // 如果 Extent 已经在某个碎片链表中，跳过
            if (currentState == ExtentState.FREE_FRAG || currentState == ExtentState.FULL_FRAG) {
                continue;  // 碎片 Extent，已经被使用，跳过
            }

            // 初始化新的 Extent
            extent.initialize(mtr);
            extent.setState(mtr, ExtentState.FREE);

            // 加入 FREE 链表
            fsp.getFreeList().addLast(mtr, extent.getListNode().getPage(),
                    extent.getListNode().getOffset());
        }

        // Step 3: 更新 FSP Header（逻辑大小，页面在使用时才实际分配）
        fsp.setSize(mtr, newSize);
        fsp.setFreeLimit(mtr, newSize);

        mtr.markDirty(fsp);
    }

    @Override
    public SpaceStatistics getStatistics(MiniTransaction mtr, int spaceId) throws MiniDbException {
        FspHeaderPage fsp = loadFspHeader(mtr, spaceId);

        int totalPages = fsp.getSize();
        int freeExtents = fsp.getFreeList().getLength();
        int freeFragExtents = fsp.getFreeFragList().getLength();
        int fullFragExtents = fsp.getFullFragList().getLength();
        long nextSegmentId = fsp.getNextSegmentId();

        // 估算已使用页数
        int usedPages = totalPages - (freeExtents * EXTENT_SIZE);

        return new SpaceStatistics(spaceId, totalPages, freeExtents,
                freeFragExtents, fullFragExtents, usedPages, nextSegmentId);
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
        return String.format("SpaceManagerImpl{bufferPool=%s, extentManager=%s}",
                bufferPool, extentManager);
    }
}
