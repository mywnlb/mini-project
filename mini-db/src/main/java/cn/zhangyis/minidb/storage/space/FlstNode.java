package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.common.exception.PageNotManagedByMtrException;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import lombok.Data;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.*;

/**
 * 链表节点（List Node）
 *
 * <p>FLST_NODE 是 InnoDB 中用于双向链表的节点结构。
 * 每个链表元素（如 XDES Entry、INODE Page）都包含一个 FLST_NODE，
 * 用于链接到前一个和后一个节点。</p>
 *
 * <h2>物理结构（12字节）</h2>
 * <pre>
 * Offset  Size  Field
 * ------  ----  -----
 * 0       4     Prev Page Number - 前一个节点所在页号
 * 4       2     Prev Offset - 前一个节点在页内的偏移
 * 6       4     Next Page Number - 后一个节点所在页号
 * 10      2     Next Offset - 后一个节点在页内的偏移
 * </pre>
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li>XDES Entry 中的 XDES_FLST_NODE（偏移8）</li>
 *   <li>INODE Page Header 中的链表节点</li>
 * </ul>
 *
 * <h2>链表遍历示例</h2>
 * <pre>
 * // 从首节点开始遍历
 * FlstBaseNode baseNode = ...;
 * PageId currentId = baseNode.getFirstNode();
 *
 * while (currentId != null) {
 *     Page currentPage = mtr.getPage(currentId);
 *     FlstNode node = new FlstNode(currentPage, nodeOffset);
 *
 *     // 处理当前节点
 *     processNode(node);
 *
 *     // 移动到下一个节点
 *     currentId = node.getNextNode();
 * }
 * </pre>
 *
 * <h2>InnoDB 对应关系</h2>
 * <ul>
 *   <li>对应源码: storage/innobase/include/fut0lst.h</li>
 *   <li>宏定义: FLST_NODE, flst_node_t</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
@Data
public class FlstNode {

    /**
     * 节点所在的页面
     */
    private final Page page;

    /**
     * 节点在页面中的偏移（字节）
     */
    private final int offset;

    /**
     * 构造函数
     *
     * @param page   节点所在的页面
     * @param offset 节点在页面中的偏移
     */
    public FlstNode(Page page, int offset) {
        if (page == null) {
            throw new IllegalArgumentException("Page cannot be null");
        }
        if (offset < 0 || offset > PAGE_SIZE - FLST_NODE_SIZE) {
            throw new IllegalArgumentException("Invalid offset: " + offset);
        }

        this.page = page;
        this.offset = offset;
    }

    /**
     * 获取前一个节点的页号
     *
     * @return 页号，如果没有前一个节点返回 FIL_NULL
     */
    public int getPrevPageNo() {
        return page.getInt(offset + 0);
    }

    /**
     * 获取前一个节点的偏移
     *
     * @return 偏移量
     */
    public int getPrevOffset() {
        return page.getShort(offset + 4) & 0xFFFF;
    }

    /**
     * 获取前一个节点的位置
     *
     * @return 前一个节点的 PageId，如果没有前一个节点返回 null
     */
    public PageId getPrevNode() {
        int pageNo = getPrevPageNo();
        if (pageNo == FIL_NULL) {
            return null;
        }
        return new PageId(page.getSpaceId(), pageNo);
    }

    /**
     * 设置前一个节点的位置
     *
     * @param mtr        Mini-Transaction
     * @param pageNo     前一个节点所在页号（FIL_NULL 表示无前驱）
     * @param nodeOffset 前一个节点在页内的偏移
     */
    public void setPrevNode(MiniTransaction mtr, int pageNo, int nodeOffset) throws MtrStateException, PageNotManagedByMtrException {
        page.putInt(offset + 0, pageNo);
        page.putShort(offset + 4, (short) nodeOffset);
        mtr.markDirty(page);
    }

    /**
     * 获取后一个节点的页号
     *
     * @return 页号，如果没有后一个节点返回 FIL_NULL
     */
    public int getNextPageNo() {
        return page.getInt(offset + 6);
    }

    /**
     * 获取后一个节点的偏移
     *
     * @return 偏移量
     */
    public int getNextOffset() {
        return page.getShort(offset + 10) & 0xFFFF;
    }

    /**
     * 获取后一个节点的位置
     *
     * @return 后一个节点的 PageId，如果没有后一个节点返回 null
     */
    public PageId getNextNode() {
        int pageNo = getNextPageNo();
        if (pageNo == FIL_NULL) {
            return null;
        }
        return new PageId(page.getSpaceId(), pageNo);
    }

    /**
     * 设置后一个节点的位置
     *
     * @param mtr        Mini-Transaction
     * @param pageNo     后一个节点所在页号（FIL_NULL 表示无后继）
     * @param nodeOffset 后一个节点在页内的偏移
     */
    public void setNextNode(MiniTransaction mtr, int pageNo, int nodeOffset) throws MtrStateException, PageNotManagedByMtrException {
        page.putInt(offset + 6, pageNo);
        page.putShort(offset + 10, (short) nodeOffset);
        mtr.markDirty(page);
    }

    /**
     * 初始化为孤立节点（无前驱无后继）
     *
     * @param mtr Mini-Transaction
     */
    public void initialize(MiniTransaction mtr) throws MtrStateException, PageNotManagedByMtrException {
        setPrevNode(mtr, FIL_NULL, 0);
        setNextNode(mtr, FIL_NULL, 0);
    }

    /**
     * 判断是否有前一个节点
     *
     * @return true 如果有前驱节点
     */
    public boolean hasPrev() {
        return getPrevPageNo() != FIL_NULL;
    }

    /**
     * 判断是否有后一个节点
     *
     * @return true 如果有后继节点
     */
    public boolean hasNext() {
        return getNextPageNo() != FIL_NULL;
    }

    /**
     * 判断是否为孤立节点（无前驱无后继）
     *
     * @return true 如果是孤立节点
     */
    public boolean isIsolated() {
        return !hasPrev() && !hasNext();
    }

    /**
     * 从链表中移除当前节点（更新前驱和后继的指针）
     *
     * <p>注意：此方法只更新当前节点的前驱和后继指针，
     * 调用者需要负责更新 FlstBaseNode 的首尾指针和长度。</p>
     *
     * @param mtr         Mini-Transaction
     * @param prevPage    前驱节点所在页面（如果有）
     * @param prevOffset  前驱节点偏移
     * @param nextPage    后继节点所在页面（如果有）
     * @param nextOffset  后继节点偏移
     */
    public void remove(MiniTransaction mtr, Page prevPage, int prevOffset,
                       Page nextPage, int nextOffset) throws MtrStateException, PageNotManagedByMtrException {
        // 更新前驱节点的 next 指针
        if (prevPage != null) {
            FlstNode prevNode = new FlstNode(prevPage, prevOffset);
            if (hasNext()) {
                prevNode.setNextNode(mtr, getNextPageNo(), getNextOffset());
            } else {
                prevNode.setNextNode(mtr, FIL_NULL, 0);
            }
        }

        // 更新后继节点的 prev 指针
        if (nextPage != null) {
            FlstNode nextNode = new FlstNode(nextPage, nextOffset);
            if (hasPrev()) {
                nextNode.setPrevNode(mtr, getPrevPageNo(), getPrevOffset());
            } else {
                nextNode.setPrevNode(mtr, FIL_NULL, 0);
            }
        }

        // 清空当前节点的指针
        initialize(mtr);
    }

    @Override
    public String toString() {
        return String.format("FlstNode{prev=(%d,%d), next=(%d,%d)}",
                getPrevPageNo(),
                getPrevOffset(),
                getNextPageNo(),
                getNextOffset());
    }
}
