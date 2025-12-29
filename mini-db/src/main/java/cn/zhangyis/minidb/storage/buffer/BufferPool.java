package cn.zhangyis.minidb.storage.buffer;

import com.minidb.storage.StorageConstants;
import com.minidb.storage.disk.DiskManager;
import com.minidb.storage.page.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Buffer Pool 缓冲池
 * 
 * <p>Buffer Pool 是 InnoDB 存储引擎最核心的内存组件，负责缓存数据页和索引页。
 * 通过减少磁盘 I/O 来显著提升数据库性能。</p>
 * 
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>页面缓存</b>: 将磁盘页面缓存在内存中</li>
 *   <li><b>LRU 淘汰</b>: 使用改进的 LRU 算法管理页面</li>
 *   <li><b>脏页管理</b>: 跟踪修改过的页面，支持延迟刷盘</li>
 *   <li><b>并发控制</b>: 支持多线程并发访问</li>
 * </ul>
 * 
 * <h2>核心数据结构</h2>
 * <pre>
 * +------------------------------------------------------------------+
 * |                        Buffer Pool                               |
 * +------------------------------------------------------------------+
 * |  frames[]        - 页帧数组，存储实际页面数据                      |
 * |  pageHash        - PageId → frameIndex 的哈希表，O(1) 查找        |
 * |  lruList         - LRU 链表 (Young-Old 分区)                     |
 * |  freeList        - 空闲帧链表                                    |
 * |  flushList       - 脏页链表 (按 LSN 排序)                        |
 * +------------------------------------------------------------------+
 * </pre>
 * 
 * <h2>页面获取流程 (getPage)</h2>
 * <pre>
 *                    ┌─────────────┐
 *                    │  getPage()  │
 *                    └──────┬──────┘
 *                           ▼
 *              ┌────────────────────────┐
 *              │ Page Hash 中查找 PageId │
 *              └────────────┬───────────┘
 *                    ┌──────┴──────┐
 *                    ▼             ▼
 *               [命中]          [未命中]
 *                 │               │
 *                 ▼               ▼
 *          ┌───────────┐  ┌──────────────┐
 *          │ pin++     │  │ 从 FreeList  │
 *          │ LRU access│  │ 获取空闲帧    │
 *          │ 返回 Frame│  └──────┬───────┘
 *          └───────────┘         │
 *                         ┌──────┴──────┐
 *                         ▼             ▼
 *                    [有空闲帧]    [无空闲帧]
 *                         │             │
 *                         │             ▼
 *                         │    ┌────────────────┐
 *                         │    │ LRU 淘汰       │
 *                         │    │ (可能需刷盘)   │
 *                         │    └───────┬────────┘
 *                         ▼            ▼
 *                    ┌─────────────────────────┐
 *                    │ 从磁盘读取页面到帧      │
 *                    │ 加入 PageHash 和 LRU   │
 *                    │ pin++, 返回 Frame      │
 *                    └─────────────────────────┘
 * </pre>
 * 
 * <h2>并发控制策略</h2>
 * <ul>
 *   <li><b>poolLock</b>: 读写锁保护 pageHash 和链表结构</li>
 *   <li><b>frame.pageLock</b>: 每个帧独立的读写锁保护页面内容</li>
 *   <li><b>pin/unpin</b>: 原子计数器防止正在使用的页面被淘汰</li>
 * </ul>
 * 
 * <h2>InnoDB 对应</h2>
 * <p>对应 InnoDB 的 buf_pool_t 结构和 buf0buf.cc 中的实现。</p>
 * 
 * @author MiniDB
 * @version 1.0
 * @see BufferFrame
 * @see LRUList
 * @see FlushList
 */
public class BufferPool {
    
    // ==================== 页面获取模式 ====================
    
    /**
     * 页面获取模式
     */
    public enum FetchMode {
        /**
         * 读取已存在的页面
         * <p>从磁盘加载页面数据。如果页面不存在会抛出异常。</p>
         */
        READ_EXISTING,
        
        /**
         * 创建新页面
         * <p>不从磁盘读取，直接初始化一个空页面。</p>
         */
        NEW_PAGE
    }
    
    // ==================== 核心字段 ====================
    
    /**
     * Buffer Pool 大小 (页数)
     */
    private final int poolSize;
    
    /**
     * 磁盘管理器
     * <p>用于页面的物理读写。</p>
     */
    private final DiskManager diskManager;
    
    /**
     * 页帧数组
     * 
     * <p>固定大小的数组，每个元素是一个 BufferFrame。
     * 帧的索引在整个生命周期中不变。</p>
     */
    private final BufferFrame[] frames;
    
