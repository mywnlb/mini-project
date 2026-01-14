package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.PageId;

/**
 * Space Manager - 表空间管理器接口
 *
 * <p>SpaceManager 是表空间管理的最高层抽象，负责整个表空间的生命周期管理、
 * Extent 分配和碎片页管理。它协调 SegmentManager 和 ExtentManager，
 * 提供统一的表空间操作接口。</p>
 *
 * <h2>核心职责</h2>
 * <ul>
 *   <li><b>表空间生命周期</b>: 初始化、打开、关闭表空间</li>
 *   <li><b>Extent 分配</b>: 从表空间 FREE 链表分配 Extent 给 Segment</li>
 *   <li><b>碎片页管理</b>: 管理 FREE_FRAG 和 FULL_FRAG 链表，用于小段的页面分配</li>
 *   <li><b>表空间扩展</b>: 自动扩展表空间，分配新的 Extent</li>
 *   <li><b>统计信息</b>: 提供表空间的使用情况统计</li>
 * </ul>
 *
 * <h2>架构层次</h2>
 * <pre>
 * SpaceManager (本接口) - 表空间整体管理
 *      ↓
 * SegmentManager - Segment 粒度管理
 *      ↓
 * ExtentManager - Extent 粒度管理
 *      ↓
 * FspHeaderPage, XdesPage, InodePage - 物理层
 * </pre>
 *
 * <h2>表空间初始化流程</h2>
 * <pre>
 * 1. 创建物理文件（通过 DiskManager）
 * 2. 初始化 Page 0 (FSP_HDR)：
 *    - 设置表空间元数据（spaceId, size, freeLimit）
 *    - 初始化6个 Extent 链表
 *    - 初始化 XDES Array（Extent 0-255）
 * 3. 扩展表空间到第一个 Extent（64页）
 * 4. 创建第一个 INODE Page（Page 2）
 * 5. 将 INODE Page 加入 INODES_FREE 链表
 * </pre>
 *
 * <h2>碎片页分配策略</h2>
 * <pre>
 * 碎片页用于 Segment 的前32页分配：
 *
 * 1. allocateFragPage():
 *    - 检查 FREE_FRAG 链表
 *    - 如果为空，从 FREE 链表分配一个 Extent，标记为 FREE_FRAG
 *    - 在 FREE_FRAG Extent 中分配一页
 *    - 如果 Extent 变满，移到 FULL_FRAG 链表
 *
 * 2. freeFragPage():
 *    - 如果页面属于 FULL_FRAG Extent，移回 FREE_FRAG
 *    - 如果 Extent 变空，移回 FREE 链表
 * </pre>
 *
 * <h2>表空间扩展策略</h2>
 * <pre>
 * 当 FREE 链表为空时，自动扩展表空间：
 *
 * 1. 每次扩展 4 个 Extent（256 页 = 4MB）
 * 2. 如果跨越 XDES Page 边界（16384的倍数），自动创建新 XDES Page
 * 3. 初始化新 Extent 为 FREE 状态，加入 FREE 链表
 * </pre>
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 初始化新表空间
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     spaceManager.initializeTablespace(mtr, spaceId, "my_table");
 *     mtr.commit();
 * }
 *
 * // 分配碎片页（用于 Segment 的前32页）
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     PageId fragPage = spaceManager.allocateFragPage(mtr, spaceId);
 *     mtr.commit();
 * }
 *
 * // 分配 Extent 给 Segment
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     ExtentDescriptor extent = spaceManager.allocateExtent(mtr, spaceId, segmentId);
 *     mtr.commit();
 * }
 * </pre>
 *
 * <h2>线程安全</h2>
 * <p>SpaceManager 的实现必须保证线程安全，通过 MiniTransaction 确保操作的原子性。</p>
 *
 * <h2>InnoDB 对应</h2>
 * <p>对应 InnoDB 的 fsp0fsp.cc 中的表空间管理功能。</p>
 *
 * @author MiniDB
 * @version 1.0
 * @see SegmentManager
 * @see ExtentManager
 * @see FspHeaderPage
 */
public interface SpaceManager {

    // ==================== 表空间生命周期 ====================

    /**
     * 初始化新表空间
     *
     * <p>创建并初始化一个新的表空间，包括：</p>
     * <ol>
     *   <li>初始化 Page 0 (FSP_HDR)，设置表空间元数据</li>
     *   <li>初始化 XDES Array（Extent 0-255）</li>
     *   <li>扩展到第一个 Extent（64页）</li>
     *   <li>创建第一个 INODE Page（Page 2）</li>
     *   <li>将 INODE Page 加入 INODES_FREE 链表</li>
     * </ol>
     *
     * <p><b>注意</b>：此方法会自动 commit MTR。</p>
     *
     * @param mtr     Mini-Transaction
     * @param spaceId 表空间ID
     * @throws MiniDbException 如果初始化失败
     */
    void initializeTablespace(MiniTransaction mtr, int spaceId) throws MiniDbException;

    // ==================== Extent 分配和释放 ====================

    /**
     * 从表空间 FREE 链表分配 Extent
     *
     * <p>分配流程：</p>
     * <ol>
     *   <li>检查 FSP_FREE 链表是否为空</li>
     *   <li>如果为空，调用 extendTablespace() 扩展表空间</li>
     *   <li>从 FREE 链表移除第一个 Extent</li>
     *   <li>返回 ExtentDescriptor</li>
     * </ol>
     *
     * <p><b>注意</b>：此方法只是从表空间分配 Extent，不设置 Extent 的归属。
     * 调用者需要自行设置 Extent 的 segmentId 和 state。</p>
     *
     * @param mtr     Mini-Transaction
     * @param spaceId 表空间ID
     * @return ExtentDescriptor，如果分配失败返回 null
     * @throws MiniDbException 如果操作失败
     */
    ExtentDescriptor allocateExtent(MiniTransaction mtr, int spaceId) throws MiniDbException;

