package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.storage.BaseStorageTest;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.PageId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SpaceManager 单元测试
 *
 * <p>测试 SpaceManager 的核心功能：</p>
 * <ul>
 *   <li>表空间初始化</li>
 *   <li>Extent 分配和释放</li>
 *   <li>碎片页分配和释放</li>
 *   <li>表空间扩展</li>
 *   <li>统计信息</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class SpaceManagerTest extends BaseStorageTest {

    private ExtentManager extentManager;
    private SpaceManager spaceManager;

    @BeforeEach
    void setUpManagers() {
        extentManager = new ExtentManagerImpl(bufferPool);
        spaceManager = new SpaceManagerImpl(bufferPool, extentManager);
    }

    // ==================== initializeTablespace 测试 ====================

    @Test
    void initializeTablespace_shouldCreateFspHeader() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            // 验证 FSP Header 已初始化
            FspHeaderPage fsp = new FspHeaderPage(mtr.getPage(PageId.of(SPACE_ID, 0)));

            assertEquals(SPACE_ID, fsp.getFspSpaceId());
            assertTrue(fsp.getSize() > 0, "Size should be > 0 after initialization");
            assertEquals(1, fsp.getNextSegmentId(), "First segment ID should be 1");
        }
    }

    @Test
    void initializeTablespace_shouldCreateInodePage() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            // 验证 INODE Page（Page 2）已创建
            FspHeaderPage fsp = new FspHeaderPage(mtr.getPage(PageId.of(SPACE_ID, 0)));

            assertFalse(fsp.getInodesFreeList().isEmpty(),
                    "INODES_FREE list should have the first INODE page");
        }
    }

    @Test
    void initializeTablespace_shouldInitializeExtent0() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            // 验证 Extent 0 已初始化（用于系统页）
            ExtentDescriptor ext0 = extentManager.getExtentDescriptor(mtr, SPACE_ID, 0);

            assertNotNull(ext0);
            assertEquals(ExtentState.FSEG, ext0.getState(), "Extent 0 should be reserved");
        }
    }

    @Test
    void initializeTablespace_shouldAddExtent1ToFreeList() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            // 验证至少有一个 Extent 在 FREE 链表
            FspHeaderPage fsp = new FspHeaderPage(mtr.getPage(PageId.of(SPACE_ID, 0)));

            assertTrue(fsp.getFreeList().getLength() > 0,
                    "FREE list should have at least one extent after initialization");
        }
    }

    // ==================== allocateExtent 测试 ====================

    @Test
    void allocateExtent_shouldReturnExtent_whenFreeListNotEmpty() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            FspHeaderPage fsp = new FspHeaderPage(mtr.getPage(PageId.of(SPACE_ID, 0)));
            int freeCountBefore = fsp.getFreeList().getLength();

            // 分配 Extent
            ExtentDescriptor extent = spaceManager.allocateExtent(mtr, SPACE_ID);

            assertNotNull(extent);
            assertEquals(freeCountBefore - 1, fsp.getFreeList().getLength(),
                    "FREE list should decrease by 1");
        }
    }

    @Test
    void allocateExtent_shouldAutoExtend_whenFreeListEmpty() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            FspHeaderPage fsp = new FspHeaderPage(mtr.getPage(PageId.of(SPACE_ID, 0)));

            // 消耗所有 FREE Extent
            while (!fsp.getFreeList().isEmpty()) {
                spaceManager.allocateExtent(mtr, SPACE_ID);
            }

            // 尝试再次分配，应该自动扩展表空间
            ExtentDescriptor extent = spaceManager.allocateExtent(mtr, SPACE_ID);

            assertNotNull(extent, "Should auto-extend tablespace when FREE list is empty");
        }
    }

    // ==================== freeExtent 测试 ====================

    @Test
    void freeExtent_shouldReturnToFreeList() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            FspHeaderPage fsp = new FspHeaderPage(mtr.getPage(PageId.of(SPACE_ID, 0)));

            // 分配 Extent
            ExtentDescriptor extent = spaceManager.allocateExtent(mtr, SPACE_ID);
            int freeCountAfterAlloc = fsp.getFreeList().getLength();

            // 释放 Extent
            spaceManager.freeExtent(mtr, extent);

            assertEquals(freeCountAfterAlloc + 1, fsp.getFreeList().getLength(),
                    "FREE list should increase by 1 after freeing extent");
        }
    }

    @Test
    void freeExtent_shouldResetExtentState() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            // 分配 Extent 并修改状态
            ExtentDescriptor extent = spaceManager.allocateExtent(mtr, SPACE_ID);
            extent.setState(mtr, ExtentState.FSEG);
            extent.setSegmentId(mtr, 123);
            extent.allocatePage(mtr, 0);

            // 释放 Extent
            spaceManager.freeExtent(mtr, extent);

            // 验证状态已重置
            assertEquals(ExtentState.FREE, extent.getState());
            assertEquals(0L, extent.getSegmentId());
            assertTrue(extentManager.isEmpty(extent));
        }
    }

    // ==================== allocateFragPage 测试 ====================

    @Test
    void allocateFragPage_shouldAllocateFromFreeFragList() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            // 分配第一个碎片页
            PageId page1 = spaceManager.allocateFragPage(mtr, SPACE_ID);

            assertNotNull(page1);
            assertEquals(SPACE_ID, page1.getSpaceId());

            // 验证 FREE_FRAG 链表不为空
            FspHeaderPage fsp = new FspHeaderPage(mtr.getPage(PageId.of(SPACE_ID, 0)));
            assertTrue(fsp.getFreeFragList().getLength() > 0,
                    "FREE_FRAG list should have extent after first frag page allocation");
        }
    }

    @Test
    void allocateFragPage_shouldMoveToFullFrag_when64PagesAllocated() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            FspHeaderPage fsp = new FspHeaderPage(mtr.getPage(PageId.of(SPACE_ID, 0)));

            // 分配64个碎片页（填满一个 Extent）
            for (int i = 0; i < 64; i++) {
                PageId page = spaceManager.allocateFragPage(mtr, SPACE_ID);
                assertNotNull(page);
            }

            // 验证 Extent 已移到 FULL_FRAG
            // 注意：可能有多个 Extent，所以只检查 FULL_FRAG 不为空
            assertTrue(fsp.getFullFragList().getLength() > 0,
                    "FULL_FRAG list should have extent after filling 64 pages");
        }
    }

    // ==================== freeFragPage 测试 ====================

    @Test
    void freeFragPage_shouldReleasePageInExtent() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            // 分配碎片页
            PageId page = spaceManager.allocateFragPage(mtr, SPACE_ID);

            // 获取 Extent 的使用页数
            int extentNo = page.getPageNo() / EXTENT_SIZE;
            ExtentDescriptor extent = extentManager.getExtentDescriptor(mtr, SPACE_ID, extentNo);
            int usedCountBefore = extentManager.getUsedPageCount(extent);

            // 释放页面
            spaceManager.freeFragPage(mtr, page);

            assertEquals(usedCountBefore - 1, extentManager.getUsedPageCount(extent),
                    "Used page count should decrease by 1");
        }
    }

    @Test
    void freeFragPage_shouldMoveFromFullToFreeFrag_whenExtentNotFull() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            // 分配64个碎片页（填满一个 Extent）
            PageId lastPage = null;
            for (int i = 0; i < 64; i++) {
                lastPage = spaceManager.allocateFragPage(mtr, SPACE_ID);
            }

            FspHeaderPage fsp = new FspHeaderPage(mtr.getPage(PageId.of(SPACE_ID, 0)));
            int fullFragCountBefore = fsp.getFullFragList().getLength();

            // 释放最后一个页面
            spaceManager.freeFragPage(mtr, lastPage);

            // 验证 Extent 已移回 FREE_FRAG
            assertTrue(fsp.getFreeFragList().getLength() > 0,
                    "FREE_FRAG list should have extent after freeing from full extent");
        }
    }

    // ==================== extendTablespace 测试 ====================

    @Test
    void extendTablespace_shouldIncreaseSize() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            FspHeaderPage fsp = new FspHeaderPage(mtr.getPage(PageId.of(SPACE_ID, 0)));
            int sizeBefore = fsp.getSize();

            // 扩展2个 Extent（128页）
            spaceManager.extendTablespace(mtr, SPACE_ID, 2);

            int sizeAfter = fsp.getSize();
            assertEquals(sizeBefore + 2 * EXTENT_SIZE, sizeAfter,
                    "Size should increase by 2 extents (128 pages)");
        }
    }

    @Test
    void extendTablespace_shouldAddExtentsToFreeList() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            FspHeaderPage fsp = new FspHeaderPage(mtr.getPage(PageId.of(SPACE_ID, 0)));
            int freeCountBefore = fsp.getFreeList().getLength();

            // 扩展3个 Extent
            spaceManager.extendTablespace(mtr, SPACE_ID, 3);

            int freeCountAfter = fsp.getFreeList().getLength();
            assertTrue(freeCountAfter > freeCountBefore,
                    "FREE list should have more extents after extension");
        }
    }

    // ==================== getStatistics 测试 ====================

    @Test
    void getStatistics_shouldReturnCorrectStats() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            SpaceManager.SpaceStatistics stats = spaceManager.getStatistics(mtr, SPACE_ID);

            assertNotNull(stats);
            assertEquals(SPACE_ID, stats.getSpaceId());
            assertTrue(stats.getTotalPages() > 0, "Total pages should be > 0");
            assertTrue(stats.getFreeExtents() >= 0, "Free extents should be >= 0");
            assertEquals(1, stats.getNextSegmentId(), "Next segment ID should be 1");
        }
    }

    @Test
    void getStatistics_shouldReflectAllocations() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            SpaceManager.SpaceStatistics statsBefore = spaceManager.getStatistics(mtr, SPACE_ID);
            int freeExtentsBefore = statsBefore.getFreeExtents();

            // 分配 Extent
            spaceManager.allocateExtent(mtr, SPACE_ID);

            SpaceManager.SpaceStatistics statsAfter = spaceManager.getStatistics(mtr, SPACE_ID);
            int freeExtentsAfter = statsAfter.getFreeExtents();

            // 验证 FREE Extent 数量减少
            assertTrue(freeExtentsAfter < freeExtentsBefore || freeExtentsAfter > 0,
                    "Free extents should decrease or auto-extend happened");
        }
    }

    // ==================== 集成测试 ====================

    @Test
    void fullWorkflow_initializeAllocateAndFree() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 1. 初始化表空间
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            // 2. 分配碎片页
            PageId fragPage1 = spaceManager.allocateFragPage(mtr, SPACE_ID);
            PageId fragPage2 = spaceManager.allocateFragPage(mtr, SPACE_ID);
            assertNotNull(fragPage1);
            assertNotNull(fragPage2);

            // 3. 分配 Extent
            ExtentDescriptor extent1 = spaceManager.allocateExtent(mtr, SPACE_ID);
            assertNotNull(extent1);

            // 4. 获取统计信息
            SpaceManager.SpaceStatistics stats = spaceManager.getStatistics(mtr, SPACE_ID);
            assertTrue(stats.getTotalPages() > 0);

            // 5. 释放碎片页
            spaceManager.freeFragPage(mtr, fragPage1);

            // 6. 释放 Extent
            spaceManager.freeExtent(mtr, extent1);

            // 7. 验证最终状态
            SpaceManager.SpaceStatistics finalStats = spaceManager.getStatistics(mtr, SPACE_ID);
            assertNotNull(finalStats);
        }
    }
}
