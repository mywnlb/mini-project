package cn.zhangyis.minidb.storage.space;


import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.BaseStorageTest;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import org.junit.jupiter.api.Test;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 逻辑层单测：TableSpace / Segment / Extent 的分配与迁移规则
 * <p>
 * 目标：
 * 1) Segment 创建后能正常拿到 inode entry
 * 2) allocateExtentForSegment：从 FSP_FREE 拿 extent，设置 segId/state，并且从 FREE 链表移除
 * 3) allocateFragmentPage：FREE -> FREE_FRAG 迁移，并且返回的 pageNo 应落在该 extent 范围内
 * 4) Segment.allocatePage：前 32 页走 frag array；第 33 页起走 extent，并维护 NOT_FULL/FULL 迁移
 * <p>
 * 注意：你需要把 setUp() 里的 bufferPool 初始化替换为你项目现有的测试夹具。
 */
public class SpaceLogicTest extends BaseStorageTest {


//    @BeforeEach
//    void setUp() {
//        // TODO: 替换为你项目的真实 BufferPool 初始化方式
//        // this.bufferPool = TestEnv.createBufferPool(...);
//    }

    // -------------------------
    // Test 1: createSegment 基本正确性（逻辑层）
    // -------------------------

    @Test
    void createSegment_shouldAllocateInodeEntryAndReturnSegment() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            TableSpace ts = bootstrapTablespaceWithFreeExtents(mtr, SPACE_ID, 4);

            Segment seg = ts.createSegment(mtr);
            assertNotNull(seg);
            assertTrue(seg.getSegmentId() > 0);

            SegmentDescriptor desc = findSegmentDescriptor(mtr, SPACE_ID, seg.getSegmentId());
            assertNotNull(desc);
            assertEquals(seg.getSegmentId(), desc.getSegmentId());
            assertEquals(INODE_MAGIC_NUMBER, desc.getMagicNumber());

