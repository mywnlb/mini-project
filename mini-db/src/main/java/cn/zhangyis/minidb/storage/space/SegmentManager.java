package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.PageId;

/**
 * Segment 管理器接口（逻辑层）
 *
 * <p>SegmentManager 是空间管理的核心逻辑层，负责 Segment 的生命周期管理
 * 和页面分配策略。它实现了 InnoDB 的3阶段页面分配算法。</p>
 *
 * <h2>职责范围</h2>
 * <ul>
 *   <li>Segment 生命周期管理（创建/删除）</li>
 *   <li>3阶段页面分配策略（碎片页 → Partial Extent → Free Extent）</li>
 *   <li>Extent 链表迁移（FREE → NOT_FULL → FULL）</li>
 *   <li>碎片页数组管理（前32页）</li>
 *   <li>Segment 统计信息查询</li>
 * </ul>
 *
 * <h2>3阶段页面分配策略</h2>
 * <p>为了优化小表的空间使用和大表的性能，InnoDB 采用3阶段策略：</p>
 * <ol>
 *   <li><b>阶段1：碎片页分配</b>（前32页）
 *       <ul>
 *         <li>使用 INODE Entry 的碎片页数组（32个槽位）</li>
 *         <li>从表空间的 FREE_FRAG 链表分配</li>
 *         <li>避免小表浪费整个 Extent（64页 = 1MB）</li>
 *       </ul>
 *   </li>
 *   <li><b>阶段2：Partial Extent 分配</b>（第33页起）
 *       <ul>
 *         <li>从 Segment 的 NOT_FULL 链表查找部分使用的 Extent</li>
 *         <li>分配后检查是否变满，满则迁移到 FULL 链表</li>
 *       </ul>
 *   </li>
 *   <li><b>阶段3：Free Extent 分配</b>（NOT_FULL 为空时）
 *       <ul>
 *         <li>从 Segment 的 FREE 链表分配完全空闲的 Extent</li>
 *         <li>如果 FREE 也为空，从表空间申请新 Extent</li>
 *         <li>分配第一个页后，移入 NOT_FULL 链表</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h2>Extent 状态转移</h2>
 * <pre>
 * FREE (表空间)
 *   ↓ allocateExtentForSegment()
 * FSEG_FREE (Segment.FREE链表)
 *   ↓ allocatePageForSegment() 第一个页
 * FSEG (Segment.NOT_FULL链表)
 *   ↓ allocatePageForSegment() 第64个页
 * FSEG (Segment.FULL链表)
 *   ↓ freePage() 释放一个页
 * FSEG (Segment.NOT_FULL链表)
 * </pre>
 *
 * <h2>使用示例</h2>
 * <pre>
 * SegmentManager segMgr = new SegmentManagerImpl(bufferPool, extentManager, spaceManager);
 *
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     // 创建 Segment
 *     long segmentId = segMgr.createSegment(mtr, spaceId);
 *
 *     // 分配页面（自动执行3阶段策略）
 *     PageId page1 = segMgr.allocatePageForSegment(mtr, spaceId, segmentId);  // 碎片页
 *     PageId page33 = segMgr.allocatePageForSegment(mtr, spaceId, segmentId); // Extent 页
 *
 *     // 释放页面
 *     segMgr.freePage(mtr, page1);
 *
 *     // 删除 Segment
 *     segMgr.dropSegment(mtr, spaceId, segmentId);
 *
 *     mtr.commit();
 * }
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 * @see SegmentDescriptor
 * @see ExtentManager
 */
public interface SegmentManager {

