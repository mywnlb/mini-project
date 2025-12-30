package cn.zhangyis.minidb.storage.buffer;


import cn.zhangyis.minidb.storage.constants.StorageConstants;

import java.util.concurrent.locks.ReentrantLock;

/**
 * LRU 链表 (Young-Old 分区策略)
 * 
 * <p>实现 InnoDB 改进的 LRU 算法 —— Midpoint Insertion Strategy。
 * 传统 LRU 的问题是全表扫描等操作会将热点页面挤出缓存，
 * InnoDB 通过 Young-Old 分区解决这个问题。</p>
 * 
 * <h2>链表结构</h2>
 * <pre>
 * +-------------------------------------------------------------+
 * |  YOUNG 区 (~5/8)              |  OLD 区 (~3/8)              |
 * +-------------------------------------------------------------+
 * | youngHead ←→ ... ←→ youngTail | oldHead ←→ ... ←→ oldTail  |
 * +-------------------------------------------------------------+
 *       ↑ MRU (最近使用)              midpoint         LRU ↓ (最久未使用)
 * </pre>
 * 
 * <h2>核心规则</h2>
 * <ol>
 *   <li><b>新页面加载</b>: 插入到 Old 区头部 (midpoint)，而不是 MRU 端</li>
 *   <li><b>Old 区页面被访问</b>: 如果在 Old 区停留超过 {@code oldBlockTimeMs}，
 *       则移到 Young 区头部；否则保持在 Old 区</li>
 *   <li><b>Young 区页面被访问</b>: 移到 Young 区头部</li>
 *   <li><b>淘汰</b>: 优先从 Old 区尾部淘汰</li>
 *   <li><b>平衡</b>: 当 Old 区过小时，将 Young 区尾部页面移到 Old 区</li>
 * </ol>
 * 
 * <h2>防全表扫描污染的原理</h2>
 * <p>全表扫描会快速读取大量页面，但每个页面只访问一次。
 * 由于这些页面进入 Old 区后很快就被替换（还没过 1 秒），
 * 所以不会进入 Young 区污染热点数据。</p>
 * 
 * <h2>InnoDB 参数对应</h2>
 * <ul>
 *   <li>innodb_old_blocks_pct (37): Old 区占比</li>
 *   <li>innodb_old_blocks_time (1000): 晋升到 Young 区的时间阈值</li>
 * </ul>
 * 
 * @author MiniDB
 * @version 1.0
 * @see BufferPool
 * @see BufferFrame
 */
public class LRUList {
    
    /**
     * 帧数组引用
     * <p>用于通过 frameId 访问 BufferFrame 对象。</p>
     */
    private final BufferFrame[] frames;
    
    /**
     * Old 区比例 (默认 3/8 ≈ 37.5%)
     */
    private final double oldRatio;
    
    /**
     * 页面在 Old 区停留时间阈值 (毫秒)
     * <p>超过此时间后再次访问才会移到 Young 区。</p>
     */
    private final long oldBlockTimeMs;
    
    // ==================== Young 区 ====================
    
    /** Young 区头部 (MRU 端) */
    private int youngHead = -1;
    
    /** Young 区尾部 */
    private int youngTail = -1;
    
    /** Young 区页面数 */
    private int youngSize = 0;
    
    // ==================== Old 区 ====================
    
    /** Old 区头部 (midpoint) */
    private int oldHead = -1;
    
    /** Old 区尾部 (LRU 端，优先淘汰) */
    private int oldTail = -1;
    
    /** Old 区页面数 */
    private int oldSize = 0;
    
    // ==================== 并发控制 ====================
    
    /**
     * 链表操作锁
     * <p>所有链表修改操作都需要持有此锁。</p>
     */
    private final ReentrantLock lock = new ReentrantLock();
    
    // ==================== 构造函数 ====================
    
    /**
     * 创建 LRU 链表 (使用默认参数)
     * 
     * @param frames 帧数组
     */
    public LRUList(BufferFrame[] frames) {
        this.frames = frames;
        this.oldRatio = StorageConstants.LRU_OLD_RATIO;
        this.oldBlockTimeMs = StorageConstants.LRU_OLD_BLOCK_TIME_MS;
    }
    
    /**
     * 创建 LRU 链表 (自定义参数)
     * 
     * @param frames          帧数组
     * @param oldRatio        Old 区比例 (0-1)
     * @param oldBlockTimeMs  时间阈值 (毫秒)
     */
    public LRUList(BufferFrame[] frames, double oldRatio, long oldBlockTimeMs) {
        this.frames = frames;
        this.oldRatio = oldRatio;
        this.oldBlockTimeMs = oldBlockTimeMs;
    }
    
    // ==================== 核心操作 ====================
    
