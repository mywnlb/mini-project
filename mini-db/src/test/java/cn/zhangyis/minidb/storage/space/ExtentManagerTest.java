package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.storage.BaseStorageTest;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.page.PageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ExtentManager 单元测试
 *
 * <p>测试 ExtentManager 的所有核心功能：</p>
 * <ul>
 *   <li>跨 XDES Page 的 Extent 定位（page 0 和 page 16384）</li>
 *   <li>Extent 内页面分配和释放</li>
 *   <li>Extent 初始化和状态管理</li>
 *   <li>统计信息查询</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class ExtentManagerTest extends BaseStorageTest {

    private ExtentManager extentManager;

    @BeforeEach
    void setUpExtentManager() {
        extentManager = new ExtentManagerImpl(bufferPool);
    }

    // ==================== getExtentDescriptor 测试 ====================

    @Test
    void getExtentDescriptor_shouldReturnDescriptorFromPage0_forExtent0() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 初始化 page 0 (FSP_HDR)
            Page p0 = mtr.newPage(SPACE_ID);
            assertEquals(0, p0.getPageNo());
            FspHeaderPage fsp = new FspHeaderPage(p0);
            fsp.initialize(mtr, SPACE_ID);

            // 获取 Extent 0 的描述符（应位于 page 0）
            ExtentDescriptor ext0 = extentManager.getExtentDescriptor(mtr, SPACE_ID, 0);

            assertNotNull(ext0);
            assertEquals(0, ext0.getExtentNo());
            assertEquals(0, ext0.getStartPageNo());
        }
    }

    @Test
    void getExtentDescriptor_shouldReturnDescriptorFromPage0_forExtent255() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 初始化 page 0
            Page p0 = mtr.newPage(SPACE_ID);
            FspHeaderPage fsp = new FspHeaderPage(p0);
            fsp.initialize(mtr, SPACE_ID);

            // 获取 Extent 255（page 0 中的最后一个 Extent）
            ExtentDescriptor ext255 = extentManager.getExtentDescriptor(mtr, SPACE_ID, 255);

            assertNotNull(ext255);
            assertEquals(255, ext255.getExtentNo());
            assertEquals(255 * 64, ext255.getStartPageNo());
        }
    }

    @Test
    void getExtentDescriptor_shouldReturnDescriptorFromPage16384_forExtent256() throws Exception {
        // 初始化 page 0
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page p0 = mtr.newPage(SPACE_ID);
            FspHeaderPage fsp = new FspHeaderPage(p0);
            fsp.initialize(mtr, SPACE_ID);
            mtr.commit();
        }

        // 扩展表空间到 page 16384（第二个 XDES Page）
        // 分批创建页面以避免 Buffer Pool 耗尽（每批50页，远小于64页的限制）
        final int BATCH_SIZE = 50;
        for (int batchStart = 1; batchStart < 16384; batchStart += BATCH_SIZE) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                int batchEnd = Math.min(batchStart + BATCH_SIZE, 16384);
                for (int i = batchStart; i < batchEnd; i++) {
                    mtr.newPage(SPACE_ID);
                }
                mtr.commit();
            }
        }

        // 初始化 page 16384 为 XDES Page 并测试 Extent 256
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page p16384 = mtr.newPage(SPACE_ID);
            assertEquals(16384, p16384.getPageNo());

            // 设置页面类型为 ALLOCATED，以便 XdesPage 构造函数接受
            p16384.setPageType(PageType.FIL_PAGE_TYPE_ALLOCATED);
            mtr.markDirty(p16384);

            XdesPage xdesPage = new XdesPage(p16384);
            xdesPage.initialize(mtr);

            // 获取 Extent 256（应位于 page 16384）
            ExtentDescriptor ext256 = extentManager.getExtentDescriptor(mtr, SPACE_ID, 256);

            assertNotNull(ext256);
            assertEquals(256, ext256.getExtentNo());
            assertEquals(256 * 64, ext256.getStartPageNo());
        }
    }

    @Test
    void getExtentDescriptor_shouldThrowException_forNegativeExtentNo() {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            assertThrows(IllegalArgumentException.class, () -> {
                extentManager.getExtentDescriptor(mtr, SPACE_ID, -1);
            });
        }
    }

    // ==================== allocatePageInExtent 测试 ====================

    @Test
    void allocatePageInExtent_shouldReturnPageOffset_whenExtentHasFreePages() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 初始化 page 0 和 Extent 0
            Page p0 = mtr.newPage(SPACE_ID);
            FspHeaderPage fsp = new FspHeaderPage(p0);
            fsp.initialize(mtr, SPACE_ID);

            ExtentDescriptor ext0 = extentManager.getExtentDescriptor(mtr, SPACE_ID, 0);

            // 分配第一个页面
            int offset = extentManager.allocatePageInExtent(mtr, ext0);

            assertEquals(0, offset, "First allocated page should be at offset 0");
            assertEquals(1, extentManager.getUsedPageCount(ext0));
            assertEquals(63, extentManager.getFreePageCount(ext0));
        }
    }

    @Test
    void allocatePageInExtent_shouldAllocateSequentially() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 初始化
            Page p0 = mtr.newPage(SPACE_ID);
            FspHeaderPage fsp = new FspHeaderPage(p0);
            fsp.initialize(mtr, SPACE_ID);

            ExtentDescriptor ext0 = extentManager.getExtentDescriptor(mtr, SPACE_ID, 0);

            // 分配3个页面
            assertEquals(0, extentManager.allocatePageInExtent(mtr, ext0));
            assertEquals(1, extentManager.allocatePageInExtent(mtr, ext0));
            assertEquals(2, extentManager.allocatePageInExtent(mtr, ext0));

            assertEquals(3, extentManager.getUsedPageCount(ext0));
        }
    }

    @Test
    void allocatePageInExtent_shouldReturnMinusOne_whenExtentIsFull() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 初始化
            Page p0 = mtr.newPage(SPACE_ID);
            FspHeaderPage fsp = new FspHeaderPage(p0);
            fsp.initialize(mtr, SPACE_ID);

            ExtentDescriptor ext0 = extentManager.getExtentDescriptor(mtr, SPACE_ID, 0);

            // 分配所有64个页面
            for (int i = 0; i < 64; i++) {
                int offset = extentManager.allocatePageInExtent(mtr, ext0);
                assertEquals(i, offset);
            }

            assertTrue(extentManager.isFull(ext0));

            // 尝试再次分配，应返回 -1
            int result = extentManager.allocatePageInExtent(mtr, ext0);
            assertEquals(-1, result, "Allocation should fail when extent is full");
        }
    }

    @Test
    void allocatePageInExtent_shouldThrowException_forNullExtent() {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            assertThrows(IllegalArgumentException.class, () -> {
                extentManager.allocatePageInExtent(mtr, null);
            });
        }
    }

    // ==================== freePageInExtent 测试 ====================

    @Test
    void freePageInExtent_shouldMarkPageAsFree() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 初始化
            Page p0 = mtr.newPage(SPACE_ID);
            FspHeaderPage fsp = new FspHeaderPage(p0);
            fsp.initialize(mtr, SPACE_ID);

            ExtentDescriptor ext0 = extentManager.getExtentDescriptor(mtr, SPACE_ID, 0);

            // 分配3个页面
            extentManager.allocatePageInExtent(mtr, ext0);
            extentManager.allocatePageInExtent(mtr, ext0);
            extentManager.allocatePageInExtent(mtr, ext0);
            assertEquals(3, extentManager.getUsedPageCount(ext0));

            // 释放第二个页面（offset=1）
            extentManager.freePageInExtent(mtr, ext0, 1);

            assertEquals(2, extentManager.getUsedPageCount(ext0));
            assertEquals(62, extentManager.getFreePageCount(ext0));
            assertTrue(ext0.isPageFree(1));
        }
    }

    @Test
    void freePageInExtent_shouldAllowReallocation() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 初始化
            Page p0 = mtr.newPage(SPACE_ID);
            FspHeaderPage fsp = new FspHeaderPage(p0);
            fsp.initialize(mtr, SPACE_ID);

            ExtentDescriptor ext0 = extentManager.getExtentDescriptor(mtr, SPACE_ID, 0);

            // 分配页面 0
            extentManager.allocatePageInExtent(mtr, ext0);
            assertEquals(1, extentManager.getUsedPageCount(ext0));

            // 释放页面 0
            extentManager.freePageInExtent(mtr, ext0, 0);
            assertEquals(0, extentManager.getUsedPageCount(ext0));

            // 重新分配，应该再次得到页面 0
            int offset = extentManager.allocatePageInExtent(mtr, ext0);
            assertEquals(0, offset);
        }
    }

    @Test
    void freePageInExtent_shouldThrowException_forInvalidOffset() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page p0 = mtr.newPage(SPACE_ID);
            FspHeaderPage fsp = new FspHeaderPage(p0);
            fsp.initialize(mtr, SPACE_ID);

            ExtentDescriptor ext0 = extentManager.getExtentDescriptor(mtr, SPACE_ID, 0);

            // 测试越界偏移
            assertThrows(IllegalArgumentException.class, () -> {
                extentManager.freePageInExtent(mtr, ext0, -1);
            });

            assertThrows(IllegalArgumentException.class, () -> {
                extentManager.freePageInExtent(mtr, ext0, 64);
            });
        }
    }

    @Test
    void freePageInExtent_shouldThrowException_forNullExtent() {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            assertThrows(IllegalArgumentException.class, () -> {
                extentManager.freePageInExtent(mtr, null, 0);
            });
        }
    }

    // ==================== initializeExtent 测试 ====================

    @Test
    void initializeExtent_shouldResetExtentToFreeState() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 初始化 page 0
            Page p0 = mtr.newPage(SPACE_ID);
            FspHeaderPage fsp = new FspHeaderPage(p0);
            fsp.initialize(mtr, SPACE_ID);

            ExtentDescriptor ext1 = extentManager.getExtentDescriptor(mtr, SPACE_ID, 1);

            // 修改 Extent 状态（模拟使用过的 Extent）
            ext1.setState(mtr, ExtentState.FSEG);
            ext1.setSegmentId(mtr, 123);
            ext1.allocatePage(mtr, 0);

            // 重新初始化
            extentManager.initializeExtent(mtr, ext1);

            // 验证状态已重置
            assertEquals(ExtentState.FREE, ext1.getState());
            assertEquals(0L, ext1.getSegmentId());
            assertTrue(extentManager.isEmpty(ext1));
            assertTrue(ext1.getListNode().isIsolated());
        }
    }

    @Test
    void initializeExtent_shouldThrowException_forNullExtent() {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            assertThrows(IllegalArgumentException.class, () -> {
                extentManager.initializeExtent(mtr, null);
            });
        }
    }

    // ==================== setExtentState 测试 ====================

    @Test
    void setExtentState_shouldUpdateState() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 初始化
            Page p0 = mtr.newPage(SPACE_ID);
            FspHeaderPage fsp = new FspHeaderPage(p0);
            fsp.initialize(mtr, SPACE_ID);

            ExtentDescriptor ext0 = extentManager.getExtentDescriptor(mtr, SPACE_ID, 0);

            // 初始状态应为 FREE
            assertEquals(ExtentState.FREE, ext0.getState());

            // 修改状态为 FREE_FRAG
            extentManager.setExtentState(mtr, ext0, ExtentState.FREE_FRAG);
            assertEquals(ExtentState.FREE_FRAG, ext0.getState());

            // 修改状态为 FULL_FRAG
            extentManager.setExtentState(mtr, ext0, ExtentState.FULL_FRAG);
            assertEquals(ExtentState.FULL_FRAG, ext0.getState());
        }
    }

    @Test
    void setExtentState_shouldThrowException_forNullArguments() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page p0 = mtr.newPage(SPACE_ID);
            FspHeaderPage fsp = new FspHeaderPage(p0);
            fsp.initialize(mtr, SPACE_ID);

            ExtentDescriptor ext0 = extentManager.getExtentDescriptor(mtr, SPACE_ID, 0);

            // 测试 null extent
            assertThrows(IllegalArgumentException.class, () -> {
                extentManager.setExtentState(mtr, null, ExtentState.FREE);
            });

            // 测试 null state
            assertThrows(IllegalArgumentException.class, () -> {
                extentManager.setExtentState(mtr, ext0, null);
            });
        }
    }

    // ==================== 统计方法测试 ====================

    @Test
    void getUsedPageCount_shouldReturnCorrectCount() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 初始化
            Page p0 = mtr.newPage(SPACE_ID);
            FspHeaderPage fsp = new FspHeaderPage(p0);
            fsp.initialize(mtr, SPACE_ID);

            ExtentDescriptor ext0 = extentManager.getExtentDescriptor(mtr, SPACE_ID, 0);

            // 初始为0
            assertEquals(0, extentManager.getUsedPageCount(ext0));

            // 分配5个页面
            for (int i = 0; i < 5; i++) {
                extentManager.allocatePageInExtent(mtr, ext0);
            }

            assertEquals(5, extentManager.getUsedPageCount(ext0));
        }
    }

    @Test
    void getFreePageCount_shouldReturnCorrectCount() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 初始化
            Page p0 = mtr.newPage(SPACE_ID);
            FspHeaderPage fsp = new FspHeaderPage(p0);
            fsp.initialize(mtr, SPACE_ID);

            ExtentDescriptor ext0 = extentManager.getExtentDescriptor(mtr, SPACE_ID, 0);

            // 初始为64
            assertEquals(64, extentManager.getFreePageCount(ext0));

            // 分配10个页面
            for (int i = 0; i < 10; i++) {
                extentManager.allocatePageInExtent(mtr, ext0);
            }

            assertEquals(54, extentManager.getFreePageCount(ext0));
        }
    }

    @Test
    void isEmpty_shouldReturnTrueForNewExtent() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 初始化
            Page p0 = mtr.newPage(SPACE_ID);
            FspHeaderPage fsp = new FspHeaderPage(p0);
            fsp.initialize(mtr, SPACE_ID);

            ExtentDescriptor ext0 = extentManager.getExtentDescriptor(mtr, SPACE_ID, 0);

            assertTrue(extentManager.isEmpty(ext0));

            // 分配一个页面后应为 false
            extentManager.allocatePageInExtent(mtr, ext0);
            assertFalse(extentManager.isEmpty(ext0));
        }
    }

    @Test
    void isFull_shouldReturnTrueWhenAllPagesAllocated() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 初始化
            Page p0 = mtr.newPage(SPACE_ID);
            FspHeaderPage fsp = new FspHeaderPage(p0);
            fsp.initialize(mtr, SPACE_ID);

            ExtentDescriptor ext0 = extentManager.getExtentDescriptor(mtr, SPACE_ID, 0);

            assertFalse(extentManager.isFull(ext0));

            // 分配所有64个页面
            for (int i = 0; i < 64; i++) {
                extentManager.allocatePageInExtent(mtr, ext0);
            }

            assertTrue(extentManager.isFull(ext0));
        }
    }

    @Test
    void statisticMethods_shouldThrowException_forNullExtent() {
        assertThrows(IllegalArgumentException.class, () -> {
            extentManager.getUsedPageCount(null);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            extentManager.getFreePageCount(null);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            extentManager.isEmpty(null);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            extentManager.isFull(null);
        });
    }

    // ==================== 集成测试 ====================

    @Test
    void fullWorkflow_shouldAllocateFreeAndReallocate() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 初始化表空间
            Page p0 = mtr.newPage(SPACE_ID);
            FspHeaderPage fsp = new FspHeaderPage(p0);
            fsp.initialize(mtr, SPACE_ID);

            // 获取 Extent 1
            ExtentDescriptor ext1 = extentManager.getExtentDescriptor(mtr, SPACE_ID, 1);

            // 1. 分配10个页面
            for (int i = 0; i < 10; i++) {
                int offset = extentManager.allocatePageInExtent(mtr, ext1);
                assertEquals(i, offset);
            }
            assertEquals(10, extentManager.getUsedPageCount(ext1));

            // 2. 释放页面5和页面7
            extentManager.freePageInExtent(mtr, ext1, 5);
            extentManager.freePageInExtent(mtr, ext1, 7);
            assertEquals(8, extentManager.getUsedPageCount(ext1));

            // 3. 再次分配，应该得到页面5（第一个空闲页）
            int offset = extentManager.allocatePageInExtent(mtr, ext1);
            assertEquals(5, offset);
            assertEquals(9, extentManager.getUsedPageCount(ext1));

            // 4. 修改状态
            extentManager.setExtentState(mtr, ext1, ExtentState.FSEG);
            assertEquals(ExtentState.FSEG, ext1.getState());

            // 5. 重新初始化
            extentManager.initializeExtent(mtr, ext1);
            assertTrue(extentManager.isEmpty(ext1));
            assertEquals(ExtentState.FREE, ext1.getState());
        }
    }
}
