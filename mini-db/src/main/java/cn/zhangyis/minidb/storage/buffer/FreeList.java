package cn.zhangyis.minidb.storage.buffer;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 空闲帧链表 (Free List)
 * 
 * <p>管理 Buffer Pool 中未被使用的帧。当需要加载新页面时，
 * 优先从 Free List 获取空闲帧，避免淘汰现有页面。</p>
 * 
 * <h2>工作流程</h2>
 * <pre>
 * 初始化时:
 *   所有帧 → Free List
 * 
 * 加载页面时:
 *   Free List → 取出空闲帧 → 加载页面 → LRU List
 * 
 * 淘汰页面时:
 *   LRU List → 淘汰帧 → Free List
 * </pre>
 * 
 * <h2>并发安全</h2>
 * <p>使用 {@link ConcurrentLinkedQueue} 实现无锁并发队列，
 * 支持高并发的入队和出队操作。</p>
 * 
 * <h2>InnoDB 对应</h2>
 * <p>对应 InnoDB buf_pool->free 链表。</p>
 * 
 * @author MiniDB
 * @version 1.0
 * @see BufferPool
 */
public class FreeList {
    
    /**
     * 空闲帧队列
     * 
     * <p>存储空闲帧的索引。使用无锁队列保证并发安全和高性能。</p>
     * 
     * <p>选择 ConcurrentLinkedQueue 的原因：
     * <ul>
     *   <li>无锁实现，高并发性能好</li>
     *   <li>FIFO 顺序，公平分配</li>
     *   <li>无界队列，不会阻塞</li>
     * </ul>
     * </p>
     */
    private final ConcurrentLinkedQueue<Integer> freeFrames;
    
    /**
     * 当前空闲帧数量
     * 
     * <p>使用 AtomicInteger 保证并发安全。
     * 注意：由于 ConcurrentLinkedQueue.size() 是 O(n) 操作，
     * 所以单独维护计数器。</p>
     */
    private final AtomicInteger size;
    
    // ==================== 构造函数 ====================
    
    /**
     * 创建空的 Free List
     * 
     * <p>Buffer Pool 初始化时会调用 add() 将所有帧加入。</p>
     */
    public FreeList() {
        this.freeFrames = new ConcurrentLinkedQueue<>();
        this.size = new AtomicInteger(0);
    }
    
    // ==================== 核心操作 ====================
    
    /**
     * 添加空闲帧到队列尾部
     * 
     * <p>在以下场景调用：
     * <ul>
     *   <li>Buffer Pool 初始化时，添加所有帧</li>
     *   <li>页面被淘汰后，帧重新变为空闲</li>
     *   <li>页面被删除后，帧释放</li>
     * </ul>
     * </p>
     * 
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>将帧索引入队</li>
     *   <li>原子递增计数器</li>
     * </ol>
     * 
     * @param frameIndex 空闲帧的索引
     */
    public void add(int frameIndex) {
        freeFrames.offer(frameIndex);
        size.incrementAndGet();
    }
    
    /**
     * 从队列头部获取一个空闲帧
     * 
     * <p>在加载新页面时调用。如果 Free List 为空，
     * 需要通过 LRU 淘汰机制获取帧。</p>
     * 
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>从队列头部取出帧索引</li>
     *   <li>如果成功，原子递减计数器</li>
     *   <li>返回帧索引或 null</li>
     * </ol>
     * 
     * @return 空闲帧索引，如果没有空闲帧返回 null
     */
    public Integer poll() {
        Integer index = freeFrames.poll();
        if (index != null) {
            size.decrementAndGet();
        }
        return index;
    }
    
    /**
     * 查看队列头部的空闲帧 (不移除)
     * 
     * <p>用于检查是否有空闲帧，而不实际获取。</p>
     * 
     * @return 队列头部的帧索引，队列为空返回 null
     */
    public Integer peek() {
        return freeFrames.peek();
    }
    
    // ==================== 状态查询 ====================
    
    /**
     * 获取当前空闲帧数量
     * 
     * <p>O(1) 时间复杂度，直接返回计数器值。</p>
     * 
     * @return 空闲帧数量
     */
    public int size() {
        return size.get();
    }
    
    /**
     * 检查是否没有空闲帧
     * 
     * @return 如果 Free List 为空返回 true
     */
    public boolean isEmpty() {
        return freeFrames.isEmpty();
    }
    
    /**
     * 清空 Free List
     * 
     * <p>在 Buffer Pool 关闭时调用。</p>
     */
    public void clear() {
        freeFrames.clear();
        size.set(0);
    }
}
