package cn.zhangyis.minidb.storage.buffer;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 脏页链表 (Flush List)
 * 
 * <p>按照 oldest_modification LSN 顺序维护所有脏页。
 * 用于 Checkpoint 时按顺序刷盘和确定恢复起点。</p>
 * 
 * <h2>核心概念</h2>
 * <ul>
 *   <li><b>oldest_modification</b>: 页面首次被修改时的 LSN。
 *       后续修改不更新此值，保证 Flush List 按首次修改顺序排列。</li>
 *   <li><b>newest_modification</b>: 页面最后被修改的 LSN (存储在页面本身)。</li>
 * </ul>
 * 
 * <h2>数据结构</h2>
 * <pre>
 * +------------------+     +----------------------+
 * | dirtyPages       |     | lsnIndex (TreeMap)   |
 * | frameIndex → LSN |     | LSN → Set[frameIndex]|
 * +------------------+     +----------------------+
 *         ↑                          ↑
 *         |                          |
 *    快速查找帧            按 LSN 排序遍历
 * </pre>
 * 
 * <h2>Checkpoint 流程</h2>
 * <ol>
 *   <li>获取 oldest_modification 最小的脏页</li>
 *   <li>按 LSN 顺序刷盘</li>
 *   <li>更新 Checkpoint LSN</li>
 *   <li>恢复时只需重放 Checkpoint LSN 之后的 Redo Log</li>
 * </ol>
 * 
 * <h2>InnoDB 对应</h2>
 * <p>对应 InnoDB buf_pool->flush_list，按 oldest_modification 降序排列。</p>
 * 
 * @author MiniDB
 * @version 1.0
 * @see BufferPool
 */
public class FlushList {
    
    /**
     * 脏页映射: frameIndex → oldest_modification LSN
     * 
     * <p>用于快速查找帧是否在 Flush List 中，以及获取其 LSN。</p>
     */
    private final Map<Integer, Long> dirtyPages;
    
    /**
     * LSN 索引: LSN → 帧索引集合
     * 
     * <p>使用 TreeMap 保持 LSN 有序，便于按顺序刷盘。
     * 同一 LSN 可能对应多个帧（同一事务修改多个页面）。</p>
     */
    private final TreeMap<Long, Set<Integer>> lsnIndex;
    
    /**
     * 操作锁
     * 
     * <p>保护两个数据结构的一致性。</p>
     */
    private final ReentrantLock lock;
    
    // ==================== 构造函数 ====================
    
    /**
     * 创建空的 Flush List
     */
    public FlushList() {
        this.dirtyPages = new HashMap<>();
        this.lsnIndex = new TreeMap<>();
        this.lock = new ReentrantLock();
    }
    
    // ==================== 核心操作 ====================
    