    /**
     * 页面哈希表: PageId → frame index
     * 
     * <p>用于 O(1) 时间查找页面是否在 Buffer Pool 中。
     * 使用 ConcurrentHashMap 支持并发读取。</p>
     */
    private final Map<PageId, Integer> pageHash;
    
    /**
     * LRU 链表
     * 
     * <p>实现改进的 LRU 算法 (Young-Old 分区)。
     * 管理页面的淘汰顺序。</p>
     */
    private final LRUList lruList;
    
    /**
     * 空闲帧链表
     * 
     * <p>存储未被使用的帧索引。
     * 新页面优先从这里获取帧。</p>
     */
    private final FreeList freeList;
    
    /**
     * 脏页链表
     * 
     * <p>按 oldest_modification LSN 排序存储脏页。
     * 用于 Checkpoint 和崩溃恢复。</p>
     */
    private final FlushList flushList;
    
    /**
     * Buffer Pool 全局锁
     * 
     * <p>读锁: 访问现有页面
     * 写锁: 加载新页面、淘汰页面</p>
     */
    private final ReentrantReadWriteLock poolLock;
    
    // ==================== 统计计数器 ====================
    
    /** 缓存命中次数 */
    private final AtomicLong hitCount = new AtomicLong(0);
    
    /** 缓存未命中次数 */
    private final AtomicLong missCount = new AtomicLong(0);
    
    /** 磁盘读取次数 */
    private final AtomicLong readCount = new AtomicLong(0);
    
    /** 磁盘写入次数 */
    private final AtomicLong writeCount = new AtomicLong(0);
    
    // ==================== 构造函数 ====================
    
    /**
     * 创建 Buffer Pool
     * 
     * <h3>初始化步骤</h3>
     * <ol>
     *   <li>分配页帧数组</li>
     *   <li>初始化 Page Hash (ConcurrentHashMap)</li>
     *   <li>初始化 Free List 并添加所有帧</li>
     *   <li>初始化 LRU List 和 Flush List</li>
     * </ol>
     * 
     * @param poolSizePages Buffer Pool 大小 (页数)
     * @param diskManager   磁盘管理器
     */
    public BufferPool(int poolSizePages, DiskManager diskManager) {
        this.poolSize = poolSizePages;
        this.diskManager = diskManager;
        
        // 分配页帧数组
        this.frames = new BufferFrame[poolSize];
        
        // 初始化数据结构
        this.pageHash = new ConcurrentHashMap<>();
        this.freeList = new FreeList();
        this.flushList = new FlushList();
        this.poolLock = new ReentrantReadWriteLock();
        
        // 初始化所有帧并加入 Free List
        for (int i = 0; i < poolSize; i++) {
            frames[i] = new BufferFrame(i);
            freeList.add(i);
        }
        
        // 初始化 LRU List (需要帧数组引用)
        this.lruList = new LRUList(frames);
    }
    
    // ==================== 核心方法: 页面获取 ====================
    
    /**
     * 获取页面 (核心方法)
     * 
     * <p>这是 Buffer Pool 最重要的方法，实现了页面的缓存访问。</p>
     * 
     * <h3>执行流程</h3>
     * <ol>
     *   <li><b>Fast Path (读锁)</b>:
     *       <ul>
     *         <li>在 Page Hash 中查找 PageId</li>
     *         <li>如果命中: pin++, 更新 LRU, 返回帧</li>
     *       </ul>
     *   </li>
     *   <li><b>Slow Path (写锁)</b>:
     *       <ul>
     *         <li>Double Check (其他线程可能已加载)</li>
     *         <li>获取空闲帧 (可能触发淘汰)</li>
     *         <li>从磁盘读取页面数据</li>
     *         <li>加入 Page Hash 和 LRU</li>
     *         <li>pin++, 返回帧</li>
     *       </ul>
     *   </li>
     * </ol>
     * 
     * <h3>并发说明</h3>
     * <p>返回的 BufferFrame 已经被 pin，调用者必须在使用完后调用 unpinPage()。</p>
     * 
     * @param pageId 页面标识
     * @param mode   获取模式 (READ_EXISTING 或 NEW_PAGE)
     * @return BufferFrame (已 pin，使用后必须 unpin)
     * @throws IOException 如果页面不存在或磁盘读取失败
     */
    public BufferFrame getPage(PageId pageId, FetchMode mode) throws IOException {
        // ===== Fast Path: 读锁检查 Page Hash =====
        poolLock.readLock().lock();
        try {
            Integer frameIndex = pageHash.get(pageId);
            if (frameIndex != null) {
                // 缓存命中
                hitCount.incrementAndGet();
                BufferFrame frame = frames[frameIndex];
                
                // 增加 pin count (防止被淘汰)
                frame.pin();
                
                // 更新 LRU (可能从 Old 区晋升到 Young 区)
                lruList.access(frameIndex, frame);
                
                return frame;
            }
        } finally {
            poolLock.readLock().unlock();
        }
        
        // ===== Slow Path: 缓存未命中，需要从磁盘加载 =====
        missCount.incrementAndGet();
        return loadPage(pageId, mode);
    }
    
