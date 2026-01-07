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
// * InodePage 单元测试
// *
// * <p>测试 INODE Page（段目录页）的核心功能：
// * <ul>
// *   <li>构造函数验证</li>
// *   <li>INODE Page Header 访问（链表节点）</li>
// *   <li>INODE Entry 访问和偏移计算</li>
// *   <li>INODE Entry 查找（空闲、按ID查找）</li>
// *   <li>Entry 分配和释放</li>
// *   <li>统计和状态查询（isFull, isEmpty, getUsedEntryCount）</li>
// *   <li>初始化操作</li>
// * </ul>
// * </p>
// *
// * @author MiniDB
// */
//class InodePageTest extends BaseStorageTest {
//
//    // ==================== 构造函数测试 ====================
//
//    @Test
//    void testConstructor_FromPageId() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            PageId pageId = PageId.of(SPACE_ID, 10);
//            InodePage inodePage = new InodePage(pageId);
//
//            assertNotNull(inodePage);
//            assertEquals(10, inodePage.getPageNo());
//            assertEquals(SPACE_ID, inodePage.getSpaceId());
//            assertEquals(PageType.FIL_PAGE_INODE, inodePage.getPageType());
//        }
//    }
//
//    @Test
//    void testConstructor_FromPage() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            page.setPageType(PageType.FIL_PAGE_INODE);
//
//            InodePage inodePage = new InodePage(page);
//
//            assertNotNull(inodePage);
//            assertEquals(page.getPageNo(), inodePage.getPageNo());
//        }
//    }
//
//    @Test
//    void testConstructor_FromPage_InvalidType() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            page.setPageType(PageType.FIL_PAGE_INDEX);  // 错误的类型
//
//            assertThrows(IllegalArgumentException.class, () -> {
//                new InodePage(page);
//            });
//        }
//    }
//
//    @Test
//    void testConstructor_FromPage_AllocatedType() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            page.setPageType(PageType.FIL_PAGE_TYPE_ALLOCATED);  // 允许的类型
//
//            InodePage inodePage = new InodePage(page);
//            assertNotNull(inodePage);
//        }
//    }
//
//    // ==================== INODE Page Header 测试 ====================
//
//    @Test
//    void testGetListNode() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            FlstNode listNode = inodePage.getListNode();
//            assertNotNull(listNode);
//
//            // 验证链表节点的位置（INODE Page Header 从 FIL_HEADER 之后开始）
//            assertEquals(FIL_HEADER_SIZE, listNode.getOffset());
//            assertEquals(page, listNode.getPage());
//        }
//    }
//
//    // ==================== INODE Entry 访问测试 ====================
//
//    @Test
//    void testGetInodeEntryOffset() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            // Entry 0: offset = FIL_HEADER + INODE_PAGE_HEADER
//            int expectedOffset0 = FIL_HEADER_SIZE + INODE_PAGE_HEADER_SIZE;
//            assertEquals(expectedOffset0, inodePage.getInodeEntryOffset(0));
//
//            // Entry 1: offset = Entry0 + 192
//            int expectedOffset1 = expectedOffset0 + INODE_ENTRY_SIZE;
//            assertEquals(expectedOffset1, inodePage.getInodeEntryOffset(1));
//
//            // Entry 84: offset = Entry0 + 84 * 192
//            int expectedOffset84 = expectedOffset0 + 84 * INODE_ENTRY_SIZE;
//            assertEquals(expectedOffset84, inodePage.getInodeEntryOffset(84));
//        }
//    }
//
//    @Test
//    void testGetInodeEntryOffset_InvalidIndex() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            // 负数索引
//            assertThrows(IllegalArgumentException.class, () -> {
//                inodePage.getInodeEntryOffset(-1);
//            });
//
//            // 索引过大（只有85个Entry，索引0-84）
//            assertThrows(IllegalArgumentException.class, () -> {
//                inodePage.getInodeEntryOffset(85);
//            });
//
//            assertThrows(IllegalArgumentException.class, () -> {
//                inodePage.getInodeEntryOffset(100);
//            });
//        }
//    }
//
//    @Test
//    void testGetInodeEntry() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            // 获取 Entry 0
//            SegmentDescriptor entry0 = inodePage.getInodeEntry(0);
//            assertNotNull(entry0);
//
//            // 获取 Entry 10
//            SegmentDescriptor entry10 = inodePage.getInodeEntry(10);
//            assertNotNull(entry10);
//
//            // 获取最后一个 Entry（84）
//            SegmentDescriptor entry84 = inodePage.getInodeEntry(84);
//            assertNotNull(entry84);
//        }
//    }
//
//    // ==================== INODE Entry 查找测试 ====================
//
//    @Test
//    void testFindFreeEntry_AllFree() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            // 初始化
//            inodePage.initialize(mtr);
//
//            // 所有 Entry 都空闲，应该返回第一个（索引0）
//            assertEquals(0, inodePage.findFreeEntry());
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testFindFreeEntry_SomeFree() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            inodePage.initialize(mtr);
//
//            // 分配前5个 Entry
//            for (int i = 0; i < 5; i++) {
//                SegmentDescriptor entry = inodePage.getInodeEntry(i);
//                entry.initialize(mtr, 100L + i);
//            }
//
//            // 应该返回第一个空闲的 Entry（索引5）
//            assertEquals(5, inodePage.findFreeEntry());
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testFindFreeEntry_AllUsed() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            inodePage.initialize(mtr);
//
//            // 分配所有85个 Entry
//            for (int i = 0; i < INODES_PER_PAGE; i++) {
//                SegmentDescriptor entry = inodePage.getInodeEntry(i);
//                entry.initialize(mtr, 1000L + i);
//            }
//
//            // 没有空闲 Entry，应该返回 -1
//            assertEquals(-1, inodePage.findFreeEntry());
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testFindEntryBySegmentId() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            inodePage.initialize(mtr);
//
//            // 分配几个 Entry
//            inodePage.getInodeEntry(0).initialize(mtr, 100L);
//            inodePage.getInodeEntry(5).initialize(mtr, 200L);
//            inodePage.getInodeEntry(10).initialize(mtr, 300L);
//
//            // 查找
//            assertEquals(0, inodePage.findEntryBySegmentId(100L));
//            assertEquals(5, inodePage.findEntryBySegmentId(200L));
//            assertEquals(10, inodePage.findEntryBySegmentId(300L));
//
//            // 查找不存在的
//            assertEquals(-1, inodePage.findEntryBySegmentId(999L));
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testFindEntryBySegmentId_InvalidSegmentId() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            // Segment ID 不能为 0
//            assertThrows(IllegalArgumentException.class, () -> {
//                inodePage.findEntryBySegmentId(0);
//            });
//        }
//    }
//
//    // ==================== 统计和状态查询测试 ====================
//
//    @Test
//    void testGetUsedEntryCount() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            inodePage.initialize(mtr);
//
//            // 初始状态：0个已分配
//            assertEquals(0, inodePage.getUsedEntryCount());
//
//            // 分配1个
//            inodePage.getInodeEntry(0).initialize(mtr, 100L);
//            assertEquals(1, inodePage.getUsedEntryCount());
//
//            // 分配5个
//            for (int i = 1; i < 5; i++) {
//                inodePage.getInodeEntry(i).initialize(mtr, 100L + i);
//            }
//            assertEquals(5, inodePage.getUsedEntryCount());
//
//            // 分配所有
//            for (int i = 5; i < INODES_PER_PAGE; i++) {
//                inodePage.getInodeEntry(i).initialize(mtr, 100L + i);
//            }
//            assertEquals(INODES_PER_PAGE, inodePage.getUsedEntryCount());
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testIsEmpty() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            inodePage.initialize(mtr);
//
//            // 初始状态：空
//            assertTrue(inodePage.isEmpty());
//            assertFalse(inodePage.isFull());
//
//            // 分配一个后：非空
//            inodePage.getInodeEntry(0).initialize(mtr, 100L);
//            assertFalse(inodePage.isEmpty());
//            assertFalse(inodePage.isFull());
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testIsFull() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            inodePage.initialize(mtr);
//
//            // 分配所有85个 Entry
//            for (int i = 0; i < INODES_PER_PAGE; i++) {
//                assertFalse(inodePage.isFull(), "Should not be full before allocating entry " + i);
//                inodePage.getInodeEntry(i).initialize(mtr, 1000L + i);
//            }
//
//            // 现在应该是满的
//            assertTrue(inodePage.isFull());
//            assertFalse(inodePage.isEmpty());
//            assertEquals(INODES_PER_PAGE, inodePage.getUsedEntryCount());
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== 初始化测试 ====================
//
//    @Test
//    void testInitialize() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            // 初始化
//            inodePage.initialize(mtr);
//
//            // 验证页面类型
//            assertEquals(PageType.FIL_PAGE_INODE, inodePage.getPageType());
//
//            // 验证链表节点已初始化
//            FlstNode listNode = inodePage.getListNode();
//            assertTrue(listNode.isIsolated());
//            assertFalse(listNode.hasPrev());
//            assertFalse(listNode.hasNext());
//
//            // 验证所有 Entry 都已初始化为空闲
//            assertEquals(0, inodePage.getUsedEntryCount());
//            assertTrue(inodePage.isEmpty());
//
//            // 验证所有 Entry 的魔数
//            for (int i = 0; i < INODES_PER_PAGE; i++) {
//                SegmentDescriptor entry = inodePage.getInodeEntry(i);
//                assertEquals(0, entry.getSegmentId());
//                assertEquals(INODE_MAGIC_NUMBER, entry.getMagicNumber());
//            }
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testInitAllEntries() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            // 初始化所有 Entry
//            inodePage.initAllEntries(mtr);
//
//            // 验证所有 Entry
//            for (int i = 0; i < INODES_PER_PAGE; i++) {
//                SegmentDescriptor entry = inodePage.getInodeEntry(i);
//
//                assertEquals(0, entry.getSegmentId(), "Entry " + i + " should have segmentId=0");
//                assertEquals(INODE_MAGIC_NUMBER, entry.getMagicNumber(), "Entry " + i + " should have magic number");
//
//                // 验证三个链表已初始化
//                assertEquals(0, entry.getFreeList().getLength());
//                assertEquals(0, entry.getNotFullList().getLength());
//                assertEquals(0, entry.getFullList().getLength());
//
//                // 验证碎片页数组初始化为 FIL_NULL
//                for (int j = 0; j < INODE_FRAG_ARRAY_PAGES; j++) {
//                    assertEquals(FIL_NULL, entry.getFragPageNo(j));
//                }
//            }
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testInitEntry() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            inodePage.initialize(mtr);
//
//            // 分配一个 Entry
//            inodePage.getInodeEntry(5).initialize(mtr, 999L);
//            assertEquals(999L, inodePage.getInodeEntry(5).getSegmentId());
//
//            // 清除这个 Entry
//            inodePage.initEntry(mtr, 5);
//            assertEquals(0, inodePage.getInodeEntry(5).getSegmentId());
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testAllocateEntry() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            inodePage.initialize(mtr);
//
//            // 分配第一个 Entry
//            int index1 = inodePage.allocateEntry(mtr, 100L);
//            assertEquals(0, index1);
//            assertEquals(100L, inodePage.getInodeEntry(index1).getSegmentId());
//
//            // 分配第二个 Entry
//            int index2 = inodePage.allocateEntry(mtr, 200L);
//            assertEquals(1, index2);
//            assertEquals(200L, inodePage.getInodeEntry(index2).getSegmentId());
//
//            // 验证计数
//            assertEquals(2, inodePage.getUsedEntryCount());
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testAllocateEntry_InvalidSegmentId() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            inodePage.initialize(mtr);
//
//            // Segment ID 不能为 0
//            assertThrows(IllegalArgumentException.class, () -> {
//                inodePage.allocateEntry(mtr, 0);
//            });
//        }
//    }
//
//    @Test
//    void testAllocateEntry_NoFreeEntry() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            inodePage.initialize(mtr);
//
//            // 分配所有85个 Entry
//            for (int i = 0; i < INODES_PER_PAGE; i++) {
//                int index = inodePage.allocateEntry(mtr, 1000L + i);
//                assertEquals(i, index);
//            }
//
//            // 再次分配应该返回 -1（没有空闲 Entry）
//            int index = inodePage.allocateEntry(mtr, 9999L);
//            assertEquals(-1, index);
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testFreeEntry() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            inodePage.initialize(mtr);
//
//            // 分配几个 Entry
//            inodePage.allocateEntry(mtr, 100L);
//            inodePage.allocateEntry(mtr, 200L);
//            inodePage.allocateEntry(mtr, 300L);
//
//            assertEquals(3, inodePage.getUsedEntryCount());
//
//            // 释放第二个 Entry（索引1）
//            inodePage.freeEntry(mtr, 1);
//            assertEquals(2, inodePage.getUsedEntryCount());
//            assertEquals(0, inodePage.getInodeEntry(1).getSegmentId());
//
//            // 验证其他 Entry 未受影响
//            assertEquals(100L, inodePage.getInodeEntry(0).getSegmentId());
//            assertEquals(300L, inodePage.getInodeEntry(2).getSegmentId());
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== 统计方法测试 ====================
//
//    @Test
//    void testGetAllSegmentIds() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            inodePage.initialize(mtr);
//
//            // 初始状态：空数组
//            long[] segmentIds = inodePage.getAllSegmentIds();
//            assertEquals(0, segmentIds.length);
//
//            // 分配几个 Entry
//            inodePage.allocateEntry(mtr, 100L);
//            inodePage.allocateEntry(mtr, 200L);
//            inodePage.allocateEntry(mtr, 300L);
//
//            // 获取所有 Segment ID
//            segmentIds = inodePage.getAllSegmentIds();
//            assertEquals(3, segmentIds.length);
//            assertEquals(100L, segmentIds[0]);
//            assertEquals(200L, segmentIds[1]);
//            assertEquals(300L, segmentIds[2]);
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testGetAllSegmentIds_WithGaps() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            inodePage.initialize(mtr);
//
//            // 分配不连续的 Entry
//            inodePage.getInodeEntry(0).initialize(mtr, 100L);
//            inodePage.getInodeEntry(5).initialize(mtr, 200L);
//            inodePage.getInodeEntry(10).initialize(mtr, 300L);
//
//            // 获取所有 Segment ID
//            long[] segmentIds = inodePage.getAllSegmentIds();
//            assertEquals(3, segmentIds.length);
//            assertEquals(100L, segmentIds[0]);
//            assertEquals(200L, segmentIds[1]);
//            assertEquals(300L, segmentIds[2]);
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== 边界条件测试 ====================
//
//    @Test
//    void testEntryIndexBoundaries() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            inodePage.initialize(mtr);
//
//            // 测试第一个 Entry（索引0）
//            SegmentDescriptor first = inodePage.getInodeEntry(0);
//            assertNotNull(first);
//            first.initialize(mtr, 100L);
//            assertEquals(100L, first.getSegmentId());
//
//            // 测试最后一个 Entry（索引84）
//            SegmentDescriptor last = inodePage.getInodeEntry(84);
//            assertNotNull(last);
//            last.initialize(mtr, 200L);
//            assertEquals(200L, last.getSegmentId());
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== 集成测试 ====================
//
//    @Test
//    void testFullAllocationAndFreeWorkflow() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            // 初始化
//            inodePage.initialize(mtr);
//            assertTrue(inodePage.isEmpty());
//            assertFalse(inodePage.isFull());
//
//            // 分配所有85个 Entry
//            for (int i = 0; i < INODES_PER_PAGE; i++) {
//                int index = inodePage.allocateEntry(mtr, 1000L + i);
//                assertEquals(i, index);
//            }
//
//            assertTrue(inodePage.isFull());
//            assertFalse(inodePage.isEmpty());
//            assertEquals(INODES_PER_PAGE, inodePage.getUsedEntryCount());
//
//            // 验证没有空闲 Entry
//            assertEquals(-1, inodePage.findFreeEntry());
//            assertEquals(-1, inodePage.allocateEntry(mtr, 9999L));
//
//            // 释放一个 Entry
//            inodePage.freeEntry(mtr, 42);
//            assertFalse(inodePage.isFull());
//            assertEquals(INODES_PER_PAGE - 1, inodePage.getUsedEntryCount());
//            assertEquals(42, inodePage.findFreeEntry());
//
//            // 可以再次分配
//            int newIndex = inodePage.allocateEntry(mtr, 8888L);
//            assertEquals(42, newIndex);
//            assertTrue(inodePage.isFull());
//
//            // 验证所有 Segment ID
//            long[] segmentIds = inodePage.getAllSegmentIds();
//            assertEquals(INODES_PER_PAGE, segmentIds.length);
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
//            Page page = mtr.newPage(SPACE_ID);
//            InodePage inodePage = new InodePage(page);
//
//            inodePage.initialize(mtr);
//
//            // 分配10个 Entry
//            for (int i = 0; i < 10; i++) {
//                inodePage.allocateEntry(mtr, 100L + i);
//            }
//
//            String str = inodePage.toString();
//            assertNotNull(str);
//            assertTrue(str.contains("InodePage"));
//            assertTrue(str.contains("10"));  // used count
//            assertTrue(str.contains("85"));  // total count
//
//            mtr.commit();
//        }
//    }
//}