    /**
     * 释放 Extent 回表空间 FREE 链表
     *
     * <p>释放流程：</p>
     * <ol>
     *   <li>重新初始化 Extent（清空 segmentId，重置 bitmap，设置状态为 FREE）</li>
     *   <li>从原链表移除 Extent</li>
     *   <li>加入 FSP_FREE 链表</li>
     * </ol>
     *
     * @param mtr    Mini-Transaction
     * @param extent 要释放的 Extent
     * @throws MiniDbException 如果操作失败
     */
    void freeExtent(MiniTransaction mtr, ExtentDescriptor extent) throws MiniDbException;

    // ==================== 碎片页分配和释放 ====================

    /**
     * 分配碎片页（用于 Segment 的前32页）
     *
     * <p>碎片页分配流程：</p>
     * <ol>
     *   <li>检查 FSP_FREE_FRAG 链表</li>
     *   <li>如果为空，从 FREE 链表分配一个 Extent，设置为 FREE_FRAG 状态</li>
     *   <li>在 FREE_FRAG Extent 中分配一个空闲页</li>
     *   <li>如果 Extent 变满，移到 FULL_FRAG 链表</li>
     *   <li>返回分配的 PageId</li>
     * </ol>
     *
     * @param mtr     Mini-Transaction
     * @param spaceId 表空间ID
     * @return 分配的 PageId，如果分配失败返回 null
     * @throws MiniDbException 如果操作失败
     */
    PageId allocateFragPage(MiniTransaction mtr, int spaceId) throws MiniDbException;

    /**
     * 释放碎片页
     *
     * <p>释放流程：</p>
     * <ol>
     *   <li>确定页面所属的 Extent</li>
     *   <li>在 Extent 中释放该页</li>
     *   <li>如果 Extent 从 FULL_FRAG 变为 FREE_FRAG，迁移链表</li>
     *   <li>如果 Extent 变空，移回 FREE 链表</li>
     * </ol>
     *
     * @param mtr    Mini-Transaction
     * @param pageId 要释放的页面ID
     * @throws MiniDbException 如果操作失败
     */
    void freeFragPage(MiniTransaction mtr, PageId pageId) throws MiniDbException;

    // ==================== 表空间扩展 ====================

    /**
     * 扩展表空间
     *
     * <p>扩展流程：</p>
     * <ol>
     *   <li>计算当前表空间大小（从 FSP_SIZE 读取）</li>
     *   <li>分配新的页面（通过 DiskManager）</li>
     *   <li>如果跨越 XDES Page 边界（16384的倍数），创建新 XDES Page</li>
     *   <li>初始化新 Extent 为 FREE 状态</li>
     *   <li>将新 Extent 加入 FSP_FREE 链表</li>
     *   <li>更新 FSP_SIZE 和 FSP_FREE_LIMIT</li>
     * </ol>
     *
     * @param mtr         Mini-Transaction
     * @param spaceId     表空间ID
     * @param extentCount 扩展的 Extent 数量（每个 Extent = 64 页 = 1MB）
     * @throws MiniDbException 如果扩展失败
     */
    void extendTablespace(MiniTransaction mtr, int spaceId, int extentCount) throws MiniDbException;

    // ==================== 统计信息 ====================

    /**
     * 获取表空间统计信息
     *
     * @param mtr     Mini-Transaction
     * @param spaceId 表空间ID
     * @return SpaceStatistics 对象
     * @throws MiniDbException 如果操作失败
     */
    SpaceStatistics getStatistics(MiniTransaction mtr, int spaceId) throws MiniDbException;

    /**
     * 表空间统计信息
     */
    class SpaceStatistics {
        private final int spaceId;
        private final int totalPages;          // 总页数
        private final int freeExtents;         // FREE 链表中的 Extent 数
        private final int freeFragExtents;     // FREE_FRAG 链表中的 Extent 数
        private final int fullFragExtents;     // FULL_FRAG 链表中的 Extent 数
        private final int usedPages;           // 已使用的页数（估算）
        private final long nextSegmentId;      // 下一个 Segment ID

        public SpaceStatistics(int spaceId, int totalPages, int freeExtents,
                               int freeFragExtents, int fullFragExtents,
                               int usedPages, long nextSegmentId) {
            this.spaceId = spaceId;
            this.totalPages = totalPages;
            this.freeExtents = freeExtents;
            this.freeFragExtents = freeFragExtents;
            this.fullFragExtents = fullFragExtents;
            this.usedPages = usedPages;
            this.nextSegmentId = nextSegmentId;
        }

        public int getSpaceId() {
            return spaceId;
        }

        public int getTotalPages() {
            return totalPages;
        }

        public int getFreeExtents() {
            return freeExtents;
        }

        public int getFreeFragExtents() {
            return freeFragExtents;
        }

        public int getFullFragExtents() {
            return fullFragExtents;
        }

        public int getUsedPages() {
            return usedPages;
        }

        public long getNextSegmentId() {
            return nextSegmentId;
        }

        public double getUsageRate() {
            return totalPages > 0 ? (double) usedPages / totalPages : 0.0;
        }

        @Override
        public String toString() {
            return String.format("SpaceStatistics{spaceId=%d, totalPages=%d, usedPages=%d, " +
                            "usage=%.2f%%, freeExtents=%d, freeFragExtents=%d, fullFragExtents=%d, nextSegId=%d}",
                    spaceId, totalPages, usedPages, getUsageRate() * 100,
                    freeExtents, freeFragExtents, fullFragExtents, nextSegmentId);
        }
    }
}
