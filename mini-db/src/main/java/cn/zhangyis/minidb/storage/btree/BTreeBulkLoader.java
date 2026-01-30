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
import java.util.Iterator;
import java.util.List;

/**
 * B+Tree 批量加载器
 *
 * <p>实现高效的批量加载，自底向上构建 B+Tree。</p>
 *
 * <h2>批量加载流程</h2>
 * <ol>
 *   <li>验证输入数据已排序</li>
 *   <li>创建叶子页面，按填充率填充记录</li>
 *   <li>收集每个叶子页面的第一个键</li>
 *   <li>自底向上构建非叶子层</li>
 *   <li>设置页面间的链接</li>
 * </ol>
 *
 * <h2>优势</h2>
 * <ul>
 *   <li>比逐条插入快得多（无需搜索和分裂）</li>
 *   <li>可控制页面填充率</li>
 *   <li>生成更紧凑的树结构</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class BTreeBulkLoader {

    /** 页面大小 */
    private static final int PAGE_SIZE = 16 * 1024;

    /** 页面头部大小 */
    private static final int PAGE_HEADER_SIZE = IndexPageLayout.SUPREMUM_OFFSET + IndexPageLayout.SUPREMUM_SIZE;

    /**
     * 批量加载数据到新的 B+Tree
     *
     * @param indexId    索引 ID
     * @param spaceId    空间 ID
     * @param records    记录迭代器（必须按键排序）
     * @param comparator 记录比较器
     * @param bufferPool Buffer Pool
     * @param config     加载配置
     * @param mtr        Mini-Transaction
     * @return 加载结果
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static BulkLoadResult load(long indexId, int spaceId,
                                      Iterator<BulkLoadRecord> records,
                                      RecordComparator comparator,
                                      BufferPool bufferPool,
                                      BulkLoadConfig config,
                                      MiniTransaction mtr) throws MiniDbException {
        long startTime = System.currentTimeMillis();

        try {
            // 1. 构建叶子层
            LeafBuildResult leafResult = buildLeafLevel(indexId, spaceId, records,
                    comparator, bufferPool, config, mtr);

            if (leafResult.pageCount == 0) {
                // 空数据，创建空树
                BTree emptyTree = BTree.create(indexId, spaceId, bufferPool, comparator, mtr);
                long duration = System.currentTimeMillis() - startTime;
                return BulkLoadResult.success(0, 1, 0, 1, duration);
            }

            // 2. 自底向上构建非叶子层
            List<PageId> currentLevel = leafResult.pageIds;
            List<byte[]> currentKeys = leafResult.firstKeys;
            int internalPageCount = 0;
            int level = 1;

            while (currentLevel.size() > 1) {
                InternalBuildResult internalResult = buildInternalLevel(
                        indexId, spaceId, currentLevel, currentKeys, level,
                        comparator, bufferPool, config, mtr);

                internalPageCount += internalResult.pageCount;
                currentLevel = internalResult.pageIds;
                currentKeys = internalResult.firstKeys;
                level++;
            }

            // 3. 创建 BTree 对象
            PageId rootPageId = currentLevel.get(0);
            BTreeMetadata metadata = new BTreeMetadata(indexId, spaceId, rootPageId.getPageNo());
            metadata.setTreeHeight(level);
            metadata.setRecordCount(leafResult.recordCount);

            long duration = System.currentTimeMillis() - startTime;

            BulkLoadResult result = BulkLoadResult.success(
                    leafResult.recordCount,
                    leafResult.pageCount,
                    internalPageCount,
                    level,
                    duration
            );

            // 4. 可选：收集统计信息
            if (config.isCollectStats()) {
                BTree btree = new BTree(metadata, bufferPool, comparator);
                BTreeStats stats = BTreeStatsCollector.collect(btree, mtr);
                result.setStats(stats);
            }

            return result;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            return BulkLoadResult.failure(e.getMessage(), duration);
        }
    }

    /**
     * 批量加载数据到新的 B+Tree（返回 BTree 对象）
     */
    public static BTree loadAndGetTree(long indexId, int spaceId,
                                       Iterator<BulkLoadRecord> records,
                                       RecordComparator comparator,
                                       BufferPool bufferPool,
                                       BulkLoadConfig config,
                                       MiniTransaction mtr) throws MiniDbException {
        long startTime = System.currentTimeMillis();

        // 1. 构建叶子层
        LeafBuildResult leafResult = buildLeafLevel(indexId, spaceId, records,
                comparator, bufferPool, config, mtr);

        if (leafResult.pageCount == 0) {
            // 空数据，创建空树
            return BTree.create(indexId, spaceId, bufferPool, comparator, mtr);
        }

        // 2. 自底向上构建非叶子层
        List<PageId> currentLevel = leafResult.pageIds;
        List<byte[]> currentKeys = leafResult.firstKeys;
        int level = 1;

        while (currentLevel.size() > 1) {
            InternalBuildResult internalResult = buildInternalLevel(
                    indexId, spaceId, currentLevel, currentKeys, level,
                    comparator, bufferPool, config, mtr);

            currentLevel = internalResult.pageIds;
            currentKeys = internalResult.firstKeys;
            level++;
        }

        // 3. 创建 BTree 对象
        PageId rootPageId = currentLevel.get(0);
        BTreeMetadata metadata = new BTreeMetadata(indexId, spaceId, rootPageId.getPageNo());
        metadata.setTreeHeight(level);
        metadata.setRecordCount(leafResult.recordCount);

        return new BTree(metadata, bufferPool, comparator);
    }

    /**
     * 构建叶子层
     */
    private static LeafBuildResult buildLeafLevel(long indexId, int spaceId,
                                                  Iterator<BulkLoadRecord> records,
                                                  RecordComparator comparator,
                                                  BufferPool bufferPool,
                                                  BulkLoadConfig config,
                                                  MiniTransaction mtr) throws MiniDbException {
        LeafBuildResult result = new LeafBuildResult();
        int targetUsedSpace = config.getTargetUsedSpace(PAGE_SIZE);

        BufferFrame currentFrame = null;
        PageId currentPageId = null;
        int currentUsedSpace = PAGE_HEADER_SIZE;
        byte[] prevKey = null;
        byte[] firstKeyInPage = null;

        while (records.hasNext()) {
            BulkLoadRecord record = records.next();

            // 验证排序
            if (config.isValidateSorted() && prevKey != null) {
                if (compareKeys(record.getKey(), prevKey, comparator) <= 0) {
                    throw new IllegalArgumentException("Records are not sorted");
                }
            }

            int recordSize = record.getData().length;

            // 检查是否需要新页面
            if (currentFrame == null || currentUsedSpace + recordSize > targetUsedSpace) {
                // 完成当前页面
                if (currentFrame != null) {
                    currentFrame.writeUnlock();
                    result.pageIds.add(currentPageId);
                    result.firstKeys.add(firstKeyInPage);
                }

                // 创建新页面
                Page newPage = mtr.newPage(spaceId);
                currentPageId = newPage.getPageId();
                currentFrame = bufferPool.getPage(currentPageId, BufferPool.FetchMode.READ_EXISTING);
                currentFrame.writeLock();

                // 初始化页面
                IndexPageOps.initPage(currentFrame, indexId, 0, mtr);

                // 设置前后链接
                if (result.pageCount > 0) {
                    PageId prevPageId = result.pageIds.get(result.pageCount - 1);
                    setPageLinks(bufferPool, prevPageId, currentPageId, mtr);
                }

                result.pageCount++;
                currentUsedSpace = PAGE_HEADER_SIZE;
                firstKeyInPage = record.getKey().clone();
            }

            // 插入记录
            PageInsert.insertRecord(currentFrame, record.getData(), record.getKey(), comparator, mtr);
            currentUsedSpace += recordSize;
            prevKey = record.getKey();
            result.recordCount++;
        }

        // 完成最后一个页面
        if (currentFrame != null) {
            currentFrame.writeUnlock();
            result.pageIds.add(currentPageId);
            result.firstKeys.add(firstKeyInPage);
        }

        return result;
    }

    /**
     * 构建非叶子层
     */
    private static InternalBuildResult buildInternalLevel(long indexId, int spaceId,
                                                          List<PageId> childPageIds,
                                                          List<byte[]> childKeys,
                                                          int level,
                                                          RecordComparator comparator,
                                                          BufferPool bufferPool,
                                                          BulkLoadConfig config,
                                                          MiniTransaction mtr) throws MiniDbException {
        InternalBuildResult result = new InternalBuildResult();
        int targetUsedSpace = config.getTargetUsedSpace(PAGE_SIZE);

        // 非叶子节点记录大小
        int recordSize = SimpleRecordBuilder.RECORD_HEADER_SIZE + SimpleRecordBuilder.KEY_SIZE + 4;

        BufferFrame currentFrame = null;
        PageId currentPageId = null;
        int currentUsedSpace = PAGE_HEADER_SIZE;
        byte[] firstKeyInPage = null;

        for (int i = 0; i < childPageIds.size(); i++) {
            PageId childPageId = childPageIds.get(i);
            byte[] key = childKeys.get(i);

            // 检查是否需要新页面
            if (currentFrame == null || currentUsedSpace + recordSize > targetUsedSpace) {
                // 完成当前页面
                if (currentFrame != null) {
                    currentFrame.writeUnlock();
                    result.pageIds.add(currentPageId);
                    result.firstKeys.add(firstKeyInPage);
                }

                // 创建新页面
                Page newPage = mtr.newPage(spaceId);
                currentPageId = newPage.getPageId();
                currentFrame = bufferPool.getPage(currentPageId, BufferPool.FetchMode.READ_EXISTING);
                currentFrame.writeLock();

                // 初始化页面
                IndexPageOps.initPage(currentFrame, indexId, level, mtr);

                result.pageCount++;
                currentUsedSpace = PAGE_HEADER_SIZE;
                firstKeyInPage = key.clone();
            }

            // 构建非叶子节点记录
            byte[] nodeRecord = SimpleRecordBuilder.buildNodePtrRecord(
                    IntKeyComparator.bytesToInt(key),
                    childPageId.getPageNo(),
                    i + 2 // heapNo
            );

            // 插入记录
            PageInsert.insertRecord(currentFrame, nodeRecord, key, comparator, mtr);
            currentUsedSpace += recordSize;
        }

        // 完成最后一个页面
        if (currentFrame != null) {
            currentFrame.writeUnlock();
            result.pageIds.add(currentPageId);
            result.firstKeys.add(firstKeyInPage);
        }

        return result;
    }

    /**
     * 设置页面前后链接
     */
    private static void setPageLinks(BufferPool bufferPool, PageId prevPageId, PageId nextPageId,
                                     MiniTransaction mtr) throws MiniDbException {
        // 设置前一页的 next 指针
        BufferFrame prevFrame = bufferPool.getPage(prevPageId, BufferPool.FetchMode.READ_EXISTING);
        prevFrame.writeLock();
        try {
            IndexPageOps.setNextPage(prevFrame, nextPageId.getPageNo(), mtr);
        } finally {
            prevFrame.writeUnlock();
        }

        // 设置当前页的 prev 指针
        BufferFrame nextFrame = bufferPool.getPage(nextPageId, BufferPool.FetchMode.READ_EXISTING);
        nextFrame.writeLock();
        try {
            IndexPageOps.setPrevPage(nextFrame, prevPageId.getPageNo(), mtr);
        } finally {
            nextFrame.writeUnlock();
        }
    }

    /**
     * 比较两个键
     */
    private static int compareKeys(byte[] key1, byte[] key2, RecordComparator comparator) {
        int k1 = IntKeyComparator.bytesToInt(key1);
        int k2 = IntKeyComparator.bytesToInt(key2);
        return Integer.compare(k1, k2);
    }

    // ==================== 内部类 ====================

    /**
     * 叶子层构建结果
     */
    private static class LeafBuildResult {
        List<PageId> pageIds = new ArrayList<>();
        List<byte[]> firstKeys = new ArrayList<>();
        int pageCount = 0;
        long recordCount = 0;
    }

    /**
     * 非叶子层构建结果
     */
    private static class InternalBuildResult {
        List<PageId> pageIds = new ArrayList<>();
        List<byte[]> firstKeys = new ArrayList<>();
        int pageCount = 0;
    }

    // 禁止实例化
    private BTreeBulkLoader() {
        throw new UnsupportedOperationException("BTreeBulkLoader is a utility class");
    }
}
