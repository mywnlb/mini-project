package cn.zhangyis.minidb.storage.page;

import java.util.Objects;

/**
 * 页面唯一标识符
 * 
 * <p>InnoDB 使用 (space_id, page_no) 二元组来唯一标识一个页面。
 * 这是 Buffer Pool 中页面查找的关键数据结构。</p>
 * 
 * <h2>组成部分</h2>
 * <ul>
 *   <li><b>space_id</b>: 表空间 ID，标识页面属于哪个表空间</li>
 *   <li><b>page_no</b>: 页面在表空间内的序号 (从 0 开始)</li>
 * </ul>
 * 
 * <h2>使用场景</h2>
 * <ul>
 *   <li>Buffer Pool 的 Page Hash 表的 key</li>
 *   <li>页面的读写定位</li>
 *   <li>B+Tree 节点间的指针引用</li>
 * </ul>
 * 
 * <h2>InnoDB 对应</h2>
 * <p>对应 InnoDB 中的 page_id_t 类型 (buf0types.h)</p>
 * 
 * <h2>不可变性</h2>
 * <p>PageId 是不可变类 (Immutable)，创建后 space_id 和 page_no 不可修改。
 * 这保证了它可以安全地用作 HashMap 的 key。</p>
 * 
 * @author MiniDB
 * @version 1.0
 */
public final class PageId implements Comparable<PageId> {
    
    /**
     * 表空间 ID
     * 
     * <p>标识页面所属的表空间：
     * <ul>
     *   <li>0: 系统表空间 (ibdata1)</li>
     *   <li>1+: 独立表空间 (每个表的 .ibd 文件)</li>
     * </ul>
     * </p>
     */
    private final int spaceId;
    
    /**
     * 页面序号
     * 
     * <p>页面在表空间文件中的位置，从 0 开始。
     * 页面在文件中的物理偏移 = page_no * PAGE_SIZE</p>
     */
    private final int pageNo;
    
    /**
     * 构造函数
     * 
     * @param spaceId 表空间 ID
     * @param pageNo  页面序号
     */
    public PageId(int spaceId, int pageNo) {
        this.spaceId = spaceId;
        this.pageNo = pageNo;
    }
    
    /**
     * 静态工厂方法 (推荐使用)
     * 
     * <p>提供更简洁的创建方式：{@code PageId.of(1, 100)}</p>
     * 
     * @param spaceId 表空间 ID
     * @param pageNo  页面序号
     * @return 新的 PageId 实例
     */
    public static PageId of(int spaceId, int pageNo) {
        return new PageId(spaceId, pageNo);
    }
    
    /**
     * 获取表空间 ID
     * 
     * @return 表空间 ID
     */
    public int getSpaceId() {
        return spaceId;
    }
    
    /**
     * 获取页面序号
     * 
     * @return 页面序号
     */
    public int getPageNo() {
        return pageNo;
    }
    
    /**
     * 计算页面在文件中的物理偏移量
     * 
     * @param pageSize 页面大小 (通常为 16384)
     * @return 文件偏移量 (字节)
     */
    public long getFileOffset(int pageSize) {
        return (long) pageNo * pageSize;
    }
    
    /**
     * 判断是否相等
     * 
     * <p>两个 PageId 相等当且仅当 space_id 和 page_no 都相等。</p>
     * 
     * @param o 要比较的对象
     * @return 如果相等返回 true
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PageId other)) return false;
        return spaceId == other.spaceId && pageNo == other.pageNo;
    }
    
    /**
     * 计算哈希值
     * 
     * <p>基于 space_id 和 page_no 计算，用于 HashMap 等数据结构。</p>
     * 
     * @return 哈希值
     */
    @Override
    public int hashCode() {
        return Objects.hash(spaceId, pageNo);
    }
    
    /**
     * 比较两个 PageId 的顺序
     * 
     * <p>先按 space_id 排序，space_id 相同时按 page_no 排序。
     * 这个顺序对应页面在磁盘上的物理顺序，有利于顺序 I/O。</p>
     * 
     * @param other 要比较的 PageId
     * @return 负数、零或正数，表示小于、等于或大于
     */
    @Override
    public int compareTo(PageId other) {
        int cmp = Integer.compare(this.spaceId, other.spaceId);
        return cmp != 0 ? cmp : Integer.compare(this.pageNo, other.pageNo);
    }
    
    /**
     * 返回字符串表示
     * 
     * @return 格式为 "PageId(space_id, page_no)" 的字符串
     */
    @Override
    public String toString() {
        return String.format("PageId(%d, %d)", spaceId, pageNo);
    }
}
