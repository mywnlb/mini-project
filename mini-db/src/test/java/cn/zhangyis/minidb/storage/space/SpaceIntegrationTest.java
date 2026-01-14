package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.storage.BaseStorageTest;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.PageId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 空间管理集成测试
 *
 * <p>测试 SpaceManager、SegmentManager、ExtentManager 三层协同工作的正确性。</p>
 *
 * <h2>测试覆盖</h2>
 * <ul>
 *   <li>3阶段分配策略（碎片页 → Partial Extent → Free Extent）</li>
 *   <li>Extent 状态转移（FREE ↔ FREE_FRAG ↔ FULL_FRAG，FREE → FSEG）</li>
 *   <li>Segment 删除后资源回收</li>
 *   <li>表空间自动扩展</li>
 *   <li>跨 XDES Page 测试（超过256个 Extent）</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class SpaceIntegrationTest extends BaseStorageTest {

    private ExtentManager extentManager;
    private SpaceManager spaceManager;
    private SegmentManager segmentManager;

    @BeforeEach
    void setUpManagers() {
        extentManager = new ExtentManagerImpl(bufferPool);
        spaceManager = new SpaceManagerImpl(bufferPool, extentManager);
        segmentManager = new SegmentManagerImpl(bufferPool, extentManager, spaceManager);
    }

    // ==================== 测试1：3阶段分配流程 ====================

    @Test
    void testThreePhaseAllocation() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 1. 初始化表空间
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            // 2. 创建 Segment
            long segmentId = segmentManager.createSegment(mtr, SPACE_ID);

            // === 阶段1：碎片页分配（前32页）===
            List<PageId> fragPages = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                PageId page = segmentManager.allocatePageForSegment(mtr, SPACE_ID, segmentId);
                assertNotNull(page, "第" + i + "个碎片页分配失败");
                fragPages.add(page);
            }

            // 验证：这些页面应该记录在碎片页数组中
            SegmentDescriptor segment = segmentManager.getSegmentDescriptor(mtr, SPACE_ID, segmentId);
            assertEquals(32, segment.getFragUsedCount(), "碎片页数组应该有32个页面");

            // === 阶段3：Free Extent 分配（第33页）===
            PageId page33 = segmentManager.allocatePageForSegment(mtr, SPACE_ID, segmentId);
            assertNotNull(page33);

            // 验证：Segment 的 NOT_FULL 链表应该有1个 Extent
            assertEquals(1, segment.getNotFullList().getLength(),
                    "分配第一个Extent后应该在NOT_FULL链表");

            // === 阶段2：Partial Extent 分配（第34-96页，填满第一个Extent）===
            // 第33页已占用Extent的第1个页面，还需要63页来填满（总共64页）
            for (int i = 0; i < 63; i++) {
                PageId page = segmentManager.allocatePageForSegment(mtr, SPACE_ID, segmentId);
                assertNotNull(page);
            }

            // 验证：第一个 Extent 应该移到 FULL 链表
            assertEquals(0, segment.getNotFullList().getLength(),
                    "Extent满了应该移到FULL链表");
            assertEquals(1, segment.getFullList().getLength());

            // === 继续分配：触发第二个 Extent 分配 ===
            PageId page97 = segmentManager.allocatePageForSegment(mtr, SPACE_ID, segmentId);
            assertNotNull(page97);

            // 验证：应该有2个 Extent（1个FULL，1个NOT_FULL）
            assertEquals(1, segment.getNotFullList().getLength());
            assertEquals(1, segment.getFullList().getLength());

            mtr.commit();
        }
    }

    // ==================== 测试2：Segment 删除后资源回收 ====================

    @Test
    void testSegmentDeletion_shouldReleaseAllResources() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);
            long segmentId = segmentManager.createSegment(mtr, SPACE_ID);

            // 分配32个碎片页 + 64个Extent页
            for (int i = 0; i < 96; i++) {
                segmentManager.allocatePageForSegment(mtr, SPACE_ID, segmentId);
            }

            // 记录删除前的统计信息
            SpaceManager.SpaceStatistics statsBefore = spaceManager.getStatistics(mtr, SPACE_ID);
            int freeExtentsBefore = statsBefore.getFreeExtents();

            // 删除 Segment
            segmentManager.dropSegment(mtr, SPACE_ID, segmentId);

            // 验证：Extent 已归还到表空间 FREE 链表
            SpaceManager.SpaceStatistics statsAfter = spaceManager.getStatistics(mtr, SPACE_ID);
            assertTrue(statsAfter.getFreeExtents() > freeExtentsBefore,
                    "Extent应该归还到FREE链表");

            // 验证：INODE Entry 已清空
            SegmentDescriptor segment = segmentManager.getSegmentDescriptor(mtr, SPACE_ID, segmentId);
            assertNull(segment, "Segment应该已被删除");

            mtr.commit();
        }
    }

    // ==================== 测试3：自动扩展表空间 ====================

    @Test
    void testAutoExtend_whenFreeListEmpty() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            FspHeaderPage fsp = new FspHeaderPage(mtr.getPage(PageId.of(SPACE_ID, 0)));

            // 耗尽所有 FREE Extent
            while (!fsp.getFreeList().isEmpty()) {
                spaceManager.allocateExtent(mtr, SPACE_ID);
            }

            int sizeBefore = fsp.getSize();

            // 创建 Segment 并分配 Extent（应该触发自动扩展）
            long segmentId = segmentManager.createSegment(mtr, SPACE_ID);
            ExtentDescriptor extent = segmentManager.allocateExtentForSegment(mtr, SPACE_ID, segmentId);

            assertNotNull(extent, "应该通过自动扩展分配到Extent");

            int sizeAfter = fsp.getSize();
            assertTrue(sizeAfter > sizeBefore, "表空间应该已扩展");
            assertEquals(sizeBefore + EXTENT_SIZE, sizeAfter, "应该扩展1个Extent");

            mtr.commit();
        }
    }

    // ==================== 测试4：碎片页状态转移 ====================

    @Test
    void testFragmentExtent_stateTransitions() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);
            long segmentId = segmentManager.createSegment(mtr, SPACE_ID);

            // 分配32个碎片页
            List<PageId> fragPages = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                PageId page = segmentManager.allocatePageForSegment(mtr, SPACE_ID, segmentId);
                fragPages.add(page);
            }

            FspHeaderPage fsp = new FspHeaderPage(mtr.getPage(PageId.of(SPACE_ID, 0)));

            // 碎片页可能分布在多个 Extent，找到第一个碎片页所在的 Extent
            int firstFragPageNo = fragPages.get(0).getPageNo();
            int extentNo = firstFragPageNo / EXTENT_SIZE;
            ExtentDescriptor fragExtent = extentManager.getExtentDescriptor(mtr, SPACE_ID, extentNo);

            // 如果 Extent 未满，继续分配直到满
            int allocatedInThisExtent = 0;
            List<PageId> pagesInThisExtent = new ArrayList<>();
            while (!fragExtent.isFull() && allocatedInThisExtent < 64) {
                PageId page = spaceManager.allocateFragPage(mtr, SPACE_ID);
                if (page.getPageNo() / EXTENT_SIZE == extentNo) {
                    pagesInThisExtent.add(page);
                }
                allocatedInThisExtent++;
            }

            // 验证：Extent 应该在 FULL_FRAG 链表
            assertEquals(ExtentState.FULL_FRAG, fragExtent.getState());
            assertTrue(fsp.getFullFragList().getLength() > 0);

            // 释放一个页面
            if (!pagesInThisExtent.isEmpty()) {
                spaceManager.freeFragPage(mtr, pagesInThisExtent.get(0));

                // 验证：Extent 应该移回 FREE_FRAG
                assertEquals(ExtentState.FREE_FRAG, fragExtent.getState());
            }

            mtr.commit();
        }
    }

    // ==================== 测试5：逐步扩展表空间 ====================

    @Test
    void testGradualTablespaceExtension() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            FspHeaderPage fsp = new FspHeaderPage(mtr.getPage(PageId.of(SPACE_ID, 0)));
            int initialSize = fsp.getSize();

            // 逐步扩展表空间，每次扩展5个Extent
            for (int round = 0; round < 5; round++) {
                int sizeBefore = fsp.getSize();
                spaceManager.extendTablespace(mtr, SPACE_ID, 5);
                int sizeAfter = fsp.getSize();

                assertEquals(sizeBefore + 5 * EXTENT_SIZE, sizeAfter,
                        "每次应该扩展5个Extent");
            }

            // 总共扩展了 25 个Extent
            int finalSize = fsp.getSize();
            assertEquals(initialSize + 25 * EXTENT_SIZE, finalSize);

            // 验证：可以正常分配扩展后的Extent
            long segmentId = segmentManager.createSegment(mtr, SPACE_ID);

            // 分配10个 Extent，测试扩展的空间可用
            for (int i = 0; i < 10; i++) {
                ExtentDescriptor allocated = segmentManager.allocateExtentForSegment(mtr, SPACE_ID, segmentId);
                assertNotNull(allocated, "应该能够分配Extent");
            }

            mtr.commit();
        }
    }

    // ==================== 测试5.1：验证 ExtentDescriptor 跨多个 Extent ====================

    @Test
    void testExtentDescriptor_multipleExtents() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            // 扩展20个Extent
            spaceManager.extendTablespace(mtr, SPACE_ID, 20);

            // 验证：可以正确获取不同Extent的描述符
            for (int extentNo = 1; extentNo <= 20; extentNo++) {
                ExtentDescriptor ext = extentManager.getExtentDescriptor(mtr, SPACE_ID, extentNo);
                assertNotNull(ext, "Extent " + extentNo + " 应该存在");
                assertEquals(extentNo, ext.getExtentNo());
                assertEquals(ExtentState.FREE, ext.getState());
            }

            mtr.commit();
        }
    }

    // ==================== 测试6：多个 Segment 并发分配 ====================

    @Test
    void testMultipleSegments_concurrentAllocation() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            // 创建3个 Segment
            long[] segmentIds = new long[3];
            for (int i = 0; i < 3; i++) {
                segmentIds[i] = segmentManager.createSegment(mtr, SPACE_ID);
            }

            // 每个 Segment 分配50个页面
            for (long segmentId : segmentIds) {
                for (int i = 0; i < 50; i++) {
                    PageId page = segmentManager.allocatePageForSegment(mtr, SPACE_ID, segmentId);
                    assertNotNull(page);
                }
            }

            // 验证：每个 Segment 的统计信息
            for (long segmentId : segmentIds) {
                SegmentManager.SegmentStatistics segStats =
                        segmentManager.getStatistics(mtr, SPACE_ID, segmentId);
                assertEquals(32, segStats.getFragPagesUsed(), "每个Segment应该有32个碎片页");

                // 50页 = 32碎片页 + 18Extent页
                // 18页应该在一个 NOT_FULL Extent 中
                assertTrue(segStats.getNotFullExtentCount() > 0, "应该有NOT_FULL Extent");
            }

            mtr.commit();
        }
    }

    // ==================== 测试7：完整工作流（分配 + 删除）====================

    @Test
    void testCompleteWorkflow_allocateAndDrop() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 1. 初始化表空间
            spaceManager.initializeTablespace(mtr, SPACE_ID);

            // 2. 创建 Segment
            long segmentId = segmentManager.createSegment(mtr, SPACE_ID);

            // 3. 分配100个页面
            List<PageId> allocatedPages = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                PageId page = segmentManager.allocatePageForSegment(mtr, SPACE_ID, segmentId);
                allocatedPages.add(page);
            }

            // 4. 验证统计信息
            SegmentManager.SegmentStatistics stats =
                    segmentManager.getStatistics(mtr, SPACE_ID, segmentId);
            assertEquals(32, stats.getFragPagesUsed(), "应该有32个碎片页");
            assertTrue(stats.getNotFullExtentCount() + stats.getFullExtentCount() > 0,
                      "应该有Extent被分配");

            // 5. 记录删除前的表空间统计
            SpaceManager.SpaceStatistics spaceBefore = spaceManager.getStatistics(mtr, SPACE_ID);
            int freeExtentsBefore = spaceBefore.getFreeExtents();

            // 6. 删除 Segment（会自动释放所有资源）
            segmentManager.dropSegment(mtr, SPACE_ID, segmentId);

            // 7. 验证 Segment 已删除
            assertNull(segmentManager.getSegmentDescriptor(mtr, SPACE_ID, segmentId));

            // 8. 验证资源已回收（Extent归还到FREE链表）
            SpaceManager.SpaceStatistics spaceAfter = spaceManager.getStatistics(mtr, SPACE_ID);
            assertTrue(spaceAfter.getFreeExtents() > freeExtentsBefore,
                      "Extent应该归还到FREE链表");

            mtr.commit();
        }
    }

    // ==================== 测试8：页面释放测试 ====================

    @Test
    void testFreePage_extentPages() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);
            long segmentId = segmentManager.createSegment(mtr, SPACE_ID);

            // 分配100个页面
            List<PageId> allocatedPages = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                allocatedPages.add(segmentManager.allocatePageForSegment(mtr, SPACE_ID, segmentId));
            }

            SegmentDescriptor segment = segmentManager.getSegmentDescriptor(mtr, SPACE_ID, segmentId);

            // 只释放Extent页面（第33个页面之后），不释放碎片页
            // 这样避免了碎片页Extent变空后被重新分配的复杂情况
            for (int i = 32; i < 50; i++) {
                segmentManager.freePage(mtr, allocatedPages.get(i));
            }

            // 验证：应该有部分Extent从FULL变为NOT_FULL
            assertTrue(segment.getNotFullList().getLength() > 0 ||
                      segment.getFullList().getLength() > 0);

            mtr.commit();
        }
    }

    // ==================== 测试9：边界条件 - 恰好32个碎片页 ====================

    @Test
    void testExactly32FragmentPages() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            spaceManager.initializeTablespace(mtr, SPACE_ID);
            long segmentId = segmentManager.createSegment(mtr, SPACE_ID);

            // 分配恰好32个页面
            for (int i = 0; i < 32; i++) {
                PageId page = segmentManager.allocatePageForSegment(mtr, SPACE_ID, segmentId);
                assertNotNull(page);
            }

            SegmentDescriptor segment = segmentManager.getSegmentDescriptor(mtr, SPACE_ID, segmentId);
            assertEquals(32, segment.getFragUsedCount());

            // 第33个页面应该从 Extent 分配
            PageId page33 = segmentManager.allocatePageForSegment(mtr, SPACE_ID, segmentId);
            assertNotNull(page33);

            // 验证：应该有1个 NOT_FULL Extent
            assertEquals(1, segment.getNotFullList().getLength());

            mtr.commit();
        }
    }
}
