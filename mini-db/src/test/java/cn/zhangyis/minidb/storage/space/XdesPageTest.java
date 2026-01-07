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
// * XdesPage 单元测试
// *
// * <p>测试 XDES Page（区描述页）的核心功能：
// * <ul>
// *   <li>构造函数验证（页号必须是16384的倍数）</li>
// *   <li>静态工具方法（getXdesPageNo, getXdesEntryOffset, isXdesPage, getExtentRange）</li>
// *   <li>XDES Entry 访问</li>
// *   <li>Extent 范围计算</li>
// *   <li>初始化和批量初始化</li>
// * </ul>
// * </p>
// *
// * @author MiniDB
// */
//class XdesPageTest extends BaseStorageTest {
//
//    // ==================== 构造函数测试 ====================
//
//    @Test
//    void testConstructor_FromPageId_ValidPageNo() throws Exception {
//        // 构造函数测试不需要 MTR，直接创建即可
//        PageId pageId = PageId.of(SPACE_ID, 16384);
//        XdesPage xdesPage = new XdesPage(pageId);
//
//        assertNotNull(xdesPage);
//        assertEquals(16384, xdesPage.getPageNo());
//        assertEquals(SPACE_ID, xdesPage.getSpaceId());
//        assertEquals(PageType.FIL_PAGE_TYPE_XDES, xdesPage.getPageType());
//    }
//
//    @Test
//    void testConstructor_FromPageId_InvalidPageNo_Zero() {
//        // page 0 不能用 XdesPage（应该用 FspHeaderPage）
//        PageId pageId = PageId.of(SPACE_ID, 0);
//        assertThrows(IllegalArgumentException.class, () -> {
//            new XdesPage(pageId);
//        });
//    }
//
//    @Test
//    void testConstructor_FromPageId_InvalidPageNo_NotMultiple() {
//        // 页号不是16384的倍数
//        PageId pageId = PageId.of(SPACE_ID, 100);
//        assertThrows(IllegalArgumentException.class, () -> {
//            new XdesPage(pageId);
//        });
//    }
//
//    @Test
//    void testConstructor_FromPageId_ValidMultiples() throws Exception {
//        // 构造函数测试不需要 MTR，直接创建即可
//        int[] validPageNos = {16384, 32768, 49152, 65536};
//
//        for (int pageNo : validPageNos) {
//            PageId pageId = PageId.of(SPACE_ID, pageNo);
//            XdesPage xdesPage = new XdesPage(pageId);
//
//            assertNotNull(xdesPage);
//            assertEquals(pageNo, xdesPage.getPageNo());
//        }
//    }
//
//    @Test
//    void testConstructor_FromPage() throws Exception {
//        // 构造函数测试不需要 MTR，直接创建内存页面即可
//        PageId pageId = PageId.of(SPACE_ID, 16384);
//        Page xdesPageBase = new Page(pageId);
//        xdesPageBase.setPageType(PageType.FIL_PAGE_TYPE_XDES);
//
//        XdesPage xdesPage = new XdesPage(xdesPageBase);
//
//        assertNotNull(xdesPage);
//        assertEquals(16384, xdesPage.getPageNo());
//    }
//
//    @Test
//    void testConstructor_FromPage_InvalidType() throws Exception {
//        PageId pageId = PageId.of(SPACE_ID, 16384);
//        Page page = new Page(pageId);
//        page.setPageType(PageType.FIL_PAGE_INDEX);  // 错误的类型
//
//        assertThrows(IllegalArgumentException.class, () -> {
//            new XdesPage(page);
//        });
//    }
//
//    @Test
//    void testConstructor_FromPage_AllocatedType() throws Exception {
//        PageId pageId = PageId.of(SPACE_ID, 16384);
//        Page page = new Page(pageId);
//        page.setPageType(PageType.FIL_PAGE_TYPE_ALLOCATED);  // 允许的类型
//
//        XdesPage xdesPage = new XdesPage(page);
//        assertNotNull(xdesPage);
//    }
//
//    // ==================== 静态工具方法测试 ====================
//
//    @Test
//    void testGetXdesPageNo() {
//        // Extent 0-255 → page 0 (FSP_HDR)
//        assertEquals(0, XdesPage.getXdesPageNo(0));
//        assertEquals(0, XdesPage.getXdesPageNo(100));
//        assertEquals(0, XdesPage.getXdesPageNo(255));
//
//        // Extent 256-511 → page 16384
//        assertEquals(16384, XdesPage.getXdesPageNo(256));
//        assertEquals(16384, XdesPage.getXdesPageNo(300));
//        assertEquals(16384, XdesPage.getXdesPageNo(511));
//
//        // Extent 512-767 → page 32768
//        assertEquals(32768, XdesPage.getXdesPageNo(512));
//        assertEquals(32768, XdesPage.getXdesPageNo(600));
//        assertEquals(32768, XdesPage.getXdesPageNo(767));
//
//        // Extent 768-1023 → page 49152
//        assertEquals(49152, XdesPage.getXdesPageNo(768));
//        assertEquals(49152, XdesPage.getXdesPageNo(900));
//        assertEquals(49152, XdesPage.getXdesPageNo(1023));
//    }
//
//    @Test
//    void testGetXdesPageNo_InvalidExtentNo() {
//        assertThrows(IllegalArgumentException.class, () -> {
//            XdesPage.getXdesPageNo(-1);
//        });
//    }
//
//    @Test
//    void testGetXdesEntryOffset() {
//        // Extent 0: offset = FIL_HEADER_SIZE + 0 * 40
//        assertEquals(FIL_HEADER_SIZE, XdesPage.getXdesEntryOffset(0));
//
//        // Extent 1: offset = FIL_HEADER_SIZE + 1 * 40
//        assertEquals(FIL_HEADER_SIZE + XDES_ENTRY_SIZE, XdesPage.getXdesEntryOffset(1));
//
//        // Extent 255: offset = FIL_HEADER_SIZE + 255 * 40
//        assertEquals(FIL_HEADER_SIZE + 255 * XDES_ENTRY_SIZE, XdesPage.getXdesEntryOffset(255));
//
//        // Extent 256: offset = FIL_HEADER_SIZE + 0 * 40（新的XDES Page）
//        assertEquals(FIL_HEADER_SIZE, XdesPage.getXdesEntryOffset(256));
//
//        // Extent 300: 在 page 16384 中，本地索引为 300 % 256 = 44
//        assertEquals(FIL_HEADER_SIZE + 44 * XDES_ENTRY_SIZE, XdesPage.getXdesEntryOffset(300));
//    }
//
//    @Test
//    void testGetXdesEntryOffset_InvalidExtentNo() {
//        assertThrows(IllegalArgumentException.class, () -> {
//            XdesPage.getXdesEntryOffset(-1);
//        });
//    }
//
//    @Test
//    void testIsXdesPage() {
//        // page 0 是 FSP_HDR，也包含 XDES Array
//        assertTrue(XdesPage.isXdesPage(0));
//
//        // 16384的倍数
//        assertTrue(XdesPage.isXdesPage(16384));
//        assertTrue(XdesPage.isXdesPage(32768));
//        assertTrue(XdesPage.isXdesPage(49152));
//        assertTrue(XdesPage.isXdesPage(65536));
//
//        // 非16384的倍数
//        assertFalse(XdesPage.isXdesPage(1));
//        assertFalse(XdesPage.isXdesPage(100));
//        assertFalse(XdesPage.isXdesPage(16383));
//        assertFalse(XdesPage.isXdesPage(16385));
//    }
//
//    @Test
//    void testGetExtentRange() {
//        // Extent 0-255: group 0
//        assertArrayEquals(new int[]{0, 255}, XdesPage.getExtentRange(0));
//        assertArrayEquals(new int[]{0, 255}, XdesPage.getExtentRange(100));
//        assertArrayEquals(new int[]{0, 255}, XdesPage.getExtentRange(255));
//
//        // Extent 256-511: group 1
//        assertArrayEquals(new int[]{256, 511}, XdesPage.getExtentRange(256));
//        assertArrayEquals(new int[]{256, 511}, XdesPage.getExtentRange(300));
//        assertArrayEquals(new int[]{256, 511}, XdesPage.getExtentRange(511));
//
//        // Extent 512-767: group 2
//        assertArrayEquals(new int[]{512, 767}, XdesPage.getExtentRange(512));
//        assertArrayEquals(new int[]{512, 767}, XdesPage.getExtentRange(600));
//        assertArrayEquals(new int[]{512, 767}, XdesPage.getExtentRange(767));
//    }
//
//    // ==================== XDES Entry 访问测试 ====================
//
//    @Test
//    void testGetXdesEntry_ValidExtent() throws Exception {
//        // 创建内存页面进行测试
//        PageId pageId = PageId.of(SPACE_ID, 16384);
//        Page page = new Page(pageId);
//        page.setPageType(PageType.FIL_PAGE_TYPE_XDES);
//        XdesPage xdesPage = new XdesPage(page);
//
//        // page 16384 管理 Extent 256-511
//        ExtentDescriptor ext256 = xdesPage.getXdesEntry(256);
//        assertNotNull(ext256);
//        assertEquals(256, ext256.getExtentNo());
//        assertEquals(256 * 64, ext256.getStartPageNo());
//
//        ExtentDescriptor ext300 = xdesPage.getXdesEntry(300);
//        assertNotNull(ext300);
//        assertEquals(300, ext300.getExtentNo());
//        assertEquals(300 * 64, ext300.getStartPageNo());
//
//        ExtentDescriptor ext511 = xdesPage.getXdesEntry(511);
//        assertNotNull(ext511);
//        assertEquals(511, ext511.getExtentNo());
//        assertEquals(511 * 64, ext511.getStartPageNo());
//    }
//
//    @Test
//    void testGetXdesEntry_WrongPage() throws Exception {
//        // 创建内存页面进行测试
//        PageId pageId = PageId.of(SPACE_ID, 16384);
//        Page page = new Page(pageId);
//        page.setPageType(PageType.FIL_PAGE_TYPE_XDES);
//        XdesPage xdesPage = new XdesPage(page);
//
//        // page 16384 管理 Extent 256-511，不能访问 Extent 100
//        assertThrows(IllegalArgumentException.class, () -> {
//            xdesPage.getXdesEntry(100);
//        });
//
//        // 也不能访问 Extent 512（属于 page 32768）
//        assertThrows(IllegalArgumentException.class, () -> {
//            xdesPage.getXdesEntry(512);
//        });
//    }
//
//    @Test
//    void testGetLocalExtentRange() throws Exception {
//        // 创建内存页面进行测试
//        // page 16384: Extent 256-511
//        PageId pageId1 = PageId.of(SPACE_ID, 16384);
//        Page page1 = new Page(pageId1);
//        page1.setPageType(PageType.FIL_PAGE_TYPE_XDES);
//        XdesPage xdesPage1 = new XdesPage(page1);
//        assertArrayEquals(new int[]{256, 511}, xdesPage1.getLocalExtentRange());
//
//        // page 32768: Extent 512-767
//        PageId pageId2 = PageId.of(SPACE_ID, 32768);
//        Page page2 = new Page(pageId2);
//        page2.setPageType(PageType.FIL_PAGE_TYPE_XDES);
//        XdesPage xdesPage2 = new XdesPage(page2);
//        assertArrayEquals(new int[]{512, 767}, xdesPage2.getLocalExtentRange());
//
//        // page 49152: Extent 768-1023
//        PageId pageId3 = PageId.of(SPACE_ID, 49152);
//        Page page3 = new Page(pageId3);
//        page3.setPageType(PageType.FIL_PAGE_TYPE_XDES);
//        XdesPage xdesPage3 = new XdesPage(page3);
//        assertArrayEquals(new int[]{768, 1023}, xdesPage3.getLocalExtentRange());
//    }
//
//    // ==================== 初始化测试 ====================
//
//    @Test
//    void testInitialize() throws Exception {
//        // 注意：initialize() 需要 MTR，但 XdesPage 要求特定页号（16384的倍数）
//        // 由于 buffer pool 中页面0不存在页面16384，我们需要先分配足够的页面
//        // 这里我们使用 page 0 (FSP_HDR) 来测试，它也包含 XDES Array
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            // 使用 page 0 进行测试（FSP_HDR 也有 XDES Array）
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page basePage = mtr.getPage(pageId);
//
//            // 注意：page 0 应该用 FspHeaderPage，这里仅用于测试 initialize 逻辑
//            // 实际生产代码中不应该这样使用
//            basePage.setPageType(PageType.FIL_PAGE_TYPE_XDES);
//            XdesPage xdesPage = new XdesPage(basePage);
//
//            // 初始化
//            xdesPage.initialize(mtr);
//
//            // 验证页面类型
//            assertEquals(PageType.FIL_PAGE_TYPE_XDES, xdesPage.getPageType());
//
//            // 验证所有256个 XDES Entry 都被初始化为 FREE 状态
//            int[] range = xdesPage.getLocalExtentRange();
//            for (int extentNo = range[0]; extentNo <= range[1]; extentNo++) {
//                ExtentDescriptor ext = xdesPage.getXdesEntry(extentNo);
//
//                assertEquals(0, ext.getSegmentId(), "Extent " + extentNo + " should have segmentId=0");
//                assertEquals(ExtentState.FREE, ext.getState(), "Extent " + extentNo + " should be FREE");
//                assertEquals(64, ext.getFreePageCount(), "Extent " + extentNo + " should have 64 free pages");
//                assertTrue(ext.isEmpty(), "Extent " + extentNo + " should be empty");
//            }
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testInitializeRange() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            // 使用 page 0 进行测试
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page basePage = mtr.getPage(pageId);
//            basePage.setPageType(PageType.FIL_PAGE_TYPE_XDES);
//            XdesPage xdesPage = new XdesPage(basePage);
//
//            // 只初始化 Extent 0-4（前5个，page 0 管理 Extent 0-255）
//            xdesPage.initializeRange(mtr, 0, 4);
//
//            // 验证前5个被初始化
//            for (int extentNo = 0; extentNo <= 4; extentNo++) {
//                ExtentDescriptor ext = xdesPage.getXdesEntry(extentNo);
//
//                assertEquals(0, ext.getSegmentId());
//                assertEquals(ExtentState.FREE, ext.getState());
//                assertEquals(64, ext.getFreePageCount());
//            }
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testInitializeRange_InvalidRange() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            // 使用 page 0 进行测试（管理 Extent 0-255）
//            PageId pageId = PageId.of(SPACE_ID, 0);
//            Page basePage = mtr.getPage(pageId);
//            basePage.setPageType(PageType.FIL_PAGE_TYPE_XDES);
//            XdesPage xdesPage = new XdesPage(basePage);
//
//            // page 0 管理 Extent 0-255，不能初始化 Extent 256（属于page 16384）
//            assertThrows(IllegalArgumentException.class, () -> {
//                xdesPage.initializeRange(mtr, 256, 260);
//            });
//
//            // 也不能初始化跨页的范围（0-255属于page 0，256属于page 16384）
//            assertThrows(IllegalArgumentException.class, () -> {
//                xdesPage.initializeRange(mtr, 0, 256);
//            });
//        }
//    }
//
//    // ==================== 边界条件测试 ====================
//
//    @Test
//    void testExtentRangeBoundaries() throws Exception {
//        // 创建内存页面测试边界（page 0 管理 Extent 0-255）
//        PageId pageId = PageId.of(SPACE_ID, 0);
//        Page page = new Page(pageId);
//        page.setPageType(PageType.FIL_PAGE_TYPE_XDES);
//        XdesPage xdesPage = new XdesPage(page);
//
//        // 能访问第一个 extent（0）
//        ExtentDescriptor first = xdesPage.getXdesEntry(0);
//        assertNotNull(first);
//        assertEquals(0, first.getExtentNo());
//
//        // 能访问最后一个 extent（255）
//        ExtentDescriptor last = xdesPage.getXdesEntry(255);
//        assertNotNull(last);
//        assertEquals(255, last.getExtentNo());
//
//        // 不能访问后一个（256，属于 page 16384）
//        assertThrows(IllegalArgumentException.class, () -> {
//            xdesPage.getXdesEntry(256);
//        });
//    }
//
//    @Test
//    void testPageNumberBoundaries() {
//        // 最小有效 XDES Page 号（除了0）
//        PageId pageId1 = PageId.of(SPACE_ID, 16384);
//        XdesPage xdesPage1 = new XdesPage(pageId1);
//        assertNotNull(xdesPage1);
//
//        // 大的有效 XDES Page 号
//        PageId pageId2 = PageId.of(SPACE_ID, 16384 * 10);
//        XdesPage xdesPage2 = new XdesPage(pageId2);
//        assertNotNull(xdesPage2);
//
//        // 边界-1（无效）
//        PageId pageId3 = PageId.of(SPACE_ID, 16383);
//        assertThrows(IllegalArgumentException.class, () -> {
//            new XdesPage(pageId3);
//        });
//
//        // 边界+1（无效）
//        PageId pageId4 = PageId.of(SPACE_ID, 16385);
//        assertThrows(IllegalArgumentException.class, () -> {
//            new XdesPage(pageId4);
//        });
//    }
//
//    // ==================== 集成测试 ====================
//
//    @Test
//    void testFullInitializationWorkflow() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            // 使用 page 0 进行测试
//            PageId pageId = PageId.of(SPACE_ID, 1);
//            Page basePage = mtr.getPage(pageId);
//            basePage.setPageType(PageType.FIL_PAGE_TYPE_XDES);
//            XdesPage xdesPage = new XdesPage(basePage);
//
//            // 初始化
//            xdesPage.initialize(mtr);
//
//            // 验证能正确访问所有256个 extent
//            int[] range = xdesPage.getLocalExtentRange();
//            assertEquals(0, range[0]);
//            assertEquals(255, range[1]);
//
//            // 验证每个 extent 都已初始化
//            for (int extentNo = range[0]; extentNo <= range[1]; extentNo++) {
//                ExtentDescriptor ext = xdesPage.getXdesEntry(extentNo);
//                assertNotNull(ext);
//                assertEquals(extentNo, ext.getExtentNo());
//                assertEquals(extentNo * 64, ext.getStartPageNo());
//                assertEquals(ExtentState.FREE, ext.getState());
//                assertTrue(ext.isEmpty());
//            }
//
//            // 测试修改其中一个 extent
//            ExtentDescriptor ext100 = xdesPage.getXdesEntry(100);
//            ext100.setState(mtr, ExtentState.FSEG);
//            ext100.setSegmentId(mtr, 999L);
//            ext100.allocatePage(mtr, 0);
//
//            assertEquals(ExtentState.FSEG, ext100.getState());
//            assertEquals(999L, ext100.getSegmentId());
//            assertEquals(63, ext100.getFreePageCount());
//            assertFalse(ext100.isEmpty());
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== toString 测试 ====================
//
//    @Test
//    void testToString() throws Exception {
//        // 创建内存页面测试 toString
//        PageId pageId = PageId.of(SPACE_ID, 16384);
//        Page page = new Page(pageId);
//        page.setPageType(PageType.FIL_PAGE_TYPE_XDES);
//        XdesPage xdesPage = new XdesPage(page);
//
//        String str = xdesPage.toString();
//        assertNotNull(str);
//        assertTrue(str.contains("XdesPage"));
//        assertTrue(str.contains("16384"));  // pageNo
//        assertTrue(str.contains("256"));    // startExtent
//        assertTrue(str.contains("511"));    // endExtent
//    }
//}
