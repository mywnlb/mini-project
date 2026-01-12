package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.PageId;

/**
 * Extent 管理器接口（逻辑层）
 *
 * <p>ExtentManager 是空间管理的逻辑层，负责 Extent 的查找、分配和状态管理。
 * 它封装了跨 XDES Page 的 Extent 定位逻辑，屏蔽物理层细节。</p>
 *
 * <h2>职责范围</h2>
 * <ul>
 *   <li>跨 XDES Page 获取 ExtentDescriptor（支持超大表空间）</li>
 *   <li>在 Extent 中分配和释放页面</li>
 *   <li>Extent 初始化和状态管理</li>
 *   <li>Extent 统计信息查询</li>
 * </ul>
 *
 * <h2>XDES Page 定位规则</h2>
 * <p>每256个 Extent 需要1个 XDES Page：</p>
 * <ul>
 *   <li>Extent 0-255: 位于 page 0 (FSP_HDR)</li>
 *   <li>Extent 256-511: 位于 page 16384 (XDES Page)</li>
 *   <li>Extent 512-767: 位于 page 32768 (XDES Page)</li>
 *   <li>...</li>
 * </ul>
 *
 * <h2>分层设计</h2>
 * <ul>
 *   <li>物理层：FspHeaderPage, XdesPage, ExtentDescriptor（已完成）</li>
 *   <li>逻辑层：ExtentManager（本接口）</li>
 *   <li>高级层：SegmentManager, SpaceManager（待实现）</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * ExtentManager extMgr = new ExtentManagerImpl(bufferPool);
 *
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     // 获取 Extent 256 的描述符（位于 XDES Page 16384）
 *     ExtentDescriptor ext = extMgr.getExtentDescriptor(mtr, spaceId, 256);
 *
 *     // 在 Extent 中分配页面
 *     int pageOffset = extMgr.allocatePageInExtent(mtr, ext);
 *     int pageNo = ext.getStartPageNo() + pageOffset;
 *
 *     // 检查 Extent 是否已满
 *     if (ext.isFull()) {
 *         extMgr.setExtentState(mtr, ext, ExtentState.FULL_FRAG);
 *     }
 *
 *     mtr.commit();
 * }
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 * @see ExtentDescriptor
 * @see FspHeaderPage
 * @see XdesPage
 */
public interface ExtentManager {

    /**
     * 获取 Extent 描述符
     *
     * <p>根据 extentNo 自动定位对应的 XDES Page（page 0 或其他 XDES Page），
     * 并返回该 Extent 的描述符。支持超大表空间（多个 XDES Page）。</p>
     *
     * <h3>定位逻辑</h3>
     * <ol>
     *   <li>计算 XDES Page 页号: xdesPageNo = (extentNo / 256) * 16384</li>
     *   <li>加载 XDES Page（如果是 page 0，使用 FspHeaderPage；否则使用 XdesPage）</li>
     *   <li>计算 XDES Entry 偏移: offset = XDES_ARR_OFFSET + (extentNo % 256) * 40</li>
     *   <li>创建并返回 ExtentDescriptor</li>
     * </ol>
     *
     * @param mtr      Mini-Transaction
     * @param spaceId  表空间 ID
     * @param extentNo Extent 编号（全局唯一，从 0 开始）
     * @return Extent 描述符
     * @throws MiniDbException 如果 extentNo 无效或 XDES Page 不存在
     */
    ExtentDescriptor getExtentDescriptor(MiniTransaction mtr, int spaceId, int extentNo)
            throws MiniDbException;

    /**
     * 在 Extent 中分配一个页面
     *
     * <p>在指定 Extent 中查找第一个空闲页面并分配。
     * 更新 Extent 的 Bitmap，但不修改 Extent 状态（由调用者决定）。</p>
     *
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>调用 extent.findFreePage() 查找空闲页</li>
     *   <li>如果找到，调用 extent.allocatePage(mtr, pageOffset) 分配</li>
     *   <li>返回页面在 Extent 中的偏移（0-63）</li>
     *   <li>如果无空闲页，返回 -1</li>
     * </ol>
     *
     * @param mtr    Mini-Transaction
     * @param extent Extent 描述符
     * @return 分配的页面在 Extent 中的偏移（0-63），如果无空闲页返回 -1
     * @throws MiniDbException 如果操作失败
     */
    int allocatePageInExtent(MiniTransaction mtr, ExtentDescriptor extent)
            throws MiniDbException;

