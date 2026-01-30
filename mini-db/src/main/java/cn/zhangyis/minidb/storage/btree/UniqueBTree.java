package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.PageId;

/**
 * 唯一索引 B+Tree
 *
 * <p>在普通 B+Tree 基础上增加唯一性约束检查。</p>
 *
 * <h2>唯一性保证</h2>
 * <ul>
 *   <li>插入前检查键是否已存在</li>
 *   <li>存在则抛出 DuplicateKeyException</li>
 *   <li>并发插入时使用锁保证原子性</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class UniqueBTree {

    /** 底层 B+Tree */
    private final BTree btree;

    /** 索引类型 */
    private final IndexType indexType;

    /**
     * 构造唯一索引
     *
     * @param btree     底层 B+Tree
     * @param indexType 索引类型
     */
    public UniqueBTree(BTree btree, IndexType indexType) {
        this.btree = btree;
        this.indexType = indexType;
    }

    /**
     * 创建唯一索引
     *
     * @param indexId    索引 ID
     * @param spaceId    空间 ID
     * @param bufferPool Buffer Pool
     * @param comparator 记录比较器
     * @param indexType  索引类型
     * @param mtr        Mini-Transaction
     * @return 唯一索引
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static UniqueBTree create(long indexId, int spaceId, BufferPool bufferPool,
                                     RecordComparator comparator, IndexType indexType,
                                     MiniTransaction mtr) throws MiniDbException {
        BTree btree = BTree.create(indexId, spaceId, bufferPool, comparator, mtr);
        return new UniqueBTree(btree, indexType);
    }

    /**
     * 包装现有 B+Tree 为唯一索引
     *
     * @param btree     B+Tree
     * @param indexType 索引类型
     * @return 唯一索引
     */
    public static UniqueBTree wrap(BTree btree, IndexType indexType) {
        return new UniqueBTree(btree, indexType);
    }

    // ==================== 插入操作 ====================

    /**
     * 插入记录（检查唯一性）
     *
     * @param recordData 记录数据
     * @param searchKey  搜索键
     * @param mtr        Mini-Transaction
     * @return 如果成功插入返回 true
     * @throws DuplicateKeyException 如果键已存在
     * @throws MiniDbException     如果 MTR 不在 ACTIVE 状态
     */
    public boolean insert(byte[] recordData, byte[] searchKey, MiniTransaction mtr)
            throws DuplicateKeyException, MiniDbException {
        // 唯一索引需要检查重复
        if (isUnique()) {
            checkDuplicate(searchKey, mtr);
        }

        return btree.insert(recordData, searchKey, mtr);
    }

    /**
     * 插入或更新记录
     *
     * <p>如果键已存在，先删除旧记录再插入新记录。</p>
     *
     * @param recordData 记录数据
     * @param searchKey  搜索键
     * @param recordSize 记录大小（用于删除）
     * @param mtr        Mini-Transaction
     * @return 如果是更新返回 true，如果是新插入返回 false
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public boolean insertOrUpdate(byte[] recordData, byte[] searchKey, int recordSize,
                                  MiniTransaction mtr) throws MiniDbException {
        boolean existed = btree.containsKey(searchKey, mtr);

        if (existed) {
            btree.delete(searchKey, recordSize, mtr);
        }

        btree.insert(recordData, searchKey, mtr);
        return existed;
    }

    /**
     * 尝试插入（不抛异常）
     *
     * @param recordData 记录数据
     * @param searchKey  搜索键
     * @param mtr        Mini-Transaction
     * @return 如果成功插入返回 true，如果键已存在返回 false
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public boolean tryInsert(byte[] recordData, byte[] searchKey, MiniTransaction mtr)
            throws MiniDbException {
        if (isUnique() && btree.containsKey(searchKey, mtr)) {
            return false;
        }
        return btree.insert(recordData, searchKey, mtr);
    }

    // ==================== 查询操作 ====================

    /**
     * 搜索
     *
     * @param searchKey 搜索键
     * @param mtr       Mini-Transaction
     * @return 搜索结果
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public BTreeSearchResult search(byte[] searchKey, MiniTransaction mtr)
            throws MiniDbException {
        return btree.search(searchKey, mtr);
    }

    /**
     * 检查键是否存在
     *
     * @param searchKey 搜索键
     * @param mtr       Mini-Transaction
     * @return 如果键存在返回 true
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public boolean containsKey(byte[] searchKey, MiniTransaction mtr)
            throws MiniDbException {
        return btree.containsKey(searchKey, mtr);
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
        return btree.delete(searchKey, recordSize, mtr);
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
    public BTreeRangeScanner rangeScan(MiniTransaction mtr, RangeBound lowerBound,
                                       RangeBound upperBound) {
        return btree.rangeScan(mtr, lowerBound, upperBound);
    }

    /**
     * 打开游标
     *
     * @param mtr Mini-Transaction
     * @return B+Tree 游标
     */
    public BTreeCursor openCursor(MiniTransaction mtr) {
        return btree.openCursor(mtr);
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查重复键
     *
     * @param searchKey 搜索键
     * @param mtr       Mini-Transaction
     * @throws DuplicateKeyException 如果键已存在
     * @throws MiniDbException     如果 MTR 不在 ACTIVE 状态
     */
    private void checkDuplicate(byte[] searchKey, MiniTransaction mtr)
            throws DuplicateKeyException, MiniDbException {
        if (btree.containsKey(searchKey, mtr)) {
            throw new DuplicateKeyException(searchKey, btree.getMetadata().getIndexId());
        }
    }

    /**
     * 是否为唯一索引
     *
     * @return 如果是唯一索引返回 true
     */
    public boolean isUnique() {
        return indexType == IndexType.PRIMARY || indexType == IndexType.UNIQUE;
    }

    /**
     * 是否为主键索引
     *
     * @return 如果是主键索引返回 true
     */
    public boolean isPrimary() {
        return indexType == IndexType.PRIMARY;
    }

    // ==================== Getter 方法 ====================

    public BTree getUnderlyingBTree() {
        return btree;
    }

    public IndexType getIndexType() {
        return indexType;
    }

    public int getTreeHeight() {
        return btree.getTreeHeight();
    }

    public long getRecordCount() {
        return btree.getRecordCount();
    }

    public BTreeMetadata getMetadata() {
        return btree.getMetadata();
    }

    public BufferPool getBufferPool() {
        return btree.getBufferPool();
    }

    public RecordComparator getComparator() {
        return btree.getComparator();
    }
}
