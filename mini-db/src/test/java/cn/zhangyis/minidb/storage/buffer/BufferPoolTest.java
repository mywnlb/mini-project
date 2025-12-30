package cn.zhangyis.minidb.storage.buffer;




import cn.zhangyis.minidb.storage.constants.StorageConstants;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.page.IndexPage;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.page.PageType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Buffer Pool 和 Page 测试
 */
public class BufferPoolTest {

    private static Path testDir;
    private static DiskManager diskManager;
    private static BufferPool bufferPool;

    public static void main(String[] args) {
        try {
            System.out.println("=".repeat(60));
            System.out.println("MiniDB Buffer Pool Test");
            System.out.println("=".repeat(60));

            setup();

            testPageBasics();
            testIndexPageBasics();
            testDiskManager();
            testBufferPoolBasics();
            testBufferPoolHitRatio();
            testBufferPoolEviction();
            testBufferPoolDirtyPages();
            testBufferPoolConcurrency();

            cleanup();

            System.out.println("\n" + "=".repeat(60));
            System.out.println("All tests passed!");
            System.out.println("=".repeat(60));

        } catch (Exception e) {
            System.err.println("Test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    static void setup() throws Exception {
        testDir = Files.createTempDirectory("minidb_test_");
        System.out.println("\nTest directory: " + testDir);
    }

    static void cleanup() throws Exception {
        if (bufferPool != null) {
            bufferPool.close();
        }
        if (diskManager != null) {
            diskManager.close();
        }
        // 清理测试文件
        if (testDir != null) {
            Files.walk(testDir)
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException e) {}
                    });
        }
    }

    // ============ Test: Page Basics ============

    static void testPageBasics() {
        System.out.println("\n[Test] Page Basics");

        PageId pageId = PageId.of(1, 0);
        Page page = new Page(pageId);

        // 验证页面大小
        assert page.getBytes().length == StorageConstants.PAGE_SIZE : "Page size should be 16KB";

        // 验证基本属性
        assert page.getPageNo() == 0 : "Page number should be 0";
        assert page.getSpaceId() == 1 : "Space ID should be 1";
        assert page.getPrevPage() == StorageConstants.FIL_NULL : "Prev should be FIL_NULL";
        assert page.getNextPage() == StorageConstants.FIL_NULL : "Next should be FIL_NULL";

        // 测试设置 LSN
        page.setLsn(12345L);
        assert page.getLsn() == 12345L : "LSN should be 12345";

        // 测试设置页类型
        page.setPageType(PageType.FIL_PAGE_INDEX);
        assert page.getPageType() == PageType.FIL_PAGE_INDEX : "Page type should be INDEX";

        // 测试脏页标记
        assert page.isDirty() : "Page should be dirty after modification";
        page.clearDirty();
        assert !page.isDirty() : "Page should be clean after clearDirty";

        // 测试原始字节读写 (先做，再测试校验和)
        page.putInt(100, 0x12345678);
        assert page.getInt(100) == 0x12345678 : "Int read/write failed";

        page.putLong(200, 0x123456789ABCDEFL);
        assert page.getLong(200) == 0x123456789ABCDEFL : "Long read/write failed";

        byte[] testData = {1, 2, 3, 4, 5};
        page.putBytes(300, testData);
        byte[] readData = new byte[5];
        page.getBytes(300, readData);
        for (int i = 0; i < 5; i++) {
            assert readData[i] == testData[i] : "Byte array read/write failed";
        }

        // 测试校验和 (在所有修改后)
        page.prepareForFlush();
        assert page.verifyChecksum() : "Checksum should be valid";

        System.out.println("  ✓ Page basics test passed");
    }

    // ============ Test: IndexPage Basics ============

    static void testIndexPageBasics() {
        System.out.println("\n[Test] IndexPage Basics");

        PageId pageId = PageId.of(1, 1);
        IndexPage page = new IndexPage(pageId);

        // 验证页类型
        assert page.getPageType() == PageType.FIL_PAGE_INDEX : "Should be INDEX page";

        // 验证 Page Header
        assert page.getSlotCount() == 2 : "Should have 2 slots (infimum, supremum)";
        assert page.getHeapRecordCount() == 2 : "Should have 2 heap records";
        assert page.isCompactFormat() : "Should be compact format";
        assert page.getRecordCount() == 0 : "Should have 0 user records";
        assert page.isLeaf() : "Should be leaf (level 0)";

        // 验证空闲空间计算
        int freeSpace = page.getFreeSpace();
        System.out.println("  Free space: " + freeSpace + " bytes");
        assert freeSpace > 0 : "Should have free space";
        assert freeSpace < StorageConstants.PAGE_SIZE : "Free space should be less than page size";

        // 测试 Level 设置
        page.setLevel(2);
        assert page.getLevel() == 2 : "Level should be 2";
        assert !page.isLeaf() : "Should not be leaf";
        page.setLevel(0);

        // 测试 Index ID
        page.setIndexId(100L);
        assert page.getIndexId() == 100L : "Index ID should be 100";

        // 测试 Max TRX ID
        page.setMaxTrxId(999L);
        assert page.getMaxTrxId() == 999L : "Max TRX ID should be 999";

        // 验证 Infimum/Supremum 链接
        int firstRec = page.getFirstUserRecordOffset();
        assert firstRec == 0 || firstRec == IndexPage.SUPREMUM_OFFSET :
                "First record should point to supremum (no user records)";

        // 验证 Page Directory
        int slot0 = page.getSlotOffset(0);
        int slot1 = page.getSlotOffset(1);
        assert slot0 == IndexPage.SUPREMUM_OFFSET : "Slot 0 should point to supremum";
        assert slot1 == IndexPage.INFIMUM_OFFSET : "Slot 1 should point to infimum";

        System.out.println("  ✓ IndexPage basics test passed");
    }

    // ============ Test: DiskManager ============

    static void testDiskManager() throws Exception {
        System.out.println("\n[Test] DiskManager");

        diskManager = new DiskManager(testDir);

        // 创建表空间
        diskManager.createTablespace(1, "test_table");
        assert diskManager.tablespaceExists(1) : "Tablespace should exist";
        assert diskManager.getPageCount(1) == 1 : "Should have 1 page (FSP_HDR)";

        // 分配新页面
        int pageNo1 = diskManager.allocatePage(1);
        assert pageNo1 == 1 : "First allocated page should be 1";

        int pageNo2 = diskManager.allocatePage(1);
        assert pageNo2 == 2 : "Second allocated page should be 2";

        assert diskManager.getPageCount(1) == 3 : "Should have 3 pages";

        // 写入页面
        PageId pageId = PageId.of(1, 1);
        IndexPage page = new IndexPage(pageId);
        page.setIndexId(42L);
        page.setLevel(1);
        page.prepareForFlush();

        diskManager.writePage(pageId, page.getBuffer());

        // 读取页面
        var readBuffer = diskManager.readPage(pageId);
        IndexPage readPage = new IndexPage(pageId, readBuffer);

        assert readPage.getIndexId() == 42L : "Index ID should be 42";
        assert readPage.getLevel() == 1 : "Level should be 1";
        assert readPage.verifyChecksum() : "Checksum should be valid";

        System.out.println("  ✓ DiskManager test passed");
    }

    // ============ Test: BufferPool Basics ============

    static void testBufferPoolBasics() throws Exception {
        System.out.println("\n[Test] BufferPool Basics");

        // 创建 Buffer Pool (64 pages = 1MB)
        bufferPool = new BufferPool(64, diskManager);

        // 获取新页面
        BufferFrame frame = bufferPool.newPage(1);
        PageId pageId = frame.getPageId();

        System.out.println("  Allocated new page: " + pageId);
        assert frame.getPinCount() == 1 : "Pin count should be 1";

        // 修改页面
        IndexPage page = frame.getPageAs(IndexPage.class);
        page.setIndexId(123L);
        page.setLevel(0);

        // 释放页面 (标记为脏)
        bufferPool.unpinPage(pageId, true);
        assert frame.getPinCount() == 0 : "Pin count should be 0";
        assert frame.isDirty() : "Page should be dirty";

        // 重新获取页面
        BufferFrame frame2 = bufferPool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
        assert frame2 == frame : "Should be same frame";

        IndexPage page2 = frame2.getPageAs(IndexPage.class);
        assert page2.getIndexId() == 123L : "Index ID should be 123";

        bufferPool.unpinPage(pageId, false);

        // 查看统计信息
        var stats = bufferPool.getStats();
        System.out.println("  Stats: " + stats);

        System.out.println("  ✓ BufferPool basics test passed");
    }

    // ============ Test: BufferPool Hit Ratio ============

    static void testBufferPoolHitRatio() throws Exception {
        System.out.println("\n[Test] BufferPool Hit Ratio");

        // 创建多个页面
        List<PageId> pageIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            BufferFrame frame = bufferPool.newPage(1);
            pageIds.add(frame.getPageId());
            bufferPool.unpinPage(frame.getPageId(), false);
        }

        // 多次访问相同页面 (应该命中)
        for (int i = 0; i < 100; i++) {
            PageId pageId = pageIds.get(i % pageIds.size());
            BufferFrame frame = bufferPool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
            bufferPool.unpinPage(pageId, false);
        }

        var stats = bufferPool.getStats();
        System.out.println("  Hit ratio: " + String.format("%.2f%%", stats.hitRatio() * 100));
        System.out.println("  Hits: " + stats.hitCount() + ", Misses: " + stats.missCount());

        assert stats.hitRatio() > 0.8 : "Hit ratio should be high for repeated access";

        System.out.println("  ✓ BufferPool hit ratio test passed");
    }

    // ============ Test: BufferPool Eviction ============

    static void testBufferPoolEviction() throws Exception {
        System.out.println("\n[Test] BufferPool Eviction");

        // 创建新的小 Buffer Pool (8 pages)
        BufferPool smallPool = new BufferPool(8, diskManager);

        // 创建超过 pool 容量的页面
        List<PageId> pageIds = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            BufferFrame frame = smallPool.newPage(1);
            pageIds.add(frame.getPageId());

            // 修改页面
            IndexPage page = frame.getPageAs(IndexPage.class);
            page.setIndexId(i);

            smallPool.unpinPage(frame.getPageId(), true);
        }

        var stats = smallPool.getStats();
        System.out.println("  Pool stats after eviction: " + stats);

        // 验证可以读回所有页面 (可能需要从磁盘读取)
        for (int i = 0; i < pageIds.size(); i++) {
            PageId pageId = pageIds.get(i);
            BufferFrame frame = smallPool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
            IndexPage page = frame.getPageAs(IndexPage.class);

            assert page.getIndexId() == i : "Index ID should be " + i;
            smallPool.unpinPage(pageId, false);
        }

        smallPool.close();
        System.out.println("  ✓ BufferPool eviction test passed");
    }

    // ============ Test: BufferPool Dirty Pages ============

    static void testBufferPoolDirtyPages() throws Exception {
        System.out.println("\n[Test] BufferPool Dirty Pages");

        BufferPool pool = new BufferPool(16, diskManager);

        // 创建脏页
        List<PageId> dirtyPageIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            BufferFrame frame = pool.newPage(1);
            dirtyPageIds.add(frame.getPageId());

            IndexPage page = frame.getPageAs(IndexPage.class);
            page.setIndexId(1000 + i);

            pool.unpinPage(frame.getPageId(), true);
        }

        var stats = pool.getStats();
        System.out.println("  Dirty pages: " + stats.dirtyPages());
        assert stats.dirtyPages() == 5 : "Should have 5 dirty pages";

        // 刷盘
        pool.flushAllPages();

        stats = pool.getStats();
        System.out.println("  Dirty pages after flush: " + stats.dirtyPages());
        assert stats.dirtyPages() == 0 : "Should have 0 dirty pages after flush";

        // 验证数据已持久化
        pool.close();

        // 重新打开
        BufferPool pool2 = new BufferPool(16, diskManager);

        for (int i = 0; i < dirtyPageIds.size(); i++) {
            PageId pageId = dirtyPageIds.get(i);
            BufferFrame frame = pool2.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
            IndexPage page = frame.getPageAs(IndexPage.class);

            assert page.getIndexId() == 1000 + i : "Index ID should be " + (1000 + i);
            pool2.unpinPage(pageId, false);
        }

        pool2.close();
        System.out.println("  ✓ BufferPool dirty pages test passed");
    }

    // ============ Test: BufferPool Concurrency ============

    static void testBufferPoolConcurrency() throws Exception {
        System.out.println("\n[Test] BufferPool Concurrency");

        BufferPool pool = new BufferPool(32, diskManager);

        // 创建一些初始页面
        List<PageId> pageIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            BufferFrame frame = pool.newPage(1);
            pageIds.add(frame.getPageId());
            pool.unpinPage(frame.getPageId(), false);
        }

        // 并发访问
        int numThreads = 4;
        int opsPerThread = 100;
        Thread[] threads = new Thread[numThreads];
        Exception[] errors = new Exception[1];

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                try {
                    Random rand = new Random(threadId);
                    for (int i = 0; i < opsPerThread; i++) {
                        PageId pageId = pageIds.get(rand.nextInt(pageIds.size()));
                        BufferFrame frame = pool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);

                        // 简单读取
                        IndexPage page = frame.getPageAs(IndexPage.class);
                        page.getRecordCount();

                        // 有时修改
                        boolean dirty = rand.nextBoolean();
                        if (dirty) {
                            page.setMaxTrxId(System.nanoTime());
                        }

                        pool.unpinPage(pageId, dirty);
                    }
                } catch (Exception e) {
                    errors[0] = e;
                }
            });
            threads[t].start();
        }

        // 等待所有线程完成
        for (Thread thread : threads) {
            thread.join();
        }

        if (errors[0] != null) {
            throw errors[0];
        }

        var stats = pool.getStats();
        System.out.println("  Final stats: " + stats);
        System.out.println("  Total operations: " + (numThreads * opsPerThread));

        pool.close();
        System.out.println("  ✓ BufferPool concurrency test passed");
    }
}
