//package cn.zhangyis.minidb.storage.space;
//
//import cn.zhangyis.minidb.storage.BaseStorageTest;
//import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
//import cn.zhangyis.minidb.storage.page.Page;
//import org.junit.jupiter.api.Test;
//
//import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;
//import static org.junit.jupiter.api.Assertions.*;
//
///**
// * SegmentDescriptor 单元测试
// *
// * <p>测试 Segment 描述符的核心功能：
// * <ul>
// *   <li>构造函数验证</li>
// *   <li>Segment ID 和字段管理</li>
// *   <li>碎片页数组操作</li>
// *   <li>链表访问（Free, NotFull, Full）</li>
// *   <li>初始化和清除</li>
// *   <li>状态查询</li>
// * </ul>
// * </p>
// *
// * @author MiniDB
// */
//class SegmentDescriptorTest extends BaseStorageTest {
//
//    // ==================== 构造函数测试 ====================
//
//    @Test
//    void testConstructor_ValidArguments() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//
//            // 在 FIL_HEADER + INODE_PAGE_HEADER 之后创建 SegmentDescriptor
//            int offset = FIL_HEADER_SIZE + INODE_PAGE_HEADER_SIZE;
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, offset);
//
//            assertNotNull(descriptor);
//        }
//    }
//
//    @Test
//    void testConstructor_NullPage() {
//        assertThrows(IllegalArgumentException.class, () -> {
//            new SegmentDescriptor(null, 50);
//        });
//    }
//
//    @Test
//    void testConstructor_InvalidOffset() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//
//            // 负数偏移
//            assertThrows(IllegalArgumentException.class, () -> {
//                new SegmentDescriptor(page, -1);
//            });
//
//            // 偏移太大，无法容纳192字节的INODE Entry
//            assertThrows(IllegalArgumentException.class, () -> {
//                new SegmentDescriptor(page, PAGE_SIZE - INODE_ENTRY_SIZE + 1);
//            });
//        }
//    }
//
//    // ==================== Segment ID 测试 ====================
//
//    @Test
//    void testSegmentId_GetAndSet() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, 50);
//
//            // 默认为0（未分配）
//            assertEquals(0, descriptor.getSegmentId());
//            assertFalse(descriptor.isAllocated());
//
//            // 设置 Segment ID
//            descriptor.setSegmentId(mtr, 12345L);
//            assertEquals(12345L, descriptor.getSegmentId());
//            assertTrue(descriptor.isAllocated());
//
//            // 设置为0（释放）
//            descriptor.setSegmentId(mtr, 0);
//            assertEquals(0, descriptor.getSegmentId());
//            assertFalse(descriptor.isAllocated());
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== NotFullNUsed 测试 ====================
//
//    @Test
//    void testNotFullNUsed_GetAndSet() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, 50);
//
//            // 默认为0
//            assertEquals(0, descriptor.getNotFullNUsed());
//
//            // 设置 NOT_FULL 使用页数
//            descriptor.setNotFullNUsed(mtr, 42);
//            assertEquals(42, descriptor.getNotFullNUsed());
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== Magic Number 测试 ====================
//
//    @Test
//    void testMagicNumber_GetAndSet() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, 50);
//
//            // 设置魔数
//            descriptor.setMagicNumber(mtr, INODE_MAGIC_NUMBER);
//            assertEquals(INODE_MAGIC_NUMBER, descriptor.getMagicNumber());
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== 碎片页数组测试 ====================
//
//    @Test
//    void testFragArray_GetAndSet() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, 50);
//
//            // 默认所有槽位为 FIL_NULL
//            for (int i = 0; i < INODE_FRAG_ARRAY_PAGES; i++) {
//                assertEquals(FIL_NULL, descriptor.getFragPageNo(i));
//            }
//
//            // 设置碎片页号
//            descriptor.setFragPageNo(mtr, 0, 100);
//            assertEquals(100, descriptor.getFragPageNo(0));
//
//            descriptor.setFragPageNo(mtr, 5, 500);
//            assertEquals(500, descriptor.getFragPageNo(5));
//
//            descriptor.setFragPageNo(mtr, 31, 3100);
//            assertEquals(3100, descriptor.getFragPageNo(31));
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testFragArray_InvalidIndex() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, 50);
//
//            // 负数索引
//            assertThrows(IllegalArgumentException.class, () -> {
//                descriptor.getFragPageNo(-1);
//            });
//
//            // 索引太大
//            assertThrows(IllegalArgumentException.class, () -> {
//                descriptor.getFragPageNo(32);
//            });
//        }
//    }
//
//    @Test
//    void testFindFreeFragSlot_AllFree() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, 50);
//
//            // 初始化碎片数组为 FIL_NULL
//            descriptor.clearFragArray(mtr);
//
//            // 所有槽位都空闲，应该返回第一个槽位
//            assertEquals(0, descriptor.findFreeFragSlot());
//            assertEquals(0, descriptor.getFragUsedCount());
//            assertFalse(descriptor.isFragArrayFull());
//        }
//    }
//
//    @Test
//    void testFindFreeFragSlot_SomeFree() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, 50);
//
//            descriptor.clearFragArray(mtr);
//
//            // 分配前10个槽位
//            for (int i = 0; i < 10; i++) {
//                descriptor.setFragPageNo(mtr, i, 100 + i);
//            }
//
//            // 应该返回第一个空闲槽位（索引10）
//            assertEquals(10, descriptor.findFreeFragSlot());
//            assertEquals(10, descriptor.getFragUsedCount());
//            assertFalse(descriptor.isFragArrayFull());
//        }
//    }
//
//    @Test
//    void testFindFreeFragSlot_AllAllocated() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, 50);
//
//            descriptor.clearFragArray(mtr);
//
//            // 分配所有32个槽位
//            for (int i = 0; i < INODE_FRAG_ARRAY_PAGES; i++) {
//                descriptor.setFragPageNo(mtr, i, 100 + i);
//            }
//
//            // 没有空闲槽位，应该返回-1
//            assertEquals(-1, descriptor.findFreeFragSlot());
//            assertEquals(32, descriptor.getFragUsedCount());
//            assertTrue(descriptor.isFragArrayFull());
//        }
//    }
//
//    @Test
//    void testGetFragUsedCount() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, 50);
//
//            descriptor.clearFragArray(mtr);
//
//            // 逐步分配，验证计数
//            for (int allocated = 0; allocated <= 32; allocated++) {
//                assertEquals(allocated, descriptor.getFragUsedCount());
//
//                if (allocated < 32) {
//                    descriptor.setFragPageNo(mtr, allocated, 100 + allocated);
//                }
//            }
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testClearFragArray() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, 50);
//
//            // 先分配一些槽位
//            for (int i = 0; i < 10; i++) {
//                descriptor.setFragPageNo(mtr, i, 100 + i);
//            }
//
//            assertEquals(10, descriptor.getFragUsedCount());
//
//            // 清空碎片数组
//            descriptor.clearFragArray(mtr);
//
//            // 验证所有槽位都是 FIL_NULL
//            for (int i = 0; i < INODE_FRAG_ARRAY_PAGES; i++) {
//                assertEquals(FIL_NULL, descriptor.getFragPageNo(i));
//            }
//
//            assertEquals(0, descriptor.getFragUsedCount());
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== 链表访问测试 ====================
//
//    @Test
//    void testGetFreeList() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, 50);
//
//            FlstBaseNode freeList = descriptor.getFreeList();
//            assertNotNull(freeList);
//
//            // 验证链表节点的位置（应该在 INODE_FREE 偏移处）
//            assertEquals(50 + INODE_FREE, freeList.getOffset());
//        }
//    }
//
//    @Test
//    void testGetNotFullList() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, 50);
//
//            FlstBaseNode notFullList = descriptor.getNotFullList();
//            assertNotNull(notFullList);
//
//            // 验证链表节点的位置（应该在 INODE_NOT_FULL 偏移处）
//            assertEquals(50 + INODE_NOT_FULL, notFullList.getOffset());
//        }
//    }
//
//    @Test
//    void testGetFullList() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, 50);
//
//            FlstBaseNode fullList = descriptor.getFullList();
//            assertNotNull(fullList);
//
//            // 验证链表节点的位置（应该在 INODE_FULL 偏移处）
//            assertEquals(50 + INODE_FULL, fullList.getOffset());
//        }
//    }
//
//    // ==================== 初始化和清除测试 ====================
//
//    @Test
//    void testInitialize() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, 50);
//
//            // 初始化
//            descriptor.initialize(mtr, 999L);
//
//            // 验证 Segment ID
//            assertEquals(999L, descriptor.getSegmentId());
//            assertTrue(descriptor.isAllocated());
//
//            // 验证 NOT_FULL 计数
//            assertEquals(0, descriptor.getNotFullNUsed());
//
//            // 验证魔数
//            assertEquals(INODE_MAGIC_NUMBER, descriptor.getMagicNumber());
//
//            // 验证三个链表都为空
//            assertEquals(0, descriptor.getFreeList().getLength());
//            assertEquals(0, descriptor.getNotFullList().getLength());
//            assertEquals(0, descriptor.getFullList().getLength());
//
//            // 验证碎片页数组已清空
//            for (int i = 0; i < INODE_FRAG_ARRAY_PAGES; i++) {
//                assertEquals(FIL_NULL, descriptor.getFragPageNo(i));
//            }
//
//            assertEquals(0, descriptor.getFragUsedCount());
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testClear() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, 50);
//
//            // 先初始化
//            descriptor.initialize(mtr, 999L);
//            assertTrue(descriptor.isAllocated());
//
//            // 设置一些数据
//            descriptor.setNotFullNUsed(mtr, 10);
//            descriptor.setFragPageNo(mtr, 0, 100);
//
//            // 清除
//            descriptor.clear(mtr);
//
//            // 验证 Segment ID 为 0
//            assertEquals(0, descriptor.getSegmentId());
//            assertFalse(descriptor.isAllocated());
//
//            // 验证其他字段已重置
//            assertEquals(0, descriptor.getNotFullNUsed());
//            assertEquals(INODE_MAGIC_NUMBER, descriptor.getMagicNumber());
//
//            // 验证链表已清空
//            assertEquals(0, descriptor.getFreeList().getLength());
//            assertEquals(0, descriptor.getNotFullList().getLength());
//            assertEquals(0, descriptor.getFullList().getLength());
//
//            // 验证碎片页数组已清空
//            assertEquals(FIL_NULL, descriptor.getFragPageNo(0));
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== 统计信息测试 ====================
//
//    @Test
//    void testGetTotalExtentCount() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, 50);
//
//            // 初始化链表
//            descriptor.getFreeList().initialize(mtr);
//            descriptor.getNotFullList().initialize(mtr);
//            descriptor.getFullList().initialize(mtr);
//
//            // 初始状态：0个Extent
//            assertEquals(0, descriptor.getTotalExtentCount());
//
//            // 模拟添加 Extent 到链表
//            // （这里只测试长度累加，实际添加Extent需要更复杂的操作）
//            descriptor.getFreeList().setLength(mtr, 2);
//            descriptor.getNotFullList().setLength(mtr, 3);
//            descriptor.getFullList().setLength(mtr, 5);
//
//            // 总计 = 2 + 3 + 5 = 10
//            assertEquals(10, descriptor.getTotalExtentCount());
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testGetEstimatedPageCount() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, 50);
//
//            descriptor.initialize(mtr, 100L);
//
//            // 初始状态：0个碎片页 + 0个Extent = 0页
//            assertEquals(0, descriptor.getEstimatedPageCount());
//
//            // 添加10个碎片页
//            for (int i = 0; i < 10; i++) {
//                descriptor.setFragPageNo(mtr, i, 100 + i);
//            }
//
//            // 10个碎片页 + 0个Extent = 10页
//            assertEquals(10, descriptor.getEstimatedPageCount());
//
//            // 模拟添加5个Extent
//            descriptor.getFreeList().setLength(mtr, 2);
//            descriptor.getNotFullList().setLength(mtr, 1);
//            descriptor.getFullList().setLength(mtr, 2);
//
//            // 10个碎片页 + 5个Extent × 64页 = 10 + 320 = 330页
//            assertEquals(330, descriptor.getEstimatedPageCount());
//
//            mtr.commit();
//        }
//    }
//
//    // ==================== toString 测试 ====================
//
//    @Test
//    void testToString_Unallocated() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, 50);
//
//            String str = descriptor.toString();
//            assertNotNull(str);
//            assertTrue(str.contains("unallocated"));
//        }
//    }
//
//    @Test
//    void testToString_Allocated() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, 50);
//
//            descriptor.initialize(mtr, 888L);
//
//            // 添加一些数据
//            descriptor.setFragPageNo(mtr, 0, 100);
//            descriptor.setFragPageNo(mtr, 1, 101);
//            descriptor.getFreeList().setLength(mtr, 1);
//            descriptor.getNotFullList().setLength(mtr, 2);
//            descriptor.getFullList().setLength(mtr, 3);
//
//            String str = descriptor.toString();
//            assertNotNull(str);
//            assertTrue(str.contains("888"));    // segmentId
//            assertTrue(str.contains("1"));      // freeExtents
//            assertTrue(str.contains("2"));      // partialExtents
//            assertTrue(str.contains("3"));      // fullExtents
//            assertTrue(str.contains("2/32"));   // fragPages
//
//            mtr.commit();
//        }
//    }
//
//    @Test
//    void testToDetailedString() throws Exception {
//        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
//            Page page = mtr.newPage(SPACE_ID);
//            SegmentDescriptor descriptor = new SegmentDescriptor(page, 50);
//
//            descriptor.initialize(mtr, 777L);
//
//            String detailedStr = descriptor.toDetailedString();
//            assertNotNull(detailedStr);
//            assertTrue(detailedStr.contains("Segment ID: 777"));
//            assertTrue(detailedStr.contains("Magic Number"));
//            assertTrue(detailedStr.contains("Free Extents"));
//            assertTrue(detailedStr.contains("Partial Extents"));
//            assertTrue(detailedStr.contains("Full Extents"));
//            assertTrue(detailedStr.contains("Frag Pages"));
//
//            mtr.commit();
//        }
//    }
//}
