package cn.zhangyis.minidb.storage.buffer;

import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 缓冲帧 (Buffer Frame)
 * 
 * <p>Buffer Pool 中的单个槽位，用于持有一个页面及其元数据。
 * 每个帧管理一个页面的内存缓存、状态和并发访问控制。</p>
 * 
 * <h2>InnoDB 对应</h2>
 * <p>对应 InnoDB 的 buf_block_t 和 buf_page_t 结构体。</p>
 * 
 * <h2>帧状态</h2>
 * <ul>
 *   <li><b>空闲</b>: pageId=null, 在 Free List 中</li>
 *   <li><b>已加载</b>: 持有页面，在 LRU List 中</li>
 *   <li><b>固定 (pinned)</b>: pinCount > 0，正在被使用，不能淘汰</li>
 *   <li><b>脏 (dirty)</b>: 内存中有修改，需要刷盘</li>
 * </ul>
 * 
 * <h2>LRU 位置</h2>
 * <ul>
 *   <li><b>Young 区</b>: 热点页面，oldBlock=false</li>
 *   <li><b>Old 区</b>: 新加载或冷页面，oldBlock=true</li>
 * </ul>
 * 
 * <h2>并发控制</h2>
 * <ul>
 *   <li><b>pin/unpin</b>: 原子计数器，防止正在使用的页面被淘汰</li>
 *   <li><b>pageLock</b>: 读写锁，控制对页面内容的并发访问</li>
 * </ul>
 * 
 * @author MiniDB
 * @version 1.0
 * @see BufferPool
 */
public class BufferFrame {
    
    // ==================== 身份标识 ====================
    
    /**
     * 帧 ID (在帧数组中的索引)
     * <p>帧 ID 是固定的，用于在各种链表中引用此帧。</p>
     */
    private final int frameId;
    
    /**
     * 当前持有的页面标识
     * <p>null 表示空闲帧。</p>
     */
    private PageId pageId;
    
    /**
     * 页面对象
     * <p>包含实际的 16KB 数据。</p>
     */
    private Page page;
    
    // ==================== 状态字段 ====================
    
    /**
     * 固定计数 (Pin Count)
     * 
     * <p>表示当前有多少个操作正在使用此页面。
     * pinCount > 0 时，页面不能被淘汰。</p>
     * 
     * <p>使用 AtomicInteger 保证并发安全。</p>
     */
    private final AtomicInteger pinCount;

    private final AtomicBoolean evicting;
    
    /**
     * 脏页标记
     * 
     * <p>如果为 true，表示页面在内存中被修改但尚未刷盘。
     * 脏页在淘汰前必须写回磁盘。</p>
     */
    private volatile boolean dirty;
    
    /**
     * 是否在 LRU Old 区
     * 
     * <p>true = Old 区 (冷页面)
     * false = Young 区 (热点页面)</p>
     * 
     * @see LRUList
     */
    private volatile boolean oldBlock;

    private volatile boolean promotionToYoungRequested;
    
    /**
     * 上次访问时间 (毫秒)
     * 
     * <p>用于 LRU 算法判断页面热度。</p>
     */
    private volatile long accessTime;
    
    /**
     * 进入 Old 区的时间 (毫秒)
     * 
     * <p>用于判断页面是否在 Old 区停留超过阈值。
     * 超过阈值后再次访问才会移到 Young 区。</p>
     */
    private volatile long oldBlockTime;
    
    /**
     * 最早修改的 LSN (oldest_modification)
     * 
     * <p>用于 Flush List 排序。只在第一次变脏时设置，
     * 后续修改不更新此值。这样可以保证 Flush List 按
     * 首次修改顺序排列。</p>
     */
    volatile long oldestModification;
    
    // ==================== 链表指针 ====================
    
    /**
     * LRU 链表前驱
     * <p>-1 表示无前驱 (链表头)。</p>
     */
    volatile int lruPrev = -1;
    
    /**
     * LRU 链表后继
     * <p>-1 表示无后继 (链表尾)。</p>
     */
    volatile int lruNext = -1;
    
    /**
     * Flush List 链表前驱
     */
    volatile int flushPrev = -1;
    
    /**
     * Flush List 链表后继
     */
    volatile int flushNext = -1;
    
    // ==================== 并发控制 ====================
    
    /**
     * 页面级读写锁
     * 
     * <p>用于控制对页面内容的并发访问：
     * <ul>
     *   <li><b>读锁</b>: 允许多个读操作并发</li>
     *   <li><b>写锁</b>: 独占访问，用于修改页面</li>
     * </ul>
     * </p>
     */
    private final ReentrantReadWriteLock pageLock;
    
    // ==================== 构造函数 ====================
    
    /**
     * 创建缓冲帧
     * 
     * @param frameId 帧 ID (数组索引)
     */
    public BufferFrame(int frameId) {
        this.frameId = frameId;
        this.pinCount = new AtomicInteger(0);
        this.evicting = new AtomicBoolean(false);
        this.pageLock = new ReentrantReadWriteLock();
        reset();
    }
    
