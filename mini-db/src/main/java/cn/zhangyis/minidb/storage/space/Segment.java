package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;

/**
 * Segment（段）逻辑管理
 *
 * <p>Segment是InnoDB中数据组织的逻辑单位，一个索引包含两个段：
 * <ul>
 *   <li>Leaf Segment: 存储叶子节点（数据）</li>
 *   <li>Non-Leaf Segment: 存储非叶子节点（索引）</li>
 * </ul>
 * </p>
 *
 * <h2>InnoDB的段空间分配策略</h2>
 * <pre>
 * 1. 前32个页面：从表空间的碎片区中分配单个页面（Fragment Page）
 *    - 目的：避免小表浪费空间（小表不需要整个1MB的Extent）
 *    - 存储位置：INODE Entry的32个碎片页数组
 *
 * 2. 第33个页面开始：分配完整的Extent（64页 = 1MB）
 *    - FREE链表：完全空闲的Extent
 *    - NOT_FULL链表：部分使用的Extent
 *    - FULL链表：完全占满的Extent
 * </pre>
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li>逻辑层：本类负责分配决策、链表管理</li>
 *   <li>物理层：SegmentDescriptor负责读写192字节INODE Entry</li>
 *   <li>所有物理修改必须通过MTR</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * // B+Tree叶子节点分裂，需要新页面
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     Segment leafSegment = tableSpace.getSegment(mtr, leafSegmentId);
 *
 *     // 分配页面（自动选择碎片页或Extent）
 *     Page newPage = leafSegment.allocatePage(mtr);
 *     if (newPage != null) {
 *         // 初始化新页面...
 *     }
 *
 *     mtr.commit();
 * }
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class Segment {

    /**
     * 表空间ID
     */
    private final int spaceId;

    /**
     * Segment ID
     */
    private final long segmentId;

    /**
     * 物理结构：INODE Entry描述符
     */
    private final SegmentDescriptor descriptor;

    /**
     * 所属表空间（用于分配新Extent）
     */
    private final TableSpace tableSpace;

    /**
     * 构造函数
     *
     * @param spaceId    表空间ID
     * @param segmentId  Segment ID
     * @param descriptor 物理结构描述符
     * @param tableSpace 所属表空间
     */
    public Segment(int spaceId, long segmentId, SegmentDescriptor descriptor, TableSpace tableSpace) {
        if (descriptor == null) {
            throw new IllegalArgumentException("SegmentDescriptor cannot be null");
        }
        if (tableSpace == null) {
            throw new IllegalArgumentException("TableSpace cannot be null");
        }
        if (spaceId < 0) {
            throw new IllegalArgumentException("Invalid spaceId: " + spaceId);
        }
        if (segmentId <= 0) {
            throw new IllegalArgumentException("Invalid segmentId: " + segmentId);
        }

        this.spaceId = spaceId;
        this.segmentId = segmentId;
        this.descriptor = descriptor;
        this.tableSpace = tableSpace;
    }

    /**
     * 获取表空间ID
     */
    public int getSpaceId() {
        return spaceId;
    }

    /**
     * 获取Segment ID
     */
    public long getSegmentId() {
        return segmentId;
    }

    /**
     * 分配一个页面
     *
     * <p>实现InnoDB的32个碎片页策略：</p>
     * <ol>
     *   <li>如果碎片数组未满（< 32），从表空间分配碎片页</li>
     *   <li>否则，从NOT_FULL链表的Extent分配</li>
     *   <li>如果NOT_FULL为空，从表空间申请新Extent</li>
     * </ol>
     *
     * @param mtr Mini-Transaction
     * @return 分配的页面，如果失败返回null
     * @throws MiniDbException 如果操作失败
     */
    public Page allocatePage(MiniTransaction mtr) throws MiniDbException {
        // 策略1：尝试分配碎片页（前32个页面）
        int fragSlot = findFreeFragSlot();  // 逻辑层搜索
        if (fragSlot != -1) {
            return allocateFragmentPage(mtr, fragSlot);
        }

        // 策略2：从NOT_FULL链表分配
        Extent notFullExtent = getNotFullExtent(mtr);
        if (notFullExtent != null) {
            int pageNo = notFullExtent.allocatePage(mtr);
            if (pageNo != -1) {
                // 使用 NEW_PAGE 模式，因为 extent 中的页面可能尚未在 Buffer Pool 中
                Page page = mtr.getPage(PageId.of(spaceId, pageNo), BufferPool.FetchMode.NEW_PAGE);

                // 如果Extent满了，移到FULL链表
                if (notFullExtent.isFull()) {
                    moveExtentToFull(mtr, notFullExtent);
                }

                return page;
            }
        }

        // 策略3：申请新Extent
        Extent newExtent = tableSpace.allocateExtentForSegment(mtr, segmentId);
        if (newExtent != null) {
            // 添加到NOT_FULL链表
            addExtentToNotFull(mtr, newExtent);

            // 从新Extent分配第一个页面
            int pageNo = newExtent.allocatePage(mtr);
            if (pageNo != -1) {
                // 使用 NEW_PAGE 模式
                return mtr.getPage(PageId.of(spaceId, pageNo), BufferPool.FetchMode.NEW_PAGE);
            }
        }

        return null;  // 分配失败（表空间满？）
    }

    /**
     * 释放一个页面
     *
     * <p>实现完整的页面释放逻辑，包括碎片页释放和Extent链表迁移。</p>
     *
     * <h3>碎片页释放</h3>
     * <p>释放碎片页时需要：</p>
     * <ol>
     *   <li>清除碎片数组中的槽位</li>
     *   <li>调用表空间释放碎片页（清除XDES bitmap并维护FREE_FRAG/FULL_FRAG链表）</li>
     * </ol>
     *
     * <h3>Extent页面释放的迁移规则</h3>
     * <ul>
     *   <li>FULL → NOT_FULL: 当满的Extent释放一页后变为部分使用</li>
     *   <li>NOT_FULL → FREE: 当部分使用的Extent释放最后一页后变为完全空闲</li>
     * </ul>
     *
     * @param mtr    Mini-Transaction
     * @param pageNo 要释放的页号
     * @throws MiniDbException 如果操作失败
     */
    public void freePage(MiniTransaction mtr, int pageNo) throws MiniDbException {
        // 1. 检查是否为碎片页
        for (int i = 0; i < FRAG_ARRAY_SIZE; i++) {
            if (descriptor.getFragPageNo(i) == pageNo) {
                // 释放碎片页：清除槽位并通知表空间释放
                descriptor.setFragPageNo(mtr, i, FIL_NULL);
                // 关键修复：调用表空间释放碎片页，清除XDES bitmap
                tableSpace.freeFragmentPage(mtr, pageNo);
                return;
            }
        }

        // 2. 从Extent中释放
        int extentNo = XdesLocator.pageNoToExtentNo(pageNo);
        Extent extent = tableSpace.getExtent(mtr, extentNo);

        // 验证页面属于此Segment
        if (extent.getSegmentId() != segmentId) {
            throw new MiniDbException(
                    String.format("Page %d (extent %d) does not belong to segment %d",
                            pageNo, extentNo, segmentId));
        }

        boolean wasFullBefore = extent.isFull();
        extent.freePage(mtr, pageNo);
        boolean isEmptyAfter = extent.isEmpty();

        // 3. 更新链表 - 完整迁移规则
        if (wasFullBefore) {
            // FULL → NOT_FULL: 满变为部分使用
            moveExtentFromFullToNotFull(mtr, extent);
        }

        if (isEmptyAfter) {
            // NOT_FULL → FREE: 部分使用变为完全空闲，归还给表空间
            removeExtentFromNotFull(mtr, extent);
            tableSpace.returnExtentToFree(mtr, extent);
        }
    }

    /**
     * 查找碎片数组中第一个空闲槽位（逻辑层搜索）
     *
     * @return 空闲槽位索引（0-31），如果已满返回-1
     */
    private int findFreeFragSlot() {
        for (int i = 0; i < FRAG_ARRAY_SIZE; i++) {
            if (descriptor.getFragPageNo(i) == FIL_NULL) {  // 调用物理层读取
                return i;
            }
        }
        return -1;
    }

    /**
     * 获取NOT_FULL链表的第一个Extent
     *
     * <p>使用 XdesLocator 从 FlstNode 地址定位 Extent，消除魔法偏移。</p>
     */
    private Extent getNotFullExtent(MiniTransaction mtr) throws MiniDbException {
        FlstBaseNode notFullList = descriptor.getNotFullList();
        if (notFullList.isEmpty()) {
            return null;
        }

        // 使用 getFirstNodeAddr 获取完整地址
        FilAddr nodeAddr = notFullList.getFirstNodeAddr();
        if (nodeAddr.isNull()) {
            return null;
        }

        // 使用 XdesLocator 从 FlstNode 地址定位 Extent
        XdesLocator locator = XdesLocator.fromNodeAddr(nodeAddr);
        return locator.getExtent(mtr, spaceId);
    }

    /**
     * 从碎片区分配页面
     */
    private Page allocateFragmentPage(MiniTransaction mtr, int fragSlot) throws MiniDbException {
        // 从表空间的碎片区分配一个页面
        Page page = tableSpace.allocateFragmentPage(mtr);
        if (page == null) {
            return null;
        }

        // 记录到碎片数组
        descriptor.setFragPageNo(mtr, fragSlot, page.getPageNo());
        return page;
    }

    /**
     * 将Extent添加到NOT_FULL链表
     */
    private void addExtentToNotFull(MiniTransaction mtr, Extent extent) throws MiniDbException {
        FlstBaseNode notFullList = descriptor.getNotFullList();
        FlstNode extentNode = extent.getListNode();

        // 添加到链表尾部
        Page extentPage = extentNode.getPage();
        int extentNodeOffset = extentNode.getOffset();
        notFullList.addLast(mtr, extentPage, extentNodeOffset);
    }

    /**
     * 将Extent移到FULL链表
     */
    private void moveExtentToFull(MiniTransaction mtr, Extent extent) throws MiniDbException {
        // 从NOT_FULL移除
        removeExtentFromNotFull(mtr, extent);

        // 添加到FULL
        FlstBaseNode fullList = descriptor.getFullList();
        FlstNode extentNode = extent.getListNode();
        Page extentPage = extentNode.getPage();
        int extentNodeOffset = extentNode.getOffset();
        fullList.addLast(mtr, extentPage, extentNodeOffset);
    }

    /**
     * 将Extent从FULL移到NOT_FULL
     */
    private void moveExtentFromFullToNotFull(MiniTransaction mtr, Extent extent) throws MiniDbException {
        // 1. 从FULL链表移除
        FlstBaseNode fullList = descriptor.getFullList();
        FlstNode extentNode = extent.getListNode();
        Page extentPage = extentNode.getPage();
        int extentNodeOffset = extentNode.getOffset();
        fullList.remove(mtr, extentPage, extentNodeOffset);

        // 2. 添加到NOT_FULL链表
        addExtentToNotFull(mtr, extent);
    }

    /**
     * 从NOT_FULL链表移除Extent
     */
    private void removeExtentFromNotFull(MiniTransaction mtr, Extent extent) throws MiniDbException {
        FlstBaseNode notFullList = descriptor.getNotFullList();
        FlstNode extentNode = extent.getListNode();
        Page extentPage = extentNode.getPage();
        int extentNodeOffset = extentNode.getOffset();
        notFullList.remove(mtr, extentPage, extentNodeOffset);
    }

    /**
     * 获取碎片页数量
     */
    public int getFragmentPageCount() {
        return descriptor.getFragUsedCount();
    }

    /**
     * 获取Extent总数（FREE + NOT_FULL + FULL）
     */
    public int getTotalExtentCount() {
        return descriptor.getTotalExtentCount();
    }

    /**
     * 估算页面总数
     */
    public int getEstimatedPageCount() {
        return descriptor.getEstimatedPageCount();
    }

    /**
     * 删除 Segment 并释放所有资源
     *
     * <p>释放 Segment 拥有的所有资源：</p>
     * <ol>
     *   <li>释放碎片页数组中的所有页面</li>
     *   <li>释放 FREE 链表中的所有 Extent</li>
     *   <li>释放 NOT_FULL 链表中的所有 Extent</li>
     *   <li>释放 FULL 链表中的所有 Extent</li>
     *   <li>清空 INODE Entry（Segment ID = 0）</li>
     * </ol>
     *
     * @param mtr Mini-Transaction
     * @throws MiniDbException 如果操作失败
     */
    public void drop(MiniTransaction mtr) throws MiniDbException {
        // 1. 释放碎片页数组中的所有页面
        for (int i = 0; i < FRAG_ARRAY_SIZE; i++) {
            int pageNo = descriptor.getFragPageNo(i);
            if (pageNo != FIL_NULL) {
                tableSpace.freeFragmentPage(mtr, pageNo);
                descriptor.setFragPageNo(mtr, i, FIL_NULL);
            }
        }

        // 2. 释放 FREE 链表中的所有 Extent
        releaseExtentList(mtr, descriptor.getFreeList());

        // 3. 释放 NOT_FULL 链表中的所有 Extent
        releaseExtentList(mtr, descriptor.getNotFullList());

        // 4. 释放 FULL 链表中的所有 Extent
        releaseExtentList(mtr, descriptor.getFullList());

        // 5. 清空 INODE Entry
        descriptor.clear(mtr);
    }

    /**
     * 释放 Extent 链表中的所有 Extent（归还到表空间 FREE 链表）
     *
     * @param mtr        Mini-Transaction
     * @param extentList Extent 链表
     * @throws MiniDbException 如果操作失败
     */
    private void releaseExtentList(MiniTransaction mtr, FlstBaseNode extentList) throws MiniDbException {
        while (!extentList.isEmpty()) {
            // 获取并移除第一个节点
            FilAddr removedNodeAddr = extentList.removeFirstAndGetAddr(mtr);
            if (removedNodeAddr.isNull()) {
                break;
            }

            // 使用 XdesLocator 定位 Extent
            XdesLocator locator = XdesLocator.fromNodeAddr(removedNodeAddr);
            Extent extent = locator.getExtent(mtr, spaceId);

            // 重新初始化为 FREE 状态并归还给表空间
            extent.initialize(mtr);
            tableSpace.returnExtentToFree(mtr, extent);
        }
    }

    /**
     * 获取 Segment 统计信息
     *
     * @return 统计信息对象
     */
    public SegmentStatistics getStatistics() {
        return new SegmentStatistics(
                segmentId,
                descriptor.getFragUsedCount(),
                descriptor.getFreeList().getLength(),
                descriptor.getNotFullList().getLength(),
                descriptor.getFullList().getLength()
        );
    }

    @Override
    public String toString() {
        return String.format("Segment{spaceId=%d, segmentId=%d, frags=%d, extents=%d, estimatedPages=%d}",
                spaceId, segmentId, getFragmentPageCount(), getTotalExtentCount(), getEstimatedPageCount());
    }

    // ==================== 内部类 ====================

    /**
     * Segment 统计信息
     *
     * <p>包含 Segment 的详细使用统计：</p>
     * <ul>
     *   <li>碎片页使用数（0-32）</li>
     *   <li>FREE/NOT_FULL/FULL 链表长度</li>
     *   <li>总 Extent 数</li>
     *   <li>估算总页数</li>
     * </ul>
     */
    public static class SegmentStatistics {
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
