package cn.zhangyis.minidb.storage.constants;

/**
 * 存储引擎常量定义
 * 
 * <p>本类定义了存储引擎中使用的所有常量，参考 InnoDB 存储引擎的设计。
 * 包括页面大小、记录格式、Buffer Pool 配置等核心参数。</p>
 * 
 * <h2>InnoDB 对应关系</h2>
 * <ul>
 *   <li>PAGE_SIZE: 对应 innodb_page_size，默认 16KB</li>
 *   <li>FIL_HEADER_SIZE: 对应 fil0fil.h 中的 FIL_PAGE_DATA (38字节)</li>
 *   <li>LRU_OLD_RATIO: 对应 innodb_old_blocks_pct (默认 37%)</li>
 *   <li>LRU_OLD_BLOCK_TIME_MS: 对应 innodb_old_blocks_time (默认 1000ms)</li>
 * </ul>
 * 
 * <h2>参考资料</h2>
 * <ul>
 *   <li>《MySQL技术内幕：InnoDB存储引擎》第4章</li>
 *   <li>InnoDB 源码: storage/innobase/include/fil0fil.h</li>
 *   <li>InnoDB 源码: storage/innobase/include/buf0buf.h</li>
 * </ul>
 * 
 * @author MiniDB
 * @version 1.0
 */
public final class StorageConstants {
    
    // ==================== Page 相关常量 ====================
    
    /**
     * 页面大小 (16KB = 16384 字节)
     * 
     * <p>InnoDB 默认页大小为 16384 字节。这是数据库 I/O 的基本单位，
     * 所有数据都以页为单位进行读写。</p>
     * 
     * <p>选择 16KB 的原因：
     * <ul>
     *   <li>与操作系统文件系统块大小 (4KB) 的整数倍对齐</li>
     *   <li>平衡 I/O 效率和内存利用率</li>
     *   <li>适合 B+Tree 的扇出率 (通常 100-500)</li>
     * </ul>
     * </p>
     */
    public static final int PAGE_SIZE = 16384;
    
    /**
     * FIL Header 大小 (38 字节)
     * 
     * <p>每个页面的前 38 字节是文件层头部 (FIL Header)，结构如下：</p>
     * <pre>
     * Offset  Size  Description
     * ------  ----  -----------
     *   0      4    checksum (页面校验和)
     *   4      4    page_no (页号)
     *   8      4    prev_page (前一页号，用于双向链表)
     *  12      4    next_page (后一页号)
     *  16      8    lsn (最后修改的 Log Sequence Number)
     *  24      2    page_type (页类型)
     *  26      8    flush_lsn (刷盘 LSN，仅 space 0 page 0 使用)
     *  34      4    space_id (表空间 ID)
     * </pre>
     */
    public static final int FIL_HEADER_SIZE = 38;
    
    /**
     * FIL Trailer 大小 (8 字节)
     * 
     * <p>每个页面的最后 8 字节是文件层尾部 (FIL Trailer)，结构如下：</p>
     * <pre>
     * Offset  Size  Description
     * ------  ----  -----------
     *   0      4    checksum (与 header 相同)
     *   4      4    lsn_low32 (LSN 的低 32 位)
     * </pre>
     * 
     * <p>Trailer 用于检测 partial write（页面写入不完整的情况）。
     * 如果 Header 和 Trailer 的校验和不匹配，说明页面写入时发生了崩溃。</p>
     */
    public static final int FIL_TRAILER_SIZE = 8;
    
    /**
     * 无效页号标记 (0xFFFFFFFF)
     * 
     * <p>用于表示"空"或"无效"的页面引用，相当于链表的 NULL 指针。
     * 使用场景：
     * <ul>
     *   <li>双向链表的头节点的 prev_page</li>
     *   <li>双向链表的尾节点的 next_page</li>
     *   <li>未分配的页面引用</li>
     * </ul>
     * </p>
     */
    public static final int FIL_NULL = 0xFFFFFFFF;
    
    // ==================== Record 相关常量 ====================
    
