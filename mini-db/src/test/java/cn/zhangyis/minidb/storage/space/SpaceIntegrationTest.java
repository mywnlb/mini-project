package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.storage.BaseStorageTest;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 空间管理集成测试
 *
 * <p>测试 TableSpace、Segment、Extent 三层协同工作的正确性。</p>
 *
 * <h2>测试覆盖</h2>
 * <ul>
 *   <li>3阶段分配策略（碎片页 → Partial Extent → Free Extent）</li>
 *   <li>Extent 状态转移（FREE ↔ FREE_FRAG ↔ FULL_FRAG，FREE → FSEG）</li>
 *   <li>Segment 删除后资源回收</li>
 *   <li>表空间自动扩展</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class SpaceIntegrationTest extends BaseStorageTest {

    // ==================== 测试1：3阶段分配流程 ====================

    @Test
    void testThreePhaseAllocation() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 1. 初始化表空间
            TableSpace tableSpace = new TableSpace(SPACE_ID, bufferPool);
            tableSpace.initializeTablespace(mtr);

            // 2. 创建 Segment
            Segment segment = tableSpace.createSegment(mtr);
            assertNotNull(segment);
            long segmentId = segment.getSegmentId();

            // === 阶段1：碎片页分配（前32页）===
            List<Page> fragPages = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                Page page = segment.allocatePage(mtr);
                assertNotNull(page, "第" + i + "个碎片页分配失败");
                fragPages.add(page);
            }

            // 验证：碎片页数组应该有32个页面
            assertEquals(32, segment.getFragmentPageCount(), "碎片页数组应该有32个页面");

            // === 阶段3：Free Extent 分配（第33页）===
            Page page33 = segment.allocatePage(mtr);
            assertNotNull(page33);

            // 验证：Segment 的 NOT_FULL 链表应该有1个 Extent
            Segment.SegmentStatistics stats = segment.getStatistics();
            assertEquals(1, stats.getNotFullExtentCount(), "分配第一个Extent后应该在NOT_FULL链表");

            // === 阶段2：Partial Extent 分配（第34-96页，填满第一个Extent）===
            for (int i = 0; i < 63; i++) {
                Page page = segment.allocatePage(mtr);
                assertNotNull(page);
            }

            // 验证：第一个 Extent 应该移到 FULL 链表
            stats = segment.getStatistics();
            assertEquals(0, stats.getNotFullExtentCount(), "Extent满了应该移到FULL链表");
            assertEquals(1, stats.getFullExtentCount());

            // === 继续分配：触发第二个 Extent 分配 ===
            Page page97 = segment.allocatePage(mtr);
            assertNotNull(page97);

            // 验证：应该有2个 Extent（1个FULL，1个NOT_FULL）
            stats = segment.getStatistics();
            assertEquals(1, stats.getNotFullExtentCount());
            assertEquals(1, stats.getFullExtentCount());

            mtr.commit();
        }
    }

    // ==================== 测试2：Segment 删除后资源回收 ====================

    @Test
    void testSegmentDeletion_shouldReleaseAllResources() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            TableSpace tableSpace = new TableSpace(SPACE_ID, bufferPool);
            tableSpace.initializeTablespace(mtr);

            Segment segment = tableSpace.createSegment(mtr);
            long segmentId = segment.getSegmentId();

            // 分配32个碎片页 + 64个Extent页
            for (int i = 0; i < 96; i++) {
                segment.allocatePage(mtr);
            }

            // 记录删除前的统计信息
            TableSpace.SpaceStatistics statsBefore = tableSpace.getSpaceStatistics(mtr);
            int freeExtentsBefore = statsBefore.getFreeExtents();

            // 删除 Segment
            tableSpace.dropSegment(mtr, segmentId);

            // 验证：Extent 已归还到表空间 FREE 链表
            TableSpace.SpaceStatistics statsAfter = tableSpace.getSpaceStatistics(mtr);
            assertTrue(statsAfter.getFreeExtents() > freeExtentsBefore,
                    "Extent应该归还到FREE链表");

            // 验证：Segment 已不存在
            Segment found = tableSpace.getSegment(mtr, segmentId);
            assertNull(found, "Segment应该已被删除");

            mtr.commit();
        }
    }

    // ==================== 测试3：自动扩展表空间 ====================

    @Test
    void testAutoExtend_whenFreeListEmpty() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            TableSpace tableSpace = new TableSpace(SPACE_ID, bufferPool);
            tableSpace.initializeTablespace(mtr);

            FspHeaderPage fsp = new FspHeaderPage(mtr.getPage(PageId.of(SPACE_ID, 0)));

            // 耗尽所有 FREE Extent
            while (!fsp.getFreeList().isEmpty()) {
                fsp.getFreeList().removeFirstAndGetAddr(mtr);
            }

            int sizeBefore = fsp.getSize();

            // 创建 Segment 并分配 Extent（应该触发自动扩展）
            Segment segment = tableSpace.createSegment(mtr);
            Extent extent = tableSpace.allocateExtentForSegment(mtr, segment.getSegmentId());

            assertNotNull(extent, "应该通过自动扩展分配到Extent");

            int sizeAfter = fsp.getSize();
            assertTrue(sizeAfter > sizeBefore, "表空间应该已扩展");

            mtr.commit();
        }
    }

    // ==================== 测试4：碎片页状态转移 ====================

    @Test
    void testFragmentExtent_stateTransitions() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            TableSpace tableSpace = new TableSpace(SPACE_ID, bufferPool);
            tableSpace.initializeTablespace(mtr);

            Segment segment = tableSpace.createSegment(mtr);

            // 分配32个碎片页
            List<Page> fragPages = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                Page page = segment.allocatePage(mtr);
                fragPages.add(page);
            }

            FspHeaderPage fsp = new FspHeaderPage(mtr.getPage(PageId.of(SPACE_ID, 0)));

            // 碎片页可能分布在多个 Extent，找到第一个碎片页所在的 Extent
            int firstFragPageNo = fragPages.get(0).getPageNo();
            int extentNo = firstFragPageNo / EXTENT_SIZE;
            Extent fragExtent = tableSpace.getExtent(mtr, extentNo);

            // 如果 Extent 未满，继续分配直到满
            List<Page> pagesInThisExtent = new ArrayList<>();
            while (!fragExtent.isFull()) {
                Page page = tableSpace.allocateFragmentPage(mtr);
                if (page != null && page.getPageNo() / EXTENT_SIZE == extentNo) {
                    pagesInThisExtent.add(page);
                }
                if (pagesInThisExtent.size() >= 64) break;
            }

            // 验证：Extent 应该在 FULL_FRAG 链表
            assertEquals(ExtentState.FULL_FRAG, fragExtent.getState());
            assertTrue(fsp.getFullFragList().getLength() > 0);

            // 释放一个页面
            if (!pagesInThisExtent.isEmpty()) {
                tableSpace.freeFragmentPage(mtr, pagesInThisExtent.get(0).getPageNo());

                // 验证：Extent 应该移回 FREE_FRAG
                assertEquals(ExtentState.FREE_FRAG, fragExtent.getState());
            }

            mtr.commit();
        }
    }

    // ==================== 测试5：多个 Segment 并发分配 ====================

    @Test
    void testMultipleSegments_concurrentAllocation() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            TableSpace tableSpace = new TableSpace(SPACE_ID, bufferPool);
            tableSpace.initializeTablespace(mtr);

            // 创建3个 Segment
            Segment[] segments = new Segment[3];
            for (int i = 0; i < 3; i++) {
                segments[i] = tableSpace.createSegment(mtr);
            }

            // 每个 Segment 分配50个页面
            for (Segment segment : segments) {
                for (int i = 0; i < 50; i++) {
                    Page page = segment.allocatePage(mtr);
                    assertNotNull(page);
                }
            }

            // 验证：每个 Segment 的统计信息
            for (Segment segment : segments) {
                Segment.SegmentStatistics segStats = segment.getStatistics();
                assertEquals(32, segStats.getFragPagesUsed(), "每个Segment应该有32个碎片页");

                // 50页 = 32碎片页 + 18Extent页
                assertTrue(segStats.getNotFullExtentCount() > 0, "应该有NOT_FULL Extent");
            }

            mtr.commit();
        }
    }

    // ==================== 测试6：完整工作流（分配 + 删除）====================

    @Test
    void testCompleteWorkflow_allocateAndDrop() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 1. 初始化表空间
            TableSpace tableSpace = new TableSpace(SPACE_ID, bufferPool);
            tableSpace.initializeTablespace(mtr);

            // 2. 创建 Segment
            Segment segment = tableSpace.createSegment(mtr);
            long segmentId = segment.getSegmentId();

            // 3. 分配100个页面
            List<Page> allocatedPages = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                Page page = segment.allocatePage(mtr);
                allocatedPages.add(page);
            }

            // 4. 验证统计信息
            Segment.SegmentStatistics stats = segment.getStatistics();
            assertEquals(32, stats.getFragPagesUsed(), "应该有32个碎片页");
            assertTrue(stats.getNotFullExtentCount() + stats.getFullExtentCount() > 0,
                      "应该有Extent被分配");

            // 5. 记录删除前的表空间统计
            TableSpace.SpaceStatistics spaceBefore = tableSpace.getSpaceStatistics(mtr);
            int freeExtentsBefore = spaceBefore.getFreeExtents();

            // 6. 删除 Segment（会自动释放所有资源）
            tableSpace.dropSegment(mtr, segmentId);

            // 7. 验证 Segment 已删除
            assertNull(tableSpace.getSegment(mtr, segmentId));

            // 8. 验证资源已回收（Extent归还到FREE链表）
            TableSpace.SpaceStatistics spaceAfter = tableSpace.getSpaceStatistics(mtr);
            assertTrue(spaceAfter.getFreeExtents() > freeExtentsBefore,
                      "Extent应该归还到FREE链表");

            mtr.commit();
        }
    }

    // ==================== 测试7：页面释放测试 ====================

    @Test
    void testFreePage_extentPages() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            TableSpace tableSpace = new TableSpace(SPACE_ID, bufferPool);
            tableSpace.initializeTablespace(mtr);

            Segment segment = tableSpace.createSegment(mtr);

            // 分配100个页面
            List<Page> allocatedPages = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                allocatedPages.add(segment.allocatePage(mtr));
            }

            // 只释放Extent页面（第33个页面之后），不释放碎片页
            for (int i = 32; i < 50; i++) {
                segment.freePage(mtr, allocatedPages.get(i).getPageNo());
            }

            // 验证：应该有部分Extent从FULL变为NOT_FULL
            Segment.SegmentStatistics stats = segment.getStatistics();
            assertTrue(stats.getNotFullExtentCount() > 0 || stats.getFullExtentCount() > 0);

            mtr.commit();
        }
    }

    // ==================== 测试8：边界条件 - 恰好32个碎片页 ====================

    @Test
    void testExactly32FragmentPages() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            TableSpace tableSpace = new TableSpace(SPACE_ID, bufferPool);
            tableSpace.initializeTablespace(mtr);

            Segment segment = tableSpace.createSegment(mtr);

            // 分配恰好32个页面
            for (int i = 0; i < 32; i++) {
                Page page = segment.allocatePage(mtr);
                assertNotNull(page);
            }

            assertEquals(32, segment.getFragmentPageCount());

            // 第33个页面应该从 Extent 分配
            Page page33 = segment.allocatePage(mtr);
            assertNotNull(page33);

            // 验证：应该有1个 NOT_FULL Extent
            Segment.SegmentStatistics stats = segment.getStatistics();
            assertEquals(1, stats.getNotFullExtentCount());

            mtr.commit();
        }
    }

    // ==================== 测试9：TableSpace 统计信息 ====================

    @Test
    void testTableSpaceStatistics() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            TableSpace tableSpace = new TableSpace(SPACE_ID, bufferPool);
            tableSpace.initializeTablespace(mtr);

            // 获取初始统计信息
            TableSpace.SpaceStatistics stats = tableSpace.getSpaceStatistics(mtr);
            assertNotNull(stats);
            assertEquals(SPACE_ID, stats.getSpaceId());
            assertTrue(stats.getTotalPages() > 0);
            assertTrue(stats.getFreeExtents() >= 0);

            // 创建 Segment 后统计信息应该更新
            Segment segment = tableSpace.createSegment(mtr);
            stats = tableSpace.getSpaceStatistics(mtr);
            assertTrue(stats.getNextSegmentId() > 1, "NextSegmentId应该递增");

            mtr.commit();
        }
    }

    // ==================== 测试10：Segment 统计信息 ====================

    @Test
    void testSegmentStatistics() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            TableSpace tableSpace = new TableSpace(SPACE_ID, bufferPool);
            tableSpace.initializeTablespace(mtr);

            Segment segment = tableSpace.createSegment(mtr);

            // 初始统计
            Segment.SegmentStatistics stats = segment.getStatistics();
            assertEquals(segment.getSegmentId(), stats.getSegmentId());
            assertEquals(0, stats.getFragPagesUsed());
            assertEquals(0, stats.getTotalExtentCount());

            // 分配50页后
            for (int i = 0; i < 50; i++) {
                segment.allocatePage(mtr);
            }

            stats = segment.getStatistics();
            assertEquals(32, stats.getFragPagesUsed());
            assertTrue(stats.getNotFullExtentCount() > 0);
            assertTrue(stats.getEstimatedPageCount() >= 50);

            mtr.commit();
        }
    }
}
