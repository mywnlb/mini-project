package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.page.PageId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UndoPagePool 单元测试
 *
 * <p>测试 Undo Page 池的分配、回收和统计功能。</p>
 */
class UndoPagePoolTest {

    private UndoPagePool pool;
    private static final int TEST_RSEG_ID = 0;
    private static final int TEST_SPACE_ID = 1;

    @BeforeEach
    void setUp() {
        pool = new UndoPagePool(TEST_RSEG_ID);
    }

    // ==================== 基础分配测试 ====================

    @Test
    void testAllocateFromEmptyPool() {
        // 池为空时分配应返回 null
        PageId pageId = pool.allocateUndoPage();
        assertNull(pageId, "Empty pool should return null");
    }

    @Test
    void testAllocateFromFreshPages() {
        // 添加 freshPage
        PageId page1 = PageId.of(TEST_SPACE_ID, 100);
        pool.addFreshPage(page1);

        assertEquals(1, pool.getFreshPageCount(), "Fresh page count should be 1");

        // 分配应该返回该页面
        PageId allocated = pool.allocateUndoPage();
        assertEquals(page1, allocated, "Should allocate the fresh page");
        assertEquals(0, pool.getFreshPageCount(), "Fresh page count should be 0 after allocation");
    }

    @Test
    void testAllocateFromReusablePages() {
        // 添加 reusablePage
        PageId page1 = PageId.of(TEST_SPACE_ID, 200);
        pool.addReusablePage(page1);

        assertEquals(1, pool.getReusablePageCount(), "Reusable page count should be 1");

        // 分配应该返回该页面
        PageId allocated = pool.allocateUndoPage();
        assertEquals(page1, allocated, "Should allocate the reusable page");
        assertEquals(0, pool.getReusablePageCount(), "Reusable page count should be 0 after allocation");
    }

    @Test
    void testAllocatePriority() {
        // 同时添加 fresh 和 reusable 页面
        PageId freshPage = PageId.of(TEST_SPACE_ID, 100);
        PageId reusablePage = PageId.of(TEST_SPACE_ID, 200);

        pool.addFreshPage(freshPage);
        pool.addReusablePage(reusablePage);

        // 第一次分配应该返回 freshPage（优先级更高）
        PageId allocated1 = pool.allocateUndoPage();
        assertEquals(freshPage, allocated1, "Should allocate fresh page first");

        // 第二次分配应该返回 reusablePage
        PageId allocated2 = pool.allocateUndoPage();
        assertEquals(reusablePage, allocated2, "Should allocate reusable page second");

        // 第三次分配应该返回 null
        PageId allocated3 = pool.allocateUndoPage();
        assertNull(allocated3, "Should return null when pool is empty");
    }

    // ==================== 批量操作测试 ====================

    @Test
    void testMultipleAllocations() {
        // 添加多个页面
        for (int i = 0; i < 5; i++) {
            pool.addFreshPage(PageId.of(TEST_SPACE_ID, 100 + i));
        }

        assertEquals(5, pool.getFreshPageCount(), "Fresh page count should be 5");

        // 分配所有页面
        for (int i = 0; i < 5; i++) {
            PageId allocated = pool.allocateUndoPage();
            assertNotNull(allocated, "Should allocate page " + i);
        }

        assertEquals(0, pool.getFreshPageCount(), "Fresh page count should be 0");

        // 再分配应该返回 null
        PageId allocated = pool.allocateUndoPage();
        assertNull(allocated, "Should return null when pool is empty");
    }

    @Test
    void testMixedOperations() {
        // 添加 fresh 页面
        for (int i = 0; i < 3; i++) {
            pool.addFreshPage(PageId.of(TEST_SPACE_ID, 100 + i));
        }

        // 分配一个
        PageId allocated1 = pool.allocateUndoPage();
        assertNotNull(allocated1);

        // 添加 reusable 页面
        for (int i = 0; i < 2; i++) {
            pool.addReusablePage(PageId.of(TEST_SPACE_ID, 200 + i));
        }

        // 继续分配
        PageId allocated2 = pool.allocateUndoPage();
        assertNotNull(allocated2);

        assertEquals(1, pool.getFreshPageCount(), "Fresh page count should be 1");
        assertEquals(2, pool.getReusablePageCount(), "Reusable page count should be 2");
    }

    // ==================== 池状态查询测试 ====================

    @Test
    void testPoolStats() {
        // 初始状态
        assertEquals(0, pool.getTotalPageCount(), "Total page count should be 0");
        assertEquals(0, pool.getTotalAllocated(), "Total allocated should be 0");
        assertEquals(0, pool.getTotalReused(), "Total reused should be 0");

        // 添加 fresh 页面
        for (int i = 0; i < 3; i++) {
            pool.addFreshPage(PageId.of(TEST_SPACE_ID, 100 + i));
        }

        assertEquals(3, pool.getTotalPageCount(), "Total page count should be 3");
        assertEquals(3, pool.getTotalAllocated(), "Total allocated should be 3");

        // 分配一个 fresh 页面
        pool.allocateUndoPage();

        assertEquals(2, pool.getTotalPageCount(), "Total page count should be 2");

        // 添加 reusable 页面
        pool.addReusablePage(PageId.of(TEST_SPACE_ID, 200));

        assertEquals(3, pool.getTotalPageCount(), "Total page count should be 3");

        // 分配 reusable 页面
        pool.allocateUndoPage();

        assertEquals(1, pool.getTotalReused(), "Total reused should be 1");
    }