    /**
     * 记录头大小 (Compact 格式, 5 字节)
     * 
     * <p>InnoDB Compact 行格式的记录头结构 (共 40 bits = 5 bytes)：</p>
     * <pre>
     * Bits   Description
     * ----   -----------
     *  4     info_bits (删除标记 delete_flag, 最小记录标记 min_rec_flag 等)
     *  4     n_owned (该记录在 Page Directory 中拥有的记录数)
     * 13     heap_no (记录在堆中的序号，0=infimum, 1=supremum, 2+=用户记录)
     *  3     record_type (0=普通记录, 1=B+Tree非叶节点, 2=infimum, 3=supremum)
     * 16     next_record (下一条记录的相对偏移，形成单向链表)
     * </pre>
     */
    public static final int REC_HEADER_SIZE = 5;
    
    /**
     * 隐藏列: ROW_ID 大小 (6 字节)
     * 
     * <p>当表没有显式定义主键且没有非空唯一索引时，
     * InnoDB 自动生成 6 字节的 ROW_ID 作为隐藏聚簇索引键。
     * ROW_ID 是一个全局递增的 48 位整数。</p>
     */
    public static final int ROW_ID_SIZE = 6;
    
    /**
     * 隐藏列: TRX_ID 大小 (6 字节)
     * 
     * <p>记录最后修改该行的事务 ID (Transaction ID)。
     * 用于 MVCC (Multi-Version Concurrency Control) 实现可重复读等隔离级别。
     * TRX_ID 是一个全局递增的 48 位整数。</p>
     */
    public static final int TRX_ID_SIZE = 6;
    
    /**
     * 隐藏列: ROLL_PTR 大小 (7 字节)
     * 
     * <p>回滚指针 (Rollback Pointer)，指向 Undo Log 中该行的上一个版本。</p>
     * <p>ROLL_PTR 结构 (共 56 bits = 7 bytes)：</p>
     * <pre>
     * Bits   Description
     * ----   -----------
     *  1     is_insert (是否是 INSERT 类型的 undo)
     *  7     rseg_id (Rollback Segment ID)
     * 32     page_no (Undo Log 所在的页号)
     * 16     offset (页内偏移)
     * </pre>
     * 
     * <p>用途：
     * <ul>
     *   <li>事务回滚时沿着版本链恢复数据</li>
     *   <li>MVCC 读取符合可见性的历史版本</li>
     * </ul>
     * </p>
     */
    public static final int ROLL_PTR_SIZE = 7;
    
    /**
     * 隐藏列总大小 (19 字节 = 6 + 6 + 7)
     *
     * <p>聚簇索引的每行数据都包含这三个隐藏列：
     * ROW_ID (可选) + TRX_ID + ROLL_PTR</p>
     */
    public static final int HIDDEN_COLS_SIZE = ROW_ID_SIZE + TRX_ID_SIZE + ROLL_PTR_SIZE;

    // ==================== Extent & Segment 相关常量 ====================

    /**
     * Extent 大小 (64 页)
     *
     * <p>Extent (区) 是 InnoDB 批量分配页面的基本单位。
     * 一个 Extent 包含 64 个连续的页面，大小为 1MB (64 × 16KB)。</p>
     *
     * <p>使用 Extent 的好处：
     * <ul>
     *   <li>顺序 I/O：64 个连续页面可以一次性读取，提升性能</li>
     *   <li>预读优化：利于线性预读和随机预读策略</li>
     *   <li>碎片减少：批量分配避免页面级碎片</li>
     * </ul>
     * </p>
     */
    public static final int EXTENT_SIZE = 64;

    /**
     * Extent 字节大小 (1MB = 1048576 字节)
     *
     * <p>计算公式: 64 页 × 16384 字节/页 = 1048576 字节</p>
     */
    public static final long EXTENT_SIZE_BYTES = (long) EXTENT_SIZE * PAGE_SIZE;

    /**
     * 每个 Extent Group 包含的 Extent 数量 (256 个)
     *
     * <p>每个 XDES Page (区描述页) 可以描述 256 个 Extent。
     * 对应的页面数量为 256 × 64 = 16384 页 = 256MB。</p>
     */
    public static final int EXTENTS_PER_GROUP = 256;

