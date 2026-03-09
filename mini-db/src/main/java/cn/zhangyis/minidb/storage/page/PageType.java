package cn.zhangyis.minidb.storage.page;

/**
 * InnoDB 页面类型枚举
 * 
 * <p>InnoDB 使用不同类型的页面来存储不同类型的数据。
 * 页面类型存储在 FIL Header 的 offset 24-25 (2字节)。</p>
 * 
 * <h2>常用页面类型</h2>
 * <ul>
 *   <li>FIL_PAGE_INDEX (17855): 最常见，用于存储 B+Tree 索引数据</li>
 *   <li>FIL_PAGE_UNDO_LOG (2): 存储 Undo Log 记录</li>
 *   <li>FIL_PAGE_TYPE_FSP_HDR (8): 表空间头页，存储表空间元信息</li>
 * </ul>
 * 
 * <h2>InnoDB 源码参考</h2>
 * <p>对应 storage/innobase/include/fil0fil.h 中的 FIL_PAGE_TYPE_* 定义</p>
 * 
 * @author MiniDB
 * @version 1.0
 */
public enum PageType {
    
    /**
     * 新分配的页面，尚未使用
     * <p>页面分配后的初始状态，还没有被格式化为任何特定类型。</p>
     */
    FIL_PAGE_TYPE_ALLOCATED(0),
    
    /**
     * Undo Log 页
     * <p>存储事务的 Undo Log 记录，用于：
     * <ul>
     *   <li>事务回滚</li>
     *   <li>MVCC 多版本读取</li>
     * </ul>
     * </p>
     */
    FIL_PAGE_UNDO_LOG(2),
    
    /**
     * Inode 页 (段信息节点)
     * <p>存储段 (Segment) 的管理信息，包括段中的区 (Extent) 列表。</p>
     */
    FIL_PAGE_INODE(3),
    
    /**
     * Insert Buffer 空闲列表页
     * <p>Change Buffer (原 Insert Buffer) 的空闲页管理。</p>
     */
    FIL_PAGE_IBUF_FREE_LIST(4),
    
    /**
     * Insert Buffer 位图页
     * <p>Change Buffer 的位图，标记哪些页面有待合并的变更。</p>
     */
    FIL_PAGE_IBUF_BITMAP(5),
    
    /**
     * 系统页
     * <p>存储系统级别的元数据。</p>
     */
    FIL_PAGE_TYPE_SYS(6),
    
    /**
     * 事务系统页
     * <p>存储事务系统的信息，如最大事务 ID、回滚段信息等。
     * 位于系统表空间的固定位置。</p>
     */
    FIL_PAGE_TYPE_TRX_SYS(7),
    
    /**
     * 表空间头页 (FSP_HDR)
     * <p>每个表空间的第一页 (page 0)，存储表空间的元信息：
     * <ul>
     *   <li>表空间 ID</li>
     *   <li>页面大小</li>
     *   <li>已分配的页数</li>
     *   <li>空闲区链表</li>
     * </ul>
     * </p>
     */
    FIL_PAGE_TYPE_FSP_HDR(8),
    
    /**
     * 区描述页 (XDES)
     * <p>存储区 (Extent, 64个连续页面) 的描述信息，
     * 包括区的状态和所属的段。</p>
     */
    FIL_PAGE_TYPE_XDES(9),
    
    /**
     * BLOB 页
     * <p>存储溢出的大对象数据 (如 TEXT, BLOB 类型的长内容)。</p>
     */
    FIL_PAGE_TYPE_BLOB(10),
    
    /**
     * B+Tree 索引页 (最常用)
     * <p>存储 B+Tree 索引的节点数据，包括：
     * <ul>
     *   <li>聚簇索引 (Clustered Index): 存储完整行数据</li>
     *   <li>二级索引 (Secondary Index): 存储索引列 + 主键</li>
     * </ul>
     * </p>
     * <p>值为 17855 (0x45BF) 是一个特殊值，便于页面类型识别。</p>
     */
    FIL_PAGE_INDEX(17855),
    
    /**
     * R-Tree 索引页
     * <p>用于空间数据类型的 R-Tree 索引 (GIS 功能)。</p>
     */
    FIL_PAGE_RTREE(17854),
    
    /**
     * 序列化字典信息页 (SDI)
     * <p>MySQL 8.0+ 存储数据字典的序列化信息。</p>
     */
    FIL_PAGE_SDI(17853),

    /**
     * Catalog 元数据页
     * <p>存储 Catalog 全局信息：ID 生成器计数器 + 数据库列表。
     * 位于系统表空间 Page 3。</p>
     */
    FIL_PAGE_CATALOG_META(17840),

    /**
     * 表元数据页
     * <p>存储表定义（列、索引 ID、状态等），链式存储。
     * 位于系统表空间 Page 4+。</p>
     */
    FIL_PAGE_TABLE_META(17841),

    /**
     * DDL Log 页面
     * <p>存储 DDL Log 记录，用于原子 DDL 的 crash recovery。
     * 位于系统表空间 Page 5。</p>
     */
    FIL_PAGE_DDL_LOG(17842);
    
    /** 页面类型的数值 */
    private final int value;
    
    /**
     * 构造函数
     * 
     * @param value 页面类型的数值
     */
    PageType(int value) {
        this.value = value;
    }
    
    /**
     * 获取页面类型的数值
     * 
     * @return 页面类型值 (存储在页面中的实际值)
     */
    public int getValue() {
        return value;
    }
    
    /**
     * 根据数值查找对应的页面类型
     * 
     * <p>用于从磁盘读取页面后，根据存储的类型值确定页面类型。</p>
     * 
     * @param value 页面类型数值
     * @return 对应的 PageType 枚举值，未知类型返回 FIL_PAGE_TYPE_ALLOCATED
     */
    public static PageType fromValue(int value) {
        for (PageType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        // 未知类型，返回 ALLOCATED (视为未初始化)
        return FIL_PAGE_TYPE_ALLOCATED;
    }
    
    /**
     * 判断是否为索引页类型
     * 
     * @return 如果是 B+Tree 或 R-Tree 索引页返回 true
     */
    public boolean isIndexPage() {
        return this == FIL_PAGE_INDEX || this == FIL_PAGE_RTREE;
    }
}
