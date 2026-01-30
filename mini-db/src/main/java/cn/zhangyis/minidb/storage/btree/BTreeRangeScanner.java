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
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * B+Tree 范围扫描器
 *
 * <p>提供范围查询功能，支持各种范围条件。</p>
 *
 * <h2>支持的范围类型</h2>
 * <ul>
 *   <li>全表扫描：无边界</li>
 *   <li>等值查询：key = value</li>
 *   <li>范围查询：key > value, key >= value, key < value, key <= value</li>
 *   <li>区间查询：value1 < key < value2</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class BTreeRangeScanner implements Iterable<BTreeRangeScanner.ScanEntry>, AutoCloseable {

    /** B+Tree 引用 */
    private final BTree btree;

    /** Buffer Pool */
    private final BufferPool bufferPool;

    /** 记录比较器 */
    private final RecordComparator comparator;

    /** Mini-Transaction */
    private final MiniTransaction mtr;

    /** 范围下界 */
    private final RangeBound lowerBound;

    /** 范围上界 */
    private final RangeBound upperBound;

    /** 是否已关闭 */
    private boolean closed;

    /**
     * 构造范围扫描器
     *
     * @param btree      B+Tree
     * @param bufferPool Buffer Pool
     * @param comparator 记录比较器
     * @param mtr        Mini-Transaction
     * @param lowerBound 下界
     * @param upperBound 上界
     */
    public BTreeRangeScanner(BTree btree, BufferPool bufferPool, RecordComparator comparator,
                             MiniTransaction mtr, RangeBound lowerBound, RangeBound upperBound) {
        this.btree = btree;
        this.bufferPool = bufferPool;
        this.comparator = comparator;
        this.mtr = mtr;
        this.lowerBound = lowerBound != null ? lowerBound : RangeBound.unbounded();
        this.upperBound = upperBound != null ? upperBound : RangeBound.unbounded();
        this.closed = false;
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建全表扫描器
     */
    public static BTreeRangeScanner fullScan(BTree btree, BufferPool bufferPool,
                                             RecordComparator comparator, MiniTransaction mtr) {
        return new BTreeRangeScanner(btree, bufferPool, comparator, mtr,
                RangeBound.unbounded(), RangeBound.unbounded());
    }

    /**
     * 创建等值查询扫描器
     */
    public static BTreeRangeScanner equalScan(BTree btree, BufferPool bufferPool,
                                              RecordComparator comparator, MiniTransaction mtr,
                                              byte[] key) {
        return new BTreeRangeScanner(btree, bufferPool, comparator, mtr,
                RangeBound.inclusive(key), RangeBound.inclusive(key));
    }

    /**
     * 创建 > 查询扫描器
     */
    public static BTreeRangeScanner greaterThan(BTree btree, BufferPool bufferPool,
                                                RecordComparator comparator, MiniTransaction mtr,
                                                byte[] key) {
        return new BTreeRangeScanner(btree, bufferPool, comparator, mtr,
                RangeBound.exclusive(key), RangeBound.unbounded());
    }

    /**
     * 创建 >= 查询扫描器
     */
    public static BTreeRangeScanner greaterOrEqual(BTree btree, BufferPool bufferPool,
                                                   RecordComparator comparator, MiniTransaction mtr,
                                                   byte[] key) {
        return new BTreeRangeScanner(btree, bufferPool, comparator, mtr,
                RangeBound.inclusive(key), RangeBound.unbounded());
    }

    /**
     * 创建 < 查询扫描器
     */
    public static BTreeRangeScanner lessThan(BTree btree, BufferPool bufferPool,
                                             RecordComparator comparator, MiniTransaction mtr,
                                             byte[] key) {
        return new BTreeRangeScanner(btree, bufferPool, comparator, mtr,
                RangeBound.unbounded(), RangeBound.exclusive(key));
    }

    /**
     * 创建 <= 查询扫描器
     */
    public static BTreeRangeScanner lessOrEqual(BTree btree, BufferPool bufferPool,
                                                RecordComparator comparator, MiniTransaction mtr,
                                                byte[] key) {
        return new BTreeRangeScanner(btree, bufferPool, comparator, mtr,
                RangeBound.unbounded(), RangeBound.inclusive(key));
    }

    /**
     * 创建 BETWEEN 查询扫描器（闭区间）
     */
    public static BTreeRangeScanner between(BTree btree, BufferPool bufferPool,
                                            RecordComparator comparator, MiniTransaction mtr,
                                            byte[] lowerKey, byte[] upperKey) {
        return new BTreeRangeScanner(btree, bufferPool, comparator, mtr,
                RangeBound.inclusive(lowerKey), RangeBound.inclusive(upperKey));
    }

    /**
     * 创建开区间查询扫描器
     */
    public static BTreeRangeScanner openRange(BTree btree, BufferPool bufferPool,
                                              RecordComparator comparator, MiniTransaction mtr,
                                              byte[] lowerKey, byte[] upperKey) {
        return new BTreeRangeScanner(btree, bufferPool, comparator, mtr,
                RangeBound.exclusive(lowerKey), RangeBound.exclusive(upperKey));
    }

    // ==================== 扫描操作 ====================

    /**
     * 执行扫描并返回所有匹配的键
     *
     * @return 键列表
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public List<byte[]> scanKeys() throws MiniDbException {
        checkNotClosed();
        List<byte[]> keys = new ArrayList<>();

        try (BTreeCursor cursor = createCursor()) {
            positionCursor(cursor);

            while (cursor.isValid()) {
                keys.add(cursor.getKey());
                cursor.next();
            }
        }

        return keys;
    }

    /**
     * 执行扫描并返回所有匹配的键（整数形式）
     *
     * @return 键列表
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public List<Integer> scanKeysAsInt() throws MiniDbException {
        checkNotClosed();
        List<Integer> keys = new ArrayList<>();

        try (BTreeCursor cursor = createCursor()) {
            positionCursor(cursor);

            while (cursor.isValid()) {
                Integer key = cursor.getKeyAsInt();
                if (key != null) {
                    keys.add(key);
                }
                cursor.next();
            }
        }

        return keys;
    }

    /**
     * 执行扫描并返回所有匹配的条目
     *
     * @param valueLength 值长度
     * @return 条目列表
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public List<ScanEntry> scanEntries(int valueLength) throws MiniDbException {
        checkNotClosed();
        List<ScanEntry> entries = new ArrayList<>();

        try (BTreeCursor cursor = createCursor()) {
            positionCursor(cursor);

            while (cursor.isValid()) {
                byte[] key = cursor.getKey();
                byte[] value = cursor.getValue(valueLength);
                entries.add(new ScanEntry(key, value));
                cursor.next();
            }
        }

        return entries;
    }

    /**
     * 统计匹配的记录数
     *
     * @return 记录数
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public long count() throws MiniDbException {
        checkNotClosed();
        long count = 0;

        try (BTreeCursor cursor = createCursor()) {
            positionCursor(cursor);

            while (cursor.isValid()) {
                count++;
                cursor.next();
            }
        }

        return count;
    }

    /**
     * 检查是否存在匹配的记录
     *
     * @return 如果存在匹配记录返回 true
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public boolean exists() throws MiniDbException {
        checkNotClosed();

        try (BTreeCursor cursor = createCursor()) {
            positionCursor(cursor);
            return cursor.isValid();
        }
    }

    /**
     * 获取第一条匹配的记录
     *
     * @param valueLength 值长度
     * @return 第一条记录，如果没有匹配返回 null
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public ScanEntry first(int valueLength) throws MiniDbException {
        checkNotClosed();

        try (BTreeCursor cursor = createCursor()) {
            positionCursor(cursor);

            if (cursor.isValid()) {
                byte[] key = cursor.getKey();
                byte[] value = cursor.getValue(valueLength);
                return new ScanEntry(key, value);
            }
        }

        return null;
    }

    // ==================== Iterable 实现 ====================

    @Override
    public Iterator<ScanEntry> iterator() {
        return new ScanIterator(1); // 默认值长度为 1
    }

    /**
     * 获取指定值长度的迭代器
     *
     * @param valueLength 值长度
     * @return 迭代器
     */
    public Iterator<ScanEntry> iterator(int valueLength) {
        return new ScanIterator(valueLength);
    }

    /**
     * 检查是否有下一条记录（便捷方法）
     *
     * <p>注意：此方法创建新的迭代器。对于多次调用，建议使用 iterator() 方法。</p>
     *
     * @return 如果有下一条记录返回 true
     */
    public boolean hasNext() {
        return iterator().hasNext();
    }

    /**
     * 获取下一条记录（便捷方法）
     *
     * <p>注意：此方法创建新的迭代器。对于多次调用，建议使用 iterator() 方法。</p>
     *
     * @return 下一条记录
     */
    public ScanEntry next() {
        return iterator().next();
    }

    // ==================== 资源管理 ====================

    @Override
    public void close() {
        closed = true;
    }

    // ==================== 私有方法 ====================

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("Scanner is closed");
        }
    }

    private BTreeCursor createCursor() {
        return new BTreeCursor(btree, bufferPool, comparator, mtr)
                .setRange(lowerBound, upperBound);
    }

    private void positionCursor(BTreeCursor cursor) throws MiniDbException {
        if (lowerBound.isUnbounded()) {
            cursor.seekFirst();
        } else if (lowerBound.isInclusive()) {
            cursor.seek(lowerBound.getKey());
        } else {
            cursor.seekGreater(lowerBound.getKey());
        }
    }

    // ==================== 内部类 ====================

    /**
     * 扫描条目
     */
    public static class ScanEntry {
        private final byte[] key;
        private final byte[] value;

        public ScanEntry(byte[] key, byte[] value) {
            this.key = key;
            this.value = value;
        }

        public byte[] getKey() {
            return key;
        }

        public int getKeyAsInt() {
            return IntKeyComparator.bytesToInt(key);
        }

        public byte[] getValue() {
            return value;
        }

        @Override
        public String toString() {
            return String.format("ScanEntry{key=%d}", getKeyAsInt());
        }
    }

    /**
     * 扫描迭代器
     */
    private class ScanIterator implements Iterator<ScanEntry> {
        private final BTreeCursor cursor;
        private final int valueLength;
        private boolean initialized;

        ScanIterator(int valueLength) {
            this.cursor = createCursor();
            this.valueLength = valueLength;
            this.initialized = false;
        }

        @Override
        public boolean hasNext() {
            try {
                if (!initialized) {
                    positionCursor(cursor);
                    initialized = true;
                }
                return cursor.isValid();
            } catch (MiniDbException e) {
                throw new RuntimeException("Failed to check hasNext", e);
            }
        }

        @Override
        public ScanEntry next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            try {
                byte[] key = cursor.getKey();
                byte[] value = cursor.getValue(valueLength);
                cursor.next();
                return new ScanEntry(key, value);
            } catch (MiniDbException e) {
                throw new RuntimeException("Failed to get next entry", e);
            }
        }
    }
}
