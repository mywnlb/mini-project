package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 索引管理器
 *
 * <p>负责索引的创建、打开、删除和元数据管理。</p>
 *
 * <h2>功能</h2>
 * <ul>
 *   <li>创建新索引</li>
 *   <li>打开已有索引</li>
 *   <li>删除索引</li>
 *   <li>持久化索引元数据</li>
 *   <li>系统启动时恢复索引</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class IndexManager {

    /** Buffer Pool */
    private final BufferPool bufferPool;

    /** 空间 ID */
    private final int spaceId;

    /** 元数据页面号 */
    private final int metaPageNo;

    /** 索引缓存：indexId -> BTree */
    private final Map<Long, BTree> indexCache;

    /** 索引描述符缓存：indexId -> IndexDescriptor */
    private final Map<Long, IndexDescriptor> descriptorCache;

    /** 索引名称映射：(tableId, indexName) -> indexId */
    private final Map<String, Long> nameToIdMap;

    /** 下一个索引 ID */
    private final AtomicLong nextIndexId;

    /** 是否已初始化 */
    private volatile boolean initialized;

    /**
     * 构造索引管理器
     *
     * @param bufferPool Buffer Pool
     * @param spaceId    空间 ID
     * @param metaPageNo 元数据页面号
     */
    public IndexManager(BufferPool bufferPool, int spaceId, int metaPageNo) {
        this.bufferPool = bufferPool;
        this.spaceId = spaceId;
        this.metaPageNo = metaPageNo;
        this.indexCache = new ConcurrentHashMap<>();
        this.descriptorCache = new ConcurrentHashMap<>();
        this.nameToIdMap = new ConcurrentHashMap<>();
        this.nextIndexId = new AtomicLong(1);
        this.initialized = false;
    }

    /**
     * 初始化索引管理器
     *
     * <p>从持久化数据恢复索引元数据。</p>
     *
     * @param mtr Mini-Transaction
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public synchronized void initialize(MiniTransaction mtr) throws MiniDbException {
        if (initialized) {
            return;
        }

        PageId metaPageId = new PageId(spaceId, metaPageNo);
        BufferFrame frame = mtr.getPageFrame(metaPageId, BufferPool.FetchMode.READ_EXISTING);
        frame.readLock();

        try {
            if (!IndexMetaPage.isValid(frame)) {
                // 元数据页面未初始化，初始化它
                frame.readUnlock();
                frame.writeLock();
                try {
                    IndexMetaPage.initPage(frame, mtr);
                    mtr.markDirty(frame.getPage());
                } finally {
                    frame.writeUnlock();
                    frame.readLock();
                }
            }

            // 加载所有索引描述符
            loadDescriptors(frame);

        } finally {
            frame.readUnlock();
        }

        initialized = true;
    }

    /**
     * 创建新索引
     *
     * @param indexName 索引名称
     * @param tableId   表 ID
     * @param indexType 索引类型
     * @param columns   列定义
     * @param mtr       Mini-Transaction
     * @return 新创建的索引
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public BTree createIndex(String indexName, long tableId, IndexType indexType,
                             List<IndexDescriptor.ColumnDescriptor> columns,
                             MiniTransaction mtr) throws MiniDbException {
        long indexId = nextIndexId.getAndIncrement();
        return createIndex(indexId, indexName, tableId, indexType, columns, mtr);
    }

    public BTree createIndex(long indexId, String indexName, long tableId, IndexType indexType,
                             List<IndexDescriptor.ColumnDescriptor> columns,
                             MiniTransaction mtr) throws MiniDbException {
        ensureInitialized(mtr);

        // 检查名称是否已存在
        String nameKey = makeNameKey(tableId, indexName);
        if (nameToIdMap.containsKey(nameKey)) {
            throw new IllegalArgumentException("Index already exists: " + indexName);
        }

        // 创建 B+Tree
        CompositeKeyDef keyDef = columnsToKeyDef(columns);
        CompositeKeyComparator comparator = new CompositeKeyComparator(keyDef);
        BTree btree = BTree.create(indexId, spaceId, bufferPool, comparator, mtr);

        // 创建索引描述符
        IndexDescriptor descriptor = new IndexDescriptor(
                indexId, indexName, tableId, spaceId,
                btree.getMetadata().getRootPageNo(), indexType, columns
        );

        // 持久化描述符
        persistDescriptor(descriptor, mtr);

        // 更新缓存
        indexCache.put(indexId, btree);
        descriptorCache.put(indexId, descriptor);
        nameToIdMap.put(nameKey, indexId);
        nextIndexId.updateAndGet(current -> Math.max(current, indexId + 1));

        return btree;
    }

    /**
     * 创建单列整数索引（快捷方法）
     *
     * @param indexName  索引名称
     * @param tableId    表 ID
     * @param indexType  索引类型
     * @param columnName 列名
     * @param mtr        Mini-Transaction
     * @return 新创建的索引
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public BTree createIntIndex(String indexName, long tableId, IndexType indexType,
                                String columnName, MiniTransaction mtr) throws MiniDbException {
        List<IndexDescriptor.ColumnDescriptor> columns = List.of(
                new IndexDescriptor.ColumnDescriptor(columnName, ColumnType.INT, 4, false, false)
        );
        return createIndex(indexName, tableId, indexType, columns, mtr);
    }

    /**
     * 打开已有索引
     *
     * @param indexId 索引 ID
     * @param mtr     Mini-Transaction
     * @return B+Tree
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public BTree openIndex(long indexId, MiniTransaction mtr) throws MiniDbException {
        ensureInitialized(mtr);

        // 检查缓存
        BTree cached = indexCache.get(indexId);
        if (cached != null) {
            return cached;
        }

        // 从描述符恢复
        IndexDescriptor descriptor = descriptorCache.get(indexId);
        if (descriptor == null) {
            throw new IllegalArgumentException("Index not found: " + indexId);
        }

        // 创建 B+Tree 对象
        BTreeMetadata metadata = descriptor.toBTreeMetadata();
        CompositeKeyDef keyDef = descriptor.toCompositeKeyDef();
        CompositeKeyComparator comparator = new CompositeKeyComparator(keyDef);

        BTree btree = new BTree(metadata, bufferPool, comparator);

        // 更新缓存
        indexCache.put(indexId, btree);

        return btree;
    }

    /**
     * 通过名称打开索引
     *
     * @param tableId   表 ID
     * @param indexName 索引名称
     * @param mtr       Mini-Transaction
     * @return B+Tree
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public BTree openIndexByName(long tableId, String indexName, MiniTransaction mtr)
            throws MiniDbException {
        ensureInitialized(mtr);

        String nameKey = makeNameKey(tableId, indexName);
        Long indexId = nameToIdMap.get(nameKey);

        if (indexId == null) {
            throw new IllegalArgumentException("Index not found: " + indexName);
        }

        return openIndex(indexId, mtr);
    }

    /**
     * 删除索引
     *
     * @param indexId 索引 ID
     * @param mtr     Mini-Transaction
     * @return 如果成功删除返回 true
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public boolean dropIndex(long indexId, MiniTransaction mtr) throws MiniDbException {
        ensureInitialized(mtr);

        IndexDescriptor descriptor = descriptorCache.get(indexId);
        if (descriptor == null) {
            return false;
        }

        // 标记删除
        descriptor.setDeleted(true);

        // 持久化
        updateDescriptor(descriptor, mtr);

        // 从缓存移除
        indexCache.remove(indexId);
        descriptorCache.remove(indexId);
        nameToIdMap.remove(makeNameKey(descriptor.getTableId(), descriptor.getIndexName()));

        return true;
    }

    /**
     * 通过名称删除索引
     *
     * @param tableId   表 ID
     * @param indexName 索引名称
     * @param mtr       Mini-Transaction
     * @return 如果成功删除返回 true
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public boolean dropIndexByName(long tableId, String indexName, MiniTransaction mtr)
            throws MiniDbException {
        String nameKey = makeNameKey(tableId, indexName);
        Long indexId = nameToIdMap.get(nameKey);

        if (indexId == null) {
            return false;
        }

        return dropIndex(indexId, mtr);
    }

    /**
     * 获取表的所有索引
     *
     * @param tableId 表 ID
     * @return 索引描述符列表
     */
    public List<IndexDescriptor> getTableIndexes(long tableId) {
        List<IndexDescriptor> result = new ArrayList<>();
        for (IndexDescriptor desc : descriptorCache.values()) {
            if (desc.getTableId() == tableId && !desc.isDeleted()) {
                result.add(desc);
            }
        }
        return result;
    }

    /**
     * 获取索引描述符
     *
     * @param indexId 索引 ID
     * @return 索引描述符
     */
    public IndexDescriptor getDescriptor(long indexId) {
        return descriptorCache.get(indexId);
    }

    /**
     * 更新索引元数据
     *
     * <p>在索引结构变化后调用（如分裂、合并）。</p>
     *
     * @param btree B+Tree
     * @param mtr   Mini-Transaction
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public void updateIndexMetadata(BTree btree, MiniTransaction mtr) throws MiniDbException {
        long indexId = btree.getMetadata().getIndexId();
        IndexDescriptor descriptor = descriptorCache.get(indexId);

        if (descriptor != null) {
            descriptor.updateFromMetadata(btree.getMetadata());
            updateDescriptor(descriptor, mtr);
        }
    }

    /**
     * 刷新所有索引元数据到磁盘
     *
     * @param mtr Mini-Transaction
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public void flushMetadata(MiniTransaction mtr) throws MiniDbException {
        for (Map.Entry<Long, BTree> entry : indexCache.entrySet()) {
            updateIndexMetadata(entry.getValue(), mtr);
        }
    }

    /**
     * 获取所有索引数量
     *
     * @return 索引数量
     */
    public int getIndexCount() {
        return descriptorCache.size();
    }

    /**
     * 获取所有表 ID
     *
     * <p>用于遍历所有表，例如在 Undo 压缩时扫描所有表的记录。</p>
     *
     * @return 所有表 ID 的集合
     */
    public java.util.Set<Long> getAllTableIds() {
        java.util.Set<Long> tableIds = new java.util.HashSet<>();
        for (IndexDescriptor desc : descriptorCache.values()) {
            if (!desc.isDeleted()) {
                tableIds.add(desc.getTableId());
            }
        }
        return tableIds;
    }

    /**
     * 检查索引是否存在
     *
     * @param indexId 索引 ID
     * @return 如果存在返回 true
     */
    public boolean indexExists(long indexId) {
        IndexDescriptor desc = descriptorCache.get(indexId);
        return desc != null && !desc.isDeleted();
    }

    /**
     * 检查索引是否存在（通过名称）
     *
     * @param tableId   表 ID
     * @param indexName 索引名称
     * @return 如果存在返回 true
     */
    public boolean indexExists(long tableId, String indexName) {
        return nameToIdMap.containsKey(makeNameKey(tableId, indexName));
    }

    // ==================== 私有方法 ====================

    private void ensureInitialized(MiniTransaction mtr) throws MiniDbException {
        if (!initialized) {
            initialize(mtr);
        }
    }

    private void loadDescriptors(BufferFrame frame) {
        List<IndexDescriptor> descriptors = IndexMetaPage.readAllDescriptors(frame);

        long maxId = 0;
        for (IndexDescriptor desc : descriptors) {
            descriptorCache.put(desc.getIndexId(), desc);
            nameToIdMap.put(makeNameKey(desc.getTableId(), desc.getIndexName()), desc.getIndexId());
            maxId = Math.max(maxId, desc.getIndexId());
        }

        nextIndexId.set(maxId + 1);
    }

    private void persistDescriptor(IndexDescriptor descriptor, MiniTransaction mtr)
            throws MiniDbException {
        PageId metaPageId = new PageId(spaceId, metaPageNo);
        BufferFrame frame = mtr.getPageFrame(metaPageId, BufferPool.FetchMode.READ_EXISTING);
        frame.writeLock();

        try {
            if (!IndexMetaPage.writeDescriptor(frame, descriptor)) {
                // 空间不足，需要分配新页面（简化处理：抛异常）
                throw new RuntimeException("Index metadata page is full");
            }
            mtr.markDirty(frame.getPage());
        } finally {
            frame.writeUnlock();
        }
    }

    private void updateDescriptor(IndexDescriptor descriptor, MiniTransaction mtr)
            throws MiniDbException {
        PageId metaPageId = new PageId(spaceId, metaPageNo);
        BufferFrame frame = mtr.getPageFrame(metaPageId, BufferPool.FetchMode.READ_EXISTING);
        frame.writeLock();

        try {
            IndexMetaPage.updateDescriptor(frame, descriptor);
            mtr.markDirty(frame.getPage());
        } finally {
            frame.writeUnlock();
        }
    }

    private String makeNameKey(long tableId, String indexName) {
        return tableId + ":" + indexName;
    }

    private CompositeKeyDef columnsToKeyDef(List<IndexDescriptor.ColumnDescriptor> columns) {
        List<KeyColumn> keyColumns = new ArrayList<>();
        for (IndexDescriptor.ColumnDescriptor col : columns) {
            keyColumns.add(col.toKeyColumn());
        }
        return new CompositeKeyDef(keyColumns);
    }
}