    /**
     * 每个 Extent Group 包含的页面数量 (16384 页)
     *
     * <p>计算公式: 256 个 Extent × 64 页/Extent = 16384 页</p>
     *
     * <p>这也决定了 XDES Page 的间隔：每 16384 页需要一个 XDES Page。
     * 位置分别为: page 0 (FSP_HDR), page 16384, page 32768, ...</p>
     */
    public static final int PAGES_PER_EXTENT_GROUP = EXTENTS_PER_GROUP * EXTENT_SIZE;

    // ==================== FSP Header (表空间头) 相关常量 ====================

    /**
     * FSP Header 大小 (112 字节)
     *
     * <p>FSP Header 位于表空间的第一个页面 (page 0)，紧跟在 FIL Header 之后。
     * 它存储了表空间的元信息和空闲 Extent 链表。</p>
     */
    public static final int FSP_HEADER_SIZE = 112;

    /**
     * FSP_SPACE_ID 偏移 (38)
     *
     * <p>表空间 ID (4 字节)，与 FIL Header 中的 space_id 冗余。</p>
     */
    public static final int FSP_SPACE_ID = FIL_HEADER_SIZE + 0;

    /**
     * FSP_NOT_USED 偏移 (42)
     *
     * <p>保留未使用字段 (4 字节)。</p>
     */
    public static final int FSP_NOT_USED = FIL_HEADER_SIZE + 4;

    /**
     * FSP_SIZE 偏移 (46)
     *
     * <p>当前表空间总页数 (4 字节)。
     * 表示表空间文件的物理大小（以页为单位）。</p>
     */
    public static final int FSP_SIZE = FIL_HEADER_SIZE + 8;

    /**
     * FSP_FREE_LIMIT 偏移 (50)
     *
     * <p>已初始化的页数 (4 字节)。
     * 小于此值的所有页面的 XDES Entry 都已初始化。</p>
     */
    public static final int FSP_FREE_LIMIT = FIL_HEADER_SIZE + 12;

    /**
     * FSP_SPACE_FLAGS 偏移 (54)
     *
     * <p>表空间标志位 (4 字节)，包括页大小、压缩、加密等信息。</p>
     */
    public static final int FSP_SPACE_FLAGS = FIL_HEADER_SIZE + 16;

    /**
     * FSP_FRAG_N_USED 偏移 (58)
     *
     * <p>碎片区已使用页数 (4 字节)。
     * 碎片区是不属于任何 Segment 的零散页面。</p>
     */
    public static final int FSP_FRAG_N_USED = FIL_HEADER_SIZE + 20;

    /**
     * FSP_FREE 偏移 (62)
     *
     * <p>空闲 Extent 链表基节点 (16 字节 = FLST_BASE_NODE_SIZE)。
     * 链表中的 Extent 完全空闲，不属于任何 Segment。</p>
     */
    public static final int FSP_FREE = FIL_HEADER_SIZE + 24;

    /**
     * FSP_FREE_FRAG 偏移 (78)
     *
     * <p>部分空闲的碎片 Extent 链表基节点 (16 字节)。
     * 链表中的 Extent 部分页面已分配，用于碎片页分配。</p>
     */
    public static final int FSP_FREE_FRAG = FIL_HEADER_SIZE + 40;

    /**
     * FSP_FULL_FRAG 偏移 (94)
     *
     * <p>全满的碎片 Extent 链表基节点 (16 字节)。
     * 链表中的 Extent 所有页面都已分配为碎片页。</p>
     */
    public static final int FSP_FULL_FRAG = FIL_HEADER_SIZE + 56;

    /**
     * FSP_SEG_ID 偏移 (110)
     *
     * <p>下一个可分配的 Segment ID (8 字节)。
     * Segment ID 全局递增，类似于自增主键。</p>
     */
    public static final int FSP_SEG_ID = FIL_HEADER_SIZE + 72;

    /**
     * FSP_SEG_INODES_FULL 偏移 (118)
     *
     * <p>全满的 INODE Page 链表基节点 (16 字节)。
     * 链表中的 INODE Page 所有 85 个 INODE Entry 都已分配。</p>
     */
    public static final int FSP_SEG_INODES_FULL = FIL_HEADER_SIZE + 80;

    /**
     * FSP_SEG_INODES_FREE 偏移 (134)
     *
     * <p>有空闲的 INODE Page 链表基节点 (16 字节)。
     * 链表中的 INODE Page 至少有一个 INODE Entry 未分配。</p>
     */
    public static final int FSP_SEG_INODES_FREE = FIL_HEADER_SIZE + 96;