    /**
     * 创建新的 Segment
     *
     * <p>在指定表空间中创建一个新的 Segment。自动完成：</p>
     * <ol>
     *   <li>在 INODE Page 中查找空闲的 INODE Entry</li>
     *   <li>如果所有 INODE Page 已满，分配新的 INODE Page</li>
     *   <li>从 FSP Header 分配新的 Segment ID</li>
     *   <li>初始化 INODE Entry（清空碎片页数组和3个链表）</li>
     * </ol>
     *
     * <h3>INODE Page 管理</h3>
     * <ul>
     *   <li>首次创建：分配 page 1 作为第一个 INODE Page</li>
     *   <li>INODE Page 已满：从表空间分配新页，加入 INODES_FREE 链表</li>
     *   <li>INODE Entry 分配满：将 INODE Page 从 FREE 移到 FULL 链表</li>
     * </ul>
     *
     * @param mtr     Mini-Transaction
     * @param spaceId 表空间 ID
     * @return 新创建的 Segment ID
     * @throws MiniDbException 如果操作失败
     */
    long createSegment(MiniTransaction mtr, int spaceId) throws MiniDbException;

    /**
     * 删除 Segment
     *
     * <p>释放 Segment 拥有的所有资源：</p>
     * <ol>
     *   <li>释放碎片页数组中的所有页面</li>
     *   <li>释放 FREE 链表中的所有 Extent（状态改回 FREE，加入表空间 FREE 链表）</li>
     *   <li>释放 NOT_FULL 链表中的所有 Extent</li>
     *   <li>释放 FULL 链表中的所有 Extent</li>
     *   <li>清空 INODE Entry（Segment ID = 0）</li>
     * </ol>
     *
     * @param mtr       Mini-Transaction
     * @param spaceId   表空间 ID
     * @param segmentId Segment ID
     * @throws MiniDbException 如果操作失败
     */
    void dropSegment(MiniTransaction mtr, int spaceId, long segmentId) throws MiniDbException;

    /**
     * 为 Segment 分配一个页面（核心算法：3阶段策略）
     *
     * <p>根据 Segment 当前状态，自动选择最优的分配策略：</p>
     *
     * <h3>阶段1：碎片页分配（前32页）</h3>
     * <ul>
     *   <li>条件：碎片页数组未满（已用 &lt; 32）</li>
     *   <li>操作：从表空间 FREE_FRAG 链表分配页面，记录到碎片页数组</li>
     *   <li>优点：小表不浪费空间</li>
     * </ul>
     *
     * <h3>阶段2：Partial Extent 分配（第33页起）</h3>
     * <ul>
     *   <li>条件：NOT_FULL 链表非空</li>
     *   <li>操作：从链表首个 Extent 分配页面</li>
     *   <li>状态转移：如果分配后 Extent 变满，迁移到 FULL 链表</li>
     * </ul>
     *
     * <h3>阶段3：Free Extent 分配（NOT_FULL 为空）</h3>
     * <ul>
     *   <li>条件：FREE 链表非空</li>
     *   <li>操作：从链表取出一个 Extent，分配第一个页面，移入 NOT_FULL</li>
     *   <li>如果 FREE 也为空：从表空间申请新 Extent</li>
     * </ul>
     *
     * @param mtr       Mini-Transaction
     * @param spaceId   表空间 ID
     * @param segmentId Segment ID
     * @return 新分配的页面 ID
     * @throws MiniDbException 如果操作失败（如表空间无法扩展）
     */
    PageId allocatePageForSegment(MiniTransaction mtr, int spaceId, long segmentId)
            throws MiniDbException;

    /**
     * 释放页面
     *
     * <p>将页面归还给 Segment 或表空间：</p>
     * <ol>
     *   <li>定位页面所属的 Segment（通过 Extent → INODE Entry）</li>
     *   <li>如果是碎片页：从碎片页数组移除，归还到表空间 FREE_FRAG</li>
     *   <li>如果是 Extent 页：更新 Bitmap，检查状态转移（FULL → NOT_FULL）</li>
     * </ol>
     *
     * @param mtr    Mini-Transaction
     * @param pageId 要释放的页面 ID
     * @throws MiniDbException 如果操作失败
     */
    void freePage(MiniTransaction mtr, PageId pageId) throws MiniDbException;

