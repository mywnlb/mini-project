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
     * 获取基节点在页面中的偏移
     *
     * @return 偏移量（字节）
     */
    public int getOffset() {
        return offset;
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
     * 获取首节点的完整地址
     *
     * <p>返回 FilAddr 对象，包含页号和偏移，避免分离读取导致的混淆。</p>
     *
     * @return 首节点的 FilAddr，如果链表为空返回 FilAddr.NULL
     */
    public FilAddr getFirstNodeAddr() {
        int pageNo = page.getInt(offset + 4);
        if (pageNo == FIL_NULL) {
            return FilAddr.NULL;
        }
        int nodeOffset = page.getShort(offset + 8) & 0xFFFF;
        return FilAddr.of(pageNo, nodeOffset);
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
     * 获取尾节点的完整地址
     *
     * <p>返回 FilAddr 对象，包含页号和偏移，避免分离读取导致的混淆。</p>
     *
     * @return 尾节点的 FilAddr，如果链表为空返回 FilAddr.NULL
     */
    public FilAddr getLastNodeAddr() {
        int pageNo = page.getInt(offset + 10);
        if (pageNo == FIL_NULL) {
            return FilAddr.NULL;
        }
        int nodeOffset = page.getShort(offset + 14) & 0xFFFF;
        return FilAddr.of(pageNo, nodeOffset);
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
     * @deprecated 使用 {@link #removeFirstAndGetAddr(MiniTransaction)} 代替，
     *             该方法返回完整的 FilAddr 避免地址混淆
     */
    @Deprecated
    public PageId removeFirst(MiniTransaction mtr) throws MiniDbException {
        FilAddr addr = removeFirstAndGetAddr(mtr);
        if (addr == null || addr.isNull()) {
            return null;
        }
        return new PageId(page.getSpaceId(), addr.getPageNo());
    }

    /**
     * 从链表头部移除节点并返回完整地址
     *
     * <p>这是推荐使用的方法，返回被移除节点的完整 FilAddr（包含页号和偏移），
     * 避免调用方先 removeFirst() 再 getFirstNodeOffset() 导致的地址混淆问题。</p>
     *
     * <h3>错误使用示例（旧方式）</h3>
     * <pre>
     * // 错误！removeFirst() 后 getFirstNodeOffset() 返回的是新的首节点偏移
     * PageId pageId = list.removeFirst(mtr);
     * int offset = list.getFirstNodeOffset();  // 这是错误的！
     * </pre>
     *
     * <h3>正确使用示例</h3>
     * <pre>
     * FilAddr removedAddr = list.removeFirstAndGetAddr(mtr);
     * if (removedAddr.isValid()) {
     *     int pageNo = removedAddr.getPageNo();
     *     int offset = removedAddr.getOffset();  // 正确！
     * }
     * </pre>
     *
     * @param mtr Mini-Transaction
     * @return 被移除节点的完整地址 FilAddr，如果链表为空返回 FilAddr.NULL
     * @throws MiniDbException 如果操作失败
     */
    public FilAddr removeFirstAndGetAddr(MiniTransaction mtr) throws MiniDbException {
        if (isEmpty()) {
            return FilAddr.NULL;
        }

        // 在移除前保存被移除节点的完整地址
        int firstPageNo = page.getInt(offset + 4);
        int firstOffset = page.getShort(offset + 8) & 0xFFFF;
        FilAddr removedAddr = FilAddr.of(firstPageNo, firstOffset);

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

        return removedAddr;
    }

    /**
     * 从链表尾部移除节点并返回完整地址
     *
     * @param mtr Mini-Transaction
     * @return 被移除节点的完整地址 FilAddr，如果链表为空返回 FilAddr.NULL
     * @throws MiniDbException 如果操作失败
     */
    public FilAddr removeLastAndGetAddr(MiniTransaction mtr) throws MiniDbException {
        if (isEmpty()) {
            return FilAddr.NULL;
        }

        // 在移除前保存被移除节点的完整地址
        int lastPageNo = page.getInt(offset + 10);
        int lastOffset = page.getShort(offset + 14) & 0xFFFF;
        FilAddr removedAddr = FilAddr.of(lastPageNo, lastOffset);

        int length = getLength();

        if (length == 1) {
            // 最后一个节点：清空链表
            initialize(mtr);
        } else {
            // 多个节点：移除尾节点，更新链表尾
            Page lastPage = mtr.getPage(new PageId(page.getSpaceId(), lastPageNo));
            FlstNode lastNode = new FlstNode(lastPage, lastOffset);

            // 获取新的尾节点位置
            int newLastPageNo = lastNode.getPrevPageNo();
            int newLastOffset = lastNode.getPrevOffset();

            // 加载新尾节点并清除其 next 指针
            Page newLastPage = mtr.getPage(new PageId(page.getSpaceId(), newLastPageNo));
            FlstNode newLastNode = new FlstNode(newLastPage, newLastOffset);
            newLastNode.setNextNode(mtr, FIL_NULL, 0);

            // 清除旧尾节点的指针
            lastNode.initialize(mtr);

            // 更新链表尾指针
            setLastNode(mtr, newLastPageNo, newLastOffset);
            setLength(mtr, length - 1);
        }

        return removedAddr;
    }

    /**
     * 从链表中移除指定节点
     *
     * <p>从双向链表中移除任意位置的节点，更新前后节点的指针和链表元信息。</p>
     *
     * <h3>执行步骤</h3>
     * <ol>
     *   <li>加载要移除的节点</li>
     *   <li>获取前后节点位置</li>
     *   <li>更新前后节点的指针</li>
     *   <li>如果是首节点或尾节点，更新链表基节点</li>
     *   <li>清除被移除节点的指针</li>
     *   <li>减少链表长度</li>
     * </ol>
     *
     * @param mtr        Mini-Transaction
     * @param nodePage   要移除的节点所在页面
     * @param nodeOffset 要移除的节点在页内的偏移
     * @throws MiniDbException 如果操作失败
     */
    public void remove(MiniTransaction mtr, Page nodePage, int nodeOffset)
            throws MiniDbException {
        if (isEmpty()) {
            throw new IllegalStateException("Cannot remove from empty list");
        }

        FlstNode node = new FlstNode(nodePage, nodeOffset);
        int length = getLength();

        // 获取前后节点位置
        int prevPageNo = node.getPrevPageNo();
        int prevOffset = node.getPrevOffset();
        int nextPageNo = node.getNextPageNo();
        int nextOffset = node.getNextOffset();

        boolean isFirst = (prevPageNo == FIL_NULL);
        boolean isLast = (nextPageNo == FIL_NULL);

        if (length == 1) {
            // 唯一节点：清空链表
            if (!isFirst || !isLast) {
                throw new IllegalStateException("Single node must be both first and last");
            }
            initialize(mtr);
        } else if (isFirst) {
            // 首节点：更新链表头指针
            Page nextPage = mtr.getPage(new PageId(page.getSpaceId(), nextPageNo));
            FlstNode nextNode = new FlstNode(nextPage, nextOffset);
            nextNode.setPrevNode(mtr, FIL_NULL, 0);

            setFirstNode(mtr, nextPageNo, nextOffset);
            setLength(mtr, length - 1);

            // 清除被移除节点的指针
            node.initialize(mtr);
        } else if (isLast) {
            // 尾节点：更新链表尾指针
            Page prevPage = mtr.getPage(new PageId(page.getSpaceId(), prevPageNo));
            FlstNode prevNode = new FlstNode(prevPage, prevOffset);
            prevNode.setNextNode(mtr, FIL_NULL, 0);

            setLastNode(mtr, prevPageNo, prevOffset);
            setLength(mtr, length - 1);

            // 清除被移除节点的指针
            node.initialize(mtr);
        } else {
            // 中间节点：连接前后节点
            Page prevPage = mtr.getPage(new PageId(page.getSpaceId(), prevPageNo));
            FlstNode prevNode = new FlstNode(prevPage, prevOffset);
            prevNode.setNextNode(mtr, nextPageNo, nextOffset);

            Page nextPage = mtr.getPage(new PageId(page.getSpaceId(), nextPageNo));
            FlstNode nextNode = new FlstNode(nextPage, nextOffset);
            nextNode.setPrevNode(mtr, prevPageNo, prevOffset);

            setLength(mtr, length - 1);

            // 清除被移除节点的指针
            node.initialize(mtr);
        }
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
