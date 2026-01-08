package cn.zhangyis.minidb.storage.space;

import cn.zhangyis.minidb.storage.page.PageId;

import static cn.zhangyis.minidb.storage.constants.StorageConstants.FIL_NULL;

/**
 * 文件地址（File Address）
 *
 * <p>FilAddr 表示 InnoDB 中的完整文件地址，包含页号和页内偏移。
 * 用于统一链表节点的定位，避免 pageNo 和 offset 分离导致的混淆。</p>
 *
 * <h2>物理结构（6字节）</h2>
 * <pre>
 * Offset  Size  Field
 * ------  ----  -----
 * 0       4     Page Number - 页号
 * 4       2     Offset - 页内偏移
 * </pre>
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li>链表操作中返回被移除节点的完整地址</li>
 *   <li>统一 FlstNode 的定位方式</li>
 *   <li>避免 removeFirst() 后读取错误的 offset</li>
 * </ul>
 *
 * <h2>InnoDB 对应关系</h2>
 * <ul>
 *   <li>对应源码: storage/innobase/include/fil0fil.h</li>
 *   <li>类型: fil_addr_t</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class FilAddr {

    /**
     * 空地址常量（表示无效/空引用）
     */
    public static final FilAddr NULL = new FilAddr(FIL_NULL, 0);

    /**
     * 页号
     */
    private final int pageNo;

    /**
     * 页内偏移
     */
    private final int offset;

    /**
     * 构造函数
     *
     * @param pageNo 页号
     * @param offset 页内偏移
     */
    public FilAddr(int pageNo, int offset) {
        this.pageNo = pageNo;
        this.offset = offset;
    }

    /**
     * 静态工厂方法
     *
     * @param pageNo 页号
     * @param offset 页内偏移
     * @return FilAddr 对象
     */
    public static FilAddr of(int pageNo, int offset) {
        if (pageNo == FIL_NULL) {
            return NULL;
        }
        return new FilAddr(pageNo, offset);
    }

    /**
     * 从 PageId 和 offset 创建
     *
     * @param pageId PageId 对象
     * @param offset 页内偏移
     * @return FilAddr 对象
     */
    public static FilAddr of(PageId pageId, int offset) {
        if (pageId == null) {
            return NULL;
        }
        return new FilAddr(pageId.getPageNo(), offset);
    }

    /**
     * 获取页号
     *
     * @return 页号
     */
    public int getPageNo() {
        return pageNo;
    }

    /**
     * 获取页内偏移
     *
     * @return 偏移量
     */
    public int getOffset() {
        return offset;
    }

    /**
     * 转换为 PageId（需要 spaceId）
     *
     * @param spaceId 表空间ID
     * @return PageId 对象，如果是空地址返回 null
     */
    public PageId toPageId(int spaceId) {
        if (isNull()) {
            return null;
        }
        return PageId.of(spaceId, pageNo);
    }

    /**
     * 判断是否为空地址
     *
     * @return true 如果页号为 FIL_NULL
     */
    public boolean isNull() {
        return pageNo == FIL_NULL;
    }

    /**
     * 判断是否为有效地址
     *
     * @return true 如果页号不为 FIL_NULL
     */
    public boolean isValid() {
        return pageNo != FIL_NULL;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        FilAddr filAddr = (FilAddr) obj;
        return pageNo == filAddr.pageNo && offset == filAddr.offset;
    }

    @Override
    public int hashCode() {
        return 31 * pageNo + offset;
    }

    @Override
    public String toString() {
        if (isNull()) {
            return "FilAddr{NULL}";
        }
        return String.format("FilAddr{pageNo=%d, offset=%d}", pageNo, offset);
    }
}