    /**
     * 重置帧到空闲状态
     * 
     * <p>清除所有状态，使帧可以重新分配给新页面。
     * 在淘汰页面后调用。</p>
     */
    public void reset() {
        this.pageId = null;
        this.page = null;
        this.pinCount.set(0);
        this.evicting.set(false);
        this.dirty = false;
        this.oldBlock = false;
        this.promotionToYoungRequested = false;
        this.accessTime = 0;
        this.oldBlockTime = 0;
        this.oldestModification = 0;
        this.lruPrev = -1;
        this.lruNext = -1;
        this.flushPrev = -1;
        this.flushNext = -1;
    }
    
    // ==================== 基本 Getters/Setters ====================
    
    /**
     * 获取帧 ID
     * 
     * @return 帧在数组中的索引
     */
    public int getFrameId() {
        return frameId;
    }
    
    /**
     * 获取页面标识
     * 
     * @return PageId，空闲帧返回 null
     */
    public PageId getPageId() {
        return pageId;
    }
    
    /**
     * 设置页面标识
     * 
     * @param pageId 页面标识
     */
    public void setPageId(PageId pageId) {
        this.pageId = pageId;
    }
    
    /**
     * 获取页面对象
     * 
     * @return Page 对象
     */
    public Page getPage() {
        return page;
    }
    
    /**
     * 设置页面对象
     *
     * @param page Page 对象
     */
    public void setPage(Page page) {
        this.page = page;
    }

    /**
     * 直接获取页面的 ByteBuffer
     *
     * <p>供 MTR 和 IndexPageOps 等核心组件使用。
     * 调用者必须持有适当的 latch 才能安全访问返回的 buffer。</p>
     *
     * <p><b>警告</b>：不要直接通过此 buffer 进行写操作，
     * 所有写操作应通过 MTR 进行以确保 WAL 正确性。</p>
     *
     * @return 页面的 ByteBuffer，如果页面未加载则返回 null
     */
    public java.nio.ByteBuffer buffer() {
        return page != null ? page.getBuffer() : null;
    }

    /**
     * 获取页面并转换为指定类型
     * 
     * <p>用于获取特定类型的页面，如 IndexPage。</p>
     * 
     * @param <T>  页面类型
     * @param type 目标类型 Class
     * @return 转换后的页面对象
     * @throws ClassCastException 如果类型不匹配
     */
    @SuppressWarnings("unchecked")
    public <T extends Page> T getPageAs(Class<T> type) {
        if (page == null) {
            return null;
        }
        if (!type.isInstance(page)) {
            throw new ClassCastException("Page is not of type " + type.getName());
        }
        return (T) page;
    }
    
    // ==================== Pin 操作 ====================
    
    /**
     * 获取当前 pin count
     * 
     * @return 固定计数
     */
    public int getPinCount() {
        return pinCount.get();
    }
    
    /**
     * 增加 pin count (固定页面)
     * 
     * <p>在获取页面时调用，防止页面被淘汰。</p>
     */
    public void pin() {
        pinCount.incrementAndGet();
    }
    
    public boolean tryPin() {
        while (true) {
            if (evicting.get()) {
                return false;
            }
            int cur = pinCount.get();
            if (pinCount.compareAndSet(cur, cur + 1)) {
                if (!evicting.get()) {
                    return true;
                }
                unpin();
                return false;
            }
        }
    }

    public boolean tryAcquireForEviction() {
        if (!evicting.compareAndSet(false, true)) {
            return false;
        }
        if (!pinCount.compareAndSet(0, 1)) {
            evicting.set(false);
            return false;
        }
        return true;
    }

    public void releaseEviction() {
        evicting.set(false);
    }

    public boolean isEvicting() {
        return evicting.get();
    }

    /**
     * 减少 pin count (释放页面)
     * 
     * <p>在使用完页面后调用。当 pinCount 降为 0 时，
     * 页面可以被 LRU 淘汰。</p>
     * 
     * @throws IllegalStateException 如果 pinCount 变为负数
     */
    public void unpin() {
        int count = pinCount.decrementAndGet();
        if (count < 0) {
            pinCount.set(0);
            throw new IllegalStateException("Pin count went negative for frame " + frameId);
        }
    }
    
    /**
     * 检查页面是否被固定
     * 
     * @return 如果 pinCount > 0 返回 true
     */
    public boolean isPinned() {
        return pinCount.get() > 0;
    }
    
    // ==================== 脏页状态 ====================
    
    /**
     * 检查是否为脏页
     * 
     * @return 如果页面被修改但未刷盘返回 true
     */
    public boolean isDirty() {
        return dirty;
    }
    