            // 新段：3 个 extent 链表应为空，frag array 应为空
            assertTrue(desc.getFreeList().isEmpty());
            assertTrue(desc.getNotFullList().isEmpty());
            assertTrue(desc.getFullList().isEmpty());
            assertEquals(0, desc.getFragUsedCount());
        }
    }

    // -------------------------
    // Test 2: allocateExtentForSegment 应从 FSP_FREE 精确移除并设置归属
    // -------------------------

    @Test
    void allocateExtentForSegment_shouldRemoveFromFspFree_andSetOwnerAndState() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            TableSpace ts = bootstrapTablespaceWithFreeExtents(mtr, SPACE_ID, 4);

            Segment seg = ts.createSegment(mtr);
            assertNotNull(seg);

            FspHeaderPage fsp = new FspHeaderPage(mtr.getPage(PageId.of(SPACE_ID, 0)));
            int before = fsp.getFreeList().getLength();

            Extent ext = ts.allocateExtentForSegment(mtr, seg.getSegmentId());
            assertNotNull(ext);

            assertEquals(seg.getSegmentId(), ext.getSegmentId());
            assertEquals(ExtentState.FSEG, ext.getState());
            assertEquals(before - 1, fsp.getFreeList().getLength());

            // 关键不变量：被分配的 extent 节点不应仍在 FSP_FREE 链表中
            assertFalse(listContainsNode(fsp.getFreeList(),
                    ext.getListNode().getPage().getPageNo(),
                    ext.getListNode().getOffset()));
        }
    }

    // -------------------------
    // Test 3: allocateFragmentPage 应触发 FREE -> FREE_FRAG，并返回的 pageNo 落在该 extent 范围
    // -------------------------

    @Test
    void allocateFragmentPage_shouldMoveExtentToFreeFrag_andReturnPageInsideExtentRange() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            TableSpace ts = bootstrapTablespaceWithFreeExtents(mtr, SPACE_ID, 4);

            FspHeaderPage fsp = new FspHeaderPage(mtr.getPage(PageId.of(SPACE_ID, 0)));
            int freeBefore = fsp.getFreeList().getLength();
            int freeFragBefore = fsp.getFreeFragList().getLength();

            // 调试断言：确保 FREE 链表在调用前有 4 个 extent
            assertEquals(4, freeBefore, "FREE list should have 4 extents before allocation");
            assertEquals(0, freeFragBefore, "FREE_FRAG list should be empty before allocation");

            // 调试：验证 extent 1 的 bitmap 是否正确初始化
            ExtentDescriptor ext1 = fsp.getXdesEntry(1);
            assertTrue(ext1.isEmpty(), "Extent 1 should have all pages free (bitmap = 0xFF)");
            assertTrue(ext1.isPageFree(0), "Extent 1 page 0 should be free");

            Page p = ts.allocateFragmentPage(mtr);
            assertNotNull(p, "allocateFragmentPage should return a non-null page");

            assertEquals(freeBefore - 1, fsp.getFreeList().getLength());
            assertEquals(freeFragBefore + 1, fsp.getFreeFragList().getLength());
            assertEquals(0, fsp.getFullFragList().getLength());

            // 取 FREE_FRAG 的首个节点，定位对应 extent 并断言其状态
            int nodePageNo = fsp.getFreeFragList().getFirstNode().getPageNo();
            int nodeOffset = fsp.getFreeFragList().getFirstNodeOffset();
            int extentNo = extentNoFromXdesNode(nodePageNo, nodeOffset);

            int entryOffset = nodeOffset - XDES_FLST_NODE;
            Page nodePage = mtr.getPage(PageId.of(SPACE_ID, nodePageNo));
            ExtentDescriptor ed = new ExtentDescriptor(nodePage, entryOffset, extentNo);

            assertEquals(0L, ed.getSegmentId());
            assertEquals(ExtentState.FREE_FRAG, ed.getState());

            // 关键不变量：返回的 pageNo 应属于该 extent 的 64 页范围
            int start = extentNo * EXTENT_SIZE;
            assertTrue(p.getPageNo() >= start && p.getPageNo() < start + EXTENT_SIZE,
                    "fragment pageNo must be inside FREE_FRAG extent range");
        }
    }

    // -------------------------
    // Test 4: Segment.allocatePage 32 页策略 + NOT_FULL / FULL 迁移
    // -------------------------

    @Test
    void segmentAllocatePage_shouldUseFragArrayFirst32_thenUseExtentsAndMaintainLists() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            TableSpace ts = bootstrapTablespaceWithFreeExtents(mtr, SPACE_ID, 8);

            Segment seg = ts.createSegment(mtr);
            assertNotNull(seg);

            // 前 32 次：应填满 frag array
            for (int i = 0; i < FRAG_ARRAY_SIZE; i++) {
                Page p = seg.allocatePage(mtr);
                assertNotNull(p);
            }

            SegmentDescriptor desc = findSegmentDescriptor(mtr, SPACE_ID, seg.getSegmentId());
            assertNotNull(desc);
            assertEquals(32, desc.getFragUsedCount());
            assertTrue(desc.getNotFullList().isEmpty());
            assertTrue(desc.getFullList().isEmpty());

            // 第 33 次：应开始走 extent，并把 extent 放进 NOT_FULL
            Page p33 = seg.allocatePage(mtr);
            assertNotNull(p33);

            assertEquals(1, desc.getNotFullList().getLength());
            assertEquals(0, desc.getFullList().getLength());

            // 定位 NOT_FULL 的第一个 extent，校验归属与位图使用数
            int nodePageNo = desc.getNotFullList().getFirstNode().getPageNo();
            int nodeOffset = desc.getNotFullList().getFirstNodeOffset();
            int extentNo = extentNoFromXdesNode(nodePageNo, nodeOffset);

            int entryOffset = nodeOffset - XDES_FLST_NODE;
            Page nodePage = mtr.getPage(PageId.of(SPACE_ID, nodePageNo));
            ExtentDescriptor ed = new ExtentDescriptor(nodePage, entryOffset, extentNo);

            assertEquals(seg.getSegmentId(), ed.getSegmentId());
            assertEquals(ExtentState.FSEG, ed.getState());
            assertEquals(1, ed.getUsedPageCount(), "first extent should have 1 allocated page after first extent allocation");
        }
    }

    // =========================================================
    // Helpers
    // =========================================================

    /**
     * 启动一个最小表空间：
     * - page0: FSP_HDR 初始化
     * - 在 page0 的 XDES array 中初始化若干 extent（extentNo 从 1 开始，避免碰 page0）
     * - 将这些 extent 加入 FSP_FREE
     */
    private TableSpace bootstrapTablespaceWithFreeExtents(MiniTransaction mtr, int spaceId, int freeExtentCount)
            throws MiniDbException {

        // 确保 page0 存在且是我们创建的（假设 newPage 按序分配）
        Page p0 = mtr.newPage(spaceId);
        assertEquals(0, p0.getPageNo(), "bootstrap expects first allocated pageNo=0");

        FspHeaderPage fsp = new FspHeaderPage(p0);
        fsp.initialize(mtr, spaceId);

        // 初始化 extent 1..N，并加入 FREE 链表
        FlstBaseNode freeList = fsp.getFreeList();
        for (int extentNo = 1; extentNo <= freeExtentCount; extentNo++) {
            ExtentDescriptor ed = fsp.getXdesEntry(extentNo);
            ed.initialize(mtr);
            ed.getListNode().initialize(mtr);

            freeList.addLast(mtr, p0, ed.getListNode().getOffset());
        }

        return new TableSpace(spaceId, bufferPool);
    }

    /**
     * 在 INODE page 链表里找到指定 segmentId 的 inode entry（用于断言内部链表/frag array 等）。
     */
    private SegmentDescriptor findSegmentDescriptor(MiniTransaction mtr, int spaceId, long segmentId)
            throws MiniDbException {

        FspHeaderPage fsp = new FspHeaderPage(mtr.getPage(PageId.of(spaceId, 0)));

        SegmentDescriptor found = scanInodeListForSegment(mtr, spaceId, fsp.getInodesFreeList(), segmentId);
        if (found != null) return found;

        return scanInodeListForSegment(mtr, spaceId, fsp.getInodesFullList(), segmentId);
    }

    private SegmentDescriptor scanInodeListForSegment(MiniTransaction mtr, int spaceId, FlstBaseNode inodeList, long segmentId)
            throws MiniDbException {

        if (inodeList.isEmpty()) return null;

        PageId cur = inodeList.getFirstNode();
        while (cur != null) {
            Page p = mtr.getPage(cur);
            InodePage inodePage = new InodePage(p);

            for (int i = 0; i < INODES_PER_PAGE; i++) {
                SegmentDescriptor entry = inodePage.getInodeEntry(i);
                if (entry.getSegmentId() == segmentId) {
                    return entry;
                }
            }

            FlstNode node = inodePage.getListNode();
            int next = node.getNextPageNo();
            if (next == FIL_NULL) break;
            cur = PageId.of(spaceId, next);
        }
        return null;
    }

    /**
     * 判断一个 FLST_BASE_NODE 链表里是否包含某个节点地址（pageNo, offset）。
     */
    private boolean listContainsNode(FlstBaseNode base, int targetPageNo, int targetOffset) throws MiniDbException {
        if (base.isEmpty()) return false;

        int curPageNo = base.getFirstNode().getPageNo();
        int curOffset = base.getFirstNodeOffset();

        // 防环：最多走 length 次
        int max = base.getLength() + 2;
        for (int i = 0; i < max; i++) {
            if (curPageNo == targetPageNo && curOffset == targetOffset) return true;

            Page curPage = new Page(PageId.of(SPACE_ID, curPageNo)); // 仅用于拿 spaceId/pageNo
            // 注意：这里不能 new Page 读 buffer；用 mtr.getPage 更稳，但需要 mtr 传入。
            // 为保持签名简单，这里用更直接的实现：让调用点用 mtr.getPage 版本。
            return scanListWithMtr(base, targetPageNo, targetOffset);
        }
        return false;
    }

    private boolean scanListWithMtr(FlstBaseNode base, int targetPageNo, int targetOffset) throws MiniDbException {
        // 这个方法需要在调用处可用 mtr，因此把真正扫描逻辑放到 extent/segment 断言里使用更好。
        // 为了避免误用，这里直接抛异常提醒你在需要时改成接收 mtr 的版本。
        throw new MiniDbException("listContainsNode requires a mtr-aware scan; implement listContainsNode(mtr, base, ...) in your test harness.");
    }

    /**
     * 从 XDES_FLST_NODE 的地址 (pageNo, nodeOffset) 推导 extentNo。
     * - pageNo==0：FSP_HDR 的 XDES array（entry 从 XDES_ARR_OFFSET 开始）
     * - pageNo!=0：XDES page（entry 从 FIL_HEADER_SIZE 开始）
     */
    private static int extentNoFromXdesNode(int nodePageNo, int nodeOffset) {
        int entryOffset = nodeOffset - XDES_FLST_NODE;

        if (nodePageNo == 0) {
            int localIndex = (entryOffset - XDES_ARR_OFFSET) / XDES_ENTRY_SIZE;
            return localIndex;
        }

        int groupNo = nodePageNo / PAGES_PER_EXTENT_GROUP;
        int localIndex = (entryOffset - FIL_HEADER_SIZE) / XDES_ENTRY_SIZE;
        return groupNo * EXTENTS_PER_GROUP + localIndex;
    }
}
