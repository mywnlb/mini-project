package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.page.PageId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Undo Page 池 - 预分配和复用 Undo 页面
 *
 * <p>为每个 Rollback Segment 维护两层页面池：
 * <ul>
 *   <li><b>freshPages</b>: 从未写过的页面，可直接分配</li>
 *   <li><b>reusablePages</b>: Purge 后变空的页面，可重用</li>
 * </ul>
 * </p>
 *
 * <h2>设计目标</h2>
 * <ul>
 *   <li>将 Undo 页面分配从 O(log n) 降低到 O(1)</li>
 *   <li>减少 Extent 查询和元数据更新开销</li>
 *   <li>支持页面复用，提升并发性能</li>
 * </ul>
 *
 * <h2>分配流程</h2>
 * <pre>
 * allocateUndoPage() {
 *     if (!freshPages.isEmpty()) return freshPages.poll();
 *     if (!reusablePages.isEmpty()) return reusablePages.poll();
 *     return allocateNewPageFromExtent();  // 最后才扩展
 * }
 * </pre>
 *
 * <h2>回收流程</h2>
 * <pre>
 * releaseUndoPage(pageId) {
 *     // Purge 线程调用，页面已清空
 *     reusablePages.offer(pageId);
 * }
 * </pre>
 *
 * <h2>关键约束</h2>
 * <ul>
 *   <li><b>不做真实释放</b>: 只做复用，不调用 deallocatePage</li>
 *   <li><b>统计分开</b>: freshPages 和 reusablePages 统计分开</li>
 *   <li><b>无锁队列</b>: 使用 ConcurrentLinkedQueue 保证并发安全</li>
 *   <li><b>异步补充</b>: 补充操作在后台线程执行，不阻塞分配</li>
 * </ul>
 *
 * <h2>不变量维护</h2>
 * <ul>
 *   <li><b>U4</b>: Undo 页面分配 - 预分配仍遵循 32 页 + Extent 规则</li>
 *   <li><b>U7</b>: Undo 空间回收 - 池中的页面可被复用</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class UndoPagePool {

    private static final Logger logger = LoggerFactory.getLogger(UndoPagePool.class);

    // ==================== 常量 ====================

    /**
     * 默认初始池大小（每个 Rollback Segment）
     */
    private static final int DEFAULT_POOL_SIZE = 10;

    /**
     * 最小阈值：当空闲页面 < minThreshold 时，异步补充
     */
    private static final int MIN_THRESHOLD = 5;

    /**
     * 最大阈值：当空闲页面 > maxThreshold 时，释放多余页面
     */
    private static final int MAX_THRESHOLD = 20;

    // ==================== 字段 ====================

    /**
     * Rollback Segment ID
     */
    private final int rsegId;

    /**
     * 从未写过的页面队列（无锁）
     *
     * <p>这些页面是预先从 Extent 分配的，从未被写入过 Undo 记录。
     * 分配时优先从此队列取。</p>
     */
    private final ConcurrentLinkedQueue<PageId> freshPages;

    /**
     * Purge 后变空的页面队列（无锁）
     *
     * <p>这些页面曾经存储过 Undo 记录，但已被 Purge 清空。
     * 可以被后续事务重用。</p>
     */
    private final ConcurrentLinkedQueue<PageId> reusablePages;

    /**
     * 当前 freshPages 中的页面数
     */
    private final AtomicInteger freshPageCount;

    /**
     * 当前 reusablePages 中的页面数
     */
    private final AtomicInteger reusablePageCount;

    /**
     * 总分配的页面数（用于统计）
     */
    private final AtomicInteger totalAllocated;

    /**
     * 总复用的页面数（用于统计）
     */
    private final AtomicInteger totalReused;

    // ==================== 构造函数 ====================

    /**
     * 创建 Undo Page 池
     *
     * @param rsegId Rollback Segment ID
     */
    public UndoPagePool(int rsegId) {
        this.rsegId = rsegId;
        this.freshPages = new ConcurrentLinkedQueue<>();
        this.reusablePages = new ConcurrentLinkedQueue<>();
        this.freshPageCount = new AtomicInteger(0);
        this.reusablePageCount = new AtomicInteger(0);
        this.totalAllocated = new AtomicInteger(0);
        this.totalReused = new AtomicInteger(0);

        logger.debug("UndoPagePool created for rsegId={}", rsegId);
    }

    // ==================== 页面分配 ====================

    /**
     * 从池中分配一个 Undo 页面
     *
     * <p>优先级：
     * <ol>
     *   <li>freshPages（从未写过）</li>
     *   <li>reusablePages（已清空，可复用）</li>
     *   <li>null（需要从 Extent 分配新页）</li>
     * </ol>
     * </p>
     *
     * @return 页面 ID，如果池为空返回 null
     */
    public PageId allocateUndoPage() {
        // 优先从 freshPages 取
        PageId pageId = freshPages.poll();
        if (pageId != null) {
            freshPageCount.decrementAndGet();
            logger.trace("Allocated fresh page from pool: pageId={}, rsegId={}", pageId, rsegId);
            return pageId;
        }

        // 其次从 reusablePages 取
        pageId = reusablePages.poll();
        if (pageId != null) {
            reusablePageCount.decrementAndGet();
            totalReused.incrementAndGet();
            logger.trace("Allocated reusable page from pool: pageId={}, rsegId={}", pageId, rsegId);
            return pageId;
        }

        // 池为空，需要从 Extent 分配新页
        logger.trace("Pool empty for rsegId={}, need to allocate from Extent", rsegId);
        return null;
    }

    // ==================== 页面回收 ====================

    /**
     * 将页面放回 freshPages 池（预分配的新页）
     *
     * <p>此方法由后台 refiller 线程调用，用于补充池中的页面。</p>
     *
     * @param pageId 页面 ID
     */
    public void addFreshPage(PageId pageId) {
        if (pageId == null) {
            throw new IllegalArgumentException("pageId cannot be null");
        }

        freshPages.offer(pageId);
        freshPageCount.incrementAndGet();
        totalAllocated.incrementAndGet();

        logger.trace("Added fresh page to pool: pageId={}, rsegId={}, count={}",
                pageId, rsegId, freshPageCount.get());
    }

    /**
     * 将页面放回 reusablePages 池（Purge 后变空的页面）
     *
     * <p>此方法由 Purge 线程调用，表示该页面已清空，可被后续事务重用。</p>
     *
     * @param pageId 页面 ID
     */
    public void addReusablePage(PageId pageId) {
        if (pageId == null) {
            throw new IllegalArgumentException("pageId cannot be null");
        }

        reusablePages.offer(pageId);
        reusablePageCount.incrementAndGet();

        logger.trace("Added reusable page to pool: pageId={}, rsegId={}, count={}",
                pageId, rsegId, reusablePageCount.get());
    }

    // ==================== 池状态查询 ====================

    /**
     * 获取 freshPages 中的页面数
     *
     * @return 页面数
     */
    public int getFreshPageCount() {
        return freshPageCount.get();
    }

    /**
     * 获取 reusablePages 中的页面数
     *
     * @return 页面数
     */
    public int getReusablePageCount() {
        return reusablePageCount.get();
    }

    /**
     * 获取池中的总页面数
     *
     * @return 页面数
     */
    public int getTotalPageCount() {
        return freshPageCount.get() + reusablePageCount.get();
    }

    /**
     * 检查是否需要补充 freshPages
     *
     * <p>当 freshPages 数量 < MIN_THRESHOLD 时返回 true</p>
     *
     * @return 是否需要补充
     */
    public boolean needsRefill() {
        return freshPageCount.get() < MIN_THRESHOLD;
    }

    /**
     * 检查是否需要释放多余页面
     *
     * <p>当 freshPages 数量 > MAX_THRESHOLD 时返回 true</p>
     *
     * @return 是否需要释放
     */
    public boolean hasExcessPages() {
        return freshPageCount.get() > MAX_THRESHOLD;
    }

    /**
     * 获取需要补充的页面数
     *
     * @return 页面数
     */
    public int getRefillCount() {
        int current = freshPageCount.get();
        if (current < MIN_THRESHOLD) {
            return MAX_THRESHOLD - current;
        }
        return 0;
    }

    // ==================== 统计信息 ====================

    /**
     * 获取总分配的页面数
     *
     * @return 页面数
     */
    public int getTotalAllocated() {
        return totalAllocated.get();
    }

    /**
     * 获取总复用的页面数
     *
     * @return 页面数
     */
    public int getTotalReused() {
        return totalReused.get();
    }

    /**
     * 获取池命中率（复用页面 / 总分配）
     *
     * @return 命中率 (0.0 - 1.0)
     */
    public double getHitRate() {
        int total = totalAllocated.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) totalReused.get() / total;
    }

    /**
     * 获取池的统计信息
     *
     * @return 统计字符串
     */
    public String getStats() {
        return String.format(
                "UndoPagePool[rsegId=%d]: fresh=%d, reusable=%d, total=%d, allocated=%d, reused=%d, hitRate=%.2f%%",
                rsegId,
                freshPageCount.get(),
                reusablePageCount.get(),
                getTotalPageCount(),
                totalAllocated.get(),
                totalReused.get(),
                getHitRate() * 100
        );
    }

    // ==================== 清空池 ====================

    /**
     * 清空池中的所有页面（用于关闭或测试）
     *
     * @return 清空的页面数
     */
    public int clear() {
        int count = freshPages.size() + reusablePages.size();
        freshPages.clear();
        reusablePages.clear();
        freshPageCount.set(0);
        reusablePageCount.set(0);

        logger.debug("Cleared UndoPagePool for rsegId={}, cleared {} pages", rsegId, count);
        return count;
    }
}
