package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.storage.BaseStorageTest;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SegmentManager 单元测试
 *
 * <p>测试 SegmentManager 的核心功能：</p>
 * <ul>
 *   <li>Segment 创建和删除</li>
 *   <li>Extent 分配</li>
 *   <li>Segment 查找</li>
 *   <li>统计信息</li>
 * </ul>
 *
 * <p><b>注意</b>：此测试类使用手动初始化表空间的方式，
 * 完整的集成测试（包含碎片页分配）请参见 {@link SpaceIntegrationTest}。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class SegmentManagerTest extends BaseStorageTest {

    private ExtentManager extentManager;
    private SpaceManager spaceManager;
    private SegmentManager segmentManager;

    @BeforeEach
    void setUpManagers() {
        extentManager = new ExtentManagerImpl(bufferPool);
        spaceManager = new SpaceManagerImpl(bufferPool, extentManager);
        segmentManager = new SegmentManagerImpl(bufferPool, extentManager, spaceManager);
    }

    // ==================== 辅助方法 ====================

    /**
     * 初始化表空间（创建 page 0 和基础结构）
     */
    private FspHeaderPage initializeTablespace(MiniTransaction mtr) throws Exception {
        Page p0 = mtr.newPage(SPACE_ID);
        assertEquals(0, p0.getPageNo());
        FspHeaderPage fsp = new FspHeaderPage(p0);
        fsp.initialize(mtr, SPACE_ID);
        return fsp;
    }

    /**
     * 手动添加 Extent 到表空间 FREE 链表（模拟空间扩展）
     */
    private void addExtentsToFreeList(MiniTransaction mtr, FspHeaderPage fsp, int count)
            throws Exception {
        for (int i = 1; i <= count; i++) {
            ExtentDescriptor ext = fsp.getXdesEntry(i);
            ext.initialize(mtr);
            fsp.getFreeList().addLast(mtr, fsp, ext.getListNode().getOffset());
        }
    }

    // ==================== createSegment 测试 ====================

    @Test
    void createSegment_shouldAllocateSegmentId() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = initializeTablespace(mtr);

            // 创建第一个 Segment
            long segId1 = segmentManager.createSegment(mtr, SPACE_ID);

            assertTrue(segId1 > 0, "Segment ID should be positive");
            assertEquals(1, segId1, "First segment ID should be 1");

            // 创建第二个 Segment
            long segId2 = segmentManager.createSegment(mtr, SPACE_ID);

            assertEquals(2, segId2, "Second segment ID should be 2");
        }
    }

    @Test
    void createSegment_shouldInitializeInodeEntry() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = initializeTablespace(mtr);

            // 创建 Segment
            long segId = segmentManager.createSegment(mtr, SPACE_ID);

            // 验证 INODE Entry 已初始化
            SegmentDescriptor segment = segmentManager.getSegmentDescriptor(mtr, SPACE_ID, segId);

            assertNotNull(segment);
            assertEquals(segId, segment.getSegmentId());
            assertEquals(INODE_MAGIC_NUMBER, segment.getMagicNumber());
            assertEquals(0, segment.getFragUsedCount());
            assertTrue(segment.getFreeList().isEmpty());
            assertTrue(segment.getNotFullList().isEmpty());
            assertTrue(segment.getFullList().isEmpty());
        }
    }

    @Test
    void createSegment_shouldCreateInodePage_whenFirstSegment() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = initializeTablespace(mtr);

            // 创建第一个 Segment（应自动创建 INODE Page）
            long segId = segmentManager.createSegment(mtr, SPACE_ID);

            // 验证 INODES_FREE 链表不为空
            assertFalse(fsp.getInodesFreeList().isEmpty(),
                    "INODES_FREE list should have the new INODE page");
        }
    }

    @Test
    void createSegment_shouldMoveInodePageToFull_when85SegmentsCreated() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = initializeTablespace(mtr);

            // 创建85个 Segment（填满一个 INODE Page）
            for (int i = 0; i < INODES_PER_PAGE; i++) {
                long segId = segmentManager.createSegment(mtr, SPACE_ID);
                assertEquals(i + 1, segId);
            }

            // 验证 INODE Page 已移到 FULL 链表
            assertTrue(fsp.getInodesFreeList().isEmpty(),
                    "INODES_FREE should be empty after 85 segments");
            assertFalse(fsp.getInodesFullList().isEmpty(),
                    "INODES_FULL should have the filled INODE page");
        }
    }

    // ==================== getSegmentDescriptor 测试 ====================

    @Test
    void getSegmentDescriptor_shouldReturnDescriptor_forExistingSegment() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = initializeTablespace(mtr);

            long segId = segmentManager.createSegment(mtr, SPACE_ID);

            SegmentDescriptor segment = segmentManager.getSegmentDescriptor(mtr, SPACE_ID, segId);

            assertNotNull(segment);
            assertEquals(segId, segment.getSegmentId());
        }
    }

    @Test
    void getSegmentDescriptor_shouldReturnNull_forNonExistentSegment() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = initializeTablespace(mtr);

            SegmentDescriptor segment = segmentManager.getSegmentDescriptor(mtr, SPACE_ID, 999);

            assertNull(segment, "Non-existent segment should return null");
        }
    }

    @Test
    void getSegmentDescriptor_shouldFindInFreeList() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = initializeTablespace(mtr);

            long segId = segmentManager.createSegment(mtr, SPACE_ID);

            // INODE Page 应在 FREE 链表（未满）
            assertFalse(fsp.getInodesFreeList().isEmpty());

            SegmentDescriptor segment = segmentManager.getSegmentDescriptor(mtr, SPACE_ID, segId);

            assertNotNull(segment);
        }
    }

    // ==================== allocateExtentForSegment 测试 ====================

    @Test
    void allocateExtentForSegment_shouldAllocateFromFspFreeList() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = initializeTablespace(mtr);

            // 添加2个 Extent 到 FREE 链表
            addExtentsToFreeList(mtr, fsp, 2);
            assertEquals(2, fsp.getFreeList().getLength());

            // 创建 Segment
            long segId = segmentManager.createSegment(mtr, SPACE_ID);

            // 分配 Extent
            ExtentDescriptor ext = segmentManager.allocateExtentForSegment(mtr, SPACE_ID, segId);

            assertNotNull(ext);
            assertEquals(segId, ext.getSegmentId());
            assertEquals(ExtentState.FSEG_FREE, ext.getState());
            assertEquals(1, fsp.getFreeList().getLength(), "FSP FREE list should decrease");
        }
    }

    @Test
    void allocateExtentForSegment_shouldAddToSegmentFreeList() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = initializeTablespace(mtr);

            // 添加 Extent 到 FREE 链表
            addExtentsToFreeList(mtr, fsp, 3);

            // 创建 Segment
            long segId = segmentManager.createSegment(mtr, SPACE_ID);
            SegmentDescriptor segment = segmentManager.getSegmentDescriptor(mtr, SPACE_ID, segId);

            // 分配 Extent
            ExtentDescriptor ext = segmentManager.allocateExtentForSegment(mtr, SPACE_ID, segId);

            assertNotNull(ext);
            assertEquals(1, segment.getFreeList().getLength(),
                    "Extent should be in segment FREE list");
        }
    }

    @Test
    void allocateExtentForSegment_shouldReturnNull_whenFspFreeListEmpty() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = initializeTablespace(mtr);

            // 不添加任何 Extent（FREE 链表为空）

            long segId = segmentManager.createSegment(mtr, SPACE_ID);

            ExtentDescriptor ext = segmentManager.allocateExtentForSegment(mtr, SPACE_ID, segId);

            assertNull(ext, "Should return null when FSP FREE list is empty");
        }
    }

    // ==================== allocatePageForSegment 测试 ====================

    @Test
    void allocatePageForSegment_phase3_shouldAllocateFromFreeExtent() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = initializeTablespace(mtr);

            // 添加 Extent 到 FREE 链表
            addExtentsToFreeList(mtr, fsp, 2);

            // 创建 Segment
            long segId = segmentManager.createSegment(mtr, SPACE_ID);
            SegmentDescriptor segment = segmentManager.getSegmentDescriptor(mtr, SPACE_ID, segId);

            // 阶段3：从 Free Extent 分配页面
            // （由于 SpaceManager 未实现，碎片页和 Partial Extent 会被跳过）
            PageId page = segmentManager.allocatePageForSegment(mtr, SPACE_ID, segId);

            assertNotNull(page);
            assertEquals(SPACE_ID, page.getSpaceId());

            // 验证 Extent 已移到 NOT_FULL 链表
            assertEquals(0, segment.getFreeList().getLength());
            assertEquals(1, segment.getNotFullList().getLength());
            assertEquals(0, segment.getFullList().getLength());
        }
    }

    @Test
    void allocatePageForSegment_shouldMoveExtentToFull_when64PagesAllocated() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = initializeTablespace(mtr);

            // 添加2个 Extent
            addExtentsToFreeList(mtr, fsp, 2);

            long segId = segmentManager.createSegment(mtr, SPACE_ID);
            SegmentDescriptor segment = segmentManager.getSegmentDescriptor(mtr, SPACE_ID, segId);

            // 分配64个页面（填满一个 Extent）
            for (int i = 0; i < 64; i++) {
                PageId page = segmentManager.allocatePageForSegment(mtr, SPACE_ID, segId);
                assertNotNull(page);
            }

            // 验证 Extent 已移到 FULL 链表
            assertEquals(0, segment.getNotFullList().getLength());
            assertEquals(1, segment.getFullList().getLength());
        }
    }

    // ==================== dropSegment 测试 ====================

    @Test
    void dropSegment_shouldClearInodeEntry() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = initializeTablespace(mtr);

            // 创建 Segment
            long segId = segmentManager.createSegment(mtr, SPACE_ID);

            // 删除 Segment
            segmentManager.dropSegment(mtr, SPACE_ID, segId);

            // 验证 INODE Entry 已清空
            SegmentDescriptor segment = segmentManager.getSegmentDescriptor(mtr, SPACE_ID, segId);
            assertNull(segment, "Deleted segment should not be found");
        }
    }

    @Test
    void dropSegment_shouldReleaseExtents() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = initializeTablespace(mtr);

            // 添加 Extent
            addExtentsToFreeList(mtr, fsp, 3);

            long segId = segmentManager.createSegment(mtr, SPACE_ID);

            // 分配2个 Extent
            segmentManager.allocateExtentForSegment(mtr, SPACE_ID, segId);
            segmentManager.allocateExtentForSegment(mtr, SPACE_ID, segId);

            int fspFreeCountBefore = fsp.getFreeList().getLength();

            // 删除 Segment
            segmentManager.dropSegment(mtr, SPACE_ID, segId);

            // 验证 Extent 已归还到 FSP FREE 链表
            int fspFreeCountAfter = fsp.getFreeList().getLength();
            assertEquals(fspFreeCountBefore + 2, fspFreeCountAfter,
                    "Released extents should be returned to FSP FREE list");
        }
    }

    @Test
    void dropSegment_shouldThrowException_forNonExistentSegment() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = initializeTablespace(mtr);

            assertThrows(Exception.class, () -> {
                segmentManager.dropSegment(mtr, SPACE_ID, 999);
            });
        }
    }

    // ==================== getStatistics 测试 ====================

    @Test
    void getStatistics_shouldReturnCorrectStats() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = initializeTablespace(mtr);

            // 添加 Extent
            addExtentsToFreeList(mtr, fsp, 5);

            long segId = segmentManager.createSegment(mtr, SPACE_ID);

            // 分配3个 Extent
            segmentManager.allocateExtentForSegment(mtr, SPACE_ID, segId);
            segmentManager.allocateExtentForSegment(mtr, SPACE_ID, segId);
            segmentManager.allocateExtentForSegment(mtr, SPACE_ID, segId);

            // 获取统计信息
            SegmentManager.SegmentStatistics stats =
                    segmentManager.getStatistics(mtr, SPACE_ID, segId);

            assertNotNull(stats);
            assertEquals(segId, stats.getSegmentId());
            assertEquals(0, stats.getFragPagesUsed()); // 碎片页未使用
            assertEquals(3, stats.getFreeExtentCount());
            assertEquals(0, stats.getNotFullExtentCount());
            assertEquals(0, stats.getFullExtentCount());
            assertEquals(3, stats.getTotalExtentCount());
        }
    }

    @Test
    void getStatistics_shouldThrowException_forNonExistentSegment() {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            FspHeaderPage fsp = initializeTablespace(mtr);

            assertThrows(Exception.class, () -> {
                segmentManager.getStatistics(mtr, SPACE_ID, 999);
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== 集成测试 ====================

    @Test
    void fullWorkflow_createAllocateAndDrop() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 1. 初始化表空间
            FspHeaderPage fsp = initializeTablespace(mtr);
            addExtentsToFreeList(mtr, fsp, 10);

            // 2. 创建 Segment
            long segId = segmentManager.createSegment(mtr, SPACE_ID);
            assertTrue(segId > 0);

            // 3. 分配 Extent
            ExtentDescriptor ext1 = segmentManager.allocateExtentForSegment(mtr, SPACE_ID, segId);
            assertNotNull(ext1);

            // 4. 分配页面
            PageId page1 = segmentManager.allocatePageForSegment(mtr, SPACE_ID, segId);
            assertNotNull(page1);

            // 5. 检查统计
            SegmentManager.SegmentStatistics stats =
                    segmentManager.getStatistics(mtr, SPACE_ID, segId);
            assertEquals(1, stats.getNotFullExtentCount());

            // 6. 删除 Segment
            segmentManager.dropSegment(mtr, SPACE_ID, segId);

            // 7. 验证已删除
            SegmentDescriptor segment = segmentManager.getSegmentDescriptor(mtr, SPACE_ID, segId);
            assertNull(segment);
        }
    }
}
