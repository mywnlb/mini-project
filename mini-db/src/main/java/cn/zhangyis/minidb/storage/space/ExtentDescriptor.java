package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.common.exception.PageNotManagedByMtrException;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;

/**
 * Extent 描述符（区描述符）- 物理层
 *
 * <p>ExtentDescriptor 封装了 XDES Entry (40字节) 的物理读写操作。
 * 这是纯物理层类，只负责字节级别的 get/put 操作，不包含分配逻辑。</p>
 *
 * <h2>XDES Entry 物理结构（40字节）</h2>
 * <pre>
 * Offset  Size  Field
 * ------  ----  -----
 * 0       8     XDES_ID - Segment ID (0 表示不属于任何 Segment)
 * 8       12    XDES_FLST_NODE - 链表节点
 * 20      4     XDES_STATE - Extent 状态 (FREE/FREE_FRAG/FULL_FRAG/FSEG/FSEG_FREE)
 * 24      16    XDES_BITMAP - 64 页的 Bitmap (每页 2 bits = 128 bits = 16 bytes)
 * </pre>
 *
 * <h2>Bitmap 编码</h2>
 * <p>每个页面使用 2 bits：</p>
 * <ul>
 *   <li>Bit 0 (FREE): 1=空闲, 0=已分配</li>
 *   <li>Bit 1 (CLEAN): 1=干净, 0=脏页 [简化实现可忽略]</li>
 * </ul>
 *
 * <h2>分层设计</h2>
 * <ul>
 *   <li>物理层（本类）：负责 get/put/isPageFree/allocatePage 等底层位操作</li>
 *   <li>逻辑层（Extent类）：负责页面分配决策、搜索算法</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 物理层操作示例
 * ExtentDescriptor descriptor = new ExtentDescriptor(xdesPage, offset, extentNo);
 * long segId = descriptor.getSegmentId();  // 读取字段
 * descriptor.setSegmentId(mtr, 123);       // 修改字段
 * boolean free = descriptor.isPageFree(5); // 检查页面5是否空闲
 * descriptor.allocatePage(mtr, 5);         // 分配页面5（设置bitmap位）
 *
 * // 逻辑层操作应该使用 Extent 类
 * Extent extent = new Extent(spaceId, extentNo, descriptor);
 * int pageNo = extent.allocatePage(mtr);  // 自动查找并分配
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class ExtentDescriptor {

    /**
     * XDES Entry 所在的页面（FSP_HDR Page 或 XDES Page）
     */
    private final Page page;

    /**
     * XDES Entry 在页面中的偏移
     */
    private final int offset;

    /**
     * Extent 编号（全局唯一）
     */
    private final int extentNo;

    /**
     * Extent 起始页号 = extentNo * 64
     */
    private final int startPageNo;

    /**
     * 构造函数
     *
     * @param page     XDES Entry 所在的页面
     * @param offset   XDES Entry 在页面中的偏移
     * @param extentNo Extent 编号
     */
    public ExtentDescriptor(Page page, int offset, int extentNo) {
        if (page == null) {
            throw new IllegalArgumentException("Page cannot be null");
        }
        if (offset < 0 || offset > PAGE_SIZE - XDES_ENTRY_SIZE) {
            throw new IllegalArgumentException("Invalid offset: " + offset);
        }
        if (extentNo < 0) {
            throw new IllegalArgumentException("Invalid extentNo: " + extentNo);
        }

        this.page = page;
        this.offset = offset;
        this.extentNo = extentNo;
        this.startPageNo = extentNo * EXTENT_SIZE;
    }

    // ==================== 基本字段访问 ====================

    /**
     * 获取 Segment ID
     *
     * @return Segment ID，0 表示不属于任何 Segment
     */
    public long getSegmentId() {
        return page.getLong(offset + XDES_ID);
    }

    /**
     * 设置 Segment ID
     *
     * @param mtr       Mini-Transaction
     * @param segmentId Segment ID
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void setSegmentId(MiniTransaction mtr, long segmentId)
            throws PageNotManagedByMtrException, MtrStateException {
        page.putLong(offset + XDES_ID, segmentId);
        mtr.markDirty(page);
    }

    /**
     * 获取 Extent 状态
     *
     * @return ExtentState 枚举
     */
    public ExtentState getState() {
        int stateValue = page.getInt(offset + XDES_STATE);
        return ExtentState.fromValue(stateValue);
    }

    /**
     * 设置 Extent 状态
     *
     * @param mtr   Mini-Transaction
     * @param state 新的状态
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void setState(MiniTransaction mtr, ExtentState state)
            throws PageNotManagedByMtrException, MtrStateException {
        page.putInt(offset + XDES_STATE, state.getValue());
        mtr.markDirty(page);
    }

    /**
     * 获取 Extent 编号
     *
     * @return Extent 编号
     */
    public int getExtentNo() {
        return extentNo;
    }

    /**
     * 获取 Extent 起始页号
     *
     * @return 起始页号 = extentNo * 64
     */
    public int getStartPageNo() {
        return startPageNo;
    }

    /**
     * 获取链表节点
     *
     * @return FlstNode 对象
     */
    public FlstNode getListNode() {
        return new FlstNode(page, offset + XDES_FLST_NODE);
    }

    // ==================== Bitmap 操作 ====================

    /**
     * 判断指定页面是否空闲
     *
     * @param pageOffset 页面在 Extent 中的偏移 (0-63)
     * @return true 如果页面空闲
     */
    public boolean isPageFree(int pageOffset) {
        validatePageOffset(pageOffset);
        return getBit(pageOffset, 0);  // Bit 0 = FREE
    }

    /**
     * 分配页面（将页面标记为已分配）
     *
     * @param mtr        Mini-Transaction
     * @param pageOffset 页面在 Extent 中的偏移 (0-63)
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void allocatePage(MiniTransaction mtr, int pageOffset)
            throws PageNotManagedByMtrException, MtrStateException {
        validatePageOffset(pageOffset);
        if (!isPageFree(pageOffset)) {
            throw new IllegalStateException("Page " + pageOffset + " is already allocated");
        }
        clearBit(mtr, pageOffset, 0);  // 清除 FREE 位
    }

    /**
     * 释放页面（将页面标记为空闲）
     *
     * @param mtr        Mini-Transaction
     * @param pageOffset 页面在 Extent 中的偏移 (0-63)
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void freePage(MiniTransaction mtr, int pageOffset)
            throws PageNotManagedByMtrException, MtrStateException {
        validatePageOffset(pageOffset);
        if (isPageFree(pageOffset)) {
            throw new IllegalStateException("Page " + pageOffset + " is already free");
        }
        setBit(mtr, pageOffset, 0);  // 设置 FREE 位
    }

    /**
     * 初始化 Extent 为 FREE 状态
     *
     * <p>完整的初始化操作包括：</p>
     * <ul>
     *   <li>设置 Segment ID 为 0（不属于任何段）</li>
     *   <li>设置状态为 FREE</li>
     *   <li>初始化 Bitmap（所有页面标记为空闲）</li>
     * </ul>
     *
     * @param mtr Mini-Transaction
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void initialize(MiniTransaction mtr)
            throws PageNotManagedByMtrException, MtrStateException {
        // 1. 设置 Segment ID 为 0
        setSegmentId(mtr, 0);

        // 2. 设置状态为 FREE
        setState(mtr, ExtentState.FREE);

        // 3. 初始化 Bitmap
        initBitmap(mtr);
    }

    /**
     * 初始化 Bitmap（设置所有页面为空闲）
     *
     * @param mtr Mini-Transaction
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void initBitmap(MiniTransaction mtr)
            throws PageNotManagedByMtrException, MtrStateException {
        int bitmapOffset = offset + XDES_BITMAP;
        // 设置所有 bits 为 1（所有页面空闲）
        for (int i = 0; i < 16; i++) {
            page.putByte(bitmapOffset + i, (byte) 0xFF);
        }
        mtr.markDirty(page);
    }

    // ==================== 状态查询 ====================

    /**
     * 判断 Extent 是否已满（所有64页都已分配）
     *
     * @return true 如果已满
     */
    public boolean isFull() {
        return getUsedPageCount() == EXTENT_SIZE;
    }

    /**
     * 判断 Extent 是否为空（所有64页都未分配）
     *
     * @return true 如果为空
     */
    public boolean isEmpty() {
        return getUsedPageCount() == 0;
    }

    /**
     * 获取已使用的页面数量
     *
     * @return 已分配的页面数 (0-64)
     */
    public int getUsedPageCount() {
        int count = 0;
        for (int i = 0; i < EXTENT_SIZE; i++) {
            if (!isPageFree(i)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取空闲页面数量
     *
     * @return 空闲页面数 (0-64)
     */
    public int getFreePageCount() {
        return EXTENT_SIZE - getUsedPageCount();
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 获取指定页面的指定位
     *
     * @param pageOffset 页面偏移 (0-63)
     * @param bitIndex   位索引 (0=FREE, 1=CLEAN)
     * @return true 如果位为 1
     */
    private boolean getBit(int pageOffset, int bitIndex) {
        // 计算字节偏移和位偏移
        int bitPosition = pageOffset * 2 + bitIndex;  // 每页2 bits
        int byteOffset = offset + XDES_BITMAP + (bitPosition / 8);
        int bitOffset = bitPosition % 8;

        byte b = page.getByte(byteOffset);
        return ((b >> bitOffset) & 1) == 1;
    }

    /**
     * 设置指定页面的指定位为 1
     *
     * @param mtr        Mini-Transaction
     * @param pageOffset 页面偏移 (0-63)
     * @param bitIndex   位索引 (0=FREE, 1=CLEAN)
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    private void setBit(MiniTransaction mtr, int pageOffset, int bitIndex)
            throws PageNotManagedByMtrException, MtrStateException {
        int bitPosition = pageOffset * 2 + bitIndex;
        int byteOffset = offset + XDES_BITMAP + (bitPosition / 8);
        int bitOffset = bitPosition % 8;

        byte b = page.getByte(byteOffset);
        b |= (1 << bitOffset);
        page.putByte(byteOffset, b);
        mtr.markDirty(page);
    }

    /**
     * 清除指定页面的指定位为 0
     *
     * @param mtr        Mini-Transaction
     * @param pageOffset 页面偏移 (0-63)
     * @param bitIndex   位索引 (0=FREE, 1=CLEAN)
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    private void clearBit(MiniTransaction mtr, int pageOffset, int bitIndex)
            throws PageNotManagedByMtrException, MtrStateException {
        int bitPosition = pageOffset * 2 + bitIndex;
        int byteOffset = offset + XDES_BITMAP + (bitPosition / 8);
        int bitOffset = bitPosition % 8;

        byte b = page.getByte(byteOffset);
        b &= ~(1 << bitOffset);
        page.putByte(byteOffset, b);
        mtr.markDirty(page);
    }

    /**
     * 验证页面偏移的有效性
     *
     * @param pageOffset 页面偏移
     */
    private void validatePageOffset(int pageOffset) {
        if (pageOffset < 0 || pageOffset >= EXTENT_SIZE) {
            throw new IllegalArgumentException(
                    "Invalid page offset: " + pageOffset + " (must be 0-63)");
        }
    }

    @Override
    public String toString() {
        return String.format("ExtentDescriptor{extentNo=%d, startPage=%d, state=%s, " +
                        "segmentId=%d, used=%d/64}",
                extentNo, startPageNo, getState(), getSegmentId(), getUsedPageCount());
    }
}