    /**
     * 为 Segment 分配一个完整的 Extent
     *
     * <p>从表空间的 FREE 链表分配一个完全空闲的 Extent，
     * 设置其归属于指定 Segment，并加入 Segment 的 FREE 链表。</p>
     *
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>从表空间 FREE 链表移除第一个 Extent</li>
     *   <li>设置 Extent.segmentId = segmentId</li>
     *   <li>设置 Extent.state = FSEG_FREE</li>
     *   <li>将 Extent 加入 Segment 的 FREE 链表</li>
     * </ol>
     *
     * <p><b>注意</b>：如果表空间 FREE 链表为空，调用者应先调用
     * SpaceManager.extendTablespace() 扩展表空间。</p>
     *
     * @param mtr       Mini-Transaction
     * @param spaceId   表空间 ID
     * @param segmentId Segment ID
     * @return 分配的 Extent 描述符，如果表空间 FREE 链表为空返回 null
     * @throws MiniDbException 如果操作失败
     */
    ExtentDescriptor allocateExtentForSegment(MiniTransaction mtr, int spaceId, long segmentId)
            throws MiniDbException;

    /**
     * 获取 Segment 描述符
     *
     * <p>查找指定 Segment ID 对应的 INODE Entry。
     * 遍历 INODES_FREE 和 INODES_FULL 链表查找。</p>
     *
     * @param mtr       Mini-Transaction
     * @param spaceId   表空间 ID
     * @param segmentId Segment ID
     * @return Segment 描述符，如果未找到返回 null
     * @throws MiniDbException 如果操作失败
     */
    SegmentDescriptor getSegmentDescriptor(MiniTransaction mtr, int spaceId, long segmentId)
            throws MiniDbException;

    /**
     * 获取 Segment 统计信息
     *
     * <p>返回 Segment 的详细统计：</p>
     * <ul>
     *   <li>碎片页使用数（0-32）</li>
     *   <li>FREE/NOT_FULL/FULL 链表长度</li>
     *   <li>总 Extent 数</li>
     *   <li>估算总页数</li>
     * </ul>
     *
     * @param mtr       Mini-Transaction
     * @param spaceId   表空间 ID
     * @param segmentId Segment ID
     * @return 统计信息对象
     * @throws MiniDbException 如果 Segment 不存在
     */
    SegmentStatistics getStatistics(MiniTransaction mtr, int spaceId, long segmentId)
            throws MiniDbException;

    /**
     * Segment 统计信息
     */
    class SegmentStatistics {
        private final long segmentId;
        private final int fragPagesUsed;
        private final int freeExtentCount;
        private final int notFullExtentCount;
        private final int fullExtentCount;
        private final int totalExtentCount;
        private final int estimatedPageCount;

        public SegmentStatistics(long segmentId, int fragPagesUsed,
                                 int freeExtentCount, int notFullExtentCount, int fullExtentCount) {
            this.segmentId = segmentId;
            this.fragPagesUsed = fragPagesUsed;
            this.freeExtentCount = freeExtentCount;
            this.notFullExtentCount = notFullExtentCount;
            this.fullExtentCount = fullExtentCount;
            this.totalExtentCount = freeExtentCount + notFullExtentCount + fullExtentCount;
            this.estimatedPageCount = fragPagesUsed + totalExtentCount * 64;
        }

        public long getSegmentId() {
            return segmentId;
        }

        public int getFragPagesUsed() {
            return fragPagesUsed;
        }

        public int getFreeExtentCount() {
            return freeExtentCount;
        }

        public int getNotFullExtentCount() {
            return notFullExtentCount;
        }

        public int getFullExtentCount() {
            return fullExtentCount;
        }

        public int getTotalExtentCount() {
            return totalExtentCount;
        }

        public int getEstimatedPageCount() {
            return estimatedPageCount;
        }

        @Override
        public String toString() {
            return String.format("SegmentStatistics{segmentId=%d, fragPages=%d, " +
                            "extents=(free=%d, notFull=%d, full=%d), totalPages~=%d}",
                    segmentId, fragPagesUsed, freeExtentCount, notFullExtentCount,
                    fullExtentCount, estimatedPageCount);
        }
    }
}