    /**
     * 从磁盘加载页面 (Slow Path)
     * 
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>获取写锁</li>
     *   <li>Double Check: 其他线程可能已经加载了该页面</li>
     *   <li>获取空闲帧 (可能触发 LRU 淘汰)</li>
     *   <li>根据 mode 加载页面数据:
     *       <ul>
     *         <li>READ_EXISTING: 从磁盘读取</li>
     *         <li>NEW_PAGE: 初始化空页面</li>
     *       </ul>
     *   </li>
     *   <li>设置帧状态 (pageId, pin, oldBlock)</li>
     *   <li>加入 Page Hash 和 LRU Old 区</li>
     *   <li>返回帧</li>
     * </ol>
     * 
     * @param pageId 页面标识
     * @param mode   获取模式
     * @return BufferFrame
     * @throws IOException 如果加载失败
     */
    private BufferFrame loadPage(PageId pageId, FetchMode mode) throws IOException {
        poolLock.writeLock().lock();
        try {
            // ===== Step 1: Double Check =====
            // 在等待写锁期间，其他线程可能已经加载了该页面
            Integer existing = pageHash.get(pageId);
            if (existing != null) {
                BufferFrame frame = frames[existing];
                frame.pin();
                return frame;
            }
            
            // ===== Step 2: 获取空闲帧 =====
            int frameIndex = getFreeFrame();
            BufferFrame frame = frames[frameIndex];
            
            // ===== Step 3: 加载页面数据 =====
            if (mode == FetchMode.READ_EXISTING) {
                // 从磁盘读取
                ByteBuffer data = diskManager.readPage(pageId);
                readCount.incrementAndGet();
                
                // 设置正确的字节序
                data.order(ByteOrder.LITTLE_ENDIAN);
                
                // 根据页类型创建对应的 Page 对象
                PageType type = PageType.fromValue(data.getShort(Page.FIL_PAGE_TYPE) & 0xFFFF);
                Page page;
                if (type == PageType.FIL_PAGE_INDEX) {
                    page = new IndexPage(pageId, data);
                } else {
                    page = new Page(pageId, data);
                }
                frame.setPage(page);
            } else {
                // NEW_PAGE: 创建新的空白 IndexPage
                frame.setPage(new IndexPage(pageId));
            }
            
            // ===== Step 4: 更新帧状态 =====
            frame.setPageId(pageId);
            frame.pin();                           // pin count = 1
            frame.setDirty(false);                 // 刚加载，未修改
            frame.setOldBlock(true);               // 新页面进入 Old 区
            frame.setAccessTime(System.currentTimeMillis());
            
            // ===== Step 5: 加入数据结构 =====
            pageHash.put(pageId, frameIndex);      // 加入 Page Hash
            lruList.addToOld(frameIndex);          // 加入 LRU Old 区
            
            return frame;
        } finally {
            poolLock.writeLock().unlock();
        }
    }
    
    /**
     * 获取空闲帧
     * 
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>尝试从 Free List 获取</li>
     *   <li>如果 Free List 为空，执行 LRU 淘汰</li>
     * </ol>
     * 
     * @return 空闲帧索引
     * @throws IOException 如果无法获取空闲帧
     */
    private int getFreeFrame() throws IOException {
        // 优先从 Free List 获取
        Integer freeIndex = freeList.poll();
        if (freeIndex != null) {
            return freeIndex;
        }
        
        // Free List 为空，需要淘汰页面
        return evictPage();
    }
    
