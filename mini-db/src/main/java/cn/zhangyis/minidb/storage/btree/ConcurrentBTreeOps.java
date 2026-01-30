package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.IndexPageLayout;
import cn.zhangyis.minidb.storage.page.PageId;

import java.nio.ByteBuffer;

/**
 * 并发 B+Tree 操作
 *
 * <p>实现支持并发访问的 B+Tree 操作，使用蟹行协议（Crabbing Protocol）。</p>
 *
 * <h2>蟹行协议</h2>
 * <ol>
 *   <li>从根节点开始，获取锁</li>
 *   <li>获取子节点的锁</li>
 *   <li>如果子节点"安全"（不会分裂/合并），释放父节点的锁</li>
 *   <li>继续向下，直到叶子节点</li>
 * </ol>
 *
 * <h2>安全节点定义</h2>
 * <ul>
 *   <li>插入时：节点未满（不会分裂）</li>
 *   <li>删除时：节点记录数 > 最小值（不会合并）</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class ConcurrentBTreeOps {

    /** 页面被认为"安全"的最小空闲空间（用于插入） */
    private static final int SAFE_FREE_SPACE = 200;

    /** 页面被认为"安全"的最小记录数（用于删除） */
    private static final int SAFE_MIN_RECORDS = 3;

    /**
     * 并发搜索
     *
     * <p>使用共享锁进行搜索，支持多个并发读取。</p>
     *
     * @param btree      B+Tree
     * @param searchKey  搜索键
     * @param mtr        Mini-Transaction
     * @return 搜索结果
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static BTreeSearchResult concurrentSearch(BTree btree, byte[] searchKey, MiniTransaction mtr)
            throws MiniDbException {
        BufferPool bufferPool = btree.getBufferPool();
        RecordComparator comparator = btree.getComparator();
        BTreeMetadata metadata = btree.getMetadata();

        LatchHolder latchHolder = new LatchHolder();
        BTreePath path = new BTreePath();

        try {
            PageId currentPageId = metadata.getRootPageId();

            while (true) {
                BufferFrame frame = bufferPool.getPage(currentPageId, BufferPool.FetchMode.READ_EXISTING);

                // 获取共享锁
                latchHolder.acquire(frame, LatchMode.SHARED);

                ByteBuffer buf = frame.buffer();
                int level = IndexPageLayout.readLevel(buf);

                // 在当前页面搜索
                PageSearchResult pageResult = PageSearch.search(buf, searchKey, comparator);

                // 记录路径
                path.addNode(currentPageId, level, pageResult.getRecordOffset());

                if (level == 0) {
                    // 到达叶子节点，保持锁直到操作完成
                    return new BTreeSearchResult(
                            currentPageId,
                            pageResult.getRecordOffset(),
                            pageResult.isExactMatch(),
                            path
                    );
                }

                // 获取子页面 ID
                int childPageNo = getChildPageNo(buf, pageResult.getRecordOffset(), searchKey);
                PageId childPageId = new PageId(metadata.getSpaceId(), childPageNo);

                // 蟹行：释放父节点锁（读操作总是安全的）
                latchHolder.releaseAllButLast();

                currentPageId = childPageId;
            }
        } finally {
            // 确保释放所有锁
            latchHolder.releaseAll();
        }
    }

    /**
     * 并发插入
     *
     * <p>使用蟹行协议进行插入，只在必要时持有锁。</p>
     *
     * @param btree      B+Tree
     * @param recordData 记录数据
     * @param searchKey  搜索键
     * @param mtr        Mini-Transaction
     * @return 如果成功插入返回 true
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static boolean concurrentInsert(BTree btree, byte[] recordData, byte[] searchKey,
                                           MiniTransaction mtr) throws MiniDbException {
        BufferPool bufferPool = btree.getBufferPool();
        RecordComparator comparator = btree.getComparator();
        BTreeMetadata metadata = btree.getMetadata();

        LatchHolder latchHolder = new LatchHolder();

        try {
            PageId currentPageId = metadata.getRootPageId();
            BufferFrame currentFrame = null;

            while (true) {
                BufferFrame frame = bufferPool.getPage(currentPageId, BufferPool.FetchMode.READ_EXISTING);

                // 获取排他锁
                latchHolder.acquire(frame, LatchMode.EXCLUSIVE);
                currentFrame = frame;

                ByteBuffer buf = frame.buffer();
                int level = IndexPageLayout.readLevel(buf);

                // 检查节点是否安全（不会分裂）
                if (isSafeForInsert(buf, recordData.length)) {
                    // 安全，释放所有祖先节点的锁
                    latchHolder.releaseAllExcept(frame);
                }

                if (level == 0) {
                    // 到达叶子节点，执行插入
                    int offset = PageInsert.insertRecord(frame, recordData, searchKey, comparator, mtr);
                    if (offset > 0) {
                        metadata.addRecordCount(1);
                        return true;
                    }

                    // 空间不足，需要分裂
                    // 简化处理：调用普通插入（会处理分裂）
                    return btree.insert(recordData, searchKey, mtr);
                }

                // 在当前页面搜索
                PageSearchResult pageResult = PageSearch.search(buf, searchKey, comparator);

                // 获取子页面 ID
                int childPageNo = getChildPageNo(buf, pageResult.getRecordOffset(), searchKey);
                currentPageId = new PageId(metadata.getSpaceId(), childPageNo);
            }
        } finally {
            latchHolder.releaseAll();
        }
    }

    /**
     * 并发删除
     *
     * <p>使用蟹行协议进行删除。</p>
     *
     * @param btree      B+Tree
     * @param searchKey  搜索键
     * @param recordSize 记录大小
     * @param mtr        Mini-Transaction
     * @return 如果成功删除返回 true
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static boolean concurrentDelete(BTree btree, byte[] searchKey, int recordSize,
                                           MiniTransaction mtr) throws MiniDbException {
        BufferPool bufferPool = btree.getBufferPool();
        RecordComparator comparator = btree.getComparator();
        BTreeMetadata metadata = btree.getMetadata();

        LatchHolder latchHolder = new LatchHolder();

        try {
            PageId currentPageId = metadata.getRootPageId();

            while (true) {
                BufferFrame frame = bufferPool.getPage(currentPageId, BufferPool.FetchMode.READ_EXISTING);

                // 获取排他锁
                latchHolder.acquire(frame, LatchMode.EXCLUSIVE);

                ByteBuffer buf = frame.buffer();
                int level = IndexPageLayout.readLevel(buf);

                // 检查节点是否安全（不会合并）
                if (isSafeForDelete(buf)) {
                    latchHolder.releaseAllExcept(frame);
                }

                if (level == 0) {
                    // 到达叶子节点，执行删除
                    boolean deleted = PageDelete.deleteRecord(frame, searchKey, recordSize, comparator, mtr);
                    if (deleted) {
                        metadata.addRecordCount(-1);
                    }
                    return deleted;
                }

                // 在当前页面搜索
                PageSearchResult pageResult = PageSearch.search(buf, searchKey, comparator);

                // 获取子页面 ID
                int childPageNo = getChildPageNo(buf, pageResult.getRecordOffset(), searchKey);
                currentPageId = new PageId(metadata.getSpaceId(), childPageNo);
            }
        } finally {
            latchHolder.releaseAll();
        }
    }

    /**
     * 乐观读取
     *
     * <p>不加锁进行读取，通过版本号检测冲突。</p>
     *
     * @param btree     B+Tree
     * @param searchKey 搜索键
     * @param mtr       Mini-Transaction
     * @return 搜索结果，如果检测到冲突返回 null
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static BTreeSearchResult optimisticSearch(BTree btree, byte[] searchKey, MiniTransaction mtr)
            throws MiniDbException {
        BufferPool bufferPool = btree.getBufferPool();
        RecordComparator comparator = btree.getComparator();
        BTreeMetadata metadata = btree.getMetadata();

        BTreePath path = new BTreePath();
        PageId currentPageId = metadata.getRootPageId();

        while (true) {
            BufferFrame frame = bufferPool.getPage(currentPageId, BufferPool.FetchMode.READ_EXISTING);

            // 不加锁，直接读取
            ByteBuffer buf = frame.buffer();

            // 记录版本号（用于检测并发修改）
            // 简化实现：使用 LSN 作为版本号
            long versionBefore = IndexPageLayout.readPageLSN(buf);

            int level = IndexPageLayout.readLevel(buf);
            PageSearchResult pageResult = PageSearch.search(buf, searchKey, comparator);

            // 检查版本号是否变化
            long versionAfter = IndexPageLayout.readPageLSN(buf);
            if (versionBefore != versionAfter) {
                // 检测到并发修改，需要重试
                return null;
            }

            path.addNode(currentPageId, level, pageResult.getRecordOffset());

            if (level == 0) {
                return new BTreeSearchResult(
                        currentPageId,
                        pageResult.getRecordOffset(),
                        pageResult.isExactMatch(),
                        path
                );
            }

            int childPageNo = getChildPageNo(buf, pageResult.getRecordOffset(), searchKey);
            currentPageId = new PageId(metadata.getSpaceId(), childPageNo);
        }
    }

    /**
     * 带重试的乐观读取
     *
     * @param btree      B+Tree
     * @param searchKey  搜索键
     * @param mtr        Mini-Transaction
     * @param maxRetries 最大重试次数
     * @return 搜索结果
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static BTreeSearchResult optimisticSearchWithRetry(BTree btree, byte[] searchKey,
                                                              MiniTransaction mtr, int maxRetries)
            throws MiniDbException {
        for (int i = 0; i < maxRetries; i++) {
            BTreeSearchResult result = optimisticSearch(btree, searchKey, mtr);
            if (result != null) {
                return result;
            }
            // 短暂等待后重试
            Thread.yield();
        }

        // 重试失败，回退到悲观读取
        return concurrentSearch(btree, searchKey, mtr);
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查页面是否对插入安全（不会分裂）
     */
    private static boolean isSafeForInsert(ByteBuffer buf, int recordSize) {
        int freeSpace = IndexPageLayout.freeSpace(buf);
        return freeSpace >= recordSize + SAFE_FREE_SPACE;
    }

    /**
     * 检查页面是否对删除安全（不会合并）
     */
    private static boolean isSafeForDelete(ByteBuffer buf) {
        int recordCount = IndexPageLayout.readRecordCount(buf);
        return recordCount > SAFE_MIN_RECORDS;
    }

    /**
     * 从非叶子节点记录中获取子页面号
     */
    private static int getChildPageNo(ByteBuffer buf, int recordOffset, byte[] searchKey) {
        if (recordOffset == IndexPageLayout.INFIMUM_OFFSET) {
            recordOffset = IndexPageLayout.readFirstUserRecordOffset(buf);
        }

        int childPageNoOffset = recordOffset + SimpleRecordBuilder.RECORD_HEADER_SIZE + SimpleRecordBuilder.KEY_SIZE;
        return buf.getInt(childPageNoOffset);
    }

    // 禁止实例化
    private ConcurrentBTreeOps() {
        throw new UnsupportedOperationException("ConcurrentBTreeOps is a utility class");
    }
}
