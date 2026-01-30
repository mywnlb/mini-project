package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.IndexPageLayout;
import cn.zhangyis.minidb.storage.page.PageId;

import java.nio.ByteBuffer;
import java.util.NoSuchElementException;

/**
 * B+Tree 游标
 *
 * <p>提供在 B+Tree 叶子节点上的遍历能力。</p>
 *
 * <h2>功能</h2>
 * <ul>
 *   <li>定位到指定键</li>
 *   <li>正向/反向遍历</li>
 *   <li>范围扫描</li>
 *   <li>读取当前记录</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * BTreeCursor cursor = btree.openCursor(mtr);
 * cursor.seekFirst();
 * while (cursor.isValid()) {
 *     byte[] key = cursor.getKey();
 *     byte[] value = cursor.getValue();
 *     cursor.next();
 * }
 * cursor.close();
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class BTreeCursor implements AutoCloseable {

    /** B+Tree 引用 */
    private final BTree btree;

    /** Buffer Pool */
    private final BufferPool bufferPool;

    /** 记录比较器 */
    private final RecordComparator comparator;

    /** Mini-Transaction */
    private final MiniTransaction mtr;

    /** 当前位置 */
    private CursorPosition position;

    /** 范围下界 */
    private RangeBound lowerBound;

    /** 范围上界 */
    private RangeBound upperBound;

    /** 是否已关闭 */
    private boolean closed;

    /**
     * 构造游标
     *
     * @param btree      B+Tree
     * @param bufferPool Buffer Pool
     * @param comparator 记录比较器
     * @param mtr        Mini-Transaction
     */
    public BTreeCursor(BTree btree, BufferPool bufferPool,
                       RecordComparator comparator, MiniTransaction mtr) {
        this.btree = btree;
        this.bufferPool = bufferPool;
        this.comparator = comparator;
        this.mtr = mtr;
        this.position = CursorPosition.invalid();
        this.lowerBound = RangeBound.unbounded();
        this.upperBound = RangeBound.unbounded();
        this.closed = false;
    }

    // ==================== 定位操作 ====================

    /**
     * 定位到第一条记录
     *
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public void seekFirst() throws MiniDbException {
        checkNotClosed();

        // 找到最左边的叶子节点
        PageId leafPageId = findLeftmostLeaf();
        if (leafPageId == null) {
            position = CursorPosition.invalid();
            return;
        }

        // 定位到第一条用户记录
        BufferFrame frame = bufferPool.getPage(leafPageId, BufferPool.FetchMode.READ_EXISTING);
        frame.readLock();
        try {
            ByteBuffer buf = frame.buffer();
            int firstRecord = IndexPageLayout.readFirstUserRecordOffset(buf);

            if (firstRecord == IndexPageLayout.SUPREMUM_OFFSET) {
                // 空页面，尝试下一个叶子
                position = CursorPosition.invalid();
            } else {
                position = new CursorPosition(leafPageId, firstRecord);
            }
        } finally {
            frame.readUnlock();
        }

        // 检查是否在范围内
        if (position.isValid() && !isInRange()) {
            position = CursorPosition.invalid();
        }
    }

    /**
     * 定位到最后一条记录
     *
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public void seekLast() throws MiniDbException {
        checkNotClosed();

        // 找到最右边的叶子节点
        PageId leafPageId = findRightmostLeaf();
        if (leafPageId == null) {
            position = CursorPosition.invalid();
            return;
        }

        // 定位到最后一条用户记录
        BufferFrame frame = bufferPool.getPage(leafPageId, BufferPool.FetchMode.READ_EXISTING);
        frame.readLock();
        try {
            ByteBuffer buf = frame.buffer();
            int lastRecord = findLastUserRecord(buf);

            if (lastRecord == IndexPageLayout.INFIMUM_OFFSET) {
                position = CursorPosition.invalid();
            } else {
                position = new CursorPosition(leafPageId, lastRecord);
            }
        } finally {
            frame.readUnlock();
        }

        // 检查是否在范围内
        if (position.isValid() && !isInRange()) {
            position = CursorPosition.invalid();
        }
    }

    /**
     * 定位到指定键（或第一个 >= 该键的位置）
     *
     * @param key 目标键
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public void seek(byte[] key) throws MiniDbException {
        checkNotClosed();

        BTreeSearchResult result = btree.search(key, mtr);
        position = new CursorPosition(result.getPageId(), result.getRecordOffset());

        // 如果不是精确匹配，需要移动到下一条记录
        if (!result.isExactMatch()) {
            // 当前位置是插入点的前一条记录，需要移动到下一条
            BufferFrame frame = bufferPool.getPage(position.getPageId(), BufferPool.FetchMode.READ_EXISTING);
            frame.readLock();
            try {
                ByteBuffer buf = frame.buffer();
                int nextOffset = IndexPageLayout.readRecordNext(buf, position.getRecordOffset());

                if (nextOffset == IndexPageLayout.SUPREMUM_OFFSET) {
                    // 需要移动到下一个叶子页面
                    moveToNextLeaf();
                } else {
                    position = new CursorPosition(position.getPageId(), nextOffset);
                }
            } finally {
                frame.readUnlock();
            }
        }

        // 检查是否在范围内
        if (position.isValid() && !isInRange()) {
            position = CursorPosition.invalid();
        }
    }

    /**
     * 定位到第一个 > 指定键的位置
     *
     * @param key 目标键
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public void seekGreater(byte[] key) throws MiniDbException {
        seek(key);

        // 如果当前位置等于目标键，移动到下一条
        if (position.isValid()) {
            byte[] currentKey = getKey();
            if (currentKey != null && compareKeys(currentKey, key) == 0) {
                next();
            }
        }
    }

    // ==================== 遍历操作 ====================

    /**
     * 移动到下一条记录
     *
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public void next() throws MiniDbException {
        checkNotClosed();

        if (!position.isValid()) {
            return;
        }

        BufferFrame frame = bufferPool.getPage(position.getPageId(), BufferPool.FetchMode.READ_EXISTING);
        frame.readLock();
        try {
            ByteBuffer buf = frame.buffer();
            int nextOffset = IndexPageLayout.readRecordNext(buf, position.getRecordOffset());

            if (nextOffset == IndexPageLayout.SUPREMUM_OFFSET) {
                // 当前页面结束，移动到下一个叶子页面
                moveToNextLeaf();
            } else {
                position = new CursorPosition(position.getPageId(), nextOffset);
            }
        } finally {
            frame.readUnlock();
        }

        // 检查是否超出范围
        if (position.isValid() && !isInRange()) {
            position = CursorPosition.invalid();
        }
    }

    /**
     * 移动到上一条记录
     *
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public void prev() throws MiniDbException {
        checkNotClosed();

        if (!position.isValid()) {
            return;
        }

        BufferFrame frame = bufferPool.getPage(position.getPageId(), BufferPool.FetchMode.READ_EXISTING);
        frame.readLock();
        try {
            ByteBuffer buf = frame.buffer();
            int prevOffset = findPreviousRecord(buf, position.getRecordOffset());

            if (prevOffset == IndexPageLayout.INFIMUM_OFFSET) {
                // 当前页面开始，移动到上一个叶子页面
                moveToPrevLeaf();
            } else {
                position = new CursorPosition(position.getPageId(), prevOffset);
            }
        } finally {
            frame.readUnlock();
        }

        // 检查是否超出范围
        if (position.isValid() && !isInRange()) {
            position = CursorPosition.invalid();
        }
    }

    // ==================== 读取操作 ====================

    /**
     * 获取当前记录的键
     *
     * @return 键字节数组，如果游标无效返回 null
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public byte[] getKey() throws MiniDbException {
        checkNotClosed();

        if (!position.isValid()) {
            return null;
        }

        BufferFrame frame = bufferPool.getPage(position.getPageId(), BufferPool.FetchMode.READ_EXISTING);
        frame.readLock();
        try {
            ByteBuffer buf = frame.buffer();
            return comparator.extractKey(buf, position.getRecordOffset());
        } finally {
            frame.readUnlock();
        }
    }

    /**
     * 获取当前记录的键（整数形式）
     *
     * @return 键值，如果游标无效返回 null
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public Integer getKeyAsInt() throws MiniDbException {
        byte[] key = getKey();
        if (key == null) {
            return null;
        }
        return IntKeyComparator.bytesToInt(key);
    }

    /**
     * 获取当前记录的值
     *
     * @param valueLength 值长度
     * @return 值字节数组，如果游标无效返回 null
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public byte[] getValue(int valueLength) throws MiniDbException {
        checkNotClosed();

        if (!position.isValid()) {
            return null;
        }

        BufferFrame frame = bufferPool.getPage(position.getPageId(), BufferPool.FetchMode.READ_EXISTING);
        frame.readLock();
        try {
            ByteBuffer buf = frame.buffer();
            return SimpleRecordBuilder.readValue(buf, position.getRecordOffset(), valueLength);
        } finally {
            frame.readUnlock();
        }
    }

    // ==================== 状态查询 ====================

    /**
     * 游标是否有效
     *
     * @return 如果游标指向有效记录返回 true
     */
    public boolean isValid() {
        return !closed && position.isValid();
    }

    /**
     * 获取当前位置
     *
     * @return 当前位置
     */
    public CursorPosition getPosition() {
        return position;
    }

    // ==================== 范围设置 ====================

    /**
     * 设置范围下界
     *
     * @param bound 下界
     * @return this（支持链式调用）
     */
    public BTreeCursor setLowerBound(RangeBound bound) {
        this.lowerBound = bound != null ? bound : RangeBound.unbounded();
        return this;
    }

    /**
     * 设置范围上界
     *
     * @param bound 上界
     * @return this（支持链式调用）
     */
    public BTreeCursor setUpperBound(RangeBound bound) {
        this.upperBound = bound != null ? bound : RangeBound.unbounded();
        return this;
    }

    /**
     * 设置范围（便捷方法）
     *
     * @param lower 下界
     * @param upper 上界
     * @return this
     */
    public BTreeCursor setRange(RangeBound lower, RangeBound upper) {
        setLowerBound(lower);
        setUpperBound(upper);
        return this;
    }

    // ==================== 资源管理 ====================

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            position = CursorPosition.invalid();
        }
    }

    // ==================== 私有方法 ====================

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("Cursor is closed");
        }
    }

    /**
     * 找到最左边的叶子节点
     */
    private PageId findLeftmostLeaf() throws MiniDbException {
        PageId currentPageId = btree.getMetadata().getRootPageId();

        while (true) {
            BufferFrame frame = bufferPool.getPage(currentPageId, BufferPool.FetchMode.READ_EXISTING);
            frame.readLock();
            try {
                ByteBuffer buf = frame.buffer();
                int level = IndexPageLayout.readLevel(buf);

                if (level == 0) {
                    // 到达叶子节点
                    return currentPageId;
                }

                // 获取第一条记录指向的子页面
                int firstRecord = IndexPageLayout.readFirstUserRecordOffset(buf);
                if (firstRecord == IndexPageLayout.SUPREMUM_OFFSET) {
                    return null; // 空的非叶子节点（不应该发生）
                }

                int childPageNo = buf.getInt(firstRecord + SimpleRecordBuilder.RECORD_HEADER_SIZE + SimpleRecordBuilder.KEY_SIZE);
                currentPageId = new PageId(btree.getMetadata().getSpaceId(), childPageNo);
            } finally {
                frame.readUnlock();
            }
        }
    }

    /**
     * 找到最右边的叶子节点
     */
    private PageId findRightmostLeaf() throws MiniDbException {
        PageId currentPageId = btree.getMetadata().getRootPageId();

        while (true) {
            BufferFrame frame = bufferPool.getPage(currentPageId, BufferPool.FetchMode.READ_EXISTING);
            frame.readLock();
            try {
                ByteBuffer buf = frame.buffer();
                int level = IndexPageLayout.readLevel(buf);

                if (level == 0) {
                    return currentPageId;
                }

                // 获取最后一条记录指向的子页面
                int lastRecord = findLastUserRecord(buf);
                if (lastRecord == IndexPageLayout.INFIMUM_OFFSET) {
                    return null;
                }

                int childPageNo = buf.getInt(lastRecord + SimpleRecordBuilder.RECORD_HEADER_SIZE + SimpleRecordBuilder.KEY_SIZE);
                currentPageId = new PageId(btree.getMetadata().getSpaceId(), childPageNo);
            } finally {
                frame.readUnlock();
            }
        }
    }

    /**
     * 找到页面中最后一条用户记录
     */
    private int findLastUserRecord(ByteBuffer buf) {
        int current = IndexPageLayout.INFIMUM_OFFSET;
        int last = IndexPageLayout.INFIMUM_OFFSET;

        while (true) {
            int next = IndexPageLayout.readRecordNext(buf, current);
            if (next == IndexPageLayout.SUPREMUM_OFFSET || next == 0) {
                break;
            }
            last = next;
            current = next;
        }

        return last;
    }

    /**
     * 找到指定记录的前一条记录
     */
    private int findPreviousRecord(ByteBuffer buf, int targetOffset) {
        int current = IndexPageLayout.INFIMUM_OFFSET;

        while (true) {
            int next = IndexPageLayout.readRecordNext(buf, current);
            if (next == targetOffset) {
                return current;
            }
            if (next == IndexPageLayout.SUPREMUM_OFFSET || next == 0) {
                return IndexPageLayout.INFIMUM_OFFSET;
            }
            current = next;
        }
    }

    /**
     * 移动到下一个叶子页面
     */
    private void moveToNextLeaf() throws MiniDbException {
        BufferFrame frame = bufferPool.getPage(position.getPageId(), BufferPool.FetchMode.READ_EXISTING);
        frame.readLock();
        try {
            ByteBuffer buf = frame.buffer();
            int nextPageNo = IndexPageLayout.readNextPage(buf);

            if (nextPageNo == 0) {
                // 没有下一个页面
                position = CursorPosition.invalid();
                return;
            }

            PageId nextPageId = new PageId(position.getPageId().getSpaceId(), nextPageNo);

            // 获取下一个页面的第一条记录
            BufferFrame nextFrame = bufferPool.getPage(nextPageId, BufferPool.FetchMode.READ_EXISTING);
            nextFrame.readLock();
            try {
                ByteBuffer nextBuf = nextFrame.buffer();
                int firstRecord = IndexPageLayout.readFirstUserRecordOffset(nextBuf);

                if (firstRecord == IndexPageLayout.SUPREMUM_OFFSET) {
                    // 空页面，继续下一个
                    position = new CursorPosition(nextPageId, IndexPageLayout.INFIMUM_OFFSET);
                    moveToNextLeaf();
                } else {
                    position = new CursorPosition(nextPageId, firstRecord);
                }
            } finally {
                nextFrame.readUnlock();
            }
        } finally {
            frame.readUnlock();
        }
    }

    /**
     * 移动到上一个叶子页面
     */
    private void moveToPrevLeaf() throws MiniDbException {
        BufferFrame frame = bufferPool.getPage(position.getPageId(), BufferPool.FetchMode.READ_EXISTING);
        frame.readLock();
        try {
            ByteBuffer buf = frame.buffer();
            int prevPageNo = IndexPageLayout.readPrevPage(buf);

            if (prevPageNo == 0) {
                position = CursorPosition.invalid();
                return;
            }

            PageId prevPageId = new PageId(position.getPageId().getSpaceId(), prevPageNo);

            // 获取上一个页面的最后一条记录
            BufferFrame prevFrame = bufferPool.getPage(prevPageId, BufferPool.FetchMode.READ_EXISTING);
            prevFrame.readLock();
            try {
                ByteBuffer prevBuf = prevFrame.buffer();
                int lastRecord = findLastUserRecord(prevBuf);

                if (lastRecord == IndexPageLayout.INFIMUM_OFFSET) {
                    position = new CursorPosition(prevPageId, IndexPageLayout.INFIMUM_OFFSET);
                    moveToPrevLeaf();
                } else {
                    position = new CursorPosition(prevPageId, lastRecord);
                }
            } finally {
                prevFrame.readUnlock();
            }
        } finally {
            frame.readUnlock();
        }
    }

    /**
     * 检查当前位置是否在范围内
     */
    private boolean isInRange() throws MiniDbException {
        if (!position.isValid()) {
            return false;
        }

        byte[] currentKey = getKey();
        if (currentKey == null) {
            return false;
        }

        // 检查下界
        if (!lowerBound.isUnbounded()) {
            int cmp = compareKeys(currentKey, lowerBound.getKey());
            if (lowerBound.isInclusive()) {
                if (cmp < 0) return false;
            } else {
                if (cmp <= 0) return false;
            }
        }

        // 检查上界
        if (!upperBound.isUnbounded()) {
            int cmp = compareKeys(currentKey, upperBound.getKey());
            if (upperBound.isInclusive()) {
                if (cmp > 0) return false;
            } else {
                if (cmp >= 0) return false;
            }
        }

        return true;
    }

    /**
     * 比较两个键
     */
    private int compareKeys(byte[] key1, byte[] key2) {
        int k1 = IntKeyComparator.bytesToInt(key1);
        int k2 = IntKeyComparator.bytesToInt(key2);
        return Integer.compare(k1, k2);
    }
}
