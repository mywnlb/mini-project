package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;

/**
 * XDES 定位器（Extent Descriptor Locator）
 *
 * <p>XdesLocator 封装了从 Extent 编号到 XDES Entry 位置的计算，
 * 以及从 FlstNode 地址反推 ExtentDescriptor 的逻辑。</p>
 *
 * <h2>设计目的</h2>
 * <p>消除逻辑层中的魔法数字（如 -8 偏移），将所有 XDES 定位逻辑集中管理：</p>
 * <ul>
 *   <li>extentNo → XDES Page 页号</li>
 *   <li>extentNo → XDES Entry 偏移</li>
 *   <li>extentNo → FlstNode 偏移</li>
 *   <li>FlstNode 地址 → ExtentNo + ExtentDescriptor</li>
 * </ul>
 *
 * <h2>XDES Entry 结构关系</h2>
 * <pre>
 * XDES Entry (40 bytes):
 * +--------+------------+---------+---------+
 * | XDES_ID| FLST_NODE  | STATE   | BITMAP  |
 * | 8B     | 12B        | 4B      | 16B     |
 * +--------+------------+---------+---------+
 * offset 0  offset 8    offset 20 offset 24
 *
 * FlstNode 位于 Entry 内偏移 8 处（XDES_FLST_NODE = 8）
 * 因此：entryOffset = nodeOffset - XDES_FLST_NODE
 * </pre>
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 从 extentNo 获取定位信息
 * XdesLocator locator = XdesLocator.fromExtentNo(100);
 * int xdesPageNo = locator.getXdesPageNo();
 * int entryOffset = locator.getEntryOffset();
 * int nodeOffset = locator.getNodeOffset();
 *
 * // 从 FlstNode 地址反推
 * XdesLocator locator = XdesLocator.fromNodeAddr(pageNo, nodeOffset);
 * int extentNo = locator.getExtentNo();
 * ExtentDescriptor desc = locator.getDescriptor(mtr);
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class XdesLocator {

    /**
     * Extent 编号
     */
    private final int extentNo;

    /**
     * XDES Page 页号
     */
    private final int xdesPageNo;

    /**
     * XDES Entry 在页面中的偏移
     */
    private final int entryOffset;

    /**
     * FlstNode 在页面中的偏移
     */
    private final int nodeOffset;

    /**
     * 私有构造函数
     */
    private XdesLocator(int extentNo, int xdesPageNo, int entryOffset) {
        this.extentNo = extentNo;
        this.xdesPageNo = xdesPageNo;
        this.entryOffset = entryOffset;
        this.nodeOffset = entryOffset + XDES_FLST_NODE;
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 从 Extent 编号创建定位器
     *
     * @param extentNo Extent 编号
     * @return XdesLocator 对象
     */
    public static XdesLocator fromExtentNo(int extentNo) {
        if (extentNo < 0) {
            throw new IllegalArgumentException("Invalid extentNo: " + extentNo);
        }

        // 计算 XDES Page 页号
        int xdesPageNo = (extentNo / EXTENTS_PER_GROUP) * PAGES_PER_EXTENT_GROUP;

        // 计算 XDES Entry 偏移
        int localIndex = extentNo % EXTENTS_PER_GROUP;
        int entryOffset;
        if (xdesPageNo == 0) {
            // FSP_HDR Page: XDES Array 在 FSP Header 之后
            entryOffset = XDES_ARR_OFFSET + localIndex * XDES_ENTRY_SIZE;
        } else {
            // 普通 XDES Page: XDES Array 紧跟 FIL Header
            entryOffset = FIL_HEADER_SIZE + localIndex * XDES_ENTRY_SIZE;
        }

        return new XdesLocator(extentNo, xdesPageNo, entryOffset);
    }

    /**
     * 从页号计算 Extent 编号（页号所在的 Extent）
     *
     * @param pageNo 页号
     * @return Extent 编号
     */
    public static int pageNoToExtentNo(int pageNo) {
        return pageNo / EXTENT_SIZE;
    }

    /**
     * 从页号创建定位器
     *
     * @param pageNo 任意页号
     * @return XdesLocator 对象
     */
    public static XdesLocator fromPageNo(int pageNo) {
        int extentNo = pageNoToExtentNo(pageNo);
        return fromExtentNo(extentNo);
    }

    /**
     * 从 FlstNode 地址反推定位器
     *
     * <p>这是最关键的方法，用于从链表节点地址反推 Extent 信息，
     * 消除逻辑层中的 -8 魔法偏移。</p>
     *
     * @param xdesPageNo XDES Page 页号
     * @param nodeOffset FlstNode 在页面中的偏移
     * @return XdesLocator 对象
     */
    public static XdesLocator fromNodeAddr(int xdesPageNo, int nodeOffset) {
        // 从 nodeOffset 计算 entryOffset
        int entryOffset = nodeOffset - XDES_FLST_NODE;

        // 计算 localIndex
        int localIndex;
        if (xdesPageNo == 0) {
            // FSP_HDR Page
            localIndex = (entryOffset - XDES_ARR_OFFSET) / XDES_ENTRY_SIZE;
        } else {
            // 普通 XDES Page
            localIndex = (entryOffset - FIL_HEADER_SIZE) / XDES_ENTRY_SIZE;
        }

        // 计算 extentNo
        int groupNo = xdesPageNo / PAGES_PER_EXTENT_GROUP;
        int extentNo = groupNo * EXTENTS_PER_GROUP + localIndex;

        return new XdesLocator(extentNo, xdesPageNo, entryOffset);
    }

    /**
     * 从 FilAddr（FlstNode 地址）反推定位器
     *
     * @param nodeAddr FlstNode 的文件地址
     * @return XdesLocator 对象
     */
    public static XdesLocator fromNodeAddr(FilAddr nodeAddr) {
        if (nodeAddr == null || nodeAddr.isNull()) {
            throw new IllegalArgumentException("Invalid node address");
        }
        return fromNodeAddr(nodeAddr.getPageNo(), nodeAddr.getOffset());
    }

    // ==================== 获取方法 ====================

    /**
     * 获取 Extent 编号
     */
    public int getExtentNo() {
        return extentNo;
    }

    /**
     * 获取 XDES Page 页号
     */
    public int getXdesPageNo() {
        return xdesPageNo;
    }

    /**
     * 获取 XDES Entry 偏移
     */
    public int getEntryOffset() {
        return entryOffset;
    }

    /**
     * 获取 FlstNode 偏移
     */
    public int getNodeOffset() {
        return nodeOffset;
    }

    /**
     * 获取 Extent 起始页号
     */
    public int getStartPageNo() {
        return extentNo * EXTENT_SIZE;
    }

    /**
     * 获取 FlstNode 的文件地址
     */
    public FilAddr getNodeAddr() {
        return FilAddr.of(xdesPageNo, nodeOffset);
    }

    /**
     * 获取 PageId（需要 spaceId）
     *
     * @param spaceId 表空间ID
     * @return PageId 对象
     */
    public PageId getXdesPageId(int spaceId) {
        return PageId.of(spaceId, xdesPageNo);
    }

    // ==================== 获取描述符 ====================

    /**
     * 获取 ExtentDescriptor
     *
     * @param mtr     Mini-Transaction
     * @param spaceId 表空间ID
     * @return ExtentDescriptor 对象
     * @throws MiniDbException 如果操作失败
     */
    public ExtentDescriptor getDescriptor(MiniTransaction mtr, int spaceId) throws MiniDbException {
        PageId xdesPageId = PageId.of(spaceId, xdesPageNo);
        Page xdesPage = mtr.getPage(xdesPageId);
        return new ExtentDescriptor(xdesPage, entryOffset, extentNo);
    }

    /**
     * 获取 Extent 对象
     *
     * @param mtr     Mini-Transaction
     * @param spaceId 表空间ID
     * @return Extent 对象
     * @throws MiniDbException 如果操作失败
     */
    public Extent getExtent(MiniTransaction mtr, int spaceId) throws MiniDbException {
        ExtentDescriptor descriptor = getDescriptor(mtr, spaceId);
        return new Extent(spaceId, extentNo, descriptor);
    }

    /**
     * 验证给定的 nodeOffset 是否是有效的 XDES FlstNode 偏移
     *
     * @param xdesPageNo XDES Page 页号
     * @param nodeOffset 待验证的偏移
     * @return true 如果是有效的 FlstNode 偏移
     */
    public static boolean isValidNodeOffset(int xdesPageNo, int nodeOffset) {
        int baseOffset = (xdesPageNo == 0) ? XDES_ARR_OFFSET : FIL_HEADER_SIZE;
        int relativeOffset = nodeOffset - baseOffset - XDES_FLST_NODE;

        // 必须是 XDES_ENTRY_SIZE 的整数倍
        if (relativeOffset < 0 || relativeOffset % XDES_ENTRY_SIZE != 0) {
            return false;
        }

        int localIndex = relativeOffset / XDES_ENTRY_SIZE;
        return localIndex >= 0 && localIndex < EXTENTS_PER_GROUP;
    }

    @Override
    public String toString() {
        return String.format("XdesLocator{extentNo=%d, xdesPageNo=%d, entryOffset=%d, nodeOffset=%d}",
                extentNo, xdesPageNo, entryOffset, nodeOffset);
    }
}
