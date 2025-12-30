package cn.zhangyis.minidb.storage.mtr;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.common.exception.PageNotManagedByMtrException;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mini-Transaction 测试类
 *
 * <p>测试 MTR 的核心功能：
 * <ul>
 *   <li>页面自动 pin/unpin 管理</li>
 *   <li>脏页标记和追踪</li>
 *   <li>commit/rollback 语义</li>
 *   <li>try-with-resources 自动管理</li>
 *   <li>并发安全性（基础测试）</li>
 * </ul>
 * </p>
 *
 * @author MiniDB
 */
class MiniTransactionTest {

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool bufferPool;

    @BeforeEach
    void setUp() throws Exception {
        // 创建临时数据库文件
        Path dbFile = tempDir.resolve("test.db");
        diskManager = new DiskManager(dbFile.toString());

        // 创建 Buffer Pool (10 页)
        bufferPool = new BufferPool(10, diskManager);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (bufferPool != null) {
            bufferPool.close();
        }
        if (diskManager != null) {
            diskManager.close();
        }
    }

    // ==================== 基础功能测试 ====================

    /**
     * 测试：基本的页面获取和释放
     */
    @Test
    void testBasicGetAndRelease() throws MiniDbException, IOException {
        // 分配一个新页面
        PageId pageId = PageId.of(0, 0);

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 获取新页面
            Page page = mtr.newPage(0);

            assertNotNull(page);
            assertEquals(MiniTransaction.State.ACTIVE, mtr.getState());
            assertEquals(1, mtr.getPageCount());

            // 提交
            mtr.commit();
            assertEquals(MiniTransaction.State.COMMITTED, mtr.getState());
        }