    /**
     * 在 Extent 中释放一个页面
     *
     * <p>将指定页面标记为空闲（更新 Bitmap）。
     * 不修改 Extent 状态（由调用者决定状态转移）。</p>
     *
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>验证 pageOffset 在 0-63 范围内</li>
     *   <li>调用 extent.freePage(mtr, pageOffset)</li>
     * </ol>
     *
     * @param mtr        Mini-Transaction
     * @param extent     Extent 描述符
     * @param pageOffset 页面在 Extent 中的偏移（0-63）
     * @throws MiniDbException           如果操作失败
     * @throws IllegalArgumentException  如果 pageOffset 无效
     */
    void freePageInExtent(MiniTransaction mtr, ExtentDescriptor extent, int pageOffset)
            throws MiniDbException;

    /**
     * 初始化 Extent
     *
     * <p>将 Extent 初始化为 FREE 状态：</p>
     * <ul>
     *   <li>Segment ID = 0（不属于任何 Segment）</li>
     *   <li>State = FREE</li>
     *   <li>ListNode = isolated（prev=FIL_NULL, next=FIL_NULL）</li>
     *   <li>Bitmap = 全空闲（所有 bits = 1）</li>
     * </ul>
     *
     * <p>委托给 extent.initialize(mtr)。</p>
     *
     * @param mtr    Mini-Transaction
     * @param extent Extent 描述符
     * @throws MiniDbException 如果操作失败
     */
    void initializeExtent(MiniTransaction mtr, ExtentDescriptor extent)
            throws MiniDbException;

    /**
     * 设置 Extent 状态
     *
     * <p>修改 Extent 的状态字段。
     * <b>注意</b>：此方法只修改状态字段，不处理链表迁移，
     * 链表操作应由 SegmentManager 或 SpaceManager 负责。</p>
     *
     * <h3>状态转移规则</h3>
     * <ul>
     *   <li>FREE → FSEG_FREE（分配给 Segment）</li>
     *   <li>FSEG_FREE → FSEG（开始使用）</li>
     *   <li>FREE → FREE_FRAG（用于碎片页分配）</li>
     *   <li>FREE_FRAG → FULL_FRAG（全满）</li>
     * </ul>
     *
     * @param mtr      Mini-Transaction
     * @param extent   Extent 描述符
     * @param newState 新的状态
     * @throws MiniDbException 如果操作失败
     */
    void setExtentState(MiniTransaction mtr, ExtentDescriptor extent, ExtentState newState)
            throws MiniDbException;

    /**
     * 获取 Extent 中已使用的页面数量
     *
     * <p>委托给 extent.getUsedPageCount()。</p>
     *
     * @param extent Extent 描述符
     * @return 已使用的页面数（0-64）
     */
    int getUsedPageCount(ExtentDescriptor extent);

    /**
     * 获取 Extent 中空闲页面数量
     *
     * <p>委托给 extent.getFreePageCount()。</p>
     *
     * @param extent Extent 描述符
     * @return 空闲页面数（0-64）
     */
    int getFreePageCount(ExtentDescriptor extent);

    /**
     * 判断 Extent 是否为空（所有页面都未分配）
     *
     * <p>委托给 extent.isEmpty()。</p>
     *
     * @param extent Extent 描述符
     * @return true 如果 Extent 为空
     */
    boolean isEmpty(ExtentDescriptor extent);

    /**
     * 判断 Extent 是否已满（所有页面都已分配）
     *
     * <p>委托给 extent.isFull()。</p>
     *
     * @param extent Extent 描述符
     * @return true 如果 Extent 已满
     */
    boolean isFull(ExtentDescriptor extent);
}
