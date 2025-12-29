package cn.zhangyis.minidb.storage;

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