    // ==================== XDES Entry (区描述符) 相关常量 ====================

    /**
     * XDES Entry 大小 (40 字节)
     *
     * <p>XDES Entry (区描述符) 描述一个 Extent 的状态和所属 Segment。
     * 每个 XDES Page 包含 256 个 XDES Entry。</p>
     */
    public static final int XDES_ENTRY_SIZE = 40;

    /**
     * XDES Array 偏移 (150)
     *
     * <p>FSP_HDR Page (page 0) 中 XDES Array 的起始位置。
     * FSP_HDR = FIL Header (38) + FSP Header (112) + XDES Array (10240)。</p>
     */
    public static final int XDES_ARR_OFFSET = FIL_HEADER_SIZE + FSP_HEADER_SIZE;

    /**
     * XDES_ID 偏移 (0)
     *
     * <p>Segment ID (8 字节)。
     * 如果为 0，表示此 Extent 不属于任何 Segment (FREE 状态)。</p>
     */
    public static final int XDES_ID = 0;

    /**
     * XDES_FLST_NODE 偏移 (8)
     *
     * <p>链表节点 (12 字节 = FLST_NODE_SIZE)。
     * 用于将 Extent 链接到各种链表中 (FSP_FREE, INODE_FREE 等)。</p>
     */
    public static final int XDES_FLST_NODE = 8;

    /**
     * XDES_STATE 偏移 (20)
     *
     * <p>Extent 状态 (4 字节)。
     * 取值: FREE(1), FREE_FRAG(2), FULL_FRAG(3), FSEG(4), FSEG_FREE(5)。</p>
     */
    public static final int XDES_STATE = 20;

    /**
     * XDES_BITMAP 偏移 (24)
     *
     * <p>页面 Bitmap (16 字节 = 128 bits)。
     * 每页使用 2 bits: bit 0 = FREE (1=空闲, 0=已分配), bit 1 = CLEAN (1=干净, 0=脏页)。
     * 64 页 × 2 bits = 128 bits = 16 字节。</p>
     */
    public static final int XDES_BITMAP = 24;

    // ==================== INODE Page (段目录页) 相关常量 ====================

    /**
     * INODE Entry 大小 (192 字节)
     *
     * <p>INODE Entry 描述一个 Segment 的元信息，包括所属的 Extent 链表。
     * 每个 INODE Page 包含 85 个 INODE Entry。</p>
     */
    public static final int INODE_ENTRY_SIZE = 192;

    /**
     * 每个 INODE Page 包含的 INODE Entry 数量 (85)
     *
     * <p>计算公式: (16384 - 38 - 12 - 8) / 192 = 85。
     * INODE Page 布局: FIL Header (38) + INODE Header (12) + 85×INODE Entry (16320) + Unused (6) + FIL Trailer (8)。</p>
     */
    public static final int INODES_PER_PAGE = (PAGE_SIZE - FIL_HEADER_SIZE - 12 - FIL_TRAILER_SIZE) / INODE_ENTRY_SIZE;

    /**
     * INODE Page Header 大小 (12 字节)
     *
     * <p>INODE Page Header 结构:
     * <ul>
     *   <li>0-3: INODE_PAGE_LIST - FLST_NODE (链表节点, 链接到 FSP_SEG_INODES_FREE/FULL)</li>
     *   <li>4-7: INODE_PAGE_NEXT - 下一个 INODE Page</li>
     *   <li>8-11: INODE_PAGE_PREV - 上一个 INODE Page</li>
     * </ul>
     * </p>
     */
    public static final int INODE_PAGE_HEADER_SIZE = 12;

    /**
     * INODE_SEGMENT_ID 偏移 (0)
     *
     * <p>Segment ID (8 字节)。
     * 如果为 0，表示此 INODE Entry 未分配。</p>
     */
    public static final int INODE_SEGMENT_ID = 0;

    /**
     * INODE_NOT_FULL_N_USED 偏移 (8)
     *
     * <p>NOT_FULL 链表中已使用的页数 (4 字节)。
     * 用于跟踪 Partial Extent 的使用情况。</p>
     */
    public static final int INODE_NOT_FULL_N_USED = 8;

