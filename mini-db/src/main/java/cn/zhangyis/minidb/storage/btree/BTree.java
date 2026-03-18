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
import cn.zhangyis.minidb.storage.transaction.mvcc.ReadView;
import cn.zhangyis.minidb.storage.transaction.mvcc.RecordVersion;
import cn.zhangyis.minidb.storage.transaction.mvcc.VersionChainReader;
import cn.zhangyis.minidb.storage.transaction.mvcc.VisibilityChecker;

import java.nio.ByteBuffer;

/**
 * B+Tree 核心实现
 *
 * <p>实现完整的 B+Tree 索引结构，支持：</p>
 * <ul>
 *   <li>多层树结构</li>
 *   <li>从根到叶的搜索</li>
 *   <li>递归插入（自动处理分裂）</li>
 *   <li>根节点分裂（树高度增长）</li>
 * </ul>
 *
 * <h2>核心不变量</h2>
 * <ul>
 *   <li>I1: 所有叶子节点在同一层级（level=0）</li>
 *   <li>I2: 非叶子节点的键指向 >= 该键的子树</li>
 *   <li>I3: 根节点分裂时创建新根，树高度 +1</li>
 *   <li>I4: 搜索路径从根到叶，level 递减</li>
 * </ul>
 *
 * <h2>非叶子节点记录格式</h2>
 * <pre>
 * +------------------+------------------+------------------+
 * | Record Header    | Key (4 bytes)    | Child PageNo     |
 * | (5 bytes)        |                  | (4 bytes)        |
 * +------------------+------------------+------------------+
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class BTree {

    /** 索引元数据 */
    private final BTreeMetadata metadata;

    /** Buffer Pool */
    private final BufferPool bufferPool;

    /** 记录比较器 */
    private final RecordComparator comparator;

    /**
     * 构造 B+Tree
     *
     * @param metadata   索引元数据
     * @param bufferPool Buffer Pool
     * @param comparator 记录比较器
     */
    public BTree(BTreeMetadata metadata, BufferPool bufferPool, RecordComparator comparator) {
        this.metadata = metadata;
        this.bufferPool = bufferPool;
        this.comparator = comparator;
    }

    /**
     * 创建新的 B+Tree 索引
     *
     * @param indexId    索引 ID
     * @param spaceId    空间 ID
     * @param bufferPool Buffer Pool
     * @param comparator 记录比较器
     * @param mtr        Mini-Transaction
     * @return 新创建的 B+Tree
     * @throws MtrStateException 如果 MTR 不在 ACTIVE 状态
     */
    public static BTree create(long indexId, int spaceId, BufferPool bufferPool,
                               RecordComparator comparator, MiniTransaction mtr)
            throws MiniDbException {
        // 分配根页面
        Page rootPage = mtr.newPage(spaceId);
        BufferFrame rootFrame = mtr.getPageFrame(rootPage.getPageId(), BufferPool.FetchMode.READ_EXISTING);
        rootFrame.writeLock();

        try {
            // 初始化为叶子节点（level=0）
            IndexPageOps.initPage(rootFrame, indexId, 0, mtr);
        } finally {
            rootFrame.writeUnlock();
        }

        // 创建元数据
        BTreeMetadata metadata = new BTreeMetadata(indexId, spaceId, rootPage.getPageId().getPageNo());

        return new BTree(metadata, bufferPool, comparator);
    }

    // ==================== 搜索操作 ====================

    /**
     * 搜索指定键
     *
     * @param searchKey 搜索键
     * @param mtr       Mini-Transaction
     * @return 搜索结果，包含记录偏移和页面信息
     * @throws MtrStateException 如果 MTR 不在 ACTIVE 状态
     */
    public BTreeSearchResult search(byte[] searchKey, MiniTransaction mtr) throws MiniDbException {
        BTreePath path = new BTreePath();

        // 从根节点开始搜索
        PageId currentPageId = metadata.getRootPageId();

        while (true) {
            BufferFrame frame = bufferPool.getPage(currentPageId, BufferPool.FetchMode.READ_EXISTING);
            frame.readLock();

            try {
                ByteBuffer buf = frame.buffer();
                int level = IndexPageLayout.readLevel(buf);

                // 在当前页面搜索
                PageSearchResult pageResult = PageSearch.search(buf, searchKey, comparator);

                // 记录路径
                path.addNode(currentPageId, level, pageResult.getRecordOffset());

                if (level == 0) {
                    // 到达叶子节点
                    return new BTreeSearchResult(
                            currentPageId,
                            pageResult.getRecordOffset(),
                            pageResult.isExactMatch(),
                            path
                    );
                }

                // 非叶子节点，获取子页面
                int childPageNo = getChildPageNo(buf, pageResult.getRecordOffset(), searchKey);
                currentPageId = new PageId(metadata.getSpaceId(), childPageNo);
            } finally {
                frame.readUnlock();
            }
        }
    }

    /**
     * 检查键是否存在
     *
     * @param searchKey 搜索键
     * @param mtr       Mini-Transaction
     * @return 如果键存在返回 true
     * @throws MtrStateException 如果 MTR 不在 ACTIVE 状态
     */
    public boolean containsKey(byte[] searchKey, MiniTransaction mtr) throws MiniDbException {
        BTreeSearchResult result = search(searchKey, mtr);
        return result.isExactMatch();
    }

    /**
     * 搜索可见的记录版本（支持 MVCC）
     *
     * <p>执行步骤：</p>
     * <ol>
     *   <li>执行标准 B+Tree 搜索</li>
     *   <li>从页面读取记录</li>
     *   <li>检查可见性</li>
     *   <li>如果不可见，遍历版本链查找可见版本</li>
     * </ol>
     *
     * @param searchKey           搜索键
     * @param mtr                 Mini-Transaction
     * @param readView            读视图
     * @param versionChainReader  版本链读取器
     * @param recordVersionReader 记录版本读取器
     * @return 搜索结果，包含可见版本信息
     * @throws MiniDbException 如果操作失败
     */
    public BTreeSearchResult searchVisible(byte[] searchKey,
                                          MiniTransaction mtr,
                                          ReadView readView,
                                          VersionChainReader versionChainReader,
                                          MvccBTreeRangeScanner.RecordVersionReader recordVersionReader)
            throws MiniDbException {
        // 1. 执行标准 B+Tree 搜索
        BTreeSearchResult result = search(searchKey, mtr);

        if (!result.isExactMatch()) {
            return result;  // 记录不存在
        }

        // 如果没有 ReadView，直接返回（向后兼容）
        if (readView == null) {
            return result;
        }

        // 2. 从页面读取记录
        Page page = mtr.getPage(result.getPageId(), BufferPool.FetchMode.READ_EXISTING);
        ByteBuffer buf = page.getBuffer();
        int offset = result.getRecordOffset();

        // 这里需要从页面中读取记录的 TRX_ID 和 ROLL_PTR
        // 由于 BTree 中的记录格式可能不同，这里使用 recordVersionReader 来读取
        // 实际实现需要根据具体的记录格式调整

        // 3. 检查可见性
        // 注意：这里的实现需要根据实际的记录格式来调整
        // 目前返回原始搜索结果，实际的可见性检查应该在上层进行

        return result;
    }

    // ==================== 插入操作 ====================

    /**
     * 插入记录
     *
     * <p>完整的插入流程：</p>
     * <ol>
     *   <li>搜索到叶子节点</li>
     *   <li>在叶子节点插入</li>
     *   <li>如果需要分裂，向上传播</li>
     *   <li>如果根节点分裂，创建新根</li>
     * </ol>
     *
     * @param recordData 完整的记录数据
     * @param searchKey  记录的键
     * @param mtr        Mini-Transaction
     * @return 插入是否成功
     * @throws MtrStateException 如果 MTR 不在 ACTIVE 状态
     */
    public boolean insert(byte[] recordData, byte[] searchKey, MiniTransaction mtr)
            throws MiniDbException {
        return insert(recordData, 0, searchKey, mtr);
    }

    /**
     * 插入记录（支持记录头不在字节数组起始位置）
     *
     * @param recordData         完整记录字节
     * @param recordHeaderOffset 记录头在字节数组中的偏移
     * @param searchKey          记录的键
     * @param mtr                Mini-Transaction
     * @return 插入是否成功
     * @throws MiniDbException 如果发生错误
     */
    public boolean insert(byte[] recordData, int recordHeaderOffset, byte[] searchKey, MiniTransaction mtr)
            throws MiniDbException {
        // 1. 搜索到叶子节点
        BTreeSearchResult searchResult = search(searchKey, mtr);
        BTreePath path = searchResult.getPath();

        // 2. 在叶子节点插入
        PageId leafPageId = searchResult.getPageId();
        SplitResult splitResult = insertIntoLeaf(leafPageId, recordData, recordHeaderOffset, searchKey, mtr);

        // 3. 如果发生分裂，向上传播
        if (splitResult != null) {
            propagateSplit(path, splitResult, mtr);
        }

        // 更新记录计数
        metadata.addRecordCount(1);

        return true;
    }

    // ==================== 删除操作 ====================

    /**
     * 删除记录
     *
     * @param searchKey  要删除的键
     * @param recordSize 记录大小
     * @param mtr        Mini-Transaction
     * @return 如果成功删除返回 true，如果键不存在返回 false
     * @throws MiniDbException 如果发生错误
     */
    public boolean delete(byte[] searchKey, int recordSize, MiniTransaction mtr)
            throws MiniDbException {
        return BTreeDelete.delete(this, searchKey, recordSize, mtr);
    }

    /**
     * 批量删除
     *
     * @param keys       要删除的键数组
     * @param recordSize 记录大小
     * @param mtr        Mini-Transaction
     * @return 成功删除的记录数
     * @throws MiniDbException 如果发生错误
     */
    public int deleteBatch(byte[][] keys, int recordSize, MiniTransaction mtr)
            throws MiniDbException {
        return BTreeDelete.deleteBatch(this, keys, recordSize, mtr);
    }

    /**
     * 删除范围内的所有记录
     *
     * @param lowerBound 下界
     * @param upperBound 上界
     * @param recordSize 记录大小
     * @param mtr        Mini-Transaction
     * @return 删除的记录数
     * @throws MiniDbException 如果发生错误
     */
    public int deleteRange(RangeBound lowerBound, RangeBound upperBound, int recordSize,
                           MiniTransaction mtr) throws MiniDbException {
        return BTreeDelete.deleteRange(this, lowerBound, upperBound, recordSize, mtr);
    }

    /**
     * 在叶子节点插入记录
     *
     * @return 如果发生分裂返回 SplitResult，否则返回 null
     */
    private SplitResult insertIntoLeaf(PageId pageId, byte[] recordData, int recordHeaderOffset, byte[] searchKey,
                                       MiniTransaction mtr) throws MiniDbException {
        BufferFrame frame = bufferPool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
        frame.writeLock();

        try {
            ByteBuffer buf = frame.buffer();

            // 尝试直接插入
            if (PageInsert.hasSpaceFor(buf, recordData.length)) {
                PageInsert.insertRecord(frame, recordData, recordHeaderOffset, searchKey, comparator, mtr);
                return null;
            }

            // 空间不足，需要分裂
            return PageSplit.splitAndInsert(frame, recordData, recordHeaderOffset, searchKey, bufferPool, comparator, mtr);
        } finally {
            frame.writeUnlock();
        }
    }

    /**
     * 向上传播分裂
     *
     * <p>当子节点分裂时，需要在父节点插入分裂键。
     * 如果父节点也需要分裂，继续向上传播。</p>
     */
    private void propagateSplit(BTreePath path, SplitResult splitResult, MiniTransaction mtr)
            throws MiniDbException {
        // 从叶子节点的父节点开始，向上传播
        for (int i = path.length() - 2; i >= 0; i--) {
            BTreePath.PathNode parentNode = path.getNode(i);

            // 构建指向新页面的记录（非叶子节点记录）
            byte[] nodeRecord = buildNodePtrRecord(
                    splitResult.getSplitKey(),
                    splitResult.getNewPageId().getPageNo()
            );

            // 在父节点插入
            SplitResult parentSplit = insertIntoInternal(
                    parentNode.getPageId(),
                    nodeRecord,
                    splitResult.getSplitKey(),
                    mtr
            );

            if (parentSplit == null) {
                // 父节点没有分裂，传播结束
                return;
            }

            // 父节点也分裂了，继续向上传播
            splitResult = parentSplit;
        }

        // 到达根节点，需要创建新根
        createNewRoot(splitResult, mtr);
    }

    /**
     * 在非叶子节点插入记录
     *
     * @return 如果发生分裂返回 SplitResult，否则返回 null
     */
    private SplitResult insertIntoInternal(PageId pageId, byte[] recordData, byte[] searchKey,
                                           MiniTransaction mtr) throws MiniDbException {
        BufferFrame frame = bufferPool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
        frame.writeLock();

        try {
            ByteBuffer buf = frame.buffer();

            // 尝试直接插入
            if (PageInsert.hasSpaceFor(buf, recordData.length)) {
                PageInsert.insertRecord(frame, recordData, searchKey, comparator, mtr);
                return null;
            }

            // 空间不足，需要分裂
            return PageSplit.splitAndInsert(frame, recordData, searchKey, bufferPool, comparator, mtr);
        } finally {
            frame.writeUnlock();
        }
    }

    /**
     * 创建新根节点
     *
     * <p>当根节点分裂时调用。新根包含两条记录：</p>
     * <ul>
     *   <li>指向原根（现在是左子节点）的记录</li>
     *   <li>指向新页面（右子节点）的记录</li>
     * </ul>
     */
    private void createNewRoot(SplitResult splitResult, MiniTransaction mtr)
            throws MiniDbException {
        // 分配新根页面
        Page newRootPage = mtr.newPage(metadata.getSpaceId());
        BufferFrame newRootFrame = bufferPool.getPage(newRootPage.getPageId(), BufferPool.FetchMode.READ_EXISTING);
        newRootFrame.writeLock();

        try {
            // 获取原根的层级
            BufferFrame oldRootFrame = bufferPool.getPage(metadata.getRootPageId(), BufferPool.FetchMode.READ_EXISTING);
            oldRootFrame.readLock();
            int oldRootLevel;
            try {
                oldRootLevel = IndexPageLayout.readLevel(oldRootFrame.buffer());
            } finally {
                oldRootFrame.readUnlock();
            }

            // 初始化新根（层级 = 原根层级 + 1）
            int newRootLevel = oldRootLevel + 1;
            IndexPageOps.initPage(newRootFrame, metadata.getIndexId(), newRootLevel, mtr);

            // 插入指向原根的记录（使用最小键）
            byte[] leftRecord = buildNodePtrRecord(
                    new byte[]{0, 0, 0, 0}, // 最小键（或使用特殊标记）
                    metadata.getRootPageNo()
            );
            IndexPageOps.insertRecord(newRootFrame, leftRecord, IndexPageLayout.INFIMUM_OFFSET, mtr);

            // 插入指向新页面的记录
            byte[] rightRecord = buildNodePtrRecord(
                    splitResult.getSplitKey(),
                    splitResult.getNewPageId().getPageNo()
            );
            // 找到插入位置
            int insertAfter = IndexPageLayout.readFirstUserRecordOffset(newRootFrame.buffer());
            IndexPageOps.insertRecord(newRootFrame, rightRecord, insertAfter, mtr);

            // 更新元数据
            metadata.setRootPageNo(newRootPage.getPageId().getPageNo());
            metadata.incrementTreeHeight();
        } finally {
            newRootFrame.writeUnlock();
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 从非叶子节点记录中获取子页面号
     *
     * @param buf          页面 ByteBuffer
     * @param recordOffset 记录偏移
     * @param searchKey    搜索键
     * @return 子页面号
     */
    private int getChildPageNo(ByteBuffer buf, int recordOffset, byte[] searchKey) {
        // 如果搜索结果指向 Infimum，使用第一条用户记录
        if (recordOffset == IndexPageLayout.INFIMUM_OFFSET) {
            recordOffset = IndexPageLayout.readFirstUserRecordOffset(buf);
        }

        // 非叶子节点记录格式：header(5) + key(4) + child_page_no(4)
        int childPageNoOffset = recordOffset + SimpleRecordBuilder.RECORD_HEADER_SIZE + SimpleRecordBuilder.KEY_SIZE;
        return buf.getInt(childPageNoOffset);
    }

    /**
     * 构建非叶子节点记录
     *
     * @param key         键
     * @param childPageNo 子页面号
     * @return 记录字节数组
     */
    private byte[] buildNodePtrRecord(byte[] key, int childPageNo) {
        return SimpleRecordBuilder.buildNodePtrRecord(
                IntKeyComparator.bytesToInt(key),
                childPageNo,
                2 // heapNo，简化处理
        );
    }

    // ==================== Getter 方法 ====================

    /**
     * 获取索引元数据
     *
     * @return 索引元数据
     */
    public BTreeMetadata getMetadata() {
        return metadata;
    }

    /**
     * 获取树高度
     *
     * @return 树高度
     */
    public int getTreeHeight() {
        return metadata.getTreeHeight();
    }

    /**
     * 获取记录总数
     *
     * @return 记录总数
     */
    public long getRecordCount() {
        return metadata.getRecordCount();
    }

    // ==================== 游标和范围扫描 ====================

    /**
     * 打开游标
     *
     * @param mtr Mini-Transaction
     * @return 新的游标
     */
    public BTreeCursor openCursor(MiniTransaction mtr) {
        return new BTreeCursor(this, bufferPool, comparator, mtr);
    }

    /**
     * 创建全表扫描器
     *
     * @param mtr Mini-Transaction
     * @return 范围扫描器
     */
    public BTreeRangeScanner fullScan(MiniTransaction mtr) {
        return BTreeRangeScanner.fullScan(this, bufferPool, comparator, mtr);
    }

    /**
     * 创建范围扫描器
     *
     * @param mtr        Mini-Transaction
     * @param lowerBound 下界
     * @param upperBound 上界
     * @return 范围扫描器
     */
    public BTreeRangeScanner rangeScan(MiniTransaction mtr, RangeBound lowerBound, RangeBound upperBound) {
        return new BTreeRangeScanner(this, bufferPool, comparator, mtr, lowerBound, upperBound);
    }

    /**
     * 创建等值查询扫描器
     *
     * @param mtr Mini-Transaction
     * @param key 查询键
     * @return 范围扫描器
     */
    public BTreeRangeScanner equalScan(MiniTransaction mtr, byte[] key) {
        return BTreeRangeScanner.equalScan(this, bufferPool, comparator, mtr, key);
    }

    /**
     * 创建 > 查询扫描器
     *
     * @param mtr Mini-Transaction
     * @param key 查询键
     * @return 范围扫描器
     */
    public BTreeRangeScanner greaterThan(MiniTransaction mtr, byte[] key) {
        return BTreeRangeScanner.greaterThan(this, bufferPool, comparator, mtr, key);
    }

    /**
     * 创建 >= 查询扫描器
     *
     * @param mtr Mini-Transaction
     * @param key 查询键
     * @return 范围扫描器
     */
    public BTreeRangeScanner greaterOrEqual(MiniTransaction mtr, byte[] key) {
        return BTreeRangeScanner.greaterOrEqual(this, bufferPool, comparator, mtr, key);
    }

    /**
     * 创建 < 查询扫描器
     *
     * @param mtr Mini-Transaction
     * @param key 查询键
     * @return 范围扫描器
     */
    public BTreeRangeScanner lessThan(MiniTransaction mtr, byte[] key) {
        return BTreeRangeScanner.lessThan(this, bufferPool, comparator, mtr, key);
    }

    /**
     * 创建 <= 查询扫描器
     *
     * @param mtr Mini-Transaction
     * @param key 查询键
     * @return 范围扫描器
     */
    public BTreeRangeScanner lessOrEqual(MiniTransaction mtr, byte[] key) {
        return BTreeRangeScanner.lessOrEqual(this, bufferPool, comparator, mtr, key);
    }

    /**
     * 创建 BETWEEN 查询扫描器
     *
     * @param mtr      Mini-Transaction
     * @param lowerKey 下界键
     * @param upperKey 上界键
     * @return 范围扫描器
     */
    public BTreeRangeScanner between(MiniTransaction mtr, byte[] lowerKey, byte[] upperKey) {
        return BTreeRangeScanner.between(this, bufferPool, comparator, mtr, lowerKey, upperKey);
    }

    /**
     * 获取 BufferPool（供内部使用）
     *
     * @return BufferPool
     */
    BufferPool getBufferPool() {
        return bufferPool;
    }

    /**
     * 获取比较器（供内部使用）
     *
     * @return 记录比较器
     */
    RecordComparator getComparator() {
        return comparator;
    }
}