    /**
     * 添加脏页到 Flush List
     * 
     * <p><b>重要</b>: 如果页面已经在 Flush List 中，不更新 LSN。
     * 这保证了 oldest_modification 语义——记录的是首次修改的 LSN。</p>
     * 
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>获取锁</li>
     *   <li>检查页面是否已在列表中</li>
     *   <li>如果不在，添加到 dirtyPages 和 lsnIndex</li>
     *   <li>释放锁</li>
     * </ol>
     * 
     * @param frameIndex 帧索引
     * @param lsn        修改的 LSN (首次修改时的 LSN)
     */
    public void add(int frameIndex, long lsn) {
        lock.lock();
        try {
            // 如果已存在，不更新 (保持 oldest_modification)
            if (dirtyPages.containsKey(frameIndex)) {
                return;
            }
            
            // 添加到脏页映射
            dirtyPages.put(frameIndex, lsn);
            
            // 添加到 LSN 索引
            lsnIndex.computeIfAbsent(lsn, k -> new HashSet<>()).add(frameIndex);
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 从 Flush List 移除脏页
     * 
     * <p>在页面刷盘后调用。</p>
     * 
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>从 dirtyPages 移除并获取 LSN</li>
     *   <li>从 lsnIndex 对应的集合中移除</li>
     *   <li>如果集合为空，移除该 LSN 条目</li>
     * </ol>
     * 
     * @param frameIndex 帧索引
     */
    public void remove(int frameIndex) {
        lock.lock();
        try {
            // 从脏页映射移除
            Long lsn = dirtyPages.remove(frameIndex);
            
            if (lsn != null) {
                // 从 LSN 索引移除
                Set<Integer> frames = lsnIndex.get(lsn);
                if (frames != null) {
                    frames.remove(frameIndex);
                    // 如果该 LSN 没有页面了，移除条目
                    if (frames.isEmpty()) {
                        lsnIndex.remove(lsn);
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 获取最老的脏页 (oldest_modification 最小)
     * 
     * <p>这是 Checkpoint 的起点。恢复时需要从这个 LSN 开始重放 Redo Log。</p>
     * 
     * @return 最老脏页的帧索引，Flush List 为空返回 null
     */
    public Integer getOldest() {
        lock.lock();
        try {
            if (lsnIndex.isEmpty()) {
                return null;
            }
            // TreeMap.firstEntry() 返回最小的 key
            Map.Entry<Long, Set<Integer>> first = lsnIndex.firstEntry();
            return first.getValue().iterator().next();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 获取所有 LSN 小于等于指定值的脏页
     * 
     * <p>用于 Checkpoint：刷盘所有 oldest_modification <= checkpoint_lsn 的页面。</p>
     * 
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>使用 TreeMap.headMap(lsn, true) 获取所有 <= lsn 的条目</li>
     *   <li>收集所有对应的帧索引</li>
     * </ol>
     * 
     * @param lsn LSN 上界 (包含)
     * @return 满足条件的帧索引列表
     */
    public List<Integer> getPagesBeforeLsn(long lsn) {
        lock.lock();
        try {
            List<Integer> result = new ArrayList<>();
            // headMap(lsn, true) 包含所有 key <= lsn 的条目
            for (Map.Entry<Long, Set<Integer>> entry : lsnIndex.headMap(lsn, true).entrySet()) {
                result.addAll(entry.getValue());
            }
            return result;
        } finally {
            lock.unlock();
        }
    }
    
    // ==================== 状态查询 ====================
    
    /**
     * 获取最老脏页的 LSN (oldest_modification)
     * 
     * <p>这是 Checkpoint LSN 的下界。恢复时至少需要从这里开始。</p>
     * 
     * @return 最小的 LSN，Flush List 为空返回 null
     */
    public Long getOldestLsn() {
        lock.lock();
        try {
            return lsnIndex.isEmpty() ? null : lsnIndex.firstKey();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 获取最新脏页的 LSN
     * 
     * @return 最大的 LSN，Flush List 为空返回 null
     */
    public Long getNewestLsn() {
        lock.lock();
        try {
            return lsnIndex.isEmpty() ? null : lsnIndex.lastKey();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 检查帧是否在 Flush List 中
     * 
     * @param frameIndex 帧索引
     * @return 如果是脏页返回 true
     */
    public boolean contains(int frameIndex) {
        lock.lock();
        try {
            return dirtyPages.containsKey(frameIndex);
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 获取帧的 oldest_modification LSN
     * 
     * @param frameIndex 帧索引
     * @return LSN，不在 Flush List 中返回 null
     */
    public Long getLsn(int frameIndex) {
        lock.lock();
        try {
            return dirtyPages.get(frameIndex);
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 获取脏页数量
     * 
     * @return 脏页数
     */
    public int size() {
        lock.lock();
        try {
            return dirtyPages.size();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 检查是否没有脏页
     * 
     * @return 如果 Flush List 为空返回 true
     */
    public boolean isEmpty() {
        return size() == 0;
    }
    
    /**
     * 获取所有脏页帧索引
     * 
     * <p>用于调试或批量刷盘。</p>
     * 
     * @return 脏页帧索引列表
     */
    public List<Integer> getAllDirtyFrames() {
        lock.lock();
        try {
            return new ArrayList<>(dirtyPages.keySet());
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 清空 Flush List
     * 
     * <p>在 Buffer Pool 关闭时调用（通常应该先 flushAll）。</p>
     */
    public void clear() {
        lock.lock();
        try {
            dirtyPages.clear();
            lsnIndex.clear();
        } finally {
            lock.unlock();
        }
    }
}