    /**
     * INODE_FREE 偏移 (12)
     *
     * <p>Free Extent 链表基节点 (16 字节)。
     * 链表中的 Extent 完全空闲，属于此 Segment。</p>
     */
    public static final int INODE_FREE = 12;

    /**
     * INODE_NOT_FULL 偏移 (28)
     *
     * <p>Partial Extent 链表基节点 (16 字节)。
     * 链表中的 Extent 部分页面已分配，属于此 Segment。</p>
     */
    public static final int INODE_NOT_FULL = 28;

    /**
     * INODE_FULL 偏移 (44)
     *
     * <p>Full Extent 链表基节点 (16 字节)。
     * 链表中的 Extent 所有 64 页都已分配，属于此 Segment。</p>
     */
    public static final int INODE_FULL = 44;

    /**
     * INODE_MAGIC_N 偏移 (60)
     *
     * <p>魔数 (4 字节)，固定值 97937874。
     * 用于校验 INODE Entry 的完整性。</p>
     */
    public static final int INODE_MAGIC_N = 60;

    /**
     * INODE_FRAG_ARRAY 偏移 (64)
     *
     * <p>碎片页数组 (128 字节 = 32 个页号 × 4 字节)。
     * Segment 的前 32 个页面单独分配（不占用完整 Extent），避免小表浪费空间。
     * PageNo = FIL_NULL 表示未分配。</p>
     */
    public static final int INODE_FRAG_ARRAY = 64;

    /**
     * 碎片页数组大小 (128 字节)
     *
     * <p>计算公式: 32 个页号 × 4 字节/页号 = 128 字节</p>
     */
    public static final int INODE_FRAG_ARRAY_SIZE = 128;

    /**
     * 碎片页数组中的页面数量 (32)
     *
     * <p>前 32 个页面（512KB）单独分配，适合小表和小索引。</p>
     */
    public static final int INODE_FRAG_ARRAY_PAGES = 32;

    /**
     * INODE Entry 魔数值 (97937874)
     *
     * <p>用于校验 INODE Entry 的完整性。</p>
     */
    public static final int INODE_MAGIC_NUMBER = 97937874;

    // ==================== 链表相关常量 ====================

    /**
     * FLST_BASE_NODE 大小 (16 字节)
     *
     * <p>链表基节点结构 (用于链表头部)：
     * <ul>
     *   <li>0-3: Length - 链表节点数 (4 字节)</li>
     *   <li>4-7: First Page Number (4 字节)</li>
     *   <li>8-9: First Offset (2 字节)</li>
     *   <li>10-13: Last Page Number (4 字节)</li>
     *   <li>14-15: Last Offset (2 字节)</li>
     * </ul>
     * </p>
     */
    public static final int FLST_BASE_NODE_SIZE = 16;

    /**
     * FLST_NODE 大小 (12 字节)
     *
     * <p>链表节点结构 (用于链表元素)：
     * <ul>
     *   <li>0-3: Prev Page Number (4 字节)</li>
     *   <li>4-5: Prev Offset (2 字节)</li>
     *   <li>6-9: Next Page Number (4 字节)</li>
     *   <li>10-11: Next Offset (2 字节)</li>
     * </ul>
     * </p>
     */
    public static final int FLST_NODE_SIZE = 12;

    // FLST_BASE_NODE 字段偏移
    /**
     * FLST_BASE_NODE: Length 字段偏移 (0)
     * <p>链表中节点的总数量 (4 字节)</p>
     */
    public static final int FLST_LEN = 0;

    /**
     * FLST_BASE_NODE: First Page Number 字段偏移 (4)
     * <p>第一个节点所在的页号 (4 字节)</p>
     */
    public static final int FLST_FIRST_PAGE_NO = 4;

    /**
     * FLST_BASE_NODE: First Offset 字段偏移 (8)
     * <p>第一个节点在页面中的偏移 (2 字节)</p>
     */
    public static final int FLST_FIRST_OFFSET = 8;

    /**
     * FLST_BASE_NODE: Last Page Number 字段偏移 (10)
     * <p>最后一个节点所在的页号 (4 字节)</p>
     */
    public static final int FLST_LAST_PAGE_NO = 10;

