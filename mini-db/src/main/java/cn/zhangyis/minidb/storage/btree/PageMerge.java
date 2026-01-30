package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.IndexPageLayout;
import cn.zhangyis.minidb.storage.page.IndexPageOps;
import cn.zhangyis.minidb.storage.page.PageId;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 页面合并操作
 *
 * <p>实现 B+Tree 页面合并和重分布，当页面记录数过少时触发。</p>
 *
 * <h2>合并策略</h2>
 * <ul>
 *   <li>当页面记录数 < 最小阈值时，尝试合并或重分布</li>
 *   <li>优先尝试与兄弟节点合并</li>
 *   <li>如果合并后超过页面容量，则进行重分布</li>
 * </ul>
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>I1: 合并后记录链完整</li>
 *   <li>I2: 合并后需要更新父节点</li>
 *   <li>I3: 重分布后两个页面记录数大致平衡</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class PageMerge {

    /** 最小记录数阈值（低于此值触发合并） */
    public static final int MIN_RECORDS_THRESHOLD = 2;

    /** 合并后最大记录数（超过此值进行重分布） */
    public static final int MAX_RECORDS_AFTER_MERGE = 100;

    /**
     * 检查页面是否需要合并
     *
     * @param pageBuffer 页面 ByteBuffer
     * @return 如果需要合并返回 true
     */
    public static boolean needsMerge(ByteBuffer pageBuffer) {
        int recordCount = IndexPageLayout.readRecordCount(pageBuffer);
        return recordCount < MIN_RECORDS_THRESHOLD;
    }

    /**
     * 合并两个页面
     *
     * <p>将 sourceFrame 的所有记录合并到 targetFrame。</p>
     *
     * @param targetFrame 目标页面（保留）
     * @param sourceFrame 源页面（将被清空）
     * @param comparator  记录比较器
     * @param mtr         Mini-Transaction
     * @return 合并的记录数
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static int mergePages(BufferFrame targetFrame, BufferFrame sourceFrame,
                                 RecordComparator comparator, MiniTransaction mtr)
            throws MiniDbException {
        ByteBuffer sourceBuf = sourceFrame.buffer();
        ByteBuffer targetBuf = targetFrame.buffer();

        // 收集源页面的所有记录
        List<RecordData> sourceRecords = collectAllRecords(sourceBuf, comparator);

        if (sourceRecords.isEmpty()) {
            return 0;
        }

        // 将记录插入目标页面
        int movedCount = 0;
        for (RecordData record : sourceRecords) {
            int offset = PageInsert.insertRecord(targetFrame, record.data, record.key, comparator, mtr);
            if (offset > 0) {
                movedCount++;
            }
        }

        // 清空源页面
        clearPage(sourceFrame, mtr);

        return movedCount;
    }

    /**
     * 重分布两个页面的记录
     *
     * <p>将记录在两个页面之间重新分配，使两个页面记录数大致相等。</p>
     *
     * @param leftFrame   左页面
     * @param rightFrame  右页面
     * @param comparator  记录比较器
     * @param mtr         Mini-Transaction
     * @return 新的分隔键
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static byte[] redistributePages(BufferFrame leftFrame, BufferFrame rightFrame,
                                           RecordComparator comparator, MiniTransaction mtr)
            throws MiniDbException {
        ByteBuffer leftBuf = leftFrame.buffer();
        ByteBuffer rightBuf = rightFrame.buffer();

        // 收集两个页面的所有记录
        List<RecordData> leftRecords = collectAllRecords(leftBuf, comparator);
        List<RecordData> rightRecords = collectAllRecords(rightBuf, comparator);

        // 合并并排序
        List<RecordData> allRecords = new ArrayList<>();
        allRecords.addAll(leftRecords);
        allRecords.addAll(rightRecords);
        allRecords.sort((a, b) -> compareKeys(a.key, b.key));

        // 计算分割点
        int splitPoint = allRecords.size() / 2;

        // 清空两个页面
        clearPage(leftFrame, mtr);
        clearPage(rightFrame, mtr);

        // 重新分配记录
        for (int i = 0; i < splitPoint; i++) {
            RecordData record = allRecords.get(i);
            PageInsert.insertRecord(leftFrame, record.data, record.key, comparator, mtr);
        }

        for (int i = splitPoint; i < allRecords.size(); i++) {
            RecordData record = allRecords.get(i);
            PageInsert.insertRecord(rightFrame, record.data, record.key, comparator, mtr);
        }

        // 返回新的分隔键（右页面的第一个键）
        return allRecords.get(splitPoint).key;
    }

    /**
     * 从左兄弟借一条记录
     *
     * @param frame        当前页面
     * @param leftSibling  左兄弟页面
     * @param separatorKey 当前分隔键
     * @param comparator   记录比较器
     * @param mtr          Mini-Transaction
     * @return 新的分隔键
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static byte[] borrowFromLeft(BufferFrame frame, BufferFrame leftSibling,
                                        byte[] separatorKey, RecordComparator comparator,
                                        MiniTransaction mtr) throws MiniDbException {
        ByteBuffer leftBuf = leftSibling.buffer();

        // 找到左兄弟的最后一条记录
        int lastOffset = findLastUserRecord(leftBuf);
        if (lastOffset == IndexPageLayout.INFIMUM_OFFSET) {
            return separatorKey; // 左兄弟为空，无法借
        }

        // 读取记录数据
        byte[] recordData = readRecordData(leftBuf, lastOffset);
        byte[] recordKey = comparator.extractKey(leftBuf, lastOffset);

        // 从左兄弟删除
        int recordSize = recordData.length;
        PageDelete.deleteRecord(leftSibling, recordKey, recordSize, comparator, mtr);

        // 插入到当前页面
        PageInsert.insertRecord(frame, recordData, recordKey, comparator, mtr);

        // 新的分隔键是借来的记录的键
        return recordKey;
    }

    /**
     * 从右兄弟借一条记录
     *
     * @param frame         当前页面
     * @param rightSibling  右兄弟页面
     * @param separatorKey  当前分隔键
     * @param comparator    记录比较器
     * @param mtr           Mini-Transaction
     * @return 新的分隔键
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static byte[] borrowFromRight(BufferFrame frame, BufferFrame rightSibling,
                                         byte[] separatorKey, RecordComparator comparator,
                                         MiniTransaction mtr) throws MiniDbException {
        ByteBuffer rightBuf = rightSibling.buffer();

        // 找到右兄弟的第一条记录
        int firstOffset = IndexPageLayout.readFirstUserRecordOffset(rightBuf);
        if (firstOffset == IndexPageLayout.SUPREMUM_OFFSET) {
            return separatorKey; // 右兄弟为空，无法借
        }

        // 读取记录数据
        byte[] recordData = readRecordData(rightBuf, firstOffset);
        byte[] recordKey = comparator.extractKey(rightBuf, firstOffset);

        // 从右兄弟删除
        int recordSize = recordData.length;
        PageDelete.deleteRecord(rightSibling, recordKey, recordSize, comparator, mtr);

        // 插入到当前页面
        PageInsert.insertRecord(frame, recordData, recordKey, comparator, mtr);

        // 新的分隔键是右兄弟新的第一条记录的键
        int newFirstOffset = IndexPageLayout.readFirstUserRecordOffset(rightSibling.buffer());
        if (newFirstOffset != IndexPageLayout.SUPREMUM_OFFSET) {
            return comparator.extractKey(rightSibling.buffer(), newFirstOffset);
        }

        return separatorKey;
    }

    /**
     * 清空页面（保留 Infimum 和 Supremum）
     */
    private static void clearPage(BufferFrame frame, MiniTransaction mtr) throws MiniDbException {
        ByteBuffer buf = frame.buffer();

        // 重置 Infimum 指向 Supremum
        IndexPageOps.setRecordNext(frame, IndexPageLayout.INFIMUM_OFFSET,
                IndexPageLayout.SUPREMUM_OFFSET, mtr);

        // 重置记录计数
        IndexPageOps.setRecordCount(frame, 0, mtr);

        // 重置堆顶指针
        int heapTop = IndexPageLayout.SUPREMUM_OFFSET + IndexPageLayout.SUPREMUM_SIZE;
        IndexPageOps.setHeapTop(frame, heapTop, mtr);
    }

    /**
     * 收集页面中所有记录的数据
     */
    private static List<RecordData> collectAllRecords(ByteBuffer buf, RecordComparator comparator) {
        List<RecordData> records = new ArrayList<>();

        int current = IndexPageLayout.readFirstUserRecordOffset(buf);
        while (current != IndexPageLayout.SUPREMUM_OFFSET && current != 0) {
            byte[] key = comparator.extractKey(buf, current);
            byte[] data = readRecordData(buf, current);
            records.add(new RecordData(key, data));
            current = IndexPageLayout.readRecordNext(buf, current);
        }

        return records;
    }

    /**
     * 读取记录数据
     */
    private static byte[] readRecordData(ByteBuffer buf, int offset) {
        // 简化实现：假设固定大小记录
        int recordSize = SimpleRecordBuilder.RECORD_HEADER_SIZE + SimpleRecordBuilder.KEY_SIZE + 1;
        byte[] data = new byte[recordSize];
        for (int i = 0; i < recordSize; i++) {
            data[i] = buf.get(offset + i);
        }
        return data;
    }

    /**
     * 找到页面中最后一条用户记录
     */
    private static int findLastUserRecord(ByteBuffer buf) {
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
     * 比较两个键
     */
    private static int compareKeys(byte[] key1, byte[] key2) {
        int k1 = IntKeyComparator.bytesToInt(key1);
        int k2 = IntKeyComparator.bytesToInt(key2);
        return Integer.compare(k1, k2);
    }

    /**
     * 记录数据（内部使用）
     */
    private static class RecordData {
        final byte[] key;
        final byte[] data;

        RecordData(byte[] key, byte[] data) {
            this.key = key;
            this.data = data;
        }
    }

    // 禁止实例化
    private PageMerge() {
        throw new UnsupportedOperationException("PageMerge is a utility class");
    }
}
