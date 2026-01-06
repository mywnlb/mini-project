package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.common.exception.PageNotManagedByMtrException;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;

/**
 * 链表基节点（List Base Node）
 *
 * <p>FLST_BASE_NODE 是 InnoDB 中用于管理双向链表的基节点结构。
 * 它位于链表的"头部"，记录链表的长度、首节点和尾节点位置。</p>
 *
 * <h2>物理结构（16字节）</h2>
 * <pre>
 * Offset  Size  Field
 * ------  ----  -----
 * 0       4     Length - 链表中节点的数量
 * 4       4     First Page Number - 首节点所在页号
 * 8       2     First Offset - 首节点在页内的偏移
 * 10      4     Last Page Number - 尾节点所在页号
 * 14      2     Last Offset - 尾节点在页内的偏移
 * </pre>
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li>FSP_FREE: 表空间的空闲 Extent 链表</li>
 *   <li>FSP_FREE_FRAG: 碎片 Extent 链表</li>
 *   <li>FSP_SEG_INODES_FREE: 有空闲的 INODE Page 链表</li>
 *   <li>INODE_FREE/NOT_FULL/FULL: Segment 的三个 Extent 链表</li>
 * </ul>
 *
 * <h2>InnoDB 对应关系</h2>
 * <ul>
 *   <li>对应源码: storage/innobase/include/fut0lst.h</li>
 *   <li>宏定义: FLST_BASE_NODE, flst_base_node_t</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class FlstBaseNode {

    /**
     * 基节点所在的页面
     */
    private final Page page;

    /**
     * 基节点在页面中的偏移（字节）
     */
    private final int offset;

    /**
     * 构造函数
     *
     * @param page   基节点所在的页面
     * @param offset 基节点在页面中的偏移
     */
    public FlstBaseNode(Page page, int offset) {
        if (page == null) {
            throw new IllegalArgumentException("Page cannot be null");
        }
        if (offset < 0 || offset > PAGE_SIZE - FLST_BASE_NODE_SIZE) {
            throw new IllegalArgumentException("Invalid offset: " + offset);
        }

        this.page = page;
        this.offset = offset;
    }

    /**
     * 获取链表长度（节点数量）
     *
     * @return 链表中节点的数量
     */
    public int getLength() {
        return page.getInt(offset + 0);
    }

    /**
     * 设置链表长度
     *
     * @param mtr    Mini-Transaction
     * @param length 新的链表长度
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void setLength(MiniTransaction mtr, int length)
            throws PageNotManagedByMtrException, MtrStateException {
        page.putInt(offset + 0, length);
        mtr.markDirty(page);
    }

    /**
     * 获取首节点位置
     *
     * @return 首节点的 (pageNo, offset)，如果链表为空返回 null
     */
    public PageId getFirstNode() {
        int pageNo = page.getInt(offset + 4);
        int nodeOffset = page.getShort(offset + 8) & 0xFFFF;

        if (pageNo == FIL_NULL) {
            return null;
        }

        return new PageId(page.getSpaceId(), pageNo);
    }

    /**
     * 获取首节点在页内的偏移
     *
     * @return 偏移量，如果链表为空返回 -1
     */
    public int getFirstNodeOffset() {
        int pageNo = page.getInt(offset + 4);
        if (pageNo == FIL_NULL) {
            return -1;
        }
        return page.getShort(offset + 8) & 0xFFFF;
    }

    /**
     * 设置首节点位置
     *
     * @param mtr        Mini-Transaction
     * @param pageNo     首节点所在页号（FIL_NULL 表示空）
     * @param nodeOffset 首节点在页内的偏移
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void setFirstNode(MiniTransaction mtr, int pageNo, int nodeOffset)
            throws PageNotManagedByMtrException, MtrStateException {
        page.putInt(offset + 4, pageNo);
        page.putShort(offset + 8, (short) nodeOffset);
        mtr.markDirty(page);
    }

    /**
     * 获取尾节点位置
     *
     * @return 尾节点的 (pageNo, offset)，如果链表为空返回 null
     */
    public PageId getLastNode() {
        int pageNo = page.getInt(offset + 10);
        int nodeOffset = page.getShort(offset + 14) & 0xFFFF;

        if (pageNo == FIL_NULL) {
            return null;
        }

        return new PageId(page.getSpaceId(), pageNo);
    }

    /**
     * 获取尾节点在页内的偏移
     *
     * @return 偏移量，如果链表为空返回 -1
     */
    public int getLastNodeOffset() {
        int pageNo = page.getInt(offset + 10);
        if (pageNo == FIL_NULL) {
            return -1;
        }
        return page.getShort(offset + 14) & 0xFFFF;
    }

    /**
     * 设置尾节点位置
     *
     * @param mtr        Mini-Transaction
     * @param pageNo     尾节点所在页号（FIL_NULL 表示空）
     * @param nodeOffset 尾节点在页内的偏移
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void setLastNode(MiniTransaction mtr, int pageNo, int nodeOffset)
            throws PageNotManagedByMtrException, MtrStateException {
        page.putInt(offset + 10, pageNo);
        page.putShort(offset + 14, (short) nodeOffset);
        mtr.markDirty(page);
    }

    /**
     * 初始化为空链表
     *
     * @param mtr Mini-Transaction
     * @throws PageNotManagedByMtrException 如果页面不在MTR管理中
     * @throws MtrStateException 如果MTR状态不正确
     */
    public void initialize(MiniTransaction mtr)
            throws PageNotManagedByMtrException, MtrStateException {
        setLength(mtr, 0);
        setFirstNode(mtr, FIL_NULL, 0);
        setLastNode(mtr, FIL_NULL, 0);
    }

    /**
     * 判断链表是否为空
     *
     * @return true 如果链表为空
     */
    public boolean isEmpty() {
        return getLength() == 0;
    }

    /**
     * 将节点添加到链表头部
     *
     * @param mtr        Mini-Transaction
     * @param nodePage   节点所在页面
     * @param nodeOffset 节点在页内的偏移
     * @throws MiniDbException 如果操作失败
     */
    public void addFirst(MiniTransaction mtr, Page nodePage, int nodeOffset)
            throws MiniDbException {
        FlstNode newNode = new FlstNode(nodePage, nodeOffset);

        int length = getLength();

        if (length == 0) {
            // 空链表：设置首尾为新节点
            newNode.setPrevNode(mtr, FIL_NULL, 0);
            newNode.setNextNode(mtr, FIL_NULL, 0);

            setFirstNode(mtr, nodePage.getPageNo(), nodeOffset);
            setLastNode(mtr, nodePage.getPageNo(), nodeOffset);
        } else {
            // 非空链表：插入到首节点前
            int oldFirstPageNo = page.getInt(offset + 4);
            int oldFirstOffset = page.getShort(offset + 8) & 0xFFFF;

            // 设置新节点的指针
            newNode.setPrevNode(mtr, FIL_NULL, 0);
            newNode.setNextNode(mtr, oldFirstPageNo, oldFirstOffset);

            // 加载旧首节点并更新其 prev 指针
            Page oldFirstPage = mtr.getPage(new PageId(page.getSpaceId(), oldFirstPageNo));
            FlstNode oldFirstNode = new FlstNode(oldFirstPage, oldFirstOffset);
            oldFirstNode.setPrevNode(mtr, nodePage.getPageNo(), nodeOffset);

            // 更新链表头指针
            setFirstNode(mtr, nodePage.getPageNo(), nodeOffset);
        }

        setLength(mtr, length + 1);
    }

    /**
     * 将节点添加到链表尾部
     *
     * @param mtr        Mini-Transaction
     * @param nodePage   节点所在页面
     * @param nodeOffset 节点在页内的偏移
     * @throws MiniDbException 如果操作失败
     */
    public void addLast(MiniTransaction mtr, Page nodePage, int nodeOffset)
            throws MiniDbException {
        FlstNode newNode = new FlstNode(nodePage, nodeOffset);

        int length = getLength();

        if (length == 0) {
            // 空链表：设置首尾为新节点
            newNode.setPrevNode(mtr, FIL_NULL, 0);
            newNode.setNextNode(mtr, FIL_NULL, 0);

            setFirstNode(mtr, nodePage.getPageNo(), nodeOffset);
            setLastNode(mtr, nodePage.getPageNo(), nodeOffset);
        } else {
            // 非空链表：插入到尾节点后
            int oldLastPageNo = page.getInt(offset + 10);
            int oldLastOffset = page.getShort(offset + 14) & 0xFFFF;

            // 设置新节点的指针
            newNode.setPrevNode(mtr, oldLastPageNo, oldLastOffset);
            newNode.setNextNode(mtr, FIL_NULL, 0);

            // 加载旧尾节点并更新其 next 指针
            Page oldLastPage = mtr.getPage(new PageId(page.getSpaceId(), oldLastPageNo));
            FlstNode oldLastNode = new FlstNode(oldLastPage, oldLastOffset);
            oldLastNode.setNextNode(mtr, nodePage.getPageNo(), nodeOffset);

            // 更新链表尾指针
            setLastNode(mtr, nodePage.getPageNo(), nodeOffset);
        }

        setLength(mtr, length + 1);
    }

    /**
     * 从链表头部移除节点
     *
     * @param mtr Mini-Transaction
     * @return 被移除节点的位置 (pageNo, offset)，如果链表为空返回 null
     * @throws MiniDbException 如果操作失败
     */
    public PageId removeFirst(MiniTransaction mtr) throws MiniDbException {
        if (isEmpty()) {
            return null;
        }

        int firstPageNo = page.getInt(offset + 4);
        int firstOffset = page.getShort(offset + 8) & 0xFFFF;
        int length = getLength();

        if (length == 1) {
            // 最后一个节点：清空链表
            initialize(mtr);
        } else {
            // 多个节点：移除首节点，更新链表头
            // 加载首节点页面
            Page firstPage = mtr.getPage(new PageId(page.getSpaceId(), firstPageNo));
            FlstNode firstNode = new FlstNode(firstPage, firstOffset);

            // 获取新的首节点位置（旧首节点的next）
            int newFirstPageNo = firstNode.getNextPageNo();
            int newFirstOffset = firstNode.getNextOffset();

            // 加载新首节点并清除其 prev 指针
            Page newFirstPage = mtr.getPage(new PageId(page.getSpaceId(), newFirstPageNo));
            FlstNode newFirstNode = new FlstNode(newFirstPage, newFirstOffset);
            newFirstNode.setPrevNode(mtr, FIL_NULL, 0);

            // 清除旧首节点的指针（将其隔离）
            firstNode.initialize(mtr);

            // 更新链表头指针
            setFirstNode(mtr, newFirstPageNo, newFirstOffset);
            setLength(mtr, length - 1);
        }

        return new PageId(page.getSpaceId(), firstPageNo);
    }

    @Override
    public String toString() {
        return String.format("FlstBaseNode{length=%d, first=(%d,%d), last=(%d,%d)}",
                getLength(),
                page.getInt(offset + 4),
                page.getShort(offset + 8) & 0xFFFF,
                page.getInt(offset + 10),
                page.getShort(offset + 14) & 0xFFFF);
    }
}
