package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.storage.BaseStorageTest;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import org.junit.jupiter.api.Test;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ExtentDescriptor 单元测试
 *
 * <p>测试 Extent 描述符的核心功能：
 * <ul>
 *   <li>构造函数验证</li>
 *   <li>Segment ID 和状态管理</li>
 *   <li>Bitmap 操作（分配/释放页面）</li>
 *   <li>空闲页查找</li>
 *   <li>状态查询（isFull, isEmpty）</li>
 *   <li>链表节点访问</li>
 * </ul>
 * </p>
 *
 * @author MiniDB
 */
class ExtentDescriptorTest extends BaseStorageTest {

    // ==================== 构造函数测试 ====================

    @Test
    void testConstructor_ValidArguments() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);

            // 在页面偏移150处创建 ExtentDescriptor（FSP_HDR中的XDES Array起始位置）
            ExtentDescriptor descriptor = new ExtentDescriptor(page, 150, 0);

            assertNotNull(descriptor);
            assertEquals(0, descriptor.getExtentNo());
            assertEquals(0, descriptor.getStartPageNo());
        }
    }

    @Test
    void testConstructor_NullPage() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ExtentDescriptor(null, 150, 0);
        });
    }

    @Test
    void testConstructor_InvalidOffset() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);

            // 负数偏移
            assertThrows(IllegalArgumentException.class, () -> {
                new ExtentDescriptor(page, -1, 0);
            });

            // 偏移太大，无法容纳40字节的XDES Entry
            assertThrows(IllegalArgumentException.class, () -> {
                new ExtentDescriptor(page, PAGE_SIZE - XDES_ENTRY_SIZE + 1, 0);
            });
        }
    }

    @Test
    void testConstructor_InvalidExtentNo() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);

            assertThrows(IllegalArgumentException.class, () -> {
                new ExtentDescriptor(page, 150, -1);
            });
        }
    }

    @Test
    void testGetStartPageNo() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);

            // Extent 0: 起始页号 = 0
            ExtentDescriptor ext0 = new ExtentDescriptor(page, 150, 0);
            assertEquals(0, ext0.getStartPageNo());

            // Extent 1: 起始页号 = 64
            ExtentDescriptor ext1 = new ExtentDescriptor(page, 190, 1);
            assertEquals(64, ext1.getStartPageNo());

            // Extent 10: 起始页号 = 640
            ExtentDescriptor ext10 = new ExtentDescriptor(page, 150, 10);
            assertEquals(640, ext10.getStartPageNo());
        }
    }

    // ==================== Segment ID 测试 ====================

    @Test
    void testSegmentId_GetAndSet() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            ExtentDescriptor descriptor = new ExtentDescriptor(page, 150, 0);

            // 默认为0
            assertEquals(0, descriptor.getSegmentId());

            // 设置 Segment ID
            descriptor.setSegmentId(mtr, 12345L);
            assertEquals(12345L, descriptor.getSegmentId());

            // 设置为0（表示不属于任何Segment）
            descriptor.setSegmentId(mtr, 0);
            assertEquals(0, descriptor.getSegmentId());

            mtr.commit();
        }
    }

    // ==================== State 测试 ====================

    @Test
    void testState_GetAndSet() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            ExtentDescriptor descriptor = new ExtentDescriptor(page, 150, 0);

            // 设置为 FREE
            descriptor.setState(mtr, ExtentState.FREE);
            assertEquals(ExtentState.FREE, descriptor.getState());

            // 设置为 FSEG
            descriptor.setState(mtr, ExtentState.FSEG);
            assertEquals(ExtentState.FSEG, descriptor.getState());

            // 设置为 FREE_FRAG
            descriptor.setState(mtr, ExtentState.FREE_FRAG);
            assertEquals(ExtentState.FREE_FRAG, descriptor.getState());

            mtr.commit();
        }
    }

    // ==================== Bitmap 操作测试 ====================

    @Test
    void testInitBitmap() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            ExtentDescriptor descriptor = new ExtentDescriptor(page, 150, 0);

            // 初始化 Bitmap
            descriptor.initBitmap(mtr);

            // 验证所有64页都是空闲的
            for (int i = 0; i < EXTENT_SIZE; i++) {
                assertTrue(descriptor.isPageFree(i), "Page " + i + " should be free");
            }

            assertEquals(64, descriptor.getFreePageCount());
            assertEquals(0, descriptor.getUsedPageCount());
            assertTrue(descriptor.isEmpty());
            assertFalse(descriptor.isFull());

            mtr.commit();
        }
    }

    @Test
    void testAllocatePage() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            ExtentDescriptor descriptor = new ExtentDescriptor(page, 150, 0);

            descriptor.initBitmap(mtr);

            // 分配第一个页面（偏移0）
            assertTrue(descriptor.isPageFree(0));
            descriptor.allocatePage(mtr, 0);
            assertFalse(descriptor.isPageFree(0));

            // 验证统计信息
            assertEquals(63, descriptor.getFreePageCount());
            assertEquals(1, descriptor.getUsedPageCount());
            assertFalse(descriptor.isEmpty());
            assertFalse(descriptor.isFull());

            // 分配第二个页面（偏移5）
            descriptor.allocatePage(mtr, 5);
            assertFalse(descriptor.isPageFree(5));
            assertEquals(62, descriptor.getFreePageCount());
            assertEquals(2, descriptor.getUsedPageCount());

            mtr.commit();
        }
    }

    @Test
    void testAllocatePage_AlreadyAllocated() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            ExtentDescriptor descriptor = new ExtentDescriptor(page, 150, 0);

            descriptor.initBitmap(mtr);
            descriptor.allocatePage(mtr, 10);

            // 重复分配应该抛出异常
            assertThrows(IllegalStateException.class, () -> {
                descriptor.allocatePage(mtr, 10);
            });
        }
    }

    @Test
    void testFreePage() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            ExtentDescriptor descriptor = new ExtentDescriptor(page, 150, 0);

            descriptor.initBitmap(mtr);
            descriptor.allocatePage(mtr, 10);
            assertFalse(descriptor.isPageFree(10));

            // 释放页面
            descriptor.freePage(mtr, 10);
            assertTrue(descriptor.isPageFree(10));

            assertEquals(64, descriptor.getFreePageCount());
            assertEquals(0, descriptor.getUsedPageCount());

            mtr.commit();
        }
    }

    @Test
    void testFreePage_AlreadyFree() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            ExtentDescriptor descriptor = new ExtentDescriptor(page, 150, 0);

            descriptor.initBitmap(mtr);

            // 释放已经空闲的页面应该抛出异常
            assertThrows(IllegalStateException.class, () -> {
                descriptor.freePage(mtr, 10);
            });
        }
    }

    @Test
    void testAllocateAndFreeMultiplePages() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            ExtentDescriptor descriptor = new ExtentDescriptor(page, 150, 0);

            descriptor.initBitmap(mtr);

            // 分配10个页面
            for (int i = 0; i < 10; i++) {
                descriptor.allocatePage(mtr, i);
            }

            assertEquals(54, descriptor.getFreePageCount());
            assertEquals(10, descriptor.getUsedPageCount());

            // 释放5个页面
            for (int i = 0; i < 5; i++) {
                descriptor.freePage(mtr, i);
            }

            assertEquals(59, descriptor.getFreePageCount());
            assertEquals(5, descriptor.getUsedPageCount());

            // 验证前5个页面是空闲的
            for (int i = 0; i < 5; i++) {
                assertTrue(descriptor.isPageFree(i));
            }

            // 验证后5个页面是已分配的
            for (int i = 5; i < 10; i++) {
                assertFalse(descriptor.isPageFree(i));
            }

            mtr.commit();
        }
    }

    // ==================== 查找空闲页测试 ====================

    @Test
    void testFindFreePage_AllFree() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            ExtentDescriptor descriptor = new ExtentDescriptor(page, 150, 0);

            descriptor.initBitmap(mtr);

            // 所有页面都空闲，应该返回第一个页面（偏移0）
            assertEquals(0, descriptor.findFreePage());
        }
    }

    @Test
    void testFindFreePage_SomeFree() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            ExtentDescriptor descriptor = new ExtentDescriptor(page, 150, 0);

            descriptor.initBitmap(mtr);

            // 分配前10个页面
            for (int i = 0; i < 10; i++) {
                descriptor.allocatePage(mtr, i);
            }

            // 应该返回第一个空闲页面（偏移10）
            assertEquals(10, descriptor.findFreePage());
        }
    }

    @Test
    void testFindFreePage_AllAllocated() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            ExtentDescriptor descriptor = new ExtentDescriptor(page, 150, 0);

            descriptor.initBitmap(mtr);

            // 分配所有64个页面
            for (int i = 0; i < EXTENT_SIZE; i++) {
                descriptor.allocatePage(mtr, i);
            }

            // 没有空闲页面，应该返回-1
            assertEquals(-1, descriptor.findFreePage());
        }
    }

    @Test
    void testFindFreePage_WithGaps() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            ExtentDescriptor descriptor = new ExtentDescriptor(page, 150, 0);

            descriptor.initBitmap(mtr);

            // 分配偶数页面
            for (int i = 0; i < EXTENT_SIZE; i += 2) {
                descriptor.allocatePage(mtr, i);
            }

            // 应该返回第一个空闲页面（偏移1）
            assertEquals(1, descriptor.findFreePage());
        }
    }

    // ==================== 状态查询测试 ====================

    @Test
    void testIsEmpty() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            ExtentDescriptor descriptor = new ExtentDescriptor(page, 150, 0);

            descriptor.initBitmap(mtr);

            // 初始状态：空
            assertTrue(descriptor.isEmpty());
            assertFalse(descriptor.isFull());

            // 分配一个页面后：非空
            descriptor.allocatePage(mtr, 0);
            assertFalse(descriptor.isEmpty());
            assertFalse(descriptor.isFull());
        }
    }

    @Test
    void testIsFull() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            ExtentDescriptor descriptor = new ExtentDescriptor(page, 150, 0);

            descriptor.initBitmap(mtr);

            // 分配所有页面
            for (int i = 0; i < EXTENT_SIZE; i++) {
                assertFalse(descriptor.isFull(), "Should not be full before allocating page " + i);
                descriptor.allocatePage(mtr, i);
            }

            // 现在应该是满的
            assertTrue(descriptor.isFull());
            assertFalse(descriptor.isEmpty());
            assertEquals(EXTENT_SIZE, descriptor.getUsedPageCount());
            assertEquals(0, descriptor.getFreePageCount());

            mtr.commit();
        }
    }

    @Test
    void testGetUsedAndFreePageCount() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            ExtentDescriptor descriptor = new ExtentDescriptor(page, 150, 0);

            descriptor.initBitmap(mtr);

            // 逐步分配，验证计数
            for (int allocated = 0; allocated <= 64; allocated++) {
                assertEquals(allocated, descriptor.getUsedPageCount());
                assertEquals(64 - allocated, descriptor.getFreePageCount());

                if (allocated < 64) {
                    descriptor.allocatePage(mtr, allocated);
                }
            }

            mtr.commit();
        }
    }

    // ==================== 链表节点测试 ====================

    @Test
    void testGetListNode() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            ExtentDescriptor descriptor = new ExtentDescriptor(page, 150, 0);

            // 获取链表节点
            FlstNode listNode = descriptor.getListNode();
            assertNotNull(listNode);

            // 验证链表节点的位置（应该在XDES_FLST_NODE偏移处）
            assertEquals(page, listNode.getPage());
            assertEquals(150 + XDES_FLST_NODE, listNode.getOffset());
        }
    }

    // ==================== 边界条件测试 ====================

    @Test
    void testPageOffset_BoundaryValues() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            ExtentDescriptor descriptor = new ExtentDescriptor(page, 150, 0);

            descriptor.initBitmap(mtr);

            // 测试第一个页面（偏移0）
            assertTrue(descriptor.isPageFree(0));
            descriptor.allocatePage(mtr, 0);
            assertFalse(descriptor.isPageFree(0));

            // 测试最后一个页面（偏移63）
            assertTrue(descriptor.isPageFree(63));
            descriptor.allocatePage(mtr, 63);
            assertFalse(descriptor.isPageFree(63));

            // 测试无效的偏移
            assertThrows(IllegalArgumentException.class, () -> {
                descriptor.isPageFree(-1);
            });

            assertThrows(IllegalArgumentException.class, () -> {
                descriptor.isPageFree(64);
            });

            mtr.commit();
        }
    }

    // ==================== toString 测试 ====================

    @Test
    void testToString() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(SPACE_ID);
            ExtentDescriptor descriptor = new ExtentDescriptor(page, 150, 5);

            descriptor.initBitmap(mtr);
            descriptor.setState(mtr, ExtentState.FSEG);
            descriptor.setSegmentId(mtr, 100);

            // 分配10个页面
            for (int i = 0; i < 10; i++) {
                descriptor.allocatePage(mtr, i);
            }

            String str = descriptor.toString();
            assertNotNull(str);
            assertTrue(str.contains("5"));      // extentNo
            assertTrue(str.contains("320"));    // startPage = 5 * 64
            assertTrue(str.contains("FSEG"));   // state
            assertTrue(str.contains("100"));    // segmentId
            assertTrue(str.contains("10"));     // used pages

            mtr.commit();
        }
    }
}
