package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;

/**
 * Extent 管理器实现类（逻辑层）
 *
 * <p>ExtentManagerImpl 是 ExtentManager 接口的标准实现，
 * 负责跨 XDES Page 的 Extent 定位和管理。</p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li>支持超大表空间：自动定位正确的 XDES Page（page 0, 16384, 32768...）</li>
 *   <li>Extent 内页面分配和释放</li>
 *   <li>Extent 状态管理</li>
 * </ul>
 *
 * <h2>XDES Page 定位算法</h2>
 * <pre>
 * xdesPageNo = (extentNo / 256) * 16384
 *
 * 示例：
 * - extentNo 0-255   → page 0     (FSP_HDR)
 * - extentNo 256-511 → page 16384 (XDES Page)
 * - extentNo 512-767 → page 32768 (XDES Page)
 * </pre>
 *
 * <h2>线程安全性</h2>
 * <p>ExtentManagerImpl 本身是线程安全的（无状态）。
 * 并发安全由 MiniTransaction 和 BufferPool 保证。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class ExtentManagerImpl implements ExtentManager {

    /**
     * Buffer Pool 引用（用于加载页面）
     */
    private final BufferPool bufferPool;

    /**
     * 构造函数
     *
     * @param bufferPool Buffer Pool 实例
     */
    public ExtentManagerImpl(BufferPool bufferPool) {
        if (bufferPool == null) {
            throw new IllegalArgumentException("BufferPool cannot be null");
        }
        this.bufferPool = bufferPool;
    }

    @Override
    public ExtentDescriptor getExtentDescriptor(MiniTransaction mtr, int spaceId, int extentNo)
            throws MiniDbException {
        if (extentNo < 0) {
            throw new IllegalArgumentException("Extent number must be non-negative: " + extentNo);
        }

        // Step 1: 计算 XDES Page 页号
        // 每256个 Extent 对应一个 XDES Page
        // XDES Page 位置: 0, 16384, 32768, 49152, ...
        int groupNo = extentNo / EXTENTS_PER_GROUP;  // groupNo = extentNo / 256
        int xdesPageNo = groupNo * PAGES_PER_EXTENT_GROUP;  // xdesPageNo = groupNo * 16384

        // Step 2: 加载 XDES Page
        PageId xdesPageId = PageId.of(spaceId, xdesPageNo);
        Page rawPage = mtr.getPage(xdesPageId);

        // Step 3: 根据页号判断使用 FspHeaderPage 还是 XdesPage
        Page xdesPage;
        if (xdesPageNo == 0) {
            // page 0: FSP_HDR Page
            xdesPage = new FspHeaderPage(rawPage);
        } else {
            // page 16384, 32768, ...: XDES Page
            xdesPage = new XdesPage(rawPage);
        }

        // Step 4: 计算 XDES Entry 在页面中的偏移
        int localIndex = extentNo % EXTENTS_PER_GROUP;  // 0-255
        int entryOffset;
        if (xdesPageNo == 0) {
            // FSP_HDR: XDES Array 从 XDES_ARR_OFFSET(150) 开始
            entryOffset = XDES_ARR_OFFSET + localIndex * XDES_ENTRY_SIZE;
        } else {
            // XDES Page: XDES Array 从 FIL_HEADER_SIZE(38) 开始
            entryOffset = FIL_HEADER_SIZE + localIndex * XDES_ENTRY_SIZE;
        }

        // Step 5: 创建并返回 ExtentDescriptor
        return new ExtentDescriptor(xdesPage, entryOffset, extentNo);
    }

    @Override
    public int allocatePageInExtent(MiniTransaction mtr, ExtentDescriptor extent)
            throws MiniDbException {
        if (extent == null) {
            throw new IllegalArgumentException("Extent descriptor cannot be null");
        }

        // Step 1: 查找第一个空闲页
        int pageOffset = extent.findFreePage();
        if (pageOffset == -1) {
            // Extent 已满，无空闲页
            return -1;
        }

        // Step 2: 分配该页（更新 Bitmap）
        extent.allocatePage(mtr, pageOffset);

        // Step 3: 返回页面偏移
        return pageOffset;
    }

    @Override
    public void freePageInExtent(MiniTransaction mtr, ExtentDescriptor extent, int pageOffset)
            throws MiniDbException {
        if (extent == null) {
            throw new IllegalArgumentException("Extent descriptor cannot be null");
        }
        if (pageOffset < 0 || pageOffset >= EXTENT_SIZE) {
            throw new IllegalArgumentException(
                    "Page offset must be 0-63, got: " + pageOffset);
        }

        // 释放页面（更新 Bitmap）
        extent.freePage(mtr, pageOffset);
    }

    @Override
    public void initializeExtent(MiniTransaction mtr, ExtentDescriptor extent)
            throws MiniDbException {
        if (extent == null) {
            throw new IllegalArgumentException("Extent descriptor cannot be null");
        }

        // 委托给 ExtentDescriptor.initialize()
        // 这会设置：
        // - Segment ID = 0
        // - State = FREE
        // - ListNode = isolated
        // - Bitmap = 全空闲
        extent.initialize(mtr);
    }

    @Override
    public void setExtentState(MiniTransaction mtr, ExtentDescriptor extent, ExtentState newState)
            throws MiniDbException {
        if (extent == null) {
            throw new IllegalArgumentException("Extent descriptor cannot be null");
        }
        if (newState == null) {
            throw new IllegalArgumentException("New state cannot be null");
        }

        // 设置状态
        extent.setState(mtr, newState);
    }

    @Override
    public int getUsedPageCount(ExtentDescriptor extent) {
        if (extent == null) {
            throw new IllegalArgumentException("Extent descriptor cannot be null");
        }
        return extent.getUsedPageCount();
    }

    @Override
    public int getFreePageCount(ExtentDescriptor extent) {
        if (extent == null) {
            throw new IllegalArgumentException("Extent descriptor cannot be null");
        }
        return extent.getFreePageCount();
    }

    @Override
    public boolean isEmpty(ExtentDescriptor extent) {
        if (extent == null) {
            throw new IllegalArgumentException("Extent descriptor cannot be null");
        }
        return extent.isEmpty();
    }

    @Override
    public boolean isFull(ExtentDescriptor extent) {
        if (extent == null) {
            throw new IllegalArgumentException("Extent descriptor cannot be null");
        }
        return extent.isFull();
    }

    /**
     * 获取 Buffer Pool 实例
     *
     * @return Buffer Pool
     */
    public BufferPool getBufferPool() {
        return bufferPool;
    }

    @Override
    public String toString() {
        return String.format("ExtentManagerImpl{bufferPool=%s}", bufferPool);
    }
}
