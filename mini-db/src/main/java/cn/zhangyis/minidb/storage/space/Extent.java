package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;

/**
 * Extent（区）逻辑管理
 *
 * <p>Extent是InnoDB空间管理的基本单位，包含64个连续的页面（1MB）。
 * 本类封装区的逻辑操作，将分配算法与物理结构分离。</p>
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li>逻辑层：负责页面分配决策、bitmap搜索算法</li>
 *   <li>物理层：ExtentDescriptor负责读写40字节XDES Entry</li>
 *   <li>所有物理修改必须在MTR中完成</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     Extent extent = tableSpace.getExtent(mtr, extentNo);
 *
 *     // 分配页面
 *     int pageNo = extent.allocatePage(mtr);
 *     if (pageNo != -1) {
 *         Page page = mtr.getPage(PageId.of(spaceId, pageNo));
 *         // 使用页面...
 *     }
 *
 *     mtr.commit();
 * }
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class Extent {

    /**
     * 表空间ID
     */
    private final int spaceId;

    /**
     * Extent编号
     */
    private final int extentNo;

    /**
     * 物理结构：XDES Entry描述符
     */
    private final ExtentDescriptor descriptor;

    /**
     * 构造函数
     *
     * @param spaceId    表空间ID
     * @param extentNo   Extent编号
     * @param descriptor 物理结构描述符
     */
    public Extent(int spaceId, int extentNo, ExtentDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("ExtentDescriptor cannot be null");
        }
        if (spaceId < 0) {
            throw new IllegalArgumentException("Invalid spaceId: " + spaceId);
        }
        if (extentNo < 0) {
            throw new IllegalArgumentException("Invalid extentNo: " + extentNo);
        }

        this.spaceId = spaceId;
        this.extentNo = extentNo;
        this.descriptor = descriptor;
    }

    /**
     * 获取表空间ID
     */
    public int getSpaceId() {
        return spaceId;
    }

    /**
     * 获取Extent编号
     */
    public int getExtentNo() {
        return extentNo;
    }

    /**
     * 获取起始页号
     */
    public int getStartPageNo() {
        return descriptor.getStartPageNo();
    }

    /**
     * 获取所属Segment ID
     */
    public long getSegmentId() {
        return descriptor.getSegmentId();
    }

    /**
     * 设置所属Segment ID
     */
    public void setSegmentId(MiniTransaction mtr, long segmentId) throws MiniDbException {
        descriptor.setSegmentId(mtr, segmentId);
    }

    /**
     * 获取Extent状态
     */
    public ExtentState getState() {
        return descriptor.getState();
    }

    /**
     * 设置Extent状态
     */
    public void setState(MiniTransaction mtr, ExtentState state) throws MiniDbException {
        descriptor.setState(mtr, state);
    }

    /**
     * 获取链表节点（用于FREE/NOT_FULL/FULL链表）
     */
    public FlstNode getListNode() {
        return descriptor.getListNode();
    }

    /**
     * 分配一个页面
     *
     * <p>逻辑操作：在Extent的64个页面中查找第一个空闲页，更新bitmap。</p>
     *
     * @param mtr Mini-Transaction
     * @return 分配的页号，如果没有空闲页返回-1
     * @throws MiniDbException 如果操作失败
     */
    public int allocatePage(MiniTransaction mtr) throws MiniDbException {
        // 1. 查找空闲页（逻辑：遍历bitmap）
        int pageOffset = findFreePage();
        if (pageOffset == -1) {
            return -1;  // 无空闲页
        }

        // 2. 标记为已使用（物理：修改bitmap）
        descriptor.allocatePage(mtr, pageOffset);

        // 3. 返回实际页号
        return getStartPageNo() + pageOffset;
    }

    /**
     * 释放一个页面
     *
     * @param mtr    Mini-Transaction
     * @param pageNo 要释放的页号
     * @throws MiniDbException 如果操作失败
     */
    public void freePage(MiniTransaction mtr, int pageNo) throws MiniDbException {
        int startPageNo = getStartPageNo();
        if (pageNo < startPageNo || pageNo >= startPageNo + 64) {
            throw new IllegalArgumentException(
                    String.format("PageNo %d is not in extent %d (range: %d-%d)",
                            pageNo, extentNo, startPageNo, startPageNo + 63));
        }

        int pageOffset = pageNo - startPageNo;
        descriptor.freePage(mtr, pageOffset);
    }

    /**
     * 查找空闲页
     *
     * <p>逻辑操作：遍历bitmap查找第一个FREE bit。</p>
     *
     * @return 页面偏移（0-63），如果没有空闲页返回-1
     */
    private int findFreePage() {
        // 遍历64个页面，查找第一个空闲页（逻辑层操作）
        for (int i = 0; i < 64; i++) {
            if (descriptor.isPageFree(i)) {  // 调用物理层的位检查
                return i;
            }
        }
        return -1;
    }

    /**
     * 获取已使用页面数
     */
    public int getUsedPageCount() {
        return descriptor.getUsedPageCount();
    }

    /**
     * 获取空闲页面数
     */
    public int getFreePageCount() {
        return descriptor.getFreePageCount();
    }

    /**
     * 判断是否为空（所有页面都空闲）
     */
    public boolean isEmpty() {
        return descriptor.isEmpty();
    }

    /**
     * 判断是否已满（所有页面都已分配）
     */
    public boolean isFull() {
        return descriptor.isFull();
    }

    /**
     * 初始化Extent为FREE状态
     *
     * @param mtr Mini-Transaction
     * @throws MiniDbException 如果操作失败
     */
    public void initialize(MiniTransaction mtr) throws MiniDbException {
        descriptor.initialize(mtr);
    }

    @Override
    public String toString() {
        return String.format("Extent{spaceId=%d, extentNo=%d, startPage=%d, state=%s, used=%d/64}",
                spaceId, extentNo, getStartPageNo(), getState(), getUsedPageCount());
    }
}