    /**
     * FLST_BASE_NODE: Last Offset 字段偏移 (14)
     * <p>最后一个节点在页面中的偏移 (2 字节)</p>
     */
    public static final int FLST_LAST_OFFSET = 14;

    // FLST_NODE 字段偏移
    /**
     * FLST_NODE: Prev Page Number 字段偏移 (0)
     * <p>前一个节点所在的页号 (4 字节)</p>
     */
    public static final int FLST_PREV_PAGE_NO = 0;

    /**
     * FLST_NODE: Prev Offset 字段偏移 (4)
     * <p>前一个节点在页面中的偏移 (2 字节)</p>
     */
    public static final int FLST_PREV_OFFSET = 4;

    /**
     * FLST_NODE: Next Page Number 字段偏移 (6)
     * <p>下一个节点所在的页号 (4 字节)</p>
     */
    public static final int FLST_NEXT_PAGE_NO = 6;

    /**
     * FLST_NODE: Next Offset 字段偏移 (10)
     * <p>下一个节点在页面中的偏移 (2 字节)</p>
     */
    public static final int FLST_NEXT_OFFSET = 10;

    // ==================== Buffer Pool 相关常量 ====================
    
    /**
     * 默认 Buffer Pool 大小 (128MB)
     * 
     * <p>Buffer Pool 是 InnoDB 最重要的内存结构，缓存数据页和索引页。
     * 生产环境建议设置为物理内存的 70-80%。</p>
     * 
     * <p>Buffer Pool 的作用：
     * <ul>
     *   <li>减少磁盘 I/O：热点数据常驻内存</li>
     *   <li>写缓冲：修改先在内存中进行，后台异步刷盘</li>
     *   <li>预读优化：顺序访问时预读相邻页面</li>
     * </ul>
     * </p>
     */
    public static final int DEFAULT_BUFFER_POOL_SIZE = 128 * 1024 * 1024;
    
    /**
     * 默认 Buffer Pool 页数 (8192 页)
     * 
     * <p>128MB / 16KB = 8192 页</p>
     */
    public static final int DEFAULT_BUFFER_POOL_PAGES = DEFAULT_BUFFER_POOL_SIZE / PAGE_SIZE;
    
    /**
     * LRU Old 区比例 (37.5% ≈ 3/8)
     * 
     * <p>InnoDB 使用改进的 LRU 算法，将链表分为两部分：</p>
     * <pre>
     * +--------------------------------------------------+
     * |  YOUNG 区 (热点页, ~5/8)  |  OLD 区 (冷页, ~3/8)  |
     * +--------------------------------------------------+
     *        ↑ MRU                  midpoint        LRU ↓
     * </pre>
     * 
     * <p>Midpoint Insertion 策略：新读取的页面插入到 midpoint 位置（Old 区头部），
     * 而不是 MRU 端。这样做的好处是防止一次性大量读取（如全表扫描、mysqldump）
     * 将真正的热点页面挤出 Buffer Pool。</p>
     * 
     * <p>对应 MySQL 参数: innodb_old_blocks_pct (默认 37)</p>
     * 
     * @see #LRU_OLD_BLOCK_TIME_MS
     */
    public static final double LRU_OLD_RATIO = 0.375;
    
    /**
     * 页面在 Old 区停留时间阈值 (1000 毫秒)
     * 
     * <p>只有在 Old 区停留超过此时间后被再次访问的页面，才会被移动到 Young 区。
     * 这进一步增强了对一次性扫描的防护。</p>
     * 
     * <p>工作原理：
     * <ol>
     *   <li>新页面加载到 Old 区头部，记录时间戳</li>
     *   <li>页面被再次访问时，检查是否已在 Old 区停留超过 1 秒</li>
     *   <li>如果超过，移动到 Young 区头部；否则保持在 Old 区</li>
     * </ol>
     * </p>
     * 
     * <p>对应 MySQL 参数: innodb_old_blocks_time (默认 1000)</p>
     */
    public static final long LRU_OLD_BLOCK_TIME_MS = 1000;
    
    /**
     * 私有构造函数，防止实例化常量类
     */
    private StorageConstants() {
        throw new UnsupportedOperationException("Constants class cannot be instantiated");
    }
}
