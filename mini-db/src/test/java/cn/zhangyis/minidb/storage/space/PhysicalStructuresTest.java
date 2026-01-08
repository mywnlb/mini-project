package cn.zhangyis.minidb.storage.space;


import cn.zhangyis.minidb.storage.BaseStorageTest;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import org.junit.jupiter.api.*;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 物理结构单测：FLST / FSP_HDR / XDES(entry) / INODE
 *
 * 你需要把 setUp() 里的 bufferPool 初始化替换成你项目现有的方式。
 */
public class PhysicalStructuresTest extends BaseStorageTest {



    // -------------------------
    // FLST: FlstNode / FlstBaseNode
    // -------------------------

    @Test
    void flstNode_initialize_shouldBeIsolated() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page p = mtr.newPage(SPACE_ID);
            int off = 200;

            FlstNode n = new FlstNode(p, off);
            n.initialize(mtr);

            assertEquals(FIL_NULL, n.getPrevPageNo());
            assertEquals(FIL_NULL, n.getNextPageNo());
            assertTrue(n.isIsolated());
        }
    }

    @Test
    void flstBaseNode_addLast_removeFirst_shouldKeepPointersConsistent() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page basePage = mtr.newPage(SPACE_ID);
            FlstBaseNode base = new FlstBaseNode(basePage, 1000);
            base.initialize(mtr);

            Page p1 = mtr.newPage(SPACE_ID);
            Page p2 = mtr.newPage(SPACE_ID);
            int o1 = 200, o2 = 300;

            // addLast two nodes
            base.addLast(mtr, p1, o1);
            base.addLast(mtr, p2, o2);

            assertEquals(2, base.getLength());
            assertEquals(p1.getPageNo(), base.getFirstNode().getPageNo());
            assertEquals(o1, base.getFirstNodeOffset());
            assertEquals(p2.getPageNo(), base.getLastNode().getPageNo());
            assertEquals(o2, base.getLastNodeOffset());

            // verify links
            FlstNode n1 = new FlstNode(p1, o1);
            FlstNode n2 = new FlstNode(p2, o2);
            assertEquals(FIL_NULL, n1.getPrevPageNo());
            assertEquals(p2.getPageNo(), n1.getNextPageNo());
            assertEquals(o2, n1.getNextOffset());

            assertEquals(p1.getPageNo(), n2.getPrevPageNo());
            assertEquals(o1, n2.getPrevOffset());
            assertEquals(FIL_NULL, n2.getNextPageNo());

            // removeFirst => node1 should be isolated, base points to node2
            PageId removed = base.removeFirst(mtr);
            assertNotNull(removed);
            assertEquals(p1.getPageNo(), removed.getPageNo());
            assertEquals(1, base.getLength());
            assertEquals(p2.getPageNo(), base.getFirstNode().getPageNo());
            assertEquals(o2, base.getFirstNodeOffset());

            FlstNode removedNode = new FlstNode(mtr.getPage(removed), o1);
            assertTrue(removedNode.isIsolated());
        }
    }

    // -------------------------
    // XDES Entry: ExtentDescriptor bitmap
    // -------------------------

    @Test
    void extentDescriptor_bitmap_allocate_free_shouldWork() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page xdesLikePage = mtr.newPage(SPACE_ID);
            int entryOff = 500;
            int extentNo = 7;

            ExtentDescriptor e = new ExtentDescriptor(xdesLikePage, entryOff, extentNo);

            e.setState(mtr, ExtentState.FREE);
            e.setSegmentId(mtr, 0);
            e.getListNode().initialize(mtr);
            e.initBitmap(mtr);

            // all free
            assertTrue(e.isEmpty());
            assertEquals(0, e.getUsedPageCount());
            assertTrue(e.isPageFree(0));
            assertTrue(e.isPageFree(63));

            // allocate two pages
            e.allocatePage(mtr, 0);
            e.allocatePage(mtr, 63);

            assertFalse(e.isPageFree(0));
            assertFalse(e.isPageFree(63));
            assertEquals(2, e.getUsedPageCount());

            // free one back
            e.freePage(mtr, 0);
            assertTrue(e.isPageFree(0));
            assertEquals(1, e.getUsedPageCount());
        }
    }

    // -------------------------
    // FSP_HDR: header fields + lists + (期望) page0 XDES init
    // -------------------------

    @Test
    void fspHeader_initialize_shouldInitHeaderFieldsAndLists() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 使用 newPage 创建 page 0，因为表空间刚建立，page 0 尚未存在于 Buffer Pool
            Page p0 = mtr.newPage(SPACE_ID);
            assertEquals(0, p0.getPageNo(), "first newPage should be page 0");
            // 强制污染数据，确保 initialize 真正写入字段（不是"刚好是0"）
            for (int i = 0; i < PAGE_SIZE; i++) p0.putByte(i, (byte) 0x7F);

            FspHeaderPage fsp = new FspHeaderPage(p0);
            fsp.initialize(mtr, SPACE_ID);

            assertEquals(SPACE_ID, fsp.getFspSpaceId());
            assertEquals(1, fsp.getSize());
            assertEquals(1, fsp.getFreeLimit());
            assertEquals(0, fsp.getFragNUsed());
            assertEquals(1L, fsp.getNextSegmentId());

            assertTrue(fsp.getFreeList().isEmpty());
            assertTrue(fsp.getFreeFragList().isEmpty());
            assertTrue(fsp.getFullFragList().isEmpty());
            assertTrue(fsp.getInodesFreeList().isEmpty());
            assertTrue(fsp.getInodesFullList().isEmpty());
        }
    }

    /**
     * 该用例用于验证：FspHeaderPage.initialize() 是否也初始化了 page0 上的 256 个 XDES entry。
     * 你当前实现【大概率会失败/抛异常】——这正是要修的点。
     */
    @Test
    void fspHeader_initialize_shouldAlsoInitXdesEntriesOnPage0() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 使用 newPage 创建 page 0
            Page p0 = mtr.newPage(SPACE_ID);
            assertEquals(0, p0.getPageNo(), "first newPage should be page 0");
            for (int i = 0; i < PAGE_SIZE; i++) p0.putByte(i, (byte) 0x7F);

            FspHeaderPage fsp = new FspHeaderPage(p0);
            fsp.initialize(mtr, SPACE_ID);

            ExtentDescriptor e0 = fsp.getXdesEntry(0);

            // 期望：能正常读取 state（不会因为垃圾值导致 fromValue() 异常）
            assertDoesNotThrow(e0::getState);

            // 期望：FREE + bitmap 全空闲
            assertEquals(ExtentState.FREE, e0.getState());
            assertEquals(0L, e0.getSegmentId());
            assertTrue(e0.isEmpty());
            assertTrue(e0.getListNode().isIsolated());
        }
    }

    // -------------------------
    // INODE: page header node + entry init（包括 NOT_FULL_N_USED）
    // -------------------------

    /**
     * 该用例用于验证：InodePage.initialize()/initAllEntries 是否把 entry 的 NOT_FULL_N_USED 写成 0。
     * 你当前实现没有写这个字段——如果页内容非0，会失败。
     */
    @Test
    void inodePage_initialize_shouldInitAllEntriesIncludingNotFullNUsed() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page raw = mtr.newPage(SPACE_ID);
            for (int i = 0; i < PAGE_SIZE; i++) raw.putByte(i, (byte) 0x7F);

            InodePage inode = new InodePage(raw);
            inode.initialize(mtr);

            // page header node isolated
            assertTrue(inode.getListNode().isIsolated());

            // check a couple entries (0 and 84)
            for (int idx : new int[]{0, INODES_PER_PAGE - 1}) {
                SegmentDescriptor entry = inode.getInodeEntry(idx);
                assertEquals(0L, entry.getSegmentId());
                assertEquals(INODE_MAGIC_NUMBER, entry.getMagicNumber());
                assertEquals(0, entry.getNotFullNUsed(), "NOT_FULL_N_USED must be 0 after init");

                // frag array cleared
                for (int i = 0; i < INODE_FRAG_ARRAY_PAGES; i++) {
                    assertEquals(FIL_NULL, entry.getFragPageNo(i));
                }

                // 3 lists empty
                assertTrue(entry.getFreeList().isEmpty());
                assertTrue(entry.getNotFullList().isEmpty());
                assertTrue(entry.getFullList().isEmpty());
            }
        }
    }
}
