package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.page.PageType;

import java.nio.ByteBuffer;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;

/**
 * XDES Page（区描述页）
 *
 * <p>XDES Page 是独立的区描述页，用于支持超过256MB的表空间。
 * 每个 XDES Page 可以描述256个 Extent，对应16384个页面（256MB）。</p>
 *
 * <h2>XDES Page 位置规律</h2>
 * <pre>
 * ExtentNo 范围    XDES Page 位置
 * ------------    --------------
 * 0-255       →   page 0 (FSP_HDR)
 * 256-511     →   page 16384
 * 512-767     →   page 32768
 * 768-1023    →   page 49152
 * ...
 *
 * 计算公式：xdesPageNo = (extentNo / 256) * 16384
 * </pre>
 *
 * <h2>物理布局（16KB）</h2>
 * <pre>
 * [FIL Header 38B]
 * [XDES Array 10240B] - 256个XDES Entry (256 × 40B)
 * [Unused Space 6098B]
 * [FIL Trailer 8B]
 * </pre>
 *
 * <h2>与 FSP_HDR 的区别</h2>
 * <ul>
 *   <li>FSP_HDR (page 0): 包含 FSP Header (112B) + XDES Array</li>
 *   <li>XDES Page: 只包含 XDES Array，无 FSP Header</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 访问 Extent 300 的 XDES Entry
 * int xdesPageNo = XdesPage.getXdesPageNo(300);  // 16384
 * PageId xdesPageId = new PageId(spaceId, xdesPageNo);
 * XdesPage xdesPage = new XdesPage(mtr.getPage(xdesPageId));
 *
 * ExtentDescriptor extent300 = xdesPage.getXdesEntry(300);
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class XdesPage extends Page {

    /**
     * 构造函数：从 PageId 创建新页面
     *
     * @param pageId 页面ID（必须是16384的倍数）
     */
    public XdesPage(PageId pageId) {
        super(pageId, PageType.FIL_PAGE_TYPE_XDES);

        validatePageNo(pageId.getPageNo());
    }

    /**
     * 构造函数：从现有 Page 对象创建
     *
     * @param page 现有页面
     */
    public XdesPage(Page page) {
        super(page.getBuffer(), page.getPageId());

        validatePageNo(page.getPageNo());

        // 验证页面类型
        PageType actualType = page.getPageType();
        if (actualType != PageType.FIL_PAGE_TYPE_XDES &&
            actualType != PageType.FIL_PAGE_TYPE_ALLOCATED) {
            throw new IllegalArgumentException(
                    "Invalid page type for XDES: " + actualType);
        }
    }

    /**
     * 构造函数：从 ByteBuffer 创建
     *
     * @param buffer 页面数据
     * @param pageId 页面ID
     */
    public XdesPage(ByteBuffer buffer, PageId pageId) {
        super(buffer, pageId);

        validatePageNo(pageId.getPageNo());
    }

    // ==================== 静态工具方法 ====================

    /**
     * 根据 Extent 编号计算对应的 XDES Page 页号
     *
     * <p>每256个 Extent 需要1个 XDES Page。</p>
     *
     * @param extentNo Extent 编号
     * @return XDES Page 页号（0, 16384, 32768, ...）
     */
    public static int getXdesPageNo(int extentNo) {
        if (extentNo < 0) {
            throw new IllegalArgumentException("Invalid extentNo: " + extentNo);
        }
        return (extentNo / EXTENTS_PER_GROUP) * PAGES_PER_EXTENT_GROUP;
    }

    /**
     * 根据 Extent 编号计算在 XDES Page 中的偏移
     *
     * @param extentNo Extent 编号
     * @return XDES Entry 在页面中的偏移
     */
    public static int getXdesEntryOffset(int extentNo) {
        if (extentNo < 0) {
            throw new IllegalArgumentException("Invalid extentNo: " + extentNo);
        }

        int localIndex = extentNo % EXTENTS_PER_GROUP;  // 0-255

        // XDES Page 的 XDES Array 从 FIL_HEADER 之后开始
        return FIL_HEADER_SIZE + localIndex * XDES_ENTRY_SIZE;
    }

    /**
     * 判断指定页号是否为 XDES Page
     *
     * @param pageNo 页号
     * @return true 如果是 XDES Page
     */
    public static boolean isXdesPage(int pageNo) {
        if (pageNo == 0) {
            return true;  // page 0 (FSP_HDR) 也包含 XDES Array
        }
        return pageNo % PAGES_PER_EXTENT_GROUP == 0;
    }

    /**
     * 计算指定 Extent 编号所在的 XDES Page 能描述的 Extent 范围
     *
     * @param extentNo Extent 编号
     * @return [startExtentNo, endExtentNo]（包含）
     */
    public static int[] getExtentRange(int extentNo) {
        int groupNo = extentNo / EXTENTS_PER_GROUP;
        int startExtentNo = groupNo * EXTENTS_PER_GROUP;
        int endExtentNo = startExtentNo + EXTENTS_PER_GROUP - 1;
        return new int[]{startExtentNo, endExtentNo};
    }

    // ==================== XDES Entry 访问 ====================

    /**
     * 获取 XDES Entry 描述符
     *
     * @param extentNo Extent 编号
     * @return ExtentDescriptor 对象
     */
    public ExtentDescriptor getXdesEntry(int extentNo) {
        // 验证 extentNo 是否在当前页面的范围内
        int xdesPageNo = getXdesPageNo(extentNo);
        if (xdesPageNo != this.getPageNo()) {
            throw new IllegalArgumentException(
                    String.format("ExtentNo %d belongs to XDES page %d, not %d",
                            extentNo, xdesPageNo, this.getPageNo()));
        }

        int offset = getXdesEntryOffset(extentNo);
        return new ExtentDescriptor(this, offset, extentNo);
    }

    /**
     * 获取当前页面能描述的 Extent 范围
     *
     * @return [startExtentNo, endExtentNo]（包含）
     */
    public int[] getLocalExtentRange() {
        int groupNo = this.getPageNo() / PAGES_PER_EXTENT_GROUP;
        int startExtentNo = groupNo * EXTENTS_PER_GROUP;
        int endExtentNo = startExtentNo + EXTENTS_PER_GROUP - 1;
        return new int[]{startExtentNo, endExtentNo};
    }

    // ==================== 初始化 ====================

    /**
     * 初始化 XDES Page
     *
     * <p>将所有256个 XDES Entry 初始化为 FREE 状态。</p>
     *
     * @param mtr Mini-Transaction
     */
    public void initialize(MiniTransaction mtr) {
        // 设置页面类型
        setPageType(mtr, PageType.FIL_PAGE_TYPE_XDES);

        // 初始化256个 XDES Entry
        int[] range = getLocalExtentRange();
        int startExtentNo = range[0];

        for (int i = 0; i < EXTENTS_PER_GROUP; i++) {
            int extentNo = startExtentNo + i;
            ExtentDescriptor extent = getXdesEntry(extentNo);

            // 初始化为 FREE 状态
            extent.setSegmentId(mtr, 0);
            extent.setState(mtr, ExtentState.FREE);
            extent.initBitmap(mtr);
            extent.getListNode().initialize(mtr);
        }

        mtr.markDirty(this);
    }

    /**
     * 批量初始化指定范围的 XDES Entry
     *
     * @param mtr            Mini-Transaction
     * @param startExtentNo  起始 Extent 编号
     * @param endExtentNo    结束 Extent 编号（包含）
     */
    public void initializeRange(MiniTransaction mtr, int startExtentNo, int endExtentNo) {
        for (int extentNo = startExtentNo; extentNo <= endExtentNo; extentNo++) {
            if (getXdesPageNo(extentNo) != this.getPageNo()) {
                throw new IllegalArgumentException(
                        String.format("ExtentNo %d does not belong to this XDES page %d",
                                extentNo, this.getPageNo()));
            }

            ExtentDescriptor extent = getXdesEntry(extentNo);
            extent.setSegmentId(mtr, 0);
            extent.setState(mtr, ExtentState.FREE);
            extent.initBitmap(mtr);
            extent.getListNode().initialize(mtr);
        }

        mtr.markDirty(this);
    }

    // ==================== 验证 ====================

    /**
     * 验证页号是否有效（必须是16384的倍数，且不为0）
     *
     * @param pageNo 页号
     */
    private void validatePageNo(int pageNo) {
        if (pageNo == 0) {
            throw new IllegalArgumentException(
                    "XDES Page cannot be page 0 (use FspHeaderPage instead)");
        }

        if (pageNo % PAGES_PER_EXTENT_GROUP != 0) {
            throw new IllegalArgumentException(
                    String.format("XDES Page number must be a multiple of %d, got: %d",
                            PAGES_PER_EXTENT_GROUP, pageNo));
        }
    }

    @Override
    public String toString() {
        int[] range = getLocalExtentRange();
        return String.format("XdesPage{pageNo=%d, extentRange=[%d-%d]}",
                getPageNo(), range[0], range[1]);
    }
}
