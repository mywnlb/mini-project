package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.IndexPageLayout;
import cn.zhangyis.minidb.storage.page.PageId;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * B+Tree 统计信息收集器
 *
 * <p>遍历 B+Tree 收集各种统计信息。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class BTreeStatsCollector {

    /** 页面大小 */
    private static final int PAGE_SIZE = 16 * 1024;

    /**
     * 收集完整的统计信息
     *
     * <p>遍历整个 B+Tree 收集统计信息。对于大树可能较慢。</p>
     *
     * @param btree B+Tree
     * @param mtr   Mini-Transaction
     * @return 统计信息
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static BTreeStats collect(BTree btree, MiniTransaction mtr) throws MiniDbException {
        long startTime = System.currentTimeMillis();

        BTreeStats stats = new BTreeStats(btree.getMetadata().getIndexId());
        stats.setTreeHeight(btree.getTreeHeight());
        stats.setRecordCount(btree.getRecordCount());

        BufferPool bufferPool = btree.getBufferPool();
        RecordComparator comparator = btree.getComparator();
        BTreeMetadata metadata = btree.getMetadata();

        // 使用 BFS 遍历所有页面
        int leafPageCount = 0;
        int internalPageCount = 0;
        long totalFreeSpace = 0;
        long totalUsedSpace = 0;

        double minFillFactor = 1.0;
        double maxFillFactor = 0.0;
        double sumFillFactor = 0.0;

        int minRecordsPerPage = Integer.MAX_VALUE;
        int maxRecordsPerPage = 0;
        long sumRecordsPerPage = 0;

        byte[] minKey = null;
        byte[] maxKey = null;

        Queue<PageId> queue = new ArrayDeque<>();
        queue.add(metadata.getRootPageId());

        while (!queue.isEmpty()) {
            PageId pageId = queue.poll();
            BufferFrame frame = bufferPool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
            frame.readLock();

            try {
                ByteBuffer buf = frame.buffer();
                int level = IndexPageLayout.readLevel(buf);
                int recordCount = IndexPageLayout.readRecordCount(buf);
                int freeSpace = IndexPageLayout.freeSpace(buf);
                int usedSpace = PAGE_SIZE - freeSpace;

                // 更新页面计数
                if (level == 0) {
                    leafPageCount++;
                } else {
                    internalPageCount++;
                }

                // 更新空间统计
                totalFreeSpace += freeSpace;
                totalUsedSpace += usedSpace;

                // 更新填充率
                double fillFactor = (double) usedSpace / PAGE_SIZE;
                minFillFactor = Math.min(minFillFactor, fillFactor);
                maxFillFactor = Math.max(maxFillFactor, fillFactor);
                sumFillFactor += fillFactor;

                // 更新记录数统计
                if (recordCount > 0) {
                    minRecordsPerPage = Math.min(minRecordsPerPage, recordCount);
                    maxRecordsPerPage = Math.max(maxRecordsPerPage, recordCount);
                    sumRecordsPerPage += recordCount;
                }

                // 收集键范围（仅叶子节点）
                if (level == 0 && recordCount > 0) {
                    // 获取第一个键
                    int firstOffset = IndexPageLayout.readFirstUserRecordOffset(buf);
                    if (firstOffset != IndexPageLayout.SUPREMUM_OFFSET) {
                        byte[] firstKey = comparator.extractKey(buf, firstOffset);
                        if (minKey == null || compareKeys(firstKey, minKey) < 0) {
                            minKey = firstKey.clone();
                        }
                    }

                    // 获取最后一个键
                    int lastOffset = findLastUserRecord(buf);
                    if (lastOffset != IndexPageLayout.INFIMUM_OFFSET) {
                        byte[] lastKey = comparator.extractKey(buf, lastOffset);
                        if (maxKey == null || compareKeys(lastKey, maxKey) > 0) {
                            maxKey = lastKey.clone();
                        }
                    }
                }

                // 如果是非叶子节点，将子页面加入队列
                if (level > 0) {
                    int current = IndexPageLayout.readFirstUserRecordOffset(buf);
                    while (current != IndexPageLayout.SUPREMUM_OFFSET && current != 0) {
                        int childPageNo = buf.getInt(current +
                                SimpleRecordBuilder.RECORD_HEADER_SIZE + SimpleRecordBuilder.KEY_SIZE);
                        queue.add(new PageId(pageId.getSpaceId(), childPageNo));
                        current = IndexPageLayout.readRecordNext(buf, current);
                    }
                }
            } finally {
                frame.readUnlock();
            }
        }

        // 设置统计结果
        int totalPageCount = leafPageCount + internalPageCount;
        stats.setLeafPageCount(leafPageCount);
        stats.setInternalPageCount(internalPageCount);
        stats.setTotalPageCount(totalPageCount);

        stats.setTotalFreeSpace(totalFreeSpace);
        stats.setTotalSpaceUsed(totalUsedSpace);

        if (totalPageCount > 0) {
            stats.setAvgFillFactor(sumFillFactor / totalPageCount);
            stats.setMinFillFactor(minFillFactor);
            stats.setMaxFillFactor(maxFillFactor);
            stats.setAvgRecordsPerPage((double) sumRecordsPerPage / totalPageCount);
        }

        if (minRecordsPerPage != Integer.MAX_VALUE) {
            stats.setMinRecordsPerPage(minRecordsPerPage);
            stats.setMaxRecordsPerPage(maxRecordsPerPage);
        }

        stats.setMinKey(minKey);
        stats.setMaxKey(maxKey);

        // 估计不同键的数量（简化：假设等于记录数）
        stats.setDistinctKeyCount(btree.getRecordCount());

        long endTime = System.currentTimeMillis();
        stats.setCollectionDurationMs(endTime - startTime);

        return stats;
    }

    /**
     * 快速收集基本统计信息
     *
     * <p>只收集元数据中已有的信息，不遍历树。</p>
     *
     * @param btree B+Tree
     * @return 基本统计信息
     */
    public static BTreeStats collectBasic(BTree btree) {
        BTreeStats stats = new BTreeStats(btree.getMetadata().getIndexId());
        stats.setTreeHeight(btree.getTreeHeight());
        stats.setRecordCount(btree.getRecordCount());
        stats.setCollectionDurationMs(0);
        return stats;
    }

    /**
     * 采样收集统计信息
     *
     * <p>通过采样部分页面来估计统计信息，比完整收集快。</p>
     *
     * @param btree      B+Tree
     * @param sampleRate 采样率（0.0 到 1.0）
     * @param mtr        Mini-Transaction
     * @return 估计的统计信息
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static BTreeStats collectSampled(BTree btree, double sampleRate, MiniTransaction mtr)
            throws MiniDbException {
        if (sampleRate >= 1.0) {
            return collect(btree, mtr);
        }

        long startTime = System.currentTimeMillis();

        BTreeStats stats = new BTreeStats(btree.getMetadata().getIndexId());
        stats.setTreeHeight(btree.getTreeHeight());
        stats.setRecordCount(btree.getRecordCount());

        BufferPool bufferPool = btree.getBufferPool();
        RecordComparator comparator = btree.getComparator();

        // 采样叶子页面
        int sampledPages = 0;
        long totalFreeSpace = 0;
        double sumFillFactor = 0.0;
        long sumRecordsPerPage = 0;

        byte[] minKey = null;
        byte[] maxKey = null;

        // 使用游标遍历叶子节点
        try (BTreeCursor cursor = btree.openCursor(mtr)) {
            cursor.seekFirst();

            PageId lastPageId = null;
            int recordsInCurrentPage = 0;

            while (cursor.isValid()) {
                CursorPosition pos = cursor.getPosition();

                // 检查是否是新页面
                if (lastPageId == null || !lastPageId.equals(pos.getPageId())) {
                    // 决定是否采样这个页面
                    if (Math.random() < sampleRate) {
                        BufferFrame frame = bufferPool.getPage(pos.getPageId(),
                                BufferPool.FetchMode.READ_EXISTING);
                        frame.readLock();
                        try {
                            ByteBuffer buf = frame.buffer();
                            int freeSpace = IndexPageLayout.freeSpace(buf);
                            int recordCount = IndexPageLayout.readRecordCount(buf);

                            totalFreeSpace += freeSpace;
                            sumFillFactor += (double) (PAGE_SIZE - freeSpace) / PAGE_SIZE;
                            sumRecordsPerPage += recordCount;
                            sampledPages++;
                        } finally {
                            frame.readUnlock();
                        }
                    }
                    lastPageId = pos.getPageId();
                }

                // 收集键范围
                byte[] key = cursor.getKey();
                if (key != null) {
                    if (minKey == null || compareKeys(key, minKey) < 0) {
                        minKey = key.clone();
                    }
                    if (maxKey == null || compareKeys(key, maxKey) > 0) {
                        maxKey = key.clone();
                    }
                }

                cursor.next();
            }
        }

        // 根据采样结果估计总体统计
        if (sampledPages > 0) {
            double scaleFactor = 1.0 / sampleRate;
            stats.setLeafPageCount((int) Math.round(sampledPages * scaleFactor));
            stats.setTotalFreeSpace((long) (totalFreeSpace * scaleFactor));
            stats.setAvgFillFactor(sumFillFactor / sampledPages);
            stats.setAvgRecordsPerPage((double) sumRecordsPerPage / sampledPages);
        }

        stats.setMinKey(minKey);
        stats.setMaxKey(maxKey);
        stats.setDistinctKeyCount(btree.getRecordCount());

        long endTime = System.currentTimeMillis();
        stats.setCollectionDurationMs(endTime - startTime);

        return stats;
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

    // 禁止实例化
    private BTreeStatsCollector() {
        throw new UnsupportedOperationException("BTreeStatsCollector is a utility class");
    }
}