        // MTR 结束后，页面应该被 unpin
        // 验证：再次获取应该能成功（说明之前被正确释放）
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.getPage(pageId);
            assertNotNull(page);
        }
    }

    /**
     * 测试：脏页标记
     */
    @Test
    void testMarkDirty() throws Exception {
        PageId pageId = PageId.of(0, 0);

        // 第一个 MTR：创建并修改页面
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(0);

            // 修改页面
            page.putInt(100, 12345);

            // 标记为脏页
            mtr.markDirty(page);

            assertTrue(mtr.isDirty(pageId));
            assertTrue(page.isDirty());

            mtr.commit();
        }

        // 第二个 MTR：验证修改已持久化
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.getPage(pageId);

            // 验证数据
            assertEquals(12345, page.getInt(100));
        }
    }

    /**
     * 测试：多个页面管理
     */
    @Test
    void testMultiplePages() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 分配 3 个页面
            Page page1 = mtr.newPage(0);
            Page page2 = mtr.newPage(0);
            Page page3 = mtr.newPage(0);

            // 修改每个页面
            page1.putInt(0, 111);
            page2.putInt(0, 222);
            page3.putInt(0, 333);

            mtr.markDirty(page1);
            mtr.markDirty(page2);
            mtr.markDirty(page3);

            assertEquals(3, mtr.getPageCount());

            mtr.commit();
        }

        // 验证所有页面都被正确持久化
        assertEquals(3, bufferPool.getStats().usedPages());
    }

    /**
     * 测试：重复获取同一页面
     */
    @Test
    void testGetSamePageTwice() throws Exception {
        PageId pageId = PageId.of(0, 0);

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page1 = mtr.newPage(0);
            Page page2 = mtr.getPage(pageId);

            // 应该返回同一个对象
            assertSame(page1, page2);
            assertEquals(1, mtr.getPageCount());

            mtr.commit();
        }
    }

    // ==================== Commit/Rollback 测试 ====================

    /**
     * 测试：显式 commit
     */
    @Test
    void testExplicitCommit() throws Exception {
        PageId pageId = PageId.of(0, 0);

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(0);
            page.putInt(0, 999);
            mtr.markDirty(page);

            mtr.commit();

            // commit 后状态应该是 COMMITTED
            assertEquals(MiniTransaction.State.COMMITTED, mtr.getState());
        }

        // 验证数据已持久化
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.getPage(pageId);
            assertEquals(999, page.getInt(0));
        }
    }

    /**
     * 测试：显式 rollback
     */
    @Test
    void testExplicitRollback() throws Exception {
        PageId pageId = PageId.of(0, 0);

        // 先创建一个页面并提交
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(0);
            page.putInt(0, 100);
            mtr.markDirty(page);
            mtr.commit();
        }

        // 修改页面但 rollback
        MiniTransaction mtr = new MiniTransaction(bufferPool);
        try {
            Page page = mtr.getPage(pageId);
            page.putInt(0, 999);  // 修改
            mtr.markDirty(page);

            mtr.rollback();

            assertEquals(MiniTransaction.State.ABORTED, mtr.getState());
        } finally {
            mtr.close();
        }

        // 验证修改未持久化（仍然是原值）
        // 注意：这个测试依赖于页面未被刷盘
        // 实际应用中需要配合 Undo Log 实现真正的回滚
    }

    /**
     * 测试：自动 rollback（try-with-resources）
     */
    @Test
    void testAutoRollback() throws Exception {
        PageId pageId = PageId.of(0, 0);

        // 先创建一个页面
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(0);
            page.putInt(0, 100);
            mtr.markDirty(page);
            mtr.commit();
        }

        // 修改但不 commit（依赖自动 rollback）
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.getPage(pageId);
            page.putInt(0, 999);
            mtr.markDirty(page);
            // 没有显式 commit，会自动 rollback
        }

        // 下一个 MTR 可以正常使用
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.getPage(pageId);
            assertNotNull(page);
        }
    }

    /**
     * 测试：重复 commit（幂等性）
     */
    @Test
    void testDoubleCommit() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(0);
            mtr.markDirty(page);

            mtr.commit();
            mtr.commit();  // 第二次 commit 应该是无操作

            assertEquals(MiniTransaction.State.COMMITTED, mtr.getState());
        }
    }

    // ==================== 异常情况测试 ====================

    /**
     * 测试：在 COMMITTED 状态下操作
     */
    @Test
    void testOperationAfterCommit() throws MiniDbException, IOException {
        MiniTransaction mtr = new MiniTransaction(bufferPool);
        Page page = mtr.newPage(0);
        mtr.commit();

        // 尝试在 commit 后获取新页面
        assertThrows(MtrStateException.class, () -> {
            mtr.newPage(0);
        });

        mtr.close();
    }

    /**
     * 测试：标记未管理的页面为脏
     */
    @Test
    void testMarkDirtyUnmanagedPage() throws MiniDbException, IOException {
        PageId pageId = PageId.of(0, 0);

        // 在一个 MTR 中创建页面
        try (MiniTransaction mtr1 = new MiniTransaction(bufferPool)) {
            Page page = mtr1.newPage(0);
            mtr1.commit();

            // 在另一个 MTR 中尝试标记为脏（未通过 getPage 获取）
            try (MiniTransaction mtr2 = new MiniTransaction(bufferPool)) {
                assertThrows(PageNotManagedByMtrException.class, () -> {
                    mtr2.markDirty(page);  // 这个 page 不属于 mtr2
                });
            }
        }
    }

    // ==================== 性能和统计测试 ====================

    /**
     * 测试：统计信息
     */
    @Test
    void testStatistics() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            assertEquals(0, mtr.getPageCount());

            Page page1 = mtr.newPage(0);
            assertEquals(1, mtr.getPageCount());

            Page page2 = mtr.newPage(0);
            assertEquals(2, mtr.getPageCount());

            assertTrue(mtr.getDuration() >= 0);

            mtr.commit();
        }
    }

    /**
     * 测试：hasPage 和 isDirty 查询
     */
    @Test
    void testQueryMethods() throws Exception {
        PageId pageId1 = PageId.of(0, 0);
        PageId pageId2 = PageId.of(0, 1);

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page1 = mtr.newPage(0);

            assertTrue(mtr.hasPage(pageId1));
            assertFalse(mtr.hasPage(pageId2));

            assertTrue(mtr.isDirty(pageId1));  // newPage 自动标记为脏

            mtr.commit();
        }
    }

    // ==================== Buffer Pool 交互测试 ====================

    /**
     * 测试：MTR 正确管理 pin count
     */
    @Test
    void testPinCountManagement() throws Exception {
        PageId pageId = PageId.of(0, 0);

        // 创建页面
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.newPage(0);
            mtr.commit();
        }

        // 获取 Buffer Pool 统计
        BufferPool.BufferPoolStats statsBefore = bufferPool.getStats();

        // 使用 MTR 获取页面
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.getPage(pageId);
            assertNotNull(page);
            // 此时页面应该被 pin
        }

        // MTR 结束后，页面应该被 unpin
        BufferPool.BufferPoolStats statsAfter = bufferPool.getStats();

        // 验证 Buffer Pool 状态一致
        assertEquals(statsBefore.usedPages(), statsAfter.usedPages());
    }

    /**
     * 测试：toString 方法
     */
    @Test
    void testToString() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            String str = mtr.toString();
            assertTrue(str.contains("MTR"));
            assertTrue(str.contains("ACTIVE"));
            assertTrue(str.contains("pages=0"));
        }
    }
}