    /**
     * 淘汰页面以获取空闲帧
     * 
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>从 LRU 尾部获取淘汰候选</li>
     *   <li>检查是否可淘汰 (pinCount == 0)</li>
     *   <li>如果是脏页，先刷盘</li>
     *   <li>从 Page Hash、LRU List、Flush List 移除</li>
     *   <li>重置帧状态</li>
     *   <li>返回帧索引</li>
     * </ol>
     * 
     * <h3>淘汰策略</h3>
     * <p>优先从 LRU Old 区尾部淘汰，这些是最久未使用的冷页面。</p>
     * 
     * @return 被淘汰帧的索引
     * @throws IOException 如果所有页面都被 pin 或刷盘失败
     */
    private int evictPage() throws IOException {
        // 尝试最多 poolSize 次
        for (int attempt = 0; attempt < poolSize; attempt++) {
            // 获取淘汰候选 (LRU 尾部)
            Integer victimIndex = lruList.getVictim();
            if (victimIndex == null) {
                throw new IOException("Buffer pool exhausted: no victim found");
            }
            
            BufferFrame victim = frames[victimIndex];
            
            // 检查是否可以淘汰 (未被 pin)
            if (victim.isPinned()) {
                // 被 pin 中，跳过尝试下一个
                // TODO: 实际应该遍历 LRU 找到可淘汰的页面
                continue;
            }
            
            // 如果是脏页，先刷盘
            if (victim.isDirty()) {
                flushPageInternal(victimIndex);
            }
            
            // 从各数据结构中移除
            PageId oldPageId = victim.getPageId();
            if (oldPageId != null) {
                pageHash.remove(oldPageId);
            }
            lruList.remove(victimIndex);
            flushList.remove(victimIndex);
            
            // 重置帧状态
            victim.reset();
            
            return victimIndex;
        }
        
        throw new IOException("Buffer pool exhausted: all pages are pinned");
    }
    
    // ==================== 页面释放 ====================
    
    /**
     * 释放页面 (unpin)
     * 
     * <p><b>重要</b>: 每次 getPage() 后必须调用 unpinPage()，
     * 否则页面永远不会被淘汰，导致 Buffer Pool 耗尽。</p>
     * 
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>在 Page Hash 中查找帧</li>
     *   <li>减少 pin count</li>
     *   <li>如果标记为脏且之前不是脏页，加入 Flush List</li>
     * </ol>
     * 
     * @param pageId  页面标识
     * @param isDirty 是否被修改过
     */
    public void unpinPage(PageId pageId, boolean isDirty) {
        poolLock.readLock().lock();
        try {
            Integer frameIndex = pageHash.get(pageId);
            if (frameIndex == null) {
                return; // 页面不在 Buffer Pool 中
            }
            
            BufferFrame frame = frames[frameIndex];
            
            // 减少 pin count
            frame.unpin();
            
            // 处理脏页标记
            if (isDirty && !frame.isDirty()) {
                frame.setDirty(true);
                
                // 加入 Flush List
                // 使用当前时间作为 LSN (简化实现，实际应使用 Redo Log LSN)
                long lsn = frame.getPage().getLsn();
                flushList.add(frameIndex, lsn > 0 ? lsn : System.nanoTime());
            }
        } finally {
            poolLock.readLock().unlock();
        }
    }
    
    // ==================== 页面刷盘 ====================
    
    /**
     * 刷新单个页面到磁盘
     * 
     * @param pageId 页面标识
     * @throws IOException 如果刷盘失败
     */
    public void flushPage(PageId pageId) throws IOException {
        poolLock.readLock().lock();
        try {
            Integer frameIndex = pageHash.get(pageId);
            if (frameIndex == null) {
                return;
            }
            flushPageInternal(frameIndex);
        } finally {
            poolLock.readLock().unlock();
        }
    }
    
    /**
     * 内部刷盘方法
     * 
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>检查是否为脏页</li>
     *   <li>调用 page.prepareForFlush() 更新校验和</li>
     *   <li>写入磁盘</li>
     *   <li>清除脏页标记</li>
     *   <li>从 Flush List 移除</li>
     * </ol>
     * 
     * @param frameIndex 帧索引
     * @throws IOException 如果写入失败
     */
    private void flushPageInternal(int frameIndex) throws IOException {
        BufferFrame frame = frames[frameIndex];
        
        if (!frame.isDirty()) {
            return; // 不是脏页，无需刷盘
        }
        
        Page page = frame.getPage();
        
        // 准备刷盘 (更新校验和)
        page.prepareForFlush();
        
        // 写入磁盘
        diskManager.writePage(page.getPageId(), page.getBuffer());
        writeCount.incrementAndGet();
        
        // 清除脏页状态
        frame.setDirty(false);
        page.clearDirty();
        
        // 从 Flush List 移除
        flushList.remove(frameIndex);
    }
    
    /**
     * 刷新所有脏页到磁盘
     * 
     * <p>在以下场景调用：
     * <ul>
     *   <li>数据库正常关闭</li>
     *   <li>Checkpoint</li>
     *   <li>手动 FLUSH TABLES</li>
     * </ul>
     * </p>
     * 
     * @throws IOException 如果任何页面刷盘失败
     */
    public void flushAllPages() throws IOException {
        poolLock.writeLock().lock();
        try {
            for (int i = 0; i < poolSize; i++) {
                if (frames[i].isDirty()) {
                    flushPageInternal(i);
                }
            }
            // 确保所有数据持久化
            diskManager.syncAll();
        } finally {
            poolLock.writeLock().unlock();
        }
    }
    
