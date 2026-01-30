package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.IndexPageLayout;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * B+Tree 单页操作统一入口
 *
 * <p>整合页内搜索、插入、删除操作，提供统一的 API。</p>
 *
 * <h2>核心不变量</h2>
 * <ul>
 *   <li>I1: 记录链完整性 - Infimum → rec1 → rec2 → ... → Supremum</li>
 *   <li>I2: PAGE_N_RECS 准确性 - 等于链表中用户记录数</li>
 *   <li>I3: Page Directory 有序性 - slot[i] key <= slot[i+1] key</li>
 *   <li>I4: 堆空间一致性 - PAGE_HEAP_TOP 指向下一个可分配位置</li>
 *   <li>I5: 所有写操作必须通过 MTR</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * BufferFrame frame = bufferPool.getPage(pageId, FetchMode.READ_EXISTING);
 * frame.writeLock();
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     // 插入记录
 *     byte[] record = SimpleRecordBuilder.buildRecord(100, value, heapNo);
 *     byte[] key = IntKeyComparator.intToBytes(100);
 *     int offset = BTreePageOperations.insert(frame, record, key, comparator, mtr);
 *
 *     // 搜索记录
 *     int found = BTreePageOperations.search(frame.buffer(), key, comparator);
 *
 *     mtr.commit();
 * } finally {
 *     frame.writeUnlock();
 * }
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class BTreePageOperations {

    // ==================== 搜索操作 ====================

    /**
     * 在页内搜索指定键
     *
     * @param pageBuffer 页面 ByteBuffer
     * @param searchKey  搜索键
     * @param comparator 记录比较器
     * @return 搜索结果
     */
    public static PageSearchResult search(ByteBuffer pageBuffer, byte[] searchKey,
                                          RecordComparator comparator) {
        return PageSearch.search(pageBuffer, searchKey, comparator);
    }

    /**
     * 查找指定键的记录偏移
     *
     * @param pageBuffer 页面 ByteBuffer
     * @param searchKey  搜索键
     * @param comparator 记录比较器
     * @return 记录偏移，未找到返回 -1
     */
    public static int findRecord(ByteBuffer pageBuffer, byte[] searchKey,
                                 RecordComparator comparator) {
        return PageSearch.findRecord(pageBuffer, searchKey, comparator);
    }

    /**
     * 检查键是否存在
     *
     * @param pageBuffer 页面 ByteBuffer
     * @param searchKey  搜索键
     * @param comparator 记录比较器
     * @return 如果键存在返回 true
     */
    public static boolean containsKey(ByteBuffer pageBuffer, byte[] searchKey,
                                      RecordComparator comparator) {
        return PageSearch.containsKey(pageBuffer, searchKey, comparator);
    }

    // ==================== 插入操作 ====================

    /**
     * 在页内插入记录
     *
     * @param frame      BufferFrame (必须持有 X-latch)
     * @param recordData 完整的记录数据（含记录头）
     * @param searchKey  记录的键
     * @param comparator 记录比较器
     * @param mtr        Mini-Transaction
     * @return 新记录的偏移，如果空间不足返回 -1
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static int insert(BufferFrame frame, byte[] recordData, byte[] searchKey,
                             RecordComparator comparator, MiniTransaction mtr)
            throws MiniDbException {
        return PageInsert.insertRecord(frame, recordData, searchKey, comparator, mtr);
    }

    /**
     * 检查是否有足够空间插入记录
     *
     * @param pageBuffer 页面 ByteBuffer
     * @param recordSize 记录大小
     * @return 如果空间足够返回 true
     */
    public static boolean hasSpaceFor(ByteBuffer pageBuffer, int recordSize) {
        return PageInsert.hasSpaceFor(pageBuffer, recordSize);
    }

    /**
     * 获取页面可容纳的最大记录大小
     *
     * @param pageBuffer 页面 ByteBuffer
     * @return 最大记录大小
     */
    public static int maxRecordSize(ByteBuffer pageBuffer) {
        return PageInsert.maxRecordSize(pageBuffer);
    }

    // ==================== 删除操作 ====================

    /**
     * 删除指定键的记录
     *
     * @param frame      BufferFrame (必须持有 X-latch)
     * @param searchKey  要删除的键
     * @param recordSize 记录大小
     * @param comparator 记录比较器
     * @param mtr        Mini-Transaction
     * @return 如果成功删除返回 true，如果键不存在返回 false
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static boolean delete(BufferFrame frame, byte[] searchKey, int recordSize,
                                 RecordComparator comparator, MiniTransaction mtr)
            throws MiniDbException {
        return PageDelete.deleteRecord(frame, searchKey, recordSize, comparator, mtr);
    }

    /**
     * 标记记录为已删除（逻辑删除）
     *
     * @param frame     BufferFrame (必须持有 X-latch)
     * @param recOffset 记录偏移
     * @param mtr       Mini-Transaction
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static void markDeleted(BufferFrame frame, int recOffset, MiniTransaction mtr)
            throws MiniDbException {
        PageDelete.markDeleted(frame, recOffset, mtr);
    }

    /**
     * 检查记录是否被标记为已删除
     *
     * @param pageBuffer 页面 ByteBuffer
     * @param recOffset  记录偏移
     * @return 如果设置了删除标记返回 true
     */
    public static boolean isMarkedDeleted(ByteBuffer pageBuffer, int recOffset) {
        return PageDelete.isMarkedDeleted(pageBuffer, recOffset);
    }

    // ==================== 遍历操作 ====================

    /**
     * 获取第一条用户记录的偏移
     *
     * @param pageBuffer 页面 ByteBuffer
     * @return 第一条用户记录偏移，页面为空时返回 Supremum 偏移
     */
    public static int getFirstUserRecord(ByteBuffer pageBuffer) {
        return IndexPageLayout.readFirstUserRecordOffset(pageBuffer);
    }

    /**
     * 获取下一条记录的偏移
     *
     * @param pageBuffer 页面 ByteBuffer
     * @param recOffset  当前记录偏移
     * @return 下一条记录偏移，链表结束返回 0
     */
    public static int getNextRecord(ByteBuffer pageBuffer, int recOffset) {
        return IndexPageLayout.readRecordNext(pageBuffer, recOffset);
    }

    /**
     * 检查是否为 Supremum 记录
     *
     * @param recOffset 记录偏移
     * @return 如果是 Supremum 返回 true
     */
    public static boolean isSupremum(int recOffset) {
        return recOffset == IndexPageLayout.SUPREMUM_OFFSET;
    }

    /**
     * 检查是否为 Infimum 记录
     *
     * @param recOffset 记录偏移
     * @return 如果是 Infimum 返回 true
     */
    public static boolean isInfimum(int recOffset) {
        return recOffset == IndexPageLayout.INFIMUM_OFFSET;
    }

    /**
     * 遍历所有用户记录
     *
     * @param pageBuffer 页面 ByteBuffer
     * @return 用户记录偏移列表
     */
    public static List<Integer> getAllUserRecords(ByteBuffer pageBuffer) {
        List<Integer> records = new ArrayList<>();
        int current = getFirstUserRecord(pageBuffer);

        while (!isSupremum(current) && current != 0) {
            records.add(current);
            current = getNextRecord(pageBuffer, current);
        }

        return records;
    }

    /**
     * 获取用户记录数
     *
     * @param pageBuffer 页面 ByteBuffer
     * @return 用户记录数
     */
    public static int getRecordCount(ByteBuffer pageBuffer) {
        return IndexPageLayout.readRecordCount(pageBuffer);
    }

    // ==================== 页面信息 ====================

    /**
     * 获取页面剩余空间
     *
     * @param pageBuffer 页面 ByteBuffer
     * @return 剩余字节数
     */
    public static int getFreeSpace(ByteBuffer pageBuffer) {
        return IndexPageLayout.freeSpace(pageBuffer);
    }

    /**
     * 获取页面层级
     *
     * @param pageBuffer 页面 ByteBuffer
     * @return 层级（0=叶子节点）
     */
    public static int getLevel(ByteBuffer pageBuffer) {
        return IndexPageLayout.readLevel(pageBuffer);
    }

    /**
     * 检查是否为叶子节点
     *
     * @param pageBuffer 页面 ByteBuffer
     * @return 如果是叶子节点返回 true
     */
    public static boolean isLeaf(ByteBuffer pageBuffer) {
        return IndexPageLayout.isLeaf(pageBuffer);
    }

    // ==================== 分裂操作 ====================

    /**
     * 检查页面是否需要分裂
     *
     * @param pageBuffer 页面 ByteBuffer
     * @param recordSize 要插入的记录大小
     * @return 如果需要分裂返回 true
     */
    public static boolean needsSplit(ByteBuffer pageBuffer, int recordSize) {
        return PageSplit.needsSplit(pageBuffer, recordSize);
    }

    /**
     * 分裂页面
     *
     * @param frame      原页面 BufferFrame (必须持有 X-latch)
     * @param bufferPool BufferPool
     * @param comparator 记录比较器
     * @param mtr        Mini-Transaction
     * @return 分裂结果
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static SplitResult split(BufferFrame frame, BufferPool bufferPool,
                                    RecordComparator comparator, MiniTransaction mtr)
            throws MiniDbException {
        return PageSplit.split(frame, bufferPool, comparator, mtr);
    }

    /**
     * 分裂页面并插入新记录
     *
     * @param frame      原页面 BufferFrame (必须持有 X-latch)
     * @param recordData 要插入的记录数据
     * @param searchKey  记录的键
     * @param bufferPool BufferPool
     * @param comparator 记录比较器
     * @param mtr        Mini-Transaction
     * @return 分裂结果
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static SplitResult splitAndInsert(BufferFrame frame, byte[] recordData, byte[] searchKey,
                                             BufferPool bufferPool, RecordComparator comparator,
                                             MiniTransaction mtr) throws MiniDbException {
        return PageSplit.splitAndInsert(frame, recordData, searchKey, bufferPool, comparator, mtr);
    }

    /**
     * 插入记录，如果空间不足则自动分裂
     *
     * <p>这是推荐的插入方法，会自动处理页面分裂。</p>
     *
     * @param frame      BufferFrame (必须持有 X-latch)
     * @param recordData 完整的记录数据
     * @param searchKey  记录的键
     * @param bufferPool BufferPool
     * @param comparator 记录比较器
     * @param mtr        Mini-Transaction
     * @return 插入结果，包含记录偏移和可能的分裂信息
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static InsertResult insertWithSplit(BufferFrame frame, byte[] recordData, byte[] searchKey,
                                               BufferPool bufferPool, RecordComparator comparator,
                                               MiniTransaction mtr) throws MiniDbException {
        ByteBuffer buf = frame.buffer();

        // 尝试直接插入
        if (hasSpaceFor(buf, recordData.length)) {
            int offset = insert(frame, recordData, searchKey, comparator, mtr);
            return new InsertResult(offset, null);
        }

        // 空间不足，需要分裂
        SplitResult splitResult = splitAndInsert(frame, recordData, searchKey, bufferPool, comparator, mtr);

        // 确定新记录在哪个页面
        int splitKeyValue = IntKeyComparator.bytesToInt(splitResult.getSplitKey());
        int insertKeyValue = IntKeyComparator.bytesToInt(searchKey);

        int recordOffset;
        if (insertKeyValue < splitKeyValue) {
            // 在原页面
            recordOffset = PageSearch.findRecord(buf, searchKey, comparator);
        } else {
            // 在新页面
            BufferFrame newFrame = bufferPool.getPage(splitResult.getNewPageId(), BufferPool.FetchMode.READ_EXISTING);
            newFrame.readLock();
            try {
                recordOffset = PageSearch.findRecord(newFrame.buffer(), searchKey, comparator);
            } finally {
                newFrame.readUnlock();
            }
        }

        return new InsertResult(recordOffset, splitResult);
    }

    // ==================== 不变量验证 ====================

    /**
     * 验证页面不变量（用于测试和调试）
     *
     * @param pageBuffer 页面 ByteBuffer
     * @param comparator 记录比较器
     * @return 如果所有不变量都满足返回 true
     * @throws IllegalStateException 如果发现不变量违反
     */
    public static boolean verifyInvariants(ByteBuffer pageBuffer, RecordComparator comparator) {
        // I1: 记录链完整性
        verifyRecordChain(pageBuffer);

        // I2: PAGE_N_RECS 准确性
        verifyRecordCount(pageBuffer);

        // I3: 记录有序性
        verifyRecordOrder(pageBuffer, comparator);

        return true;
    }

    /**
     * 验证记录链完整性
     */
    private static void verifyRecordChain(ByteBuffer pageBuffer) {
        int current = IndexPageLayout.INFIMUM_OFFSET;
        int steps = 0;
        int maxSteps = 10000;

        while (current != IndexPageLayout.SUPREMUM_OFFSET && steps < maxSteps) {
            int next = IndexPageLayout.readRecordNext(pageBuffer, current);
            if (next == 0 && current != IndexPageLayout.SUPREMUM_OFFSET) {
                throw new IllegalStateException("Record chain broken at offset " + current);
            }
            current = next;
            steps++;
        }

        if (steps >= maxSteps) {
            throw new IllegalStateException("Record chain has cycle or too many records");
        }
    }

    /**
     * 验证 PAGE_N_RECS 准确性
     */
    private static void verifyRecordCount(ByteBuffer pageBuffer) {
        int expectedCount = IndexPageLayout.readRecordCount(pageBuffer);
        int actualCount = 0;

        int current = IndexPageLayout.readFirstUserRecordOffset(pageBuffer);
        while (current != IndexPageLayout.SUPREMUM_OFFSET && current != 0) {
            actualCount++;
            current = IndexPageLayout.readRecordNext(pageBuffer, current);
        }

        if (expectedCount != actualCount) {
            throw new IllegalStateException(
                    "PAGE_N_RECS mismatch: expected=" + expectedCount + ", actual=" + actualCount);
        }
    }

    /**
     * 验证记录有序性
     */
    private static void verifyRecordOrder(ByteBuffer pageBuffer, RecordComparator comparator) {
        int prev = IndexPageLayout.INFIMUM_OFFSET;
        int current = IndexPageLayout.readFirstUserRecordOffset(pageBuffer);

        while (current != IndexPageLayout.SUPREMUM_OFFSET && current != 0) {
            if (prev != IndexPageLayout.INFIMUM_OFFSET) {
                int cmp = comparator.compareRecords(pageBuffer, prev, pageBuffer, current);
                if (cmp >= 0) {
                    throw new IllegalStateException(
                            "Records out of order at offsets " + prev + " and " + current);
                }
            }
            prev = current;
            current = IndexPageLayout.readRecordNext(pageBuffer, current);
        }
    }

    // 禁止实例化
    private BTreePageOperations() {
        throw new UnsupportedOperationException("BTreePageOperations is a utility class");
    }
}
