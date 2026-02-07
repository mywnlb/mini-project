package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.page.PageId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Undo Page 分段式 LRU 缓存
 *
 * <p>为 Undo 系统提供热点页面缓存，减少 Buffer Pool 查找开销。
 * 采用 <b>Striped LRU</b> 设计，将缓存分为 {@link #NUM_STRIPES} 个独立的段，
 * 每段拥有独立的锁和 LRU 链表，大幅降低并发访问时的锁竞争。</p>
 *
 * <h2>设计目标</h2>
 * <ul>
 *   <li>缓存活跃事务正在使用的 Undo Page</li>
 *   <li>减少 MVCC 版本链遍历时的 Buffer Pool 访问</li>
 *   <li>线程安全，支持高并发读写</li>
 * </ul>
 *
 * <h2>并发设计</h2>
 * <p>旧版使用单个 ReadWriteLock + LinkedHashMap(accessOrder=true)，
 * 存在两个问题：</p>
 * <ol>
 *   <li><b>正确性</b>：LinkedHashMap 的 accessOrder 模式下 {@code get()} 会修改内部链表结构，
 *       属于结构性修改，不能在 ReadLock 下并发执行</li>
 *   <li><b>性能</b>：单锁是热点瓶颈，MVCC 版本链遍历会频繁访问缓存</li>
 * </ol>
 *
 * <p>新版采用 Striped 设计：</p>
 * <ul>
 *   <li>16 个独立段 (Stripe)，每段独立 ReentrantLock + LRU LinkedHashMap</li>
 *   <li>段选择: {@code stripe_index = hash(pageId) & (NUM_STRIPES - 1)}</li>
 *   <li>每段容量: {@code maxPages / NUM_STRIPES}，最少 1 页</li>
 *   <li>统计使用 AtomicLong，无需加锁</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li>MVCC 版本链遍历：频繁读取历史版本</li>
 *   <li>事务回滚：逆序遍历 Undo 记录</li>
 *   <li>Purge：清理已提交事务的 Undo</li>
 * </ul>
 *
 * <h2>核心不变式</h2>
 * <ul>
 *   <li><b>C1 (缓存一致性)</b>：缓存存储的是页面数据的 <b>深拷贝</b>，
 *       返回给调用方的是只读视图，防止外部修改破坏缓存</li>
 *   <li><b>C2 (线程安全)</b>：所有对 LRU LinkedHashMap 的访问（包括 get）
 *       都持有互斥锁，避免 accessOrder 链表并发损坏</li>
 *   <li><b>C3 (缓存失效)</b>：页面释放/Purge 时必须从缓存移除，
 *       防止物理页面被复用后读到过期数据</li>
 * </ul>
 *
 * @author MiniDB
 * @version 2.0
 */
public class UndoPageCache {

    private static final Logger logger = LoggerFactory.getLogger(UndoPageCache.class);

    // ==================== 常量 ====================

    /**
     * 默认最大缓存页面数
     */
    public static final int DEFAULT_MAX_PAGES = 256;

    /**
     * 分段数量 (必须是 2 的幂)
     *
     * <p>16 段在 128 个 Rollback Segment 的负载下提供良好的分散性，
     * 同时避免过多的内存开销（每段需要一个锁和一个 LinkedHashMap 实例）。</p>
     */
    static final int NUM_STRIPES = 16;

    /**
     * 分段掩码 (NUM_STRIPES - 1)，用于快速取模
     */
    private static final int STRIPE_MASK = NUM_STRIPES - 1;

    // ==================== 字段 ====================

    /**
     * 总最大缓存页面数
     */
    private final int maxPages;

    /**
     * 缓存段数组
     */
    private final Stripe[] stripes;

    /**
     * 统计：缓存命中次数
     */
    private final AtomicLong hits;

    /**
     * 统计：缓存未命中次数
     */
    private final AtomicLong misses;

    /**
     * 统计：淘汰次数
     */
    private final AtomicLong evictions;

    // ==================== 构造函数 ====================

    /**
     * 创建 Undo Page 缓存（默认大小）
     */
    public UndoPageCache() {
        this(DEFAULT_MAX_PAGES);
    }

    /**
     * 创建 Undo Page 缓存
     *
     * @param maxPages 最大缓存页面数（总容量，均分到各段）
     */
    public UndoPageCache(int maxPages) {
        if (maxPages <= 0) {
            throw new IllegalArgumentException("maxPages must be positive: " + maxPages);
        }

        this.maxPages = maxPages;
        this.hits = new AtomicLong(0);
        this.misses = new AtomicLong(0);
        this.evictions = new AtomicLong(0);

        // 初始化各段，容量均分（至少 1 页/段）
        this.stripes = new Stripe[NUM_STRIPES];
        int perStripeCapacity = Math.max(1, maxPages / NUM_STRIPES);
        for (int i = 0; i < NUM_STRIPES; i++) {
            stripes[i] = new Stripe(perStripeCapacity, evictions);
        }
    }

    // ==================== 核心操作 ====================

    /**
     * 从缓存获取页面
     *
     * <p>返回缓存数据的只读视图（invariant C1）。
     * 使用互斥锁而非读写锁（invariant C2），
     * 因为 accessOrder LinkedHashMap 的 get() 是结构性修改。</p>
     *
     * @param pageId 页面 ID
     * @return 缓存的 ByteBuffer（只读视图），如果不存在返回 null
     */
    public ByteBuffer get(PageId pageId) {
        if (pageId == null) {
            return null;
        }

        Stripe stripe = stripeFor(pageId);
        stripe.lock.lock();
        try {
            CachedPage cached = stripe.map.get(pageId);
            if (cached != null) {
                hits.incrementAndGet();
                cached.accessCount++;
                // C1: 返回只读视图，防止调用方修改缓存内容
                return cached.buffer.asReadOnlyBuffer();
            } else {
                misses.incrementAndGet();
                return null;
            }
        } finally {
            stripe.lock.unlock();
        }
    }

    /**
     * 将页面放入缓存
     *
     * <p>深拷贝传入的 buffer（invariant C1），防止外部修改影响缓存数据。</p>
     *
     * @param pageId 页面 ID
     * @param buffer 页面数据（会被深拷贝）
     */
    public void put(PageId pageId, ByteBuffer buffer) {
        if (pageId == null || buffer == null) {
            return;
        }

        Stripe stripe = stripeFor(pageId);
        stripe.lock.lock();
        try {
            // C1: 深拷贝 buffer 内容
            ByteBuffer copy = ByteBuffer.allocate(buffer.capacity());
            int savedPos = buffer.position();
            buffer.rewind();
            copy.put(buffer);
            buffer.position(savedPos);
            copy.rewind();

            stripe.map.put(pageId, new CachedPage(copy, System.currentTimeMillis()));
            logger.trace("Cached undo page: {} (stripe {})", pageId, stripeIndexFor(pageId));
        } finally {
            stripe.lock.unlock();
        }
    }

    /**
     * 更新缓存中的页面数据
     *
     * <p>如果页面已在缓存中，更新其数据；否则插入新条目。
     * 主要用于 Undo 写入后更新缓存中的脏页面数据。</p>
     *
     * @param pageId 页面 ID
     * @param buffer 最新的页面数据（会被深拷贝）
     */
    public void update(PageId pageId, ByteBuffer buffer) {
        // update 与 put 语义相同（LinkedHashMap.put 会覆盖已有 key）
        put(pageId, buffer);
    }

    /**
     * 从缓存移除页面（invariant C3）
     *
     * @param pageId 页面 ID
     * @return true 如果成功移除
     */
    public boolean remove(PageId pageId) {
        if (pageId == null) {
            return false;
        }

        Stripe stripe = stripeFor(pageId);
        stripe.lock.lock();
        try {
            CachedPage removed = stripe.map.remove(pageId);
            if (removed != null) {
                logger.trace("Removed undo page from cache: {}", pageId);
                return true;
            }
            return false;
        } finally {
            stripe.lock.unlock();
        }
    }

    /**
     * 批量移除页面（按页号列表）
     *
     * <p>按 stripe 分组后批量移除，减少锁获取次数。
     * 主要用于事务提交释放 INSERT Undo 页面或 Purge 释放 UPDATE Undo 页面时，
     * 同步失效缓存（invariant C3）。</p>
     *
     * @param spaceId 表空间 ID
     * @param pageNos 页号列表
     * @return 移除的页面数量
     */
    public int removeBatch(int spaceId, List<Integer> pageNos) {
        if (pageNos == null || pageNos.isEmpty()) {
            return 0;
        }

        // 按 stripe 分组，减少锁切换
        @SuppressWarnings("unchecked")
        List<PageId>[] groups = new List[NUM_STRIPES];
        for (int pageNo : pageNos) {
            PageId pid = PageId.of(spaceId, pageNo);
            int si = stripeIndexFor(pid);
            if (groups[si] == null) {
                groups[si] = new ArrayList<>();
            }
            groups[si].add(pid);
        }

        int totalRemoved = 0;
        for (int i = 0; i < NUM_STRIPES; i++) {
            if (groups[i] == null) {
                continue;
            }
            Stripe stripe = stripes[i];
            stripe.lock.lock();
            try {
                for (PageId pid : groups[i]) {
                    if (stripe.map.remove(pid) != null) {
                        totalRemoved++;
                    }
                }
            } finally {
                stripe.lock.unlock();
            }
        }

        if (totalRemoved > 0) {
            logger.debug("Batch removed {} undo pages from cache (spaceId={})", totalRemoved, spaceId);
        }
        return totalRemoved;
    }

    /**
     * 批量移除页面（按 spaceId 全量扫描）
     *
     * @param spaceId 表空间 ID
     * @return 移除的页面数量
     */
    public int removeBySpace(int spaceId) {
        int totalRemoved = 0;
        for (Stripe stripe : stripes) {
            stripe.lock.lock();
            try {
                var iterator = stripe.map.entrySet().iterator();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    if (entry.getKey().getSpaceId() == spaceId) {
                        iterator.remove();
                        totalRemoved++;
                    }
                }
            } finally {
                stripe.lock.unlock();
            }
        }
        return totalRemoved;
    }

    /**
     * 清空缓存
     */
    public void clear() {
        for (Stripe stripe : stripes) {
            stripe.lock.lock();
            try {
                stripe.map.clear();
            } finally {
                stripe.lock.unlock();
            }
        }
        logger.debug("Undo page cache cleared");
    }

    /**
     * 检查页面是否在缓存中
     *
     * @param pageId 页面 ID
     * @return true 如果在缓存中
     */
    public boolean contains(PageId pageId) {
        if (pageId == null) {
            return false;
        }

        Stripe stripe = stripeFor(pageId);
        stripe.lock.lock();
        try {
            return stripe.map.containsKey(pageId);
        } finally {
            stripe.lock.unlock();
        }
    }

    // ==================== 统计信息 ====================

    /**
     * 获取当前缓存大小（所有段总计）
     */
    public int size() {
        int total = 0;
        for (Stripe stripe : stripes) {
            stripe.lock.lock();
            try {
                total += stripe.map.size();
            } finally {
                stripe.lock.unlock();
            }
        }
        return total;
    }

    /**
     * 获取最大缓存大小
     */
    public int getMaxPages() {
        return maxPages;
    }

    /**
     * 获取缓存命中次数
     */
    public long getHits() {
        return hits.get();
    }

    /**
     * 获取缓存未命中次数
     */
    public long getMisses() {
        return misses.get();
    }

    /**
     * 获取淘汰次数
     */
    public long getEvictions() {
        return evictions.get();
    }

    /**
     * 获取命中率
     */
    public double getHitRate() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        return total > 0 ? (double) h / total : 0.0;
    }

    /**
     * 重置统计信息
     */
    public void resetStats() {
        hits.set(0);
        misses.set(0);
        evictions.set(0);
    }

    /**
     * 获取缓存统计信息
     */
    public CacheStats getStats() {
        return new CacheStats(size(), maxPages, hits.get(), misses.get(), evictions.get());
    }

    @Override
    public String toString() {
        return String.format("UndoPageCache{size=%d/%d, stripes=%d, hitRate=%.2f%%, hits=%d, misses=%d, evictions=%d}",
                size(), maxPages, NUM_STRIPES, getHitRate() * 100, hits.get(), misses.get(), evictions.get());
    }

    // ==================== 内部方法 ====================

    /**
     * 根据 PageId 选择缓存段
     */
    private Stripe stripeFor(PageId pageId) {
        return stripes[stripeIndexFor(pageId)];
    }

    /**
     * 计算 PageId 对应的段索引
     *
     * <p>使用 Wang/Jenkins 风格的散列扰动，将 pageId 的 hashCode 分散到各段。
     * 对于 Undo 场景，pageNo 通常是连续分配的，简单取模会导致同一段过热，
     * 因此需要额外的散列扰动。</p>
     */
    private static int stripeIndexFor(PageId pageId) {
        int h = pageId.hashCode();
        // 散列扰动：将高位信息混入低位
        h ^= (h >>> 16);
        return h & STRIPE_MASK;
    }

    // ==================== 内部类 ====================

    /**
     * 缓存段
     *
     * <p>每段独立持有一把互斥锁和一个 LRU LinkedHashMap。
     * 段间完全独立，不存在跨段锁依赖。</p>
     */
    private static class Stripe {
        final ReentrantLock lock;
        final LinkedHashMap<PageId, CachedPage> map;

        Stripe(int capacity, AtomicLong evictionCounter) {
            this.lock = new ReentrantLock();
            this.map = new LinkedHashMap<>(capacity, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<PageId, CachedPage> eldest) {
                    boolean shouldRemove = size() > capacity;
                    if (shouldRemove) {
                        evictionCounter.incrementAndGet();
                        logger.trace("Evicting undo page from cache stripe: {}", eldest.getKey());
                    }
                    return shouldRemove;
                }
            };
        }
    }

    /**
     * 缓存的页面条目
     */
    private static class CachedPage {
        final ByteBuffer buffer;
        final long cachedTime;
        int accessCount;

        CachedPage(ByteBuffer buffer, long cachedTime) {
            this.buffer = buffer;
            this.cachedTime = cachedTime;
            this.accessCount = 1;
        }
    }

    /**
     * 缓存统计信息
     */
    public static class CacheStats {
        public final int currentSize;
        public final int maxSize;
        public final long hits;
        public final long misses;
        public final long evictions;
        public final double hitRate;

        public CacheStats(int currentSize, int maxSize, long hits, long misses, long evictions) {
            this.currentSize = currentSize;
            this.maxSize = maxSize;
            this.hits = hits;
            this.misses = misses;
            this.evictions = evictions;
            long total = hits + misses;
            this.hitRate = total > 0 ? (double) hits / total : 0.0;
        }

        @Override
        public String toString() {
            return String.format("CacheStats{size=%d/%d, hitRate=%.2f%%, hits=%d, misses=%d, evictions=%d}",
                    currentSize, maxSize, hitRate * 100, hits, misses, evictions);
        }
    }
}
