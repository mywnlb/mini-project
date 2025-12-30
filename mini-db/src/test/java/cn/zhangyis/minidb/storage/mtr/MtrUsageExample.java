package cn.zhangyis.minidb.storage.mtr;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.common.exception.PageNotManagedByMtrException;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;

import java.io.IOException;
import java.nio.file.Path;

/**
 * MTR 使用示例集合
 *
 * <p>展示 MiniTransaction 在各种实际场景中的使用方式。
 * 这些示例代码可作为开发时的参考。</p>
 *
 * @author MiniDB
 */
public class MtrUsageExample {

    private BufferPool bufferPool;

    // ==================== 示例 1: 基本的页面读取和修改 ====================

    /**
     * 示例：读取一个页面，修改并提交
     */
    public void example1_basicReadWrite(PageId pageId, int offset, int value) throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 获取页面
            Page page = mtr.getPage(pageId);

            // 修改页面
            page.putInt(offset, value);

            // 标记为脏页（重要！）
            mtr.markDirty(page);

            // 提交
            mtr.commit();
        }
        // MTR 自动关闭，页面自动 unpin
    }

    // ==================== 示例 2: 创建新页面 ====================

    /**
     * 示例：分配新页面并初始化
     */
    public PageId example2_createNewPage(int spaceId) throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 分配新页面
            Page newPage = mtr.newPage(spaceId);

            // 初始化页面内容
            newPage.putInt(0, 0x12345678);  // Magic number
            newPage.putInt(4, 1);           // Version

            // newPage 自动被标记为脏页，无需手动 markDirty

            // 提交
            mtr.commit();

            return newPage.getPageId();
        }
    }

    // ==================== 示例 3: 多页面原子操作 ====================

    /**
     * 示例：B+Tree 插入 - 需要同时修改叶子节点和父节点
     */
    public void example3_atomicMultiPageUpdate(
            PageId leafPageId,
            PageId parentPageId,
            byte[] recordData) throws Exception {

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 获取叶子节点和父节点
            Page leafPage = mtr.getPage(leafPageId);
            Page parentPage = mtr.getPage(parentPageId);

            // 在叶子节点插入记录
            int recordOffset = findInsertPosition(leafPage, recordData);
            leafPage.putBytes(recordOffset, recordData);
            mtr.markDirty(leafPage);

            // 更新父节点的统计信息
            int currentCount = parentPage.getInt(0);
            parentPage.putInt(0, currentCount + 1);
            mtr.markDirty(parentPage);

            // 原子提交：两个页面的修改要么都成功，要么都失败
            mtr.commit();
        }
    }

    // ==================== 示例 4: 链表操作 ====================

    /**
     * 示例：在双向链表中插入新页面（如 B+Tree 叶子层）
     */
    public void example4_insertIntoLinkedList(
            PageId prevPageId,
            PageId nextPageId,
            int spaceId) throws Exception {

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            // 获取前后页面
            Page prevPage = mtr.getPage(prevPageId);
            Page nextPage = mtr.getPage(nextPageId);

            // 创建新页面
            Page newPage = mtr.newPage(spaceId);

            // 更新链表指针（原子操作）
            int newPageNo = newPage.getPageNo();
            int prevPageNo = prevPage.getPageNo();
            int nextPageNo = nextPage.getPageNo();

            // prev -> new -> next
            prevPage.setNextPage(newPageNo);
            newPage.setPrevPage(prevPageNo);
            newPage.setNextPage(nextPageNo);
            nextPage.setPrevPage(newPageNo);

            // 标记所有修改的页面
            mtr.markDirty(prevPage);
            mtr.markDirty(newPage);
            mtr.markDirty(nextPage);

            // 原子提交
            mtr.commit();
        }
    }

    // ==================== 示例 5: 条件修改和回滚 ====================

    /**
     * 示例：根据条件决定是否修改，失败时回滚
     */
    public boolean example5_conditionalUpdate(
            PageId pageId,
            int expectedVersion,
            int newValue) throws Exception {

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.getPage(pageId);

            // 检查版本号（乐观锁）
            int currentVersion = page.getInt(4);
            if (currentVersion != expectedVersion) {
                // 版本不匹配，回滚
                mtr.rollback();
                return false;
            }

            // 版本匹配，执行修改
            page.putInt(8, newValue);
            page.putInt(4, currentVersion + 1);  // 增加版本号

            mtr.markDirty(page);
            mtr.commit();

            return true;
        }
    }

    // ==================== 示例 6: 批量操作 ====================

    /**
     * 示例：在一个页面中批量插入记录
     */
    public void example6_batchInsert(
            PageId pageId,
            byte[][] records) throws Exception {

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.getPage(pageId);

            int offset = 100;  // 起始偏移
            for (byte[] record : records) {
                page.putBytes(offset, record);
                offset += record.length;
            }

            mtr.markDirty(page);
            mtr.commit();
        }
    }

    // ==================== 示例 7: 错误处理和异常安全 ====================

    /**
     * 示例：异常发生时自动回滚
     */
    public void example7_exceptionSafety(PageId pageId)  {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.getPage(pageId);

            // 执行可能失败的操作
            if (!validatePageStructure(page)) {
                throw new IllegalStateException("Invalid page structure");
            }

            // 修改页面
            page.putInt(0, 999);
            mtr.markDirty(page);

            mtr.commit();

        } catch (MiniDbException e) {
            // MTR 自动回滚，无需手动清理
            System.err.println("Operation failed: " + e.getMessage());
        }
    }

    // ==================== 示例 8: 在虚拟线程中使用 ====================

    /**
     * 示例：在虚拟线程中并发操作不同页面
     */
    public void example8_virtualThreads(PageId[] pageIds) {
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

        for (PageId pageId : pageIds) {
            executor.submit(() -> {
                try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                    Page page = mtr.getPage(pageId);

                    // 每个虚拟线程独立操作自己的页面
                    page.putLong(0, System.currentTimeMillis());
                    mtr.markDirty(page);

                    mtr.commit();
                } catch (MiniDbException  e) {
                    e.printStackTrace();
                }
            });
        }

        executor.close();
    }

    // ==================== 示例 9: 页面扫描和统计 ====================

    /**
     * 示例：扫描多个页面并收集统计信息（只读操作）
     */
    public int example9_scanPages(PageId[] pageIds) throws Exception {
        int totalRecords = 0;

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            for (PageId pageId : pageIds) {
                Page page = mtr.getPage(pageId);

                // 读取记录数（假设存储在偏移 0）
                int recordCount = page.getInt(0);
                totalRecords += recordCount;
            }

            // 只读操作，无需 markDirty
            mtr.commit();
        }

        return totalRecords;
    }

    // ==================== 示例 10: 嵌套操作模拟 ====================

    /**
     * 示例：模拟嵌套操作（当前不支持真正的嵌套 MTR）
     */
    public void example10_nestedOperations(PageId pageId1, PageId pageId2) throws Exception {
        // 外层 MTR
        try (MiniTransaction outerMtr = new MiniTransaction(bufferPool)) {
            Page page1 = outerMtr.getPage(pageId1);
            page1.putInt(0, 100);
            outerMtr.markDirty(page1);

            // 内层操作（使用新的 MTR）
            innerOperation(pageId2);

            // 继续外层操作
            page1.putInt(4, 200);
            outerMtr.commit();
        }
    }

    private void innerOperation(PageId pageId) throws Exception {
        try (MiniTransaction innerMtr = new MiniTransaction(bufferPool)) {
            Page page = innerMtr.getPage(pageId);
            page.putInt(0, 999);
            innerMtr.markDirty(page);
            innerMtr.commit();
        }
    }

    // ==================== 辅助方法（示例） ====================

    private int findInsertPosition(Page page, byte[] recordData) {
        // 简化实现：返回固定偏移
        return 100;
    }

    private boolean validatePageStructure(Page page) {
        // 简化实现：检查 magic number
        return page.getInt(0) == 0x12345678;
    }

    // ==================== 示例 11: MTR 统计和监控 ====================

    /**
     * 示例：使用 MTR 统计信息进行监控
     */
    public void example11_monitoring() throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            long startTime = System.nanoTime();

            // 执行操作
            Page page1 = mtr.newPage(0);
            Page page2 = mtr.newPage(0);
            Page page3 = mtr.getPage(PageId.of(0, 0));

            mtr.markDirty(page1);
            mtr.markDirty(page2);

            // 获取统计信息
            System.out.println("MTR State: " + mtr.getState());
            System.out.println("Pages held: " + mtr.getPageCount());
            System.out.println("Duration: " + mtr.getDuration() / 1000 + " μs");

            mtr.commit();

            long endTime = System.nanoTime();
            System.out.println("Total time: " + (endTime - startTime) / 1000 + " μs");
        }
    }

    // ==================== 示例 12: 重试机制 ====================

    /**
     * 示例：MTR 失败时重试
     */
    public void example12_retryMechanism(PageId pageId, int maxRetries) throws MiniDbException {
        int retries = 0;
        boolean success = false;

        while (!success && retries < maxRetries) {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                Page page = mtr.getPage(pageId);

                // 执行操作
                page.putInt(0, retries);
                mtr.markDirty(page);

                mtr.commit();
                success = true;

            } catch (MiniDbException e) {
                retries++;
                System.err.println("Attempt " + retries + " failed: " + e.getMessage());

                if (retries >= maxRetries) {
                    System.err.println("Max retries reached, giving up");
                }

                // 短暂延迟后重试
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    // ==================== Main 方法（演示） ====================

    /**
     * 演示 MTR 的基本使用
     */
    public static void main(String[] args) throws Exception {
        // 创建临时数据库
        DiskManager diskManager = new DiskManager(Path.of("example.db"));
        BufferPool bufferPool = new BufferPool(100, diskManager);

        MtrUsageExample example = new MtrUsageExample();
        example.bufferPool = bufferPool;

        System.out.println("=== MTR 使用示例 ===\n");

        // 示例 1: 创建新页面
        System.out.println("1. 创建新页面...");
        PageId pageId1 = example.example2_createNewPage(0);
        System.out.println("   创建成功: " + pageId1 + "\n");

        // 示例 2: 修改页面
        System.out.println("2. 修改页面...");
        example.example1_basicReadWrite(pageId1, 100, 12345);
        System.out.println("   修改成功\n");

        // 示例 3: 条件更新
        System.out.println("3. 条件更新（版本检查）...");
        boolean updated = example.example5_conditionalUpdate(pageId1, 1, 99999);
        System.out.println("   更新结果: " + updated + "\n");

        // 示例 4: 监控统计
        System.out.println("4. MTR 统计信息:");
        example.example11_monitoring();

        // 清理
        bufferPool.close();
        diskManager.close();

        System.out.println("\n=== 示例完成 ===");
    }
}