    // ==================== 页面分配与删除 ====================
    
    /**
     * 分配新页面
     * 
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>调用 DiskManager 分配物理页面</li>
     *   <li>调用 getPage(NEW_PAGE) 创建内存中的页面</li>
     * </ol>
     * 
     * @param spaceId 表空间 ID
     * @return BufferFrame (已 pin)
     * @throws IOException 如果分配失败
     */
    public BufferFrame newPage(int spaceId) throws IOException {
        // 在磁盘上分配页面
        int pageNo = diskManager.allocatePage(spaceId);
        PageId pageId = PageId.of(spaceId, pageNo);
        
        // 在 Buffer Pool 中创建页面
        return getPage(pageId, FetchMode.NEW_PAGE);
    }
    
    /**
     * 删除页面
     * 
     * <p>将页面从 Buffer Pool 中移除。
     * 注意：这不会删除磁盘上的数据。</p>
     * 
     * @param pageId 页面标识
     */
    public void deletePage(PageId pageId) {
        poolLock.writeLock().lock();
        try {
            Integer frameIndex = pageHash.remove(pageId);
            if (frameIndex != null) {
                lruList.remove(frameIndex);
                flushList.remove(frameIndex);
                frames[frameIndex].reset();
                freeList.add(frameIndex);
            }
        } finally {
            poolLock.writeLock().unlock();
        }
    }
    
    // ==================== 生命周期管理 ====================
    
    /**
     * 关闭 Buffer Pool
     * 
     * <p>刷新所有脏页并释放资源。</p>
     * 
     * @throws IOException 如果刷盘失败
     */
    public void close() throws IOException {
        flushAllPages();
    }
    
    // ==================== 统计信息 ====================
    
    /**
     * 获取缓存命中率
     * 
     * <p>命中率 = hitCount / (hitCount + missCount)</p>
     * <p>高命中率 (>95%) 表示 Buffer Pool 大小合适。</p>
     * 
     * @return 命中率 (0.0 - 1.0)
     */
    public double getHitRatio() {
        long hits = hitCount.get();
        long misses = missCount.get();
        long total = hits + misses;
        return total == 0 ? 0 : (double) hits / total;
    }
    
    /**
     * 获取 Buffer Pool 统计信息
     * 
     * @return 统计信息记录
     */
    public BufferPoolStats getStats() {
        return new BufferPoolStats(
            poolSize,
            freeList.size(),
            flushList.size(),
            lruList.getYoungSize(),
            lruList.getOldSize(),
            hitCount.get(),
            missCount.get(),
            readCount.get(),
            writeCount.get()
        );
    }
    
    public int getPoolSize() {
        return poolSize;
    }
    
    public int getFreeCount() {
        return freeList.size();
    }
    
    public int getDirtyCount() {
        return flushList.size();
    }
    
    // ==================== 统计信息记录 ====================
    
    /**
     * Buffer Pool 统计信息
     * 
     * @param poolSize   池大小 (页数)
     * @param freePages  空闲页数
     * @param dirtyPages 脏页数
     * @param youngPages Young 区页数
     * @param oldPages   Old 区页数
     * @param hitCount   命中次数
     * @param missCount  未命中次数
     * @param readCount  磁盘读次数
     * @param writeCount 磁盘写次数
     */
    public record BufferPoolStats(
        int poolSize,
        int freePages,
        int dirtyPages,
        int youngPages,
        int oldPages,
        long hitCount,
        long missCount,
        long readCount,
        long writeCount
    ) {
        /**
         * 计算命中率
         * 
         * @return 命中率 (0.0 - 1.0)
         */
        public double hitRatio() {
            long total = hitCount + missCount;
            return total == 0 ? 0 : (double) hitCount / total;
        }
        
        /**
         * 获取已使用页数
         * 
         * @return poolSize - freePages
         */
        public int usedPages() {
            return poolSize - freePages;
        }
        
        @Override
        public String toString() {
            return String.format(
                "BufferPoolStats{size=%d, used=%d, free=%d, dirty=%d, young=%d, old=%d, " +
                "hit=%d, miss=%d, hitRatio=%.2f%%, read=%d, write=%d}",
                poolSize, usedPages(), freePages, dirtyPages, youngPages, oldPages,
                hitCount, missCount, hitRatio() * 100, readCount, writeCount);
        }
    }
}
