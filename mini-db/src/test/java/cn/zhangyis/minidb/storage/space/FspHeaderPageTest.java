//package cn.zhangyis.minidb.storage.space;
//
//import cn.zhangyis.minidb.storage.BaseStorageTest;
//import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
//import cn.zhangyis.minidb.storage.page.Page;
//import cn.zhangyis.minidb.storage.page.PageId;
//import cn.zhangyis.minidb.storage.page.PageType;
//import org.junit.jupiter.api.Test;
//
//import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;
//import static org.junit.jupiter.api.Assertions.*;
//
///**
// * FspHeaderPage 单元测试
// *
// * <p>测试 FSP_HDR Page (page 0) 的核心功能：
// * <ul>
// *   <li>构造函数验证</li>
// *   <li>FSP Header 字段管理</li>
// *   <li>Segment ID 分配</li>
// *   <li>链表访问（6个链表）</li>
// *   <li>XDES Array 访问</li>
// *   <li>初始化</li>
// * </ul>
// * </p>
// *
// * @author MiniDB
// */
//class FspHeaderPageTest extends BaseStorageTest {
//
//    // ==================== 构造函数测试 ====================
//
//    @Test
//    void testConstructor_FromPageId() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            FspHeaderPage fspPage = new FspHeaderPage(pageId);
//
//            assertNotNull(fspPage);
//            assertEquals(0, fspPage.getPageNo());
//            assertEquals(SPACE_ID, fspPage.getSpaceId());
//            assertEquals(PageType.FIL_PAGE_TYPE_FSP_HDR, fspPage.getPageType());
//        }
//    }
//
//    @Test
//    void testConstructor_FromPage() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            // 必须使用 page 0，因为 FspHeaderPage 要求必须是第一个页面
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page page = mtr.getPage(pageId);
//            page.setPageType(PageType.FIL_PAGE_TYPE_FSP_HDR);
//
//            FspHeaderPage fspPage = new FspHeaderPage(page);
//
//            assertNotNull(fspPage);
//            assertEquals(0, fspPage.getPageNo());
//        }
//    }
//
//    @Test
//    void testConstructor_FromPage_InvalidType() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            // 使用 page 0 进行类型验证测试
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page page = mtr.getPage(pageId);
//            page.setPageType(PageType.FIL_PAGE_INDEX);  // 错误的类型
//
//            assertThrows(IllegalArgumentException.class, () -> {
//                new FspHeaderPage(page);
//            });
//        }
//    }
//
//    @Test
//    void testConstructor_FromPage_AllocatedType() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            // 使用 page 0，ALLOCATED 是允许的初始类型
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page page = mtr.getPage(pageId);
//            page.setPageType(PageType.FIL_PAGE_TYPE_ALLOCATED);  // 允许的类型
//
//            FspHeaderPage fspPage = new FspHeaderPage(page);
//            assertNotNull(fspPage);
//        }
//    }
//
//    // ==================== FSP Header 字段测试 ====================
//
//    @Test
//    void testFspSpaceId_GetAndSet() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            // 先通过 MTR 获取页面，使其被 MTR 管理
//            Page page = mtr.getPage(pageId);
//            FspHeaderPage fspPage = new FspHeaderPage(page);
//
//            // 设置 Space ID
//            fspPage.setFspSpaceId(mtr, 999);
//            assertEquals(999, fspPage.getFspSpaceId());
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testSize_GetAndSet() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page page = mtr.getPage(pageId);
//            FspHeaderPage fspPage = new FspHeaderPage(page);
//
//            // 设置表空间大小
//            fspPage.setSize(mtr, 1000);
//            assertEquals(1000, fspPage.getSize());
//
//            fspPage.setSize(mtr, 2000);
//            assertEquals(2000, fspPage.getSize());
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testFreeLimit_GetAndSet() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page page = mtr.getPage(pageId);
//            FspHeaderPage fspPage = new FspHeaderPage(page);
//
//            // 设置 Free Limit
//            fspPage.setFreeLimit(mtr, 64);
//            assertEquals(64, fspPage.getFreeLimit());
//
//            fspPage.setFreeLimit(mtr, 128);
//            assertEquals(128, fspPage.getFreeLimit());
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testSegId_GetAndSet() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page page = mtr.getPage(pageId);
//            FspHeaderPage fspPage = new FspHeaderPage(page);
//
//            // 设置下一个 Segment ID
//            fspPage.setNextSegmentId(mtr, 100L);
//            assertEquals(100L, fspPage.getNextSegmentId());
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testAllocateSegmentId() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page page = mtr.getPage(pageId);
//            FspHeaderPage fspPage = new FspHeaderPage(page);
//
//            // 初始化 Segment ID 为 1
//            fspPage.setNextSegmentId(mtr, 1L);
//
//            // 分配 Segment ID（原子递增）
//            long segId1 = fspPage.allocateSegmentId(mtr);
//            assertEquals(1L, segId1);
//            assertEquals(2L, fspPage.getNextSegmentId());
//
//            long segId2 = fspPage.allocateSegmentId(mtr);
//            assertEquals(2L, segId2);
//            assertEquals(3L, fspPage.getNextSegmentId());
//
//            long segId3 = fspPage.allocateSegmentId(mtr);
//            assertEquals(3L, segId3);
//            assertEquals(4L, fspPage.getNextSegmentId());
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== 链表访问测试 ====================
//
//    @Test
//    void testGetFreeExtentList() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page page = mtr.getPage(pageId);
//            FspHeaderPage fspPage = new FspHeaderPage(page);
//
//            FlstBaseNode freeList = fspPage.getFreeList();
//            assertNotNull(freeList);
//
//            // 验证链表节点的位置（FSP_FREE偏移）
//            assertEquals(FSP_FREE, freeList.getOffset());
//        }
//    }
//
//    @Test
//    void testGetFreeFragExtentList() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page page = mtr.getPage(pageId);
//            FspHeaderPage fspPage = new FspHeaderPage(page);
//
//            FlstBaseNode freeFragList = fspPage.getFreeFragList();
//            assertNotNull(freeFragList);
//
//            // 验证链表节点的位置（FSP_FREE_FRAG偏移）
//            assertEquals( FSP_FREE_FRAG, freeFragList.getOffset());
//        }
//    }
//
//    @Test
//    void testGetFullFragExtentList() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page page = mtr.getPage(pageId);
//            FspHeaderPage fspPage = new FspHeaderPage(page);
//
//            FlstBaseNode fullFragList = fspPage.getFullFragList();
//            assertNotNull(fullFragList);
//
//            // 验证链表节点的位置（FSP_FULL_FRAG偏移）
//            assertEquals(FSP_FULL_FRAG, fullFragList.getOffset());
//        }
//    }
//
//    @Test
//    void testGetSegInodesFreeList() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page page = mtr.getPage(pageId);
//            FspHeaderPage fspPage = new FspHeaderPage(page);
//
//            FlstBaseNode segInodesFreeList = fspPage.getInodesFreeList();
//            assertNotNull(segInodesFreeList);
//
//            // 验证链表节点的位置（FSP_SEG_INODES_FREE偏移）
//            assertEquals(FSP_SEG_INODES_FREE, segInodesFreeList.getOffset());
//        }
//    }
//
//    @Test
//    void testGetSegInodesFullList() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page page = mtr.getPage(pageId);
//            FspHeaderPage fspPage = new FspHeaderPage(page);
//
//            FlstBaseNode segInodesFullList = fspPage.getInodesFullList();
//            assertNotNull(segInodesFullList);
//
//            // 验证链表节点的位置（FSP_SEG_INODES_FULL偏移）
//            assertEquals( FSP_SEG_INODES_FULL, segInodesFullList.getOffset());
//        }
//    }
//
//    // ==================== XDES Array 访问测试 ====================
//
//    @Test
//    void testGetXdesEntryOffset() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page page = mtr.getPage(pageId);
//            FspHeaderPage fspPage = new FspHeaderPage(page);
//
//            // Extent 0: offset = XDES_ARR_OFFSET
//            assertEquals(XDES_ARR_OFFSET, fspPage.getXdesEntryOffset(0));
//
//            // Extent 1: offset = XDES_ARR_OFFSET + 40
//            assertEquals(XDES_ARR_OFFSET + XDES_ENTRY_SIZE, fspPage.getXdesEntryOffset(1));
//
//            // Extent 255: offset = XDES_ARR_OFFSET + 255 * 40
//            assertEquals(XDES_ARR_OFFSET + 255 * XDES_ENTRY_SIZE, fspPage.getXdesEntryOffset(255));
//        }
//    }
//
//    @Test
//    void testGetXdesEntryOffset_InvalidExtentNo() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page page = mtr.getPage(pageId);
//            FspHeaderPage fspPage = new FspHeaderPage(page);
//
//            // 负数
//            assertThrows(IllegalArgumentException.class, () -> {
//                fspPage.getXdesEntryOffset(-1);
//            });
//
//            // 超出范围（FSP_HDR只能访问0-255）
//            assertThrows(IllegalArgumentException.class, () -> {
//                fspPage.getXdesEntryOffset(256);
//            });
//        }
//    }
//
//    @Test
//    void testGetXdesEntry() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page page = mtr.getPage(pageId);
//            FspHeaderPage fspPage = new FspHeaderPage(page);
//
//            // 获取 Extent 0 的描述符
//            ExtentDescriptor ext0 = fspPage.getXdesEntry(0);
//            assertNotNull(ext0);
//            assertEquals(0, ext0.getExtentNo());
//            assertEquals(0, ext0.getStartPageNo());
//
//            // 获取 Extent 10 的描述符
//            ExtentDescriptor ext10 = fspPage.getXdesEntry(10);
//            assertNotNull(ext10);
//            assertEquals(10, ext10.getExtentNo());
//            assertEquals(640, ext10.getStartPageNo());
//        }
//    }
//
//    // ==================== 初始化测试 ====================
//
//    @Test
//    void testInitialize() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page page = mtr.getPage(pageId);
//            FspHeaderPage fspPage = new FspHeaderPage(page);
//
//            // 初始化 FSP Header
//            fspPage.initialize(mtr, SPACE_ID);
//
//            // 验证页面类型
//            assertEquals(PageType.FIL_PAGE_TYPE_FSP_HDR, fspPage.getPageType());
//
//            // 验证 FSP Header 字段
//            assertEquals(SPACE_ID, fspPage.getFspSpaceId());
//            assertEquals(1, fspPage.getSize());  // 初始大小为1页
//            assertEquals(1, fspPage.getFreeLimit());  // Free Limit初始为1
//            assertEquals(1, fspPage.getNextSegmentId());  // 下一个Segment ID为1
//
//            // 验证所有链表都已初始化为空
//            assertEquals(0, fspPage.getFreeList().getLength());
//            assertEquals(0, fspPage.getFreeFragList().getLength());
//            assertEquals(0, fspPage.getFullFragList().getLength());
//            assertEquals(0, fspPage.getInodesFreeList().getLength());
//            assertEquals(0, fspPage.getInodesFullList().getLength());
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testInitExtentLists() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page page = mtr.getPage(pageId);
//            FspHeaderPage fspPage = new FspHeaderPage(page);
//
//            // 初始化链表
//            fspPage.initExtentLists(mtr);
//
//            // 验证所有链表长度为0
//            assertEquals(0, fspPage.getFreeList().getLength());
//            assertEquals(0, fspPage.getFreeFragList().getLength());
//            assertEquals(0, fspPage.getFullFragList().getLength());
//            assertEquals(0, fspPage.getInodesFreeList().getLength());
//            assertEquals(0, fspPage.getInodesFullList().getLength());
//
//            // 验证所有链表的首尾指针为 FIL_NULL
//            assertNull(fspPage.getFreeList().getFirstNode());
//            assertNull(fspPage.getFreeList().getLastNode());
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== 集成测试 ====================
//
//    @Test
//    void testFullInitializationWorkflow() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            // 创建 FSP_HDR Page
//            PageId page0Id = PageId.of(SPACE_ID, 0);
//            Page page = mtr.getPage(page0Id);
//
//            FspHeaderPage fspPage = new FspHeaderPage(page);
//
//            // 初始化
//            fspPage.initialize(mtr, SPACE_ID);
//
//            // 验证基本字段
//            assertEquals(SPACE_ID, fspPage.getFspSpaceId());
//            assertEquals(1, fspPage.getSize());
//            assertEquals(1, fspPage.getFreeLimit());
//            assertEquals(1, fspPage.getNextSegmentId());
//
//            // 验证链表
//            assertNotNull(fspPage.getFreeList());
//            assertNotNull(fspPage.getFreeFragList());
//            assertNotNull(fspPage.getFullFragList());
//            assertNotNull(fspPage.getInodesFreeList());
//            assertNotNull(fspPage.getInodesFullList());
//
//            // 验证XDES Array访问
//            for (int i = 0; i < 10; i++) {
//                ExtentDescriptor ext = fspPage.getXdesEntry(i);
//                assertNotNull(ext);
//                assertEquals(i, ext.getExtentNo());
//                assertEquals(i * 64, ext.getStartPageNo());
//            }
//
//            // 分配几个 Segment ID
//            long seg1 = fspPage.allocateSegmentId(mtr);
//            long seg2 = fspPage.allocateSegmentId(mtr);
//            long seg3 = fspPage.allocateSegmentId(mtr);
//
//            assertEquals(1L, seg1);
//            assertEquals(2L, seg2);
//            assertEquals(3L, seg3);
//            assertEquals(4L, fspPage.getNextSegmentId());
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== toString 测试 ====================
//
//    @Test
//    void testToString() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page page = mtr.getPage(pageId);
//            FspHeaderPage fspPage = new FspHeaderPage(page);
//
//            fspPage.initialize(mtr, SPACE_ID);
//
//            String str = fspPage.toString();
//            assertNotNull(str);
//            assertTrue(str.contains("FspHeaderPage"));
//            assertTrue(str.contains("spaceId=" + SPACE_ID));
//
//            mtr.commit();
//        }
//    }
//}
