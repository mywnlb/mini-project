package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.constants.StorageConstants;
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
 * <p>实现 B+Tree 页面分裂，当页面空间不足时触发。
 * 支持 SimpleRecordBuilder 固定格式和 Compact 行格式（通过 {@link RecordComparator#readFullRecord} 委托）。</p>
 *
 * <h2>分裂策略</h2>
 * <ul>
 *   <li><b>平衡分裂</b>: 将记录大致平均分配到两个页面</li>
 *   <li><b>分裂点</b>: 选择中间记录作为分裂点</li>
 *   <li><b>分裂键</b>: 新页面的最小键，用于插入父节点</li>
 * </ul>
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>I1: 分裂后原页面所有键 &lt; 分裂键</li>
 *   <li>I2: 分裂后新页面所有键 &gt;= 分裂键</li>
 *   <li>I3: 两个页面的记录链都完整（Infimum → ... → Supremum）</li>
 *   <li>I4: 分裂是原子操作（单 MTR 内完成）</li>
 *   <li>INV-S4: 叶子页双向链表（FIL_PAGE_PREV/NEXT）保持正确</li>
 * </ul>
 *
 * @author MiniDB
 * @version 2.0
 */
public final class PageSplit {

    /**
     * 分裂页面
     *
     * @param frame       原页面 BufferFrame (必须持有 X-latch)
     * @param bufferPool  BufferPool（用于分配新页面）
     * @param comparator  记录比较器（通过 readFullRecord 支持不同记录格式）
     * @param mtr         Mini-Transaction
     * @return 分裂结果，包含分裂键和新页面信息
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static SplitResult split(BufferFrame frame, BufferPool bufferPool,
                                    RecordComparator comparator, MiniTransaction mtr)
            throws MiniDbException {

        ByteBuffer buf = frame.buffer();

        // 1. 收集所有用户记录信息（含完整记录字节）
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
            int movedCount = migrateRecords(newFrame, records, splitIndex, mtr);

            // 5. 更新原页面（截断链表）
            truncateOriginalPage(frame, records, splitIndex, mtr);

            // 6. 维护叶子页双向链表（INV-S4）
            updatePageLinks(frame, newFrame, newPage.getPageId(), bufferPool, mtr);

            // 7. 提取分裂键
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
        return splitAndInsert(frame, recordData, 0, searchKey, bufferPool, comparator, mtr);
    }

    public static SplitResult splitAndInsert(BufferFrame frame, byte[] recordData, int recordHeaderOffset,
                                             byte[] searchKey, BufferPool bufferPool,
                                             RecordComparator comparator, MiniTransaction mtr)
            throws MiniDbException {
        // 1. 先执行分裂
        SplitResult result = split(frame, bufferPool, comparator, mtr);

        // 2. 决定新记录应该插入哪个页面（INV-S3: 使用 comparator 而非硬编码 IntKey）
        int cmp = comparator.compareExtractedKeys(searchKey, result.getSplitKey());

        if (cmp < 0) {
            // 插入原页面
            int offset = PageInsert.insertRecord(frame, recordData, recordHeaderOffset, searchKey, comparator, mtr);
            if (offset < 0) {
                throw new IllegalStateException("Failed to insert into original page after split");
            }
        } else {
            // 插入新页面
            BufferFrame newFrame = bufferPool.getPage(result.getNewPageId(), BufferPool.FetchMode.READ_EXISTING);
            newFrame.writeLock();
            try {
                int offset = PageInsert.insertRecord(newFrame, recordData, recordHeaderOffset, searchKey, comparator, mtr);
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
     * 收集页面中所有用户记录的信息。
     *
     * <p>通过 {@link RecordComparator#readFullRecord} 读取完整记录字节，
     * 支持 Compact 行格式（含 extraBytes）和简单定长格式。</p>
     */
    private static List<RecordInfo> collectRecords(ByteBuffer buf, RecordComparator comparator) {
        List<RecordInfo> records = new ArrayList<>();

        int current = IndexPageLayout.readFirstUserRecordOffset(buf);
        while (current != IndexPageLayout.SUPREMUM_OFFSET && current != 0) {
            byte[] key = comparator.extractKey(buf, current);
            RecordBytes fullRecord = comparator.readFullRecord(buf, current);
            records.add(new RecordInfo(current, key, fullRecord));
            current = IndexPageLayout.readRecordNext(buf, current);
        }

        return records;
    }

    /**
     * 迁移记录到新页面。
     *
     * <p>使用 {@link RecordBytes} 中的完整记录数据和 recordHeaderOffset
     * 调用 {@link IndexPageOps#insertRecord}，确保 Compact 记录的 extraBytes 正确迁移。</p>
     *
     * @return 迁移的记录数
     */
    private static int migrateRecords(BufferFrame newFrame, List<RecordInfo> records,
                                      int splitIndex, MiniTransaction mtr)
            throws MiniDbException {
        int movedCount = 0;
        int prevOffset = IndexPageLayout.INFIMUM_OFFSET;

        for (int i = splitIndex; i < records.size(); i++) {
            RecordInfo recInfo = records.get(i);
            RecordBytes rb = recInfo.fullRecord;

            // INV-S1 & INV-S2: 使用完整记录字节和正确的 recordHeaderOffset
            int newOffset = IndexPageOps.insertRecord(newFrame, rb.data(), rb.recordHeaderOffset(), prevOffset, mtr);
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
     * 维护叶子页双向链表（INV-S4）。
     *
     * <p>分裂后新页面插入到原页面的右侧：</p>
     * <pre>
     * 分裂前: ... ↔ [oldPage] ↔ [oldNextPage] ↔ ...
     * 分裂后: ... ↔ [oldPage] ↔ [newPage] ↔ [oldNextPage] ↔ ...
     * </pre>
     */
    private static void updatePageLinks(BufferFrame oldFrame, BufferFrame newFrame,
                                        PageId newPageId, BufferPool bufferPool,
                                        MiniTransaction mtr) throws MiniDbException {
        ByteBuffer oldBuf = oldFrame.buffer();
        int oldNextPageNo = IndexPageLayout.readNextPage(oldBuf);
        int oldPageNo = oldFrame.getPageId().getPageNo();
        int newPageNo = newPageId.getPageNo();

        // newPage.prev = oldPage
        IndexPageOps.setPrevPage(newFrame, oldPageNo, mtr);
        // newPage.next = oldPage 的原 next
        IndexPageOps.setNextPage(newFrame, oldNextPageNo, mtr);
        // oldPage.next = newPage
        IndexPageOps.setNextPage(oldFrame, newPageNo, mtr);

        // 如果原页面有 next 页面，更新该页面的 prev 指针
        if (oldNextPageNo != StorageConstants.FIL_NULL && oldNextPageNo != 0) {
            PageId nextPageId = new PageId(oldFrame.getPageId().getSpaceId(), oldNextPageNo);
            BufferFrame nextFrame = bufferPool.getPage(nextPageId, BufferPool.FetchMode.READ_EXISTING);
            nextFrame.writeLock();
            try {
                IndexPageOps.setPrevPage(nextFrame, newPageNo, mtr);
            } finally {
                nextFrame.writeUnlock();
            }
        }
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
        final RecordBytes fullRecord;

        RecordInfo(int offset, byte[] key, RecordBytes fullRecord) {
            this.offset = offset;
            this.key = key;
            this.fullRecord = fullRecord;
        }
    }

    // 禁止实例化
    private PageSplit() {
        throw new UnsupportedOperationException("PageSplit is a utility class");
    }
}