    /**
     * 设置脏页状态
     *
     * <p>脏页状态由 BufferFrame 唯一管理。
     * 此方法应由 MTR 在 commit 时调用。</p>
     *
     * @param dirty 脏页标记
     */
    public void setDirty(boolean dirty) {
        this.dirty = dirty;
        // 注意：不再调用 page.markDirty()
        // Page.dirty 已废弃，脏页状态由 BufferFrame 唯一管理
    }
    
    // ==================== LRU 状态 ====================
    
    /**
     * 检查是否在 Old 区
     * 
     * @return 如果在 Old 区返回 true
     */
    public boolean isOldBlock() {
        return oldBlock;
    }
    
    /**
     * 设置 Old 区标记
     * 
     * <p>设置为 true 时会记录进入 Old 区的时间戳。</p>
     * 
     * @param oldBlock Old 区标记
     */
    public void setOldBlock(boolean oldBlock) {
        this.oldBlock = oldBlock;
        if (oldBlock) {
            this.oldBlockTime = System.currentTimeMillis();
        }
    }
    
    /**
     * 获取上次访问时间
     * 
     * @return 时间戳 (毫秒)
     */
    public long getAccessTime() {
        return accessTime;
    }
    
    /**
     * 设置访问时间
     * 
     * @param accessTime 时间戳
     */
    public void setAccessTime(long accessTime) {
        this.accessTime = accessTime;
    }
    
    /**
     * 获取进入 Old 区的时间
     * 
     * @return 时间戳
     */
    public long getOldBlockTime() {
        return oldBlockTime;
    }
    
    public void requestPromotionToYoung() {
        this.promotionToYoungRequested = true;
    }

    public boolean consumePromotionToYoungRequested() {
        if (!promotionToYoungRequested) {
            return false;
        }
        promotionToYoungRequested = false;
        return true;
    }

    // ==================== 锁操作 ====================
    
    /**
     * 获取读锁 (S-latch)
     * 
     * <p>允许多个读操作并发访问页面。</p>
     */
    public void readLock() {
        pageLock.readLock().lock();
    }
    
    /**
     * 释放读锁
     */
    public void readUnlock() {
        pageLock.readLock().unlock();
    }
    
    /**
     * 获取写锁 (X-latch)
     * 
     * <p>独占访问页面，用于修改操作。</p>
     */
    public void writeLock() {
        pageLock.writeLock().lock();
    }
    
    /**
     * 释放写锁
     */
    public void writeUnlock() {
        pageLock.writeLock().unlock();
    }
    
    /**
     * 尝试获取写锁 (非阻塞)
     * 
     * @return 如果成功获取返回 true
     */
    public boolean tryWriteLock() {
        return pageLock.writeLock().tryLock();
    }
    
    /**
     * 尝试获取读锁 (非阻塞)
     *
     * @return 如果成功获取返回 true
     */
    public boolean tryReadLock() {
        return pageLock.readLock().tryLock();
    }

    /**
     * 尝试获取读锁（带超时）
     *
     * @param timeout 超时时间
     * @param unit    时间单位
     * @return 如果成功获取返回 true
     * @throws InterruptedException 如果等待时被中断
     */
    public boolean tryReadLock(long timeout, TimeUnit unit) throws InterruptedException {
        return pageLock.readLock().tryLock(timeout, unit);
    }

    /**
     * 尝试获取写锁（带超时）
     *
     * @param timeout 超时时间
     * @param unit    时间单位
     * @return 如果成功获取返回 true
     * @throws InterruptedException 如果等待时被中断
     */
    public boolean tryWriteLock(long timeout, TimeUnit unit) throws InterruptedException {
        return pageLock.writeLock().tryLock(timeout, unit);
    }

    /**
     * 检查当前线程是否持有写锁 (X-latch)
     *
     * <p>用于 MTR 断言，确保写操作在正确的锁保护下执行。</p>
     *
     * @return 如果当前线程持有写锁返回 true
     */
    public boolean isWriteLatched() {
        return pageLock.isWriteLockedByCurrentThread();
    }

    /**
     * 检查当前线程是否持有读锁 (S-latch)
     *
     * <p>用于断言读操作在正确的锁保护下执行。</p>
     *
     * @return 如果当前线程持有读锁返回 true
     */
    public boolean isReadLatched() {
        return pageLock.getReadHoldCount() > 0;
    }

    /**
     * 检查当前线程是否持有任意锁 (S-latch 或 X-latch)
     *
     * @return 如果当前线程持有任意锁返回 true
     */
    public boolean isLatched() {
        return isWriteLatched() || isReadLatched();
    }

    /**
     * 返回帧的字符串表示
     * 
     * @return 包含关键状态的字符串
     */
    @Override
    public String toString() {
        return String.format("BufferFrame{id=%d, pageId=%s, pin=%d, dirty=%s, old=%s}",
            frameId, pageId, pinCount.get(), dirty, oldBlock);
    }
}