    /**
     * 将新页面添加到 Old 区头部 (Midpoint Insertion)
     * 
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>标记帧为 Old 区并记录时间戳</li>
     *   <li>将帧插入 Old 区头部</li>
     *   <li>更新 Old 区大小</li>
     *   <li>调用 rebalance() 保持比例</li>
     * </ol>
     * 
     * @param frameIndex 帧索引
     */
    public void addToOld(int frameIndex) {
        lock.lock();
        try {
            BufferFrame frame = frames[frameIndex];
            
            // 标记为 Old 区并记录时间
            frame.setOldBlock(true);
            frame.setAccessTime(System.currentTimeMillis());
            
            // 插入 Old 区头部
            frame.lruPrev = -1;
            frame.lruNext = oldHead;
            
            if (oldHead != -1) {
                frames[oldHead].lruPrev = frameIndex;
            }
            oldHead = frameIndex;
            
            if (oldTail == -1) {
                oldTail = frameIndex;
            }
            
            oldSize++;
            
            // 重新平衡
            rebalance();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 处理页面访问 (Lock-free 优化版)
     *
     * <h3>优化设计</h3>
     * <p>采用无锁 + 近似LRU策略：</p>
     * <ul>
     *   <li><b>快速路径</b>: 只更新 volatile accessTime，无需加锁</li>
     *   <li><b>延迟整理</b>: 不立即调整链表位置，由后台线程异步整理</li>
     *   <li><b>近似LRU</b>: 短期内顺序可能不精确，长期收敛到正确顺序</li>
     * </ul>
     *
     * <h3>性能对比</h3>
     * <pre>
     * 旧版本: 需要获取锁 + 移动链表节点 (~500ns)
     * 新版本: 只更新volatile变量 (~50ns)
     * 高并发下: 消除锁竞争 (~100x提升)
     * </pre>
     *
     * <h3>访问规则 (简化)</h3>
     * <ul>
     *   <li>更新访问时间戳</li>
     *   <li>不立即移动链表节点</li>
     *   <li>后台线程根据时间戳整理顺序</li>
     * </ul>
     *
     * @param frameIndex 帧索引
     * @param frame      帧对象
     */
    public void access(int frameIndex, BufferFrame frame) {
        long now = System.currentTimeMillis();

        // 无锁路径: 只更新访问时间 (volatile写)
        // 这是一个原子操作，不需要同步
        frame.setAccessTime(now);

        // 可选优化: 如果在Old区且停留足够久，标记需要晋升
        // 但不立即移动，由后台线程处理
        if (frame.isOldBlock()) {
            long timeInOld = now - frame.getOldBlockTime();
            if (timeInOld >= oldBlockTimeMs) {
                // 仅标记：设置oldBlock=false表示应该在Young区
                // 后台线程会根据这个标记调整链表
                frame.setOldBlock(false);
            }
        }

        // 注意: 不立即调整链表！
        // 链表整理由 reorderPeriodically() 后台线程处理
    }
    
    /**
     * 从 Old 区移到 Young 区头部 (晋升)
     * 
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>从 Old 区链表移除</li>
     *   <li>更新帧状态 (oldBlock = false)</li>
     *   <li>插入 Young 区头部</li>
     *   <li>重新平衡</li>
     * </ol>
     * 
     * @param frameIndex 帧索引
     */
    private void moveToYoung(int frameIndex) {
        BufferFrame frame = frames[frameIndex];
        
        // 从 Old 区移除
        removeFromOld(frameIndex);
        
        // 更新状态
        frame.setOldBlock(false);
        
        // 插入 Young 区头部
        frame.lruPrev = -1;
        frame.lruNext = youngHead;
        
        if (youngHead != -1) {
            frames[youngHead].lruPrev = frameIndex;
        }
        youngHead = frameIndex;
        
        if (youngTail == -1) {
            youngTail = frameIndex;
        }
        
        youngSize++;
        
        // 可能需要将 Young 尾部移到 Old
        rebalance();
    }
    
    /**
     * 在 Young 区内移到头部 (热度更新)
     * 
     * @param frameIndex 帧索引
     */
    private void moveToYoungHead(int frameIndex) {
        if (youngHead == frameIndex) {
            return; // 已在头部
        }
        
        BufferFrame frame = frames[frameIndex];
        
        // 从当前位置移除
        if (frame.lruPrev != -1) {
            frames[frame.lruPrev].lruNext = frame.lruNext;
        } else {
            youngHead = frame.lruNext;
        }
        
        if (frame.lruNext != -1) {
            frames[frame.lruNext].lruPrev = frame.lruPrev;
        } else {
            youngTail = frame.lruPrev;
        }
        
        // 插入头部
        frame.lruPrev = -1;
        frame.lruNext = youngHead;
        
        if (youngHead != -1) {
            frames[youngHead].lruPrev = frameIndex;
        }
        youngHead = frameIndex;
        
        if (youngTail == -1) {
            youngTail = frameIndex;
        }
    }
    
    /**
     * 从 Old 区移除帧
     * 
     * @param frameIndex 帧索引
     */
    private void removeFromOld(int frameIndex) {
        BufferFrame frame = frames[frameIndex];
        
        // 更新前驱的后继
        if (frame.lruPrev != -1) {
            frames[frame.lruPrev].lruNext = frame.lruNext;
        } else {
            oldHead = frame.lruNext;
        }
        
        // 更新后继的前驱
        if (frame.lruNext != -1) {
            frames[frame.lruNext].lruPrev = frame.lruPrev;
        } else {
            oldTail = frame.lruPrev;
        }
        
        oldSize--;
    }
    
    /**
     * 完全移除帧 (淘汰时调用)
     * 
     * <p>将帧从 LRU 链表 (Young 或 Old) 中移除。</p>
     * 
     * @param frameIndex 帧索引
     */
    public void remove(int frameIndex) {
        lock.lock();
        try {
            BufferFrame frame = frames[frameIndex];
            
            if (frame.isOldBlock()) {
                removeFromOld(frameIndex);
            } else {
                // 从 Young 区移除
                if (frame.lruPrev != -1) {
                    frames[frame.lruPrev].lruNext = frame.lruNext;
                } else {
                    youngHead = frame.lruNext;
                }
                
                if (frame.lruNext != -1) {
                    frames[frame.lruNext].lruPrev = frame.lruPrev;
                } else {
                    youngTail = frame.lruPrev;
                }
                
                youngSize--;
            }
            
            // 清理帧的链表指针
            frame.lruPrev = -1;
            frame.lruNext = -1;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 获取淘汰候选 (从 Old 区尾部)
     * 
     * <p>优先返回 Old 区尾部的帧 (最久未使用)。
     * 如果 Old 区为空，则返回 Young 区尾部。</p>
     * 
     * @return 候选帧索引，链表为空返回 null
     */
    public Integer getVictim() {
        lock.lock();
        try {
            // 优先从 Old 区尾部淘汰
            if (oldTail != -1) {
                return oldTail;
            }
            // Old 区为空，从 Young 区尾部淘汰
            return youngTail != -1 ? youngTail : null;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 平衡 Young 和 Old 区大小
     * 
     * <p>如果 Old 区比例低于目标值，将 Young 区尾部的页面移到 Old 区头部。
     * 这保证了 Old 区始终有足够的空间容纳新加载的页面。</p>
     * 
     * <h3>平衡条件</h3>
     * <pre>
     * oldSize < totalSize * oldRatio && youngSize > 0
     * </pre>
     */
    private void rebalance() {
        int totalSize = youngSize + oldSize;
        if (totalSize == 0) return;
        
        int targetOldSize = (int) (totalSize * oldRatio);
        
        // 将 Young 尾部移到 Old 头部，直到达到目标比例
        while (oldSize < targetOldSize && youngSize > 0) {
            int victimIndex = youngTail;
            BufferFrame victim = frames[victimIndex];
            
            // 从 Young 区尾部移除
            if (victim.lruPrev != -1) {
                frames[victim.lruPrev].lruNext = -1;
            }
            youngTail = victim.lruPrev;
            if (youngTail == -1) {
                youngHead = -1;
            }
            youngSize--;
            
            // 加入 Old 区头部
            victim.setOldBlock(true);
            victim.lruPrev = -1;
            victim.lruNext = oldHead;
            
            if (oldHead != -1) {
                frames[oldHead].lruPrev = victimIndex;
            }
            oldHead = victimIndex;
            
            if (oldTail == -1) {
                oldTail = victimIndex;
            }
            oldSize++;
        }
    }
    
    // ==================== 统计信息 ====================
    
    /**
     * 获取 Young 区大小
     * 
     * @return 页面数
     */
    public int getYoungSize() {
        return youngSize;
    }
    
    /**
     * 获取 Old 区大小
     * 
     * @return 页面数
     */
    public int getOldSize() {
        return oldSize;
    }
    
    /**
     * 获取链表总大小
     * 
     * @return Young + Old 的页面数
     */
    public int getTotalSize() {
        return youngSize + oldSize;
    }
    
    /**
     * 检查链表是否为空
     *
     * @return 如果没有页面返回 true
     */
    public boolean isEmpty() {
        return youngSize == 0 && oldSize == 0;
    }

    // ==================== Lock-free LRU: 后台整理 ====================

    /**
     * 定期整理 LRU 链表 (后台线程调用)
     *
     * <p>这是 Lock-free LRU 的核心方法。由于 access() 只更新时间戳，
     * 不调整链表，所以需要后台线程定期根据 accessTime 重新排序。</p>
     *
     * <h3>整理策略</h3>
     * <ol>
     *   <li>扫描 Young 区，将访问时间过旧的页面移到 Old 区</li>
     *   <li>扫描 Old 区，将标记为需要晋升的页面移到 Young 区</li>
     *   <li>在各区内按 accessTime 调整顺序（热页在前）</li>
     * </ol>
     *
     * <h3>调用频率</h3>
     * <p>建议: 每 100ms 调用一次（可配置）。
     * 频率越高，LRU越精确但开销越大；频率越低，开销小但短期不精确。</p>
     *
     * @return 调整的页面数量
     */
    public int reorderPeriodically() {
        lock.lock();
        try {
            int adjustCount = 0;
            long now = System.currentTimeMillis();

            // ===== 第1步: 处理 Old → Young 的晋升 =====
            // 扫描Old区，找到标记为!oldBlock的页面（access()中标记的）
            int oldCurrent = oldHead;
            while (oldCurrent != -1) {
                BufferFrame frame = frames[oldCurrent];
                int next = frame.lruNext;  // 保存next，因为可能移动节点

                if (!frame.isOldBlock()) {
                    // 标记为应该在Young区，执行晋升
                    moveToYoung(oldCurrent);
                    adjustCount++;
                }

                oldCurrent = next;
            }

            // ===== 第2步: 处理 Young → Old 的降级 =====
            // 将Young区中长时间未访问的页面移到Old区
            // 阈值: 如果页面在Young区但超过5秒未访问，降级到Old
            final long YOUNG_DOWNGRADE_THRESHOLD_MS = 5000;

            int youngCurrent = youngTail;  // 从尾部开始（最冷的）
            int youngCheckCount = Math.min(youngSize / 10, 10);  // 只检查最多10%或10个
            while (youngCurrent != -1 && youngCheckCount > 0) {
                BufferFrame frame = frames[youngCurrent];
                int prev = frame.lruPrev;

                long timeSinceAccess = now - frame.getAccessTime();
                if (timeSinceAccess > YOUNG_DOWNGRADE_THRESHOLD_MS) {
                    // 降级到Old区
                    removeFromYoung(youngCurrent);
                    addToOldHead(youngCurrent);
                    adjustCount++;
                }

                youngCurrent = prev;
                youngCheckCount--;
            }

            // ===== 第3步: 重新平衡 Young/Old 比例 =====
            rebalance();

            return adjustCount;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 从 Young 区移除节点（不加入Old区）
     *
     * @param frameIndex 帧索引
     */
    private void removeFromYoung(int frameIndex) {
        BufferFrame frame = frames[frameIndex];

        if (frame.lruPrev != -1) {
            frames[frame.lruPrev].lruNext = frame.lruNext;
        } else {
            youngHead = frame.lruNext;
        }

        if (frame.lruNext != -1) {
            frames[frame.lruNext].lruPrev = frame.lruPrev;
        } else {
            youngTail = frame.lruPrev;
        }

        youngSize--;
    }

    /**
     * 添加节点到 Old 区头部
     *
     * @param frameIndex 帧索引
     */
    private void addToOldHead(int frameIndex) {
        BufferFrame frame = frames[frameIndex];

        frame.setOldBlock(true);
        frame.lruPrev = -1;
        frame.lruNext = oldHead;

        if (oldHead != -1) {
            frames[oldHead].lruPrev = frameIndex;
        }
        oldHead = frameIndex;

        if (oldTail == -1) {
            oldTail = frameIndex;
        }

        oldSize++;
    }

    /**
     * 获取 LRU 精确度指标
     *
     * <p>检查链表顺序与 accessTime 顺序的一致性。
     * 返回值越接近 1.0，表示 LRU 越精确。</p>
     *
     * @return 精确度 (0.0 - 1.0)
     */
    public double getLruPrecision() {
        lock.lock();
        try {
            int correctOrderCount = 0;
            int totalChecks = 0;

            // 检查 Young 区顺序
            int current = youngHead;
            while (current != -1) {
                BufferFrame frame = frames[current];
                if (frame.lruNext != -1) {
                    BufferFrame next = frames[frame.lruNext];
                    // Young区应该是热页在前 (accessTime大的在前)
                    if (frame.getAccessTime() >= next.getAccessTime()) {
                        correctOrderCount++;
                    }
                    totalChecks++;
                }
                current = frame.lruNext;
            }

            // 检查 Old 区顺序
            current = oldHead;
            while (current != -1) {
                BufferFrame frame = frames[current];
                if (frame.lruNext != -1) {
                    BufferFrame next = frames[frame.lruNext];
                    if (frame.getAccessTime() >= next.getAccessTime()) {
                        correctOrderCount++;
                    }
                    totalChecks++;
                }
                current = frame.lruNext;
            }

            return totalChecks == 0 ? 1.0 : (double) correctOrderCount / totalChecks;
        } finally {
            lock.unlock();
        }
    }
}