    @Test
    void testNeedsRefill() {
        // 初始状态不需要补充
        assertFalse(pool.needsRefill(), "Should not need refill initially");

        // 添加 4 个页面（MIN_THRESHOLD = 5）
        for (int i = 0; i < 4; i++) {
            pool.addFreshPage(PageId.of(TEST_SPACE_ID, 100 + i));
        }

        assertTrue(pool.needsRefill(), "Should need refill when count < MIN_THRESHOLD");

        // 添加 1 个页面达到 5 个
        pool.addFreshPage(PageId.of(TEST_SPACE_ID, 104));

        assertFalse(pool.needsRefill(), "Should not need refill when count >= MIN_THRESHOLD");
    }

    @Test
    void testHasExcessPages() {
        // 初始状态没有多余页面
        assertFalse(pool.hasExcessPages(), "Should not have excess pages initially");

        // 添加 20 个页面（MAX_THRESHOLD = 20）
        for (int i = 0; i < 20; i++) {
            pool.addFreshPage(PageId.of(TEST_SPACE_ID, 100 + i));
        }

        assertFalse(pool.hasExcessPages(), "Should not have excess pages at MAX_THRESHOLD");

        // 添加 1 个页面超过 MAX_THRESHOLD
        pool.addFreshPage(PageId.of(TEST_SPACE_ID, 120));

        assertTrue(pool.hasExcessPages(), "Should have excess pages when count > MAX_THRESHOLD");
    }

    @Test
    void testGetRefillCount() {
        // 初始状态
        assertEquals(0, pool.getRefillCount(), "Refill count should be 0 initially");

        // 添加 3 个页面（MIN_THRESHOLD = 5）
        for (int i = 0; i < 3; i++) {
            pool.addFreshPage(PageId.of(TEST_SPACE_ID, 100 + i));
        }

        // 需要补充 5 - 3 = 2 个页面
        assertEquals(2, pool.getRefillCount(), "Refill count should be 2");

        // 添加 2 个页面达到 5 个
        pool.addFreshPage(PageId.of(TEST_SPACE_ID, 103));
        pool.addFreshPage(PageId.of(TEST_SPACE_ID, 104));

        assertEquals(0, pool.getRefillCount(), "Refill count should be 0 when at MIN_THRESHOLD");
    }

    // ==================== 命中率测试 ====================

    @Test
    void testHitRate() {
        // 初始命中率为 0
        assertEquals(0.0, pool.getHitRate(), "Hit rate should be 0 initially");

        // 添加 fresh 页面并分配
        pool.addFreshPage(PageId.of(TEST_SPACE_ID, 100));
        pool.allocateUndoPage();

        // 命中率应该是 0（没有复用）
        assertEquals(0.0, pool.getHitRate(), "Hit rate should be 0 for fresh pages");

        // 添加 reusable 页面并分配
        pool.addReusablePage(PageId.of(TEST_SPACE_ID, 200));
        pool.allocateUndoPage();

        // 命中率应该是 1/2 = 0.5
        assertEquals(0.5, pool.getHitRate(), 0.01, "Hit rate should be 0.5");

        // 再添加 fresh 页面并分配
        pool.addFreshPage(PageId.of(TEST_SPACE_ID, 101));
        pool.allocateUndoPage();

        // 命中率应该是 1/3 ≈ 0.333
        assertEquals(1.0 / 3, pool.getHitRate(), 0.01, "Hit rate should be 1/3");
    }

    // ==================== 清空测试 ====================

    @Test
    void testClear() {
        // 添加页面
        for (int i = 0; i < 5; i++) {
            pool.addFreshPage(PageId.of(TEST_SPACE_ID, 100 + i));
        }
        for (int i = 0; i < 3; i++) {
            pool.addReusablePage(PageId.of(TEST_SPACE_ID, 200 + i));
        }

        assertEquals(8, pool.getTotalPageCount(), "Total page count should be 8");

        // 清空
        int cleared = pool.clear();

        assertEquals(8, cleared, "Should clear 8 pages");
        assertEquals(0, pool.getTotalPageCount(), "Total page count should be 0 after clear");
        assertEquals(0, pool.getFreshPageCount(), "Fresh page count should be 0 after clear");
        assertEquals(0, pool.getReusablePageCount(), "Reusable page count should be 0 after clear");
    }

    // ==================== 异常处理测试 ====================

    @Test
    void testAddNullFreshPage() {
        assertThrows(IllegalArgumentException.class, () -> pool.addFreshPage(null),
                "Should throw exception for null fresh page");
    }

    @Test
    void testAddNullReusablePage() {
        assertThrows(IllegalArgumentException.class, () -> pool.addReusablePage(null),
                "Should throw exception for null reusable page");
    }

    // ==================== 统计信息测试 ====================

    @Test
    void testGetStats() {
        // 添加页面
        pool.addFreshPage(PageId.of(TEST_SPACE_ID, 100));
        pool.addReusablePage(PageId.of(TEST_SPACE_ID, 200));

        String stats = pool.getStats();

        assertNotNull(stats, "Stats should not be null");
        assertTrue(stats.contains("rsegId=" + TEST_RSEG_ID), "Stats should contain rsegId");
        assertTrue(stats.contains("fresh=1"), "Stats should contain fresh page count");
        assertTrue(stats.contains("reusable=1"), "Stats should contain reusable page count");
    }
}
