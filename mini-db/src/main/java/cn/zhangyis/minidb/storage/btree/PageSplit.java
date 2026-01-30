package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.IndexPageLayout;
import cn.zhangyis.minidb.storage.page.IndexPageOps;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 页分裂操作
 *
 * <p>实现 B+Tree 页面分裂，当页面空间不足时触发。</p>
 *
 * <h2>分裂策略</h2>
 * <ul>
 *   <li><b>平衡分裂</b>: 将记录大致平均分配到两个页面</li>
 *   <li><b>分裂点</b>: 选择中间记录作为分裂点</li>
 *   <li><b>分裂键</b>: 新页面的最小键，用于插入父节点</li>
 * </ul>
 *
 * <h2>分裂流程</h2>
 * <ol>
 *   <li>分配新页面</li>
 *   <li>初始化新页面</li>
 *   <li>找到分裂点（中间记录）</li>
 *   <li>将后半部分记录迁移到新页面</li>
 *   <li>更新原页面的记录链表</li>
 *   <li>返回分裂键供上层使用</li>
 * </ol>
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>I1: 分裂后原页面所有键 < 分裂键</li>
 *   <li>I2: 分裂后新页面所有键 >= 分裂键</li>
 *   <li>I3: 两个页面的记录链都完整</li>
 *   <li>I4: 分裂是原子操作</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class PageSplit {

    /**
     * 分裂页面
     *
     * <p>将页面分裂为两个页面，返回分裂结果。</p>
     *
     * @param frame       原页面 BufferFrame (必须持有 X-latch)
     * @param bufferPool  BufferPool（用于分配新页面）
     * @param comparator  记录比较器
     * @param mtr         Mini-Transaction
     * @return 分裂结果，包含分裂键和新页面信息
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static SplitResult split(BufferFrame frame, BufferPool bufferPool,
                                    RecordComparator comparator, MiniTransaction mtr)
            throws MiniDbException {
        ByteBuffer buf = frame.buffer();

        // 1. 收集所有用户记录信息
        List<RecordInfo> records = collectRecords(buf, comparator);
        if (records.size() < 2) {
            throw new IllegalStateException("Cannot split page with less than 2 records");
        }

        // 2. 找到分裂点（中间位置）
        int splitIndex = records.size() / 2;
        RecordInfo splitRecord = records.get(splitIndex);

        // 3. 分配并初始化新页面
        int level = IndexPageLayout.readLevel(buf);
        long indexId = IndexPageLayout.readIndexId(buf);

        Page newPage = mtr.newPage(frame.getPageId().getSpaceId());
        BufferFrame newFrame = bufferPool.getPage(newPage.getPageId(), BufferPool.FetchMode.READ_EXISTING);
        newFrame.writeLock();

        try {
            IndexPageOps.initPage(newFrame, indexId, level, mtr);

            // 4. 迁移后半部分记录到新页面
            int movedCount = migrateRecords(frame, newFrame, records, splitIndex, comparator, mtr);

            // 5. 更新原页面（截断链表）
            truncateOriginalPage(frame, records, splitIndex, mtr);

            // 6. 提取分裂键
            byte[] splitKey = splitRecord.key;

            return new SplitResult(splitKey, newPage.getPageId(),
                    IndexPageLayout.readFirstUserRecordOffset(newFrame.buffer()), movedCount);
        } finally {
            newFrame.writeUnlock();
        }
    }

    /**
     * 分裂页面并插入新记录
     *
     * <p>当插入导致页面空间不足时调用。分裂后将新记录插入到合适的页面。</p>
     *
     * @param frame       原页面 BufferFrame (必须持有 X-latch)
     * @param recordData  要插入的记录数据
     * @param searchKey   记录的键
     * @param bufferPool  BufferPool
     * @param comparator  记录比较器
     * @param mtr         Mini-Transaction
     * @return 分裂结果
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static SplitResult splitAndInsert(BufferFrame frame, byte[] recordData, byte[] searchKey,
                                             BufferPool bufferPool, RecordComparator comparator,
                                             MiniTransaction mtr) throws MiniDbException {
        // 1. 先执行分裂
        SplitResult result = split(frame, bufferPool, comparator, mtr);

        // 2. 决定新记录应该插入哪个页面
        // 如果 searchKey < splitKey，插入原页面；否则插入新页面
        int cmp = compareKeys(searchKey, result.getSplitKey());

        if (cmp < 0) {
            // 插入原页面
            int offset = PageInsert.insertRecord(frame, recordData, searchKey, comparator, mtr);
            if (offset < 0) {
                throw new IllegalStateException("Failed to insert into original page after split");
            }
        } else {
            // 插入新页面
            BufferFrame newFrame = bufferPool.getPage(result.getNewPageId(), BufferPool.FetchMode.READ_EXISTING);
            newFrame.writeLock();
            try {
                int offset = PageInsert.insertRecord(newFrame, recordData, searchKey, comparator, mtr);
                if (offset < 0) {
                    throw new IllegalStateException("Failed to insert into new page after split");
                }
            } finally {
                newFrame.writeUnlock();
            }
        }

        return result;
    }

    /**
     * 收集页面中所有用户记录的信息
     */
    private static List<RecordInfo> collectRecords(ByteBuffer buf, RecordComparator comparator) {
        List<RecordInfo> records = new ArrayList<>();

        int current = IndexPageLayout.readFirstUserRecordOffset(buf);
        while (current != IndexPageLayout.SUPREMUM_OFFSET && current != 0) {
            byte[] key = comparator.extractKey(buf, current);
            records.add(new RecordInfo(current, key));
            current = IndexPageLayout.readRecordNext(buf, current);
        }

        return records;
    }

    /**
     * 迁移记录到新页面
     *
     * @return 迁移的记录数
     */
    private static int migrateRecords(BufferFrame oldFrame, BufferFrame newFrame,
                                      List<RecordInfo> records, int splitIndex,
                                      RecordComparator comparator, MiniTransaction mtr)
            throws MiniDbException {
        ByteBuffer oldBuf = oldFrame.buffer();
        int movedCount = 0;

        // 从分裂点开始，将记录复制到新页面
        int prevOffset = IndexPageLayout.INFIMUM_OFFSET;

        for (int i = splitIndex; i < records.size(); i++) {
            RecordInfo recInfo = records.get(i);

            // 读取原记录数据
            byte[] recordData = readRecordData(oldBuf, recInfo.offset);

            // 在新页面插入
            int newOffset = IndexPageOps.insertRecord(newFrame, recordData, prevOffset, mtr);
            prevOffset = newOffset;
            movedCount++;
        }

        return movedCount;
    }

    /**
     * 截断原页面的记录链表
     */
    private static void truncateOriginalPage(BufferFrame frame, List<RecordInfo> records,
                                             int splitIndex, MiniTransaction mtr)
            throws MiniDbException {
        ByteBuffer buf = frame.buffer();

        if (splitIndex == 0) {
            // 所有记录都迁移了，原页面变空
            IndexPageOps.setRecordNext(frame, IndexPageLayout.INFIMUM_OFFSET,
                    IndexPageLayout.SUPREMUM_OFFSET, mtr);
            IndexPageOps.setRecordCount(frame, 0, mtr);
            return;
        }

        // 找到分裂点前一条记录
        RecordInfo lastKeepRecord = records.get(splitIndex - 1);

        // 将最后保留的记录指向 Supremum
        IndexPageOps.setRecordNext(frame, lastKeepRecord.offset, IndexPageLayout.SUPREMUM_OFFSET, mtr);

        // 更新记录计数
        IndexPageOps.setRecordCount(frame, splitIndex, mtr);

        // 更新 Supremum 的 n_owned（简化处理，设为保留的记录数 + 1）
        IndexPageOps.setRecordOwned(frame, IndexPageLayout.SUPREMUM_OFFSET, splitIndex + 1, mtr);
    }

    /**
     * 读取记录数据（包括记录头）
     *
     * <p>简化实现：假设固定大小记录。实际应根据记录格式计算大小。</p>
     */
    private static byte[] readRecordData(ByteBuffer buf, int offset) {
        // 简化实现：读取固定大小
        // 实际应该根据记录头中的信息计算记录大小
        int recordSize = estimateRecordSize(buf, offset);
        byte[] data = new byte[recordSize];

        for (int i = 0; i < recordSize; i++) {
            data[i] = buf.get(offset + i);
        }

        return data;
    }

    /**
     * 估算记录大小
     *
     * <p>简化实现：使用固定大小。实际应解析记录头。</p>
     */
    private static int estimateRecordSize(ByteBuffer buf, int offset) {
        // 简化：假设记录头 5 字节 + 键 4 字节 + 值 1 字节 = 10 字节
        // 实际实现应该从记录头解析
        return SimpleRecordBuilder.RECORD_HEADER_SIZE + SimpleRecordBuilder.KEY_SIZE + 1;
    }

    /**
     * 比较两个键
     */
    private static int compareKeys(byte[] key1, byte[] key2) {
        // 简化：假设是整数键
        int k1 = IntKeyComparator.bytesToInt(key1);
        int k2 = IntKeyComparator.bytesToInt(key2);
        return Integer.compare(k1, k2);
    }

    /**
     * 检查页面是否需要分裂
     *
     * @param pageBuffer 页面 ByteBuffer
     * @param recordSize 要插入的记录大小
     * @return 如果需要分裂返回 true
     */
    public static boolean needsSplit(ByteBuffer pageBuffer, int recordSize) {
        return !PageInsert.hasSpaceFor(pageBuffer, recordSize);
    }

    /**
     * 计算建议的分裂点
     *
     * <p>返回分裂后原页面应保留的记录数。</p>
     *
     * @param pageBuffer 页面 ByteBuffer
     * @return 建议保留的记录数
     */
    public static int suggestSplitPoint(ByteBuffer pageBuffer) {
        int totalRecords = IndexPageLayout.readRecordCount(pageBuffer);
        return totalRecords / 2;
    }

    /**
     * 记录信息（内部使用）
     */
    private static class RecordInfo {
        final int offset;
        final byte[] key;

        RecordInfo(int offset, byte[] key) {
            this.offset = offset;
            this.key = key;
        }
    }

    // 禁止实例化
    private PageSplit() {
        throw new UnsupportedOperationException("PageSplit is a utility class");
    }
}
