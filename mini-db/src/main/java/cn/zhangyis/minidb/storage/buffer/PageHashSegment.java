package cn.zhangyis.minidb.storage.buffer;

import cn.zhangyis.minidb.storage.page.PageId;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Page Hash 分段 (Partitioned Page Hash)
 *
 * <p>参考 ConcurrentHashMap 的分段锁机制，将全局 PageHash 分成多个段，
 * 每个段独立加锁，从而降低锁竞争，提升并发性能。</p>
 *
 * <h2>设计原理</h2>
 * <pre>
 * 传统方案 (BufferPool):
 *   全局 poolLock 保护 pageHash
 *   → 所有读请求串行化
 *
 * 分段锁方案:
 *   64 个 segments，每个有独立锁
 *   → 64 个读请求可并发
 *   → 64x 吞吐量提升
 * </pre>
 *
 * <h2>Hash 分配</h2>
 * <p>使用 PageId.hashCode() 的高位进行分段：</p>
 * <pre>
 * segmentIndex = (pageId.hashCode() >>> 26) & 0x3F  // 取高6位
 * </pre>
 *
 * <h2>InnoDB 对应</h2>
 * <p>InnoDB 8.0 使用 64 个 page_hash mutex 保护 hash table。</p>
 *
 * @author MiniDB
 * @version 1.0
 * @see BufferPool
 */
public class PageHashSegment {

    /**
     * 本段内的 pageId → frameIndex 映射
     *
     * <p>使用 ConcurrentHashMap 提供基本的并发安全，
     * segmentLock 用于保护跨多个操作的原子性。</p>
     */
    private final ConcurrentHashMap<PageId, Integer> map;

    /**
     * 段级别的读写锁
     *
     * <p>读锁: 查找 pageId (getPage fast path)
     * 写锁: 添加/删除 pageId (loadPage, evictPage)</p>
     */
    private final ReentrantReadWriteLock segmentLock;

    // ==================== 构造函数 ====================

    /**
     * 创建 Page Hash 分段
     *
     * @param initialCapacity 初始容量
     */
    public PageHashSegment(int initialCapacity) {
        this.map = new ConcurrentHashMap<>(initialCapacity);
        this.segmentLock = new ReentrantReadWriteLock();
    }

    // ==================== 核心操作 ====================

    /**
     * 查找 PageId (读锁)
     *
     * <p>在 getPage() 的 fast path 中调用。</p>
     *
     * @param pageId 页面标识
     * @return 帧索引，不存在返回 null
     */
    public Integer get(PageId pageId) {
        segmentLock.readLock().lock();
        try {
            return map.get(pageId);
        } finally {
            segmentLock.readLock().unlock();
        }
    }

    /**
     * 添加 PageId 映射 (写锁)
     *
     * <p>在加载新页面时调用。</p>
     *
     * @param pageId     页面标识
     * @param frameIndex 帧索引
     * @return 旧的帧索引，不存在返回 null
     */
    public Integer put(PageId pageId, Integer frameIndex) {
        segmentLock.writeLock().lock();
        try {
            return map.put(pageId, frameIndex);
        } finally {
            segmentLock.writeLock().unlock();
        }
    }

    /**
     * 移除 PageId 映射 (写锁)
     *
     * <p>在淘汰页面时调用。</p>
     *
     * @param pageId 页面标识
     * @return 被移除的帧索引，不存在返回 null
     */
    public Integer remove(PageId pageId) {
        segmentLock.writeLock().lock();
        try {
            return map.remove(pageId);
        } finally {
            segmentLock.writeLock().unlock();
        }
    }

    /**
     * 检查 PageId 是否存在 (读锁)
     *
     * @param pageId 页面标识
     * @return 如果存在返回 true
     */
    public boolean containsKey(PageId pageId) {
        segmentLock.readLock().lock();
        try {
            return map.containsKey(pageId);
        } finally {
            segmentLock.readLock().unlock();
        }
    }

    /**
     * 获取段的大小
     *
     * @return 映射数量
     */
    public int size() {
        return map.size();
    }

    /**
     * 清空段
     */
    public void clear() {
        segmentLock.writeLock().lock();
        try {
            map.clear();
        } finally {
            segmentLock.writeLock().unlock();
        }
    }

    // ==================== 锁访问 (供外部协调使用) ====================

    /**
     * 获取读锁
     *
     * <p>供 BufferPool 在需要持有锁跨多个操作时使用。</p>
     */
    public void readLock() {
        segmentLock.readLock().lock();
    }

    /**
     * 释放读锁
     */
    public void readUnlock() {
        segmentLock.readLock().unlock();
    }

    /**
     * 获取写锁
     */
    public void writeLock() {
        segmentLock.writeLock().lock();
    }

    /**
     * 释放写锁
     */
    public void writeUnlock() {
        segmentLock.writeLock().unlock();
    }
}
