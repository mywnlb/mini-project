package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;

/**
 * 复合键 B+Tree
 *
 * <p>支持复合键的 B+Tree 实现。</p>
 *
 * <h2>特性</h2>
 * <ul>
 *   <li>支持多列复合键</li>
 *   <li>支持前缀搜索</li>
 *   <li>支持不同列类型</li>
 *   <li>支持 NULL 值</li>
 *   <li>支持降序列</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class CompositeKeyBTree {

    /** 底层 B+Tree */
    private final BTree btree;

    /** 复合键定义 */
    private final CompositeKeyDef keyDef;

    /** 复合键比较器 */
    private final CompositeKeyComparator comparator;

    /** 索引类型 */
    private final IndexType indexType;

    /**
     * 构造复合键 B+Tree
     *
     * @param btree      底层 B+Tree
     * @param keyDef     复合键定义
     * @param comparator 复合键比较器
     * @param indexType  索引类型
     */
    public CompositeKeyBTree(BTree btree, CompositeKeyDef keyDef,
                             CompositeKeyComparator comparator, IndexType indexType) {
        this.btree = btree;
        this.keyDef = keyDef;
        this.comparator = comparator;
        this.indexType = indexType;
    }

    /**
     * 创建复合键 B+Tree
     *
     * @param indexId    索引 ID
     * @param spaceId    空间 ID
     * @param bufferPool Buffer Pool
     * @param keyDef     复合键定义
     * @param indexType  索引类型
     * @param mtr        Mini-Transaction
     * @return 复合键 B+Tree
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static CompositeKeyBTree create(long indexId, int spaceId, BufferPool bufferPool,
                                           CompositeKeyDef keyDef, IndexType indexType,
                                           MiniTransaction mtr) throws MiniDbException {
        CompositeKeyComparator comparator = new CompositeKeyComparator(keyDef);
        BTree btree = BTree.create(indexId, spaceId, bufferPool, comparator, mtr);
        return new CompositeKeyBTree(btree, keyDef, comparator, indexType);
    }

    // ==================== 插入操作 ====================

    /**
     * 插入记录
     *
     * @param recordData 记录数据
     * @param keyValue   复合键值
     * @param mtr        Mini-Transaction
     * @return 如果成功插入返回 true
     * @throws DuplicateKeyException 如果是唯一索引且键已存在
     * @throws MiniDbException     如果 MTR 不在 ACTIVE 状态
     */
    public boolean insert(byte[] recordData, CompositeKeyValue keyValue, MiniTransaction mtr)
            throws DuplicateKeyException, MiniDbException {
        byte[] encodedKey = keyValue.encode();

        // 唯一索引检查
        if (isUnique() && btree.containsKey(encodedKey, mtr)) {
            throw new DuplicateKeyException(encodedKey, btree.getMetadata().getIndexId());
        }

        return btree.insert(recordData, encodedKey, mtr);
    }

    /**
     * 使用整数键插入（单列整数键快捷方法）
     */
    public boolean insert(byte[] recordData, int key, MiniTransaction mtr)
            throws DuplicateKeyException, MiniDbException {
        return insert(recordData, CompositeKeyValue.of(keyDef, key), mtr);
    }

    /**
     * 使用双整数键插入
     */
    public boolean insert(byte[] recordData, int key1, int key2, MiniTransaction mtr)
            throws DuplicateKeyException, MiniDbException {
        return insert(recordData, CompositeKeyValue.of(keyDef, key1, key2), mtr);
    }

    /**
     * 使用三整数键插入
     */
    public boolean insert(byte[] recordData, int key1, int key2, int key3, MiniTransaction mtr)
            throws DuplicateKeyException, MiniDbException {
        return insert(recordData, CompositeKeyValue.of(keyDef, key1, key2, key3), mtr);
    }

    // ==================== 查询操作 ====================

    /**
     * 精确搜索
     *
     * @param keyValue 复合键值
     * @param mtr      Mini-Transaction
     * @return 搜索结果
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public BTreeSearchResult search(CompositeKeyValue keyValue, MiniTransaction mtr)
            throws MiniDbException {
        return btree.search(keyValue.encode(), mtr);
    }

    /**
     * 检查键是否存在
     *
     * @param keyValue 复合键值
     * @param mtr      Mini-Transaction
     * @return 如果键存在返回 true
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public boolean containsKey(CompositeKeyValue keyValue, MiniTransaction mtr)
            throws MiniDbException {
        return btree.containsKey(keyValue.encode(), mtr);
    }

    /**
     * 使用整数键搜索
     */
    public BTreeSearchResult search(int key, MiniTransaction mtr) throws MiniDbException {
        return search(CompositeKeyValue.of(keyDef, key), mtr);
    }

    /**
     * 使用双整数键搜索
     */
    public BTreeSearchResult search(int key1, int key2, MiniTransaction mtr)
            throws MiniDbException {
        return search(CompositeKeyValue.of(keyDef, key1, key2), mtr);
    }

    // ==================== 前缀搜索 ====================

    /**
     * 前缀扫描
     *
     * <p>扫描所有以指定前缀开头的记录。</p>
     *
     * @param prefixKey 前缀键值（只包含前几列）
     * @param mtr       Mini-Transaction
     * @return 范围扫描器
     */
    public BTreeRangeScanner prefixScan(CompositeKeyValue prefixKey, MiniTransaction mtr) {
        byte[] encodedPrefix = prefixKey.encode();

        // 前缀扫描：从前缀开始，到前缀的下一个值结束
        RangeBound lowerBound = RangeBound.inclusive(encodedPrefix);

        // 计算前缀的上界（前缀 + 1）
        byte[] upperPrefix = incrementPrefix(encodedPrefix);
        RangeBound upperBound = upperPrefix != null
                ? RangeBound.exclusive(upperPrefix)
                : RangeBound.unbounded();

        return btree.rangeScan(mtr, lowerBound, upperBound);
    }

    /**
     * 使用单列前缀扫描
     */
    public BTreeRangeScanner prefixScan(int prefixKey, MiniTransaction mtr) {
        return prefixScan(CompositeKeyValue.of(keyDef, prefixKey), mtr);
    }

    /**
     * 使用双列前缀扫描
     */
    public BTreeRangeScanner prefixScan(int key1, int key2, MiniTransaction mtr) {
        return prefixScan(CompositeKeyValue.of(keyDef, key1, key2), mtr);
    }

    /**
     * 计算前缀的下一个值
     */
    private byte[] incrementPrefix(byte[] prefix) {
        byte[] result = prefix.clone();

        // 从最后一个字节开始加 1
        for (int i = result.length - 1; i >= 0; i--) {
            int val = (result[i] & 0xFF) + 1;
            if (val <= 255) {
                result[i] = (byte) val;
                return result;
            }
            result[i] = 0;
        }

        // 溢出，返回 null 表示无上界
        return null;
    }

    // ==================== 删除操作 ====================

    /**
     * 删除记录
     *
     * @param keyValue   复合键值
     * @param recordSize 记录大小
     * @param mtr        Mini-Transaction
     * @return 如果成功删除返回 true
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public boolean delete(CompositeKeyValue keyValue, int recordSize, MiniTransaction mtr)
            throws MiniDbException {
        return btree.delete(keyValue.encode(), recordSize, mtr);
    }

    /**
     * 使用整数键删除
     */
    public boolean delete(int key, int recordSize, MiniTransaction mtr)
            throws MiniDbException {
        return delete(CompositeKeyValue.of(keyDef, key), recordSize, mtr);
    }

    /**
     * 使用双整数键删除
     */
    public boolean delete(int key1, int key2, int recordSize, MiniTransaction mtr)
            throws MiniDbException {
        return delete(CompositeKeyValue.of(keyDef, key1, key2), recordSize, mtr);
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
     * @param lowerKey   下界键值
     * @param upperKey   上界键值
     * @param lowerInclusive 下界是否包含
     * @param upperInclusive 上界是否包含
     * @return 范围扫描器
     */
    public BTreeRangeScanner rangeScan(MiniTransaction mtr,
                                       CompositeKeyValue lowerKey, CompositeKeyValue upperKey,
                                       boolean lowerInclusive, boolean upperInclusive) {
        RangeBound lower = lowerKey != null
                ? (lowerInclusive ? RangeBound.inclusive(lowerKey.encode())
                : RangeBound.exclusive(lowerKey.encode()))
                : RangeBound.unbounded();

        RangeBound upper = upperKey != null
                ? (upperInclusive ? RangeBound.inclusive(upperKey.encode())
                : RangeBound.exclusive(upperKey.encode()))
                : RangeBound.unbounded();

        return btree.rangeScan(mtr, lower, upper);
    }

    // ==================== 辅助方法 ====================

    /**
     * 是否为唯一索引
     */
    public boolean isUnique() {
        return indexType == IndexType.PRIMARY || indexType == IndexType.UNIQUE;
    }

    /**
     * 解码键值
     */
    public CompositeKeyValue decodeKey(byte[] encodedKey) {
        return CompositeKeyValue.decode(keyDef, encodedKey);
    }

    // ==================== Getter 方法 ====================

    public BTree getUnderlyingBTree() {
        return btree;
    }

    public CompositeKeyDef getKeyDef() {
        return keyDef;
    }

    public CompositeKeyComparator getComparator() {
        return comparator;
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
}
