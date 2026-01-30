package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;

/**
 * 线程安全的 B+Tree 包装器
 *
 * <p>提供线程安全的 B+Tree 操作接口。</p>
 *
 * <h2>并发策略</h2>
 * <ul>
 *   <li>读操作：使用乐观锁，失败后回退到悲观锁</li>
 *   <li>写操作：使用蟹行协议</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class ConcurrentBTree {

    /** 底层 B+Tree */
    private final BTree btree;

    /** Buffer Pool */
    private final BufferPool bufferPool;

    /** 记录比较器 */
    private final RecordComparator comparator;

    /** 乐观读取最大重试次数 */
    private static final int MAX_OPTIMISTIC_RETRIES = 3;

    /**
     * 构造并发 B+Tree
     *
     * @param btree      底层 B+Tree
     * @param bufferPool Buffer Pool
     * @param comparator 记录比较器
     */
    public ConcurrentBTree(BTree btree, BufferPool bufferPool, RecordComparator comparator) {
        this.btree = btree;
        this.bufferPool = bufferPool;
        this.comparator = comparator;
    }

    /**
     * 从现有 B+Tree 创建并发包装器
     *
     * @param btree 底层 B+Tree
     * @return 并发 B+Tree
     */
    public static ConcurrentBTree wrap(BTree btree) {
        return new ConcurrentBTree(btree, btree.getBufferPool(), btree.getComparator());
    }

    /**
     * 创建新的并发 B+Tree
     *
     * @param indexId    索引 ID
     * @param spaceId    空间 ID
     * @param bufferPool Buffer Pool
     * @param comparator 记录比较器
     * @param mtr        Mini-Transaction
     * @return 并发 B+Tree
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static ConcurrentBTree create(long indexId, int spaceId, BufferPool bufferPool,
                                         RecordComparator comparator, MiniTransaction mtr)
            throws MiniDbException {
        BTree btree = BTree.create(indexId, spaceId, bufferPool, comparator, mtr);
        return new ConcurrentBTree(btree, bufferPool, comparator);
    }

    // ==================== 搜索操作 ====================

    /**
     * 搜索（使用乐观锁）
     *
     * @param searchKey 搜索键
     * @param mtr       Mini-Transaction
     * @return 搜索结果
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public BTreeSearchResult search(byte[] searchKey, MiniTransaction mtr) throws MiniDbException {
        return ConcurrentBTreeOps.optimisticSearchWithRetry(btree, searchKey, mtr, MAX_OPTIMISTIC_RETRIES);
    }

    /**
     * 悲观搜索（使用共享锁）
     *
     * @param searchKey 搜索键
     * @param mtr       Mini-Transaction
     * @return 搜索结果
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public BTreeSearchResult pessimisticSearch(byte[] searchKey, MiniTransaction mtr)
            throws MiniDbException {
        return ConcurrentBTreeOps.concurrentSearch(btree, searchKey, mtr);
    }

    /**
     * 检查键是否存在
     *
     * @param searchKey 搜索键
     * @param mtr       Mini-Transaction
     * @return 如果键存在返回 true
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public boolean containsKey(byte[] searchKey, MiniTransaction mtr) throws MiniDbException {
        BTreeSearchResult result = search(searchKey, mtr);
        return result != null && result.isExactMatch();
    }

    // ==================== 插入操作 ====================

    /**
     * 插入记录
     *
     * @param recordData 记录数据
     * @param searchKey  搜索键
     * @param mtr        Mini-Transaction
     * @return 如果成功插入返回 true
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public boolean insert(byte[] recordData, byte[] searchKey, MiniTransaction mtr)
            throws MiniDbException {
        return ConcurrentBTreeOps.concurrentInsert(btree, recordData, searchKey, mtr);
    }

    // ==================== 删除操作 ====================

    /**
     * 删除记录
     *
     * @param searchKey  搜索键
     * @param recordSize 记录大小
     * @param mtr        Mini-Transaction
     * @return 如果成功删除返回 true
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public boolean delete(byte[] searchKey, int recordSize, MiniTransaction mtr)
            throws MiniDbException {
        return ConcurrentBTreeOps.concurrentDelete(btree, searchKey, recordSize, mtr);
    }

    // ==================== 范围扫描 ====================

    /**
     * 全表扫描
     *
     * @param mtr Mini-Transaction
     * @return 范围扫描器
     */
    public BTreeRangeScanner fullScan(MiniTransaction mtr) {
        return btree.fullScan(mtr);
    }

    /**
     * 范围扫描
     *
     * @param mtr        Mini-Transaction
     * @param lowerBound 下界
     * @param upperBound 上界
     * @return 范围扫描器
     */
    public BTreeRangeScanner rangeScan(MiniTransaction mtr, RangeBound lowerBound, RangeBound upperBound) {
        return btree.rangeScan(mtr, lowerBound, upperBound);
    }

    // ==================== Getter 方法 ====================

    /**
     * 获取底层 B+Tree
     *
     * @return 底层 B+Tree
     */
    public BTree getUnderlyingBTree() {
        return btree;
    }

    /**
     * 获取树高度
     *
     * @return 树高度
     */
    public int getTreeHeight() {
        return btree.getTreeHeight();
    }

    /**
     * 获取记录总数
     *
     * @return 记录总数
     */
    public long getRecordCount() {
        return btree.getRecordCount();
    }

    /**
     * 获取索引元数据
     *
     * @return 索引元数据
     */
    public BTreeMetadata getMetadata() {
        return btree.getMetadata();
    }
}
