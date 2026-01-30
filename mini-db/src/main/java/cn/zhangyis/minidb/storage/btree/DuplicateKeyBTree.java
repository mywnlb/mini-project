package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.IndexPageLayout;
import cn.zhangyis.minidb.storage.page.PageId;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 支持重复键的 B+Tree
 *
 * <p>允许多个记录具有相同的键值。</p>
 *
 * <h2>重复键处理策略</h2>
 * <ul>
 *   <li>相同键的记录按插入顺序存储</li>
 *   <li>搜索返回第一个匹配的记录</li>
 *   <li>提供方法获取所有匹配的记录</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class DuplicateKeyBTree {

    /** 底层 B+Tree */
    private final BTree btree;

    /**
     * 构造支持重复键的 B+Tree
     *
     * @param btree 底层 B+Tree
     */
    public DuplicateKeyBTree(BTree btree) {
        this.btree = btree;
    }

    /**
     * 创建支持重复键的 B+Tree
     *
     * @param indexId    索引 ID
     * @param spaceId    空间 ID
     * @param bufferPool Buffer Pool
     * @param comparator 记录比较器
     * @param mtr        Mini-Transaction
     * @return 支持重复键的 B+Tree
     * @throws MtrStateException 如果 MTR 不在 ACTIVE 状态
     */
    public static DuplicateKeyBTree create(long indexId, int spaceId, BufferPool bufferPool,
                                           RecordComparator comparator, MiniTransaction mtr)
            throws MiniDbException {
        BTree btree = BTree.create(indexId, spaceId, bufferPool, comparator, mtr);
        return new DuplicateKeyBTree(btree);
    }

    /**
     * 包装现有 B+Tree
     *
     * @param btree B+Tree
     * @return 支持重复键的 B+Tree
     */
    public static DuplicateKeyBTree wrap(BTree btree) {
        return new DuplicateKeyBTree(btree);
    }

    // ==================== 插入操作 ====================

    /**
     * 插入记录（允许重复键）
     *
     * @param recordData 记录数据
     * @param searchKey  搜索键
     * @param mtr        Mini-Transaction
     * @return 如果成功插入返回 true
     * @throws MtrStateException 如果 MTR 不在 ACTIVE 状态
     */
    public boolean insert(byte[] recordData, byte[] searchKey, MiniTransaction mtr)
            throws MiniDbException {
        // 直接插入，不检查重复
        return btree.insert(recordData, searchKey, mtr);
    }

    // ==================== 查询操作 ====================

    /**
     * 搜索第一个匹配的记录
     *
     * @param searchKey 搜索键
     * @param mtr       Mini-Transaction
     * @return 搜索结果
     * @throws MtrStateException 如果 MTR 不在 ACTIVE 状态
     */
    public BTreeSearchResult searchFirst(byte[] searchKey, MiniTransaction mtr)
            throws MiniDbException {
        return btree.search(searchKey, mtr);
    }

    /**
     * 检查键是否存在
     *
     * @param searchKey 搜索键
     * @param mtr       Mini-Transaction
     * @return 如果键存在返回 true
     * @throws MtrStateException 如果 MTR 不在 ACTIVE 状态
     */
    public boolean containsKey(byte[] searchKey, MiniTransaction mtr)
            throws MiniDbException {
        return btree.containsKey(searchKey, mtr);
    }

    /**
     * 统计指定键的记录数
     *
     * @param searchKey 搜索键
     * @param mtr       Mini-Transaction
     * @return 匹配的记录数
     * @throws MiniDbException 如果发生错误
     */
    public int countKey(byte[] searchKey, MiniTransaction mtr) throws MiniDbException {
        try (BTreeRangeScanner scanner = btree.equalScan(mtr, searchKey)) {
            return (int) scanner.count();
        }
    }

    /**
     * 获取所有匹配指定键的记录值
     *
     * @param searchKey   搜索键
     * @param valueLength 值长度
     * @param mtr         Mini-Transaction
     * @return 记录值列表
     * @throws MiniDbException 如果发生错误
     */
    public List<byte[]> findAllValues(byte[] searchKey, int valueLength, MiniTransaction mtr)
            throws MiniDbException {
        List<byte[]> values = new ArrayList<>();

        try (BTreeRangeScanner scanner = btree.equalScan(mtr, searchKey)) {
            for (BTreeRangeScanner.ScanEntry entry : scanner.scanEntries(valueLength)) {
                values.add(entry.getValue());
            }
        }

        return values;
    }

    // ==================== 删除操作 ====================

    /**
     * 删除第一个匹配的记录
     *
     * @param searchKey  搜索键
     * @param recordSize 记录大小
     * @param mtr        Mini-Transaction
     * @return 如果成功删除返回 true
     * @throws MtrStateException 如果 MTR 不在 ACTIVE 状态
     */
    public boolean deleteFirst(byte[] searchKey, int recordSize, MiniTransaction mtr)
            throws MiniDbException {
        return btree.delete(searchKey, recordSize, mtr);
    }

    /**
     * 删除所有匹配的记录
     *
     * @param searchKey  搜索键
     * @param recordSize 记录大小
     * @param mtr        Mini-Transaction
     * @return 删除的记录数
     * @throws MtrStateException 如果 MTR 不在 ACTIVE 状态
     */
    public int deleteAll(byte[] searchKey, int recordSize, MiniTransaction mtr)
            throws MiniDbException {
        int deletedCount = 0;
        while (btree.delete(searchKey, recordSize, mtr)) {
            deletedCount++;
        }
        return deletedCount;
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
     * 等值扫描（获取所有匹配的记录）
     *
     * @param mtr       Mini-Transaction
     * @param searchKey 搜索键
     * @return 范围扫描器
     */
    public BTreeRangeScanner equalScan(MiniTransaction mtr, byte[] searchKey) {
        return btree.equalScan(mtr, searchKey);
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

    // ==================== Getter 方法 ====================

    public BTree getUnderlyingBTree() {
        return btree;
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

    // ==================== 内部类 ====================

    /**
     * 记录位置
     */
    public static class RecordLocation {
        private final PageId pageId;
        private final int recordOffset;

        public RecordLocation(PageId pageId, int recordOffset) {
            this.pageId = pageId;
            this.recordOffset = recordOffset;
        }

        public PageId getPageId() {
            return pageId;
        }

        public int getRecordOffset() {
            return recordOffset;
        }

        @Override
        public String toString() {
            return String.format("RecordLocation{page=%s, offset=%d}", pageId, recordOffset);
        }
    }
}
