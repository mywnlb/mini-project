package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.space.Segment;
import cn.zhangyis.minidb.storage.space.TableSpace;
import cn.zhangyis.minidb.storage.transaction.core.Transaction;
import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;
import cn.zhangyis.minidb.storage.transaction.purge.HistoryList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Undo Log 管理器
 *
 * <p>管理所有 Rollback Segment，为事务分配 Undo Segment，
 * 提供 Undo 记录的读写操作。</p>
 *
 * <h2>InnoDB Undo 架构</h2>
 * <pre>
 * UndoLogManager
 *     │
 *     ├─ TableSpace (Undo 表空间)
 *     │      └─ 物理空间管理 (Extent/Page 分配)
 *     │
 *     ├─ Segment[0..127] (每个 Rollback Segment 对应一个 Segment)
 *     │      └─ 物理页面分配 (碎片页 + Extent)
 *     │
 *     ├─ RollbackSegment[0]
 *     │      ├─ UndoSegment (Trx 100, INSERT)
 *     │      ├─ UndoSegment (Trx 100, UPDATE)
 *     │      └─ UndoSegment (Trx 105, INSERT)
 *     │
 *     ├─ RollbackSegment[1]
 *     │      └─ ...
 *     │
 *     └─ RollbackSegment[127]
 *            └─ ...
 * </pre>
 *
 * <h2>物理空间管理</h2>
 * <p>每个 Rollback Segment 对应一个 TableSpace 中的 Segment，
 * 使用 Segment 的物理分配能力管理 Undo Page：</p>
 * <ul>
 *   <li>前 32 页：从表空间碎片区分配单页</li>
 *   <li>之后：从 Segment 的 Extent 链表分配</li>
 *   <li>页面释放后可复用</li>
 * </ul>
 *
 * <h2>Rollback Segment 分配策略</h2>
 * <p>使用轮询方式分配，减少锁竞争：</p>
 * <pre>
 * rseg_id = (trx_id % num_rollback_segments)
 * </pre>
 *
 * <h2>设计约束 (Invariants)</h2>
 * <ul>
 *   <li><b>U5</b>: Rollback Segment 分配需要并发安全</li>
 *   <li><b>U7</b>: 每个事务的 INSERT/UPDATE Undo 分开管理</li>
 *   <li><b>U8</b>: Undo 记录写入必须在 MTR 提交前完成</li>
 *   <li><b>U9</b>: Undo Page 通过 Segment 物理分配，支持空间回收</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 初始化 Undo TableSpace（首次启动）
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     UndoLogManager.initializeUndoTablespace(mtr, bufferPool, spaceId);
 *     mtr.commit();
 * }
 *
 * // 创建 UndoLogManager（后续启动可恢复）
 * UndoLogManager undoMgr = new UndoLogManager(bufferPool, spaceId, 128);
 *
 * // 事务开始时分配 Undo Segment
 * Transaction trx = transactionManager.begin();
 * UndoSegment insertSeg = undoMgr.assignInsertSegment(trx);
 * UndoSegment updateSeg = undoMgr.assignUpdateSegment(trx);
 *
 * // 写入 Undo
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     RollbackPointer rollPtr = undoMgr.writeInsertUndo(mtr, trx, tableId, pk);
 *     // 使用 rollPtr 设置记录的 ROLL_PTR
 *     mtr.commit();
 * }
 *
 * // 事务提交时释放 Undo Segment
 * undoMgr.commitTransaction(trx);
 * }</pre>
 *
 * @author MiniDB
 * @version 2.0
 * @see UndoSegment
 * @see RollbackPointer
 * @see TableSpace
 * @see Segment
 */
public class UndoLogManager {

    private static final Logger logger = LoggerFactory.getLogger(UndoLogManager.class);

    // ==================== 常量 ====================

    /**
     * 默认 Rollback Segment 数量
     */
    public static final int DEFAULT_ROLLBACK_SEGMENTS = 128;

    /**
     * 最大 Rollback Segment 数量
     */
    public static final int MAX_ROLLBACK_SEGMENTS = 128;

    // ==================== 字段 ====================

    /**
     * Buffer Pool 引用
     */
    private final BufferPool bufferPool;

    /**
     * Undo 表空间 ID
     */
    private final int spaceId;

    /**
     * Rollback Segment 数量
     */
    private final int numRollbackSegments;

    /**
     * Rollback Segment 锁 (每个段一把锁)
     */
    private final ReentrantLock[] rsegLocks;

    /**
     * 事务到 INSERT UndoSegment 的映射
     */
    private final Map<TransactionId, UndoSegment> insertSegments;

    /**
     * 事务到 UPDATE UndoSegment 的映射
     */
    private final Map<TransactionId, UndoSegment> updateSegments;

    /**
     * Rollback Segment 轮询计数器
     */
    private final AtomicInteger rsegRoundRobin;

    /**
     * Undo 表空间
     *
     * <p>管理 Undo Page 的物理分配。每个 Rollback Segment 对应一个 Segment。</p>
     */
    private final TableSpace undoTableSpace;

    /**
     * Rollback Segment 对应的物理 Segment 数组
     *
     * <p>每个 Rollback Segment 使用一个独立的 Segment 进行页面分配。
     * 支持 InnoDB 的 32 碎片页策略和 Extent 分配。</p>
     */
    private final Segment[] rollbackSegmentPhysical;

    /**
     * 记录每个 Rollback Segment 的 Segment ID (用于恢复)
     */
    private final long[] rollbackSegmentIds;

    /**
     * History List（已提交事务的 UPDATE Undo 按序管理）
     *
     * <p>事务提交时将 UPDATE Undo Segment 加入 History List，
     * Purge 线程按提交顺序清理。</p>
     */
    private final HistoryList historyList;

    /**
     * Undo Page 本地缓存
     *
     * <p>缓存热点 Undo Page，减少 Buffer Pool 查找开销。
     * 主要用于 MVCC 版本链遍历和事务回滚。</p>
     */
    private final UndoPageCache pageCache;

    /**
     * Undo Page 池数组 (每个 Rollback Segment 一个池)
     *
     * <p>为每个 Rollback Segment 维护两层页面池：
     * <ul>
     *   <li>freshPages: 从未写过的页面，可直接分配</li>
     *   <li>reusablePages: Purge 后变空的页面，可重用</li>
     * </ul>
     * 将 Undo 页面分配从 O(log n) 降低到 O(1)。</p>
     */
    private final UndoPagePool[] undoPagePools;

    // ==================== 构造函数 ====================

    /**
     * 创建 Undo Log Manager（从已初始化的表空间恢复）
     *
     * <p>此构造函数用于数据库启动时恢复 Undo 系统。
     * 表空间必须已通过 {@link #initializeUndoTablespace} 初始化。</p>
     *
     * @param bufferPool Buffer Pool 实例
     * @param spaceId    Undo 表空间 ID
     * @throws MiniDbException 如果恢复失败
     */
    public UndoLogManager(BufferPool bufferPool, int spaceId) throws MiniDbException {
        this(bufferPool, spaceId, DEFAULT_ROLLBACK_SEGMENTS);
    }

    /**
     * 创建 Undo Log Manager（从已初始化的表空间恢复）
     *
     * <p>此构造函数用于数据库启动时恢复 Undo 系统。
     * 表空间必须已通过 {@link #initializeUndoTablespace} 初始化。</p>
     *
     * @param bufferPool          Buffer Pool 实例
     * @param spaceId             Undo 表空间 ID
     * @param numRollbackSegments Rollback Segment 数量
     * @throws MiniDbException 如果恢复失败
     */
    public UndoLogManager(BufferPool bufferPool, int spaceId,
                          int numRollbackSegments) throws MiniDbException {
        if (bufferPool == null) {
            throw new NullPointerException("bufferPool cannot be null");
        }
        if (numRollbackSegments <= 0 || numRollbackSegments > MAX_ROLLBACK_SEGMENTS) {
            throw new IllegalArgumentException("Invalid numRollbackSegments: " + numRollbackSegments);
        }

        this.bufferPool = bufferPool;
        this.spaceId = spaceId;
        this.numRollbackSegments = numRollbackSegments;

        // 初始化 Rollback Segment 锁
        this.rsegLocks = new ReentrantLock[numRollbackSegments];
        for (int i = 0; i < numRollbackSegments; i++) {
            rsegLocks[i] = new ReentrantLock();
        }

        this.insertSegments = new ConcurrentHashMap<>();
        this.updateSegments = new ConcurrentHashMap<>();
        this.rsegRoundRobin = new AtomicInteger(0);

        // 创建 Undo TableSpace
        this.undoTableSpace = new TableSpace(spaceId, bufferPool);

        // 初始化 Rollback Segment 物理数组
        this.rollbackSegmentPhysical = new Segment[numRollbackSegments];
        this.rollbackSegmentIds = new long[numRollbackSegments];

        // 初始化 History List
        this.historyList = new HistoryList();

        // 初始化 Undo Page 缓存
        this.pageCache = new UndoPageCache();

        // 初始化 Undo Page 池
        this.undoPagePools = new UndoPagePool[numRollbackSegments];
        for (int i = 0; i < numRollbackSegments; i++) {
            undoPagePools[i] = new UndoPagePool(i);
        }

        // 恢复或创建 Rollback Segment 对应的 Segment
        initializeRollbackSegments();

        logger.info("UndoLogManager initialized: spaceId={}, rsegCount={}",
                spaceId, numRollbackSegments);
    }

    /**
     * 内部构造函数（用于测试，跳过 Segment 初始化）
     *
     * @param bufferPool          Buffer Pool 实例
     * @param spaceId             Undo 表空间 ID
     * @param numRollbackSegments Rollback Segment 数量
     * @param skipSegmentInit     是否跳过 Segment 初始化
     */
    UndoLogManager(BufferPool bufferPool, int spaceId,
                   int numRollbackSegments, boolean skipSegmentInit) {
        if (bufferPool == null) {
            throw new NullPointerException("bufferPool cannot be null");
        }
        if (numRollbackSegments <= 0 || numRollbackSegments > MAX_ROLLBACK_SEGMENTS) {
            throw new IllegalArgumentException("Invalid numRollbackSegments: " + numRollbackSegments);
        }

        this.bufferPool = bufferPool;
        this.spaceId = spaceId;
        this.numRollbackSegments = numRollbackSegments;

        // 初始化 Rollback Segment 锁
        this.rsegLocks = new ReentrantLock[numRollbackSegments];
        for (int i = 0; i < numRollbackSegments; i++) {
            rsegLocks[i] = new ReentrantLock();
        }

        this.insertSegments = new ConcurrentHashMap<>();
        this.updateSegments = new ConcurrentHashMap<>();
        this.rsegRoundRobin = new AtomicInteger(0);

        // 创建 Undo TableSpace
        this.undoTableSpace = new TableSpace(spaceId, bufferPool);

        // 初始化 Rollback Segment 物理数组（但不创建 Segment）
        this.rollbackSegmentPhysical = new Segment[numRollbackSegments];
        this.rollbackSegmentIds = new long[numRollbackSegments];

        // 初始化 History List
        this.historyList = new HistoryList();

        // 初始化 Undo Page 缓存
        this.pageCache = new UndoPageCache();

        // 初始化 Undo Page 池
        this.undoPagePools = new UndoPagePool[numRollbackSegments];
        for (int i = 0; i < numRollbackSegments; i++) {
            undoPagePools[i] = new UndoPagePool(i);
        }

        if (!skipSegmentInit) {
            try {
                initializeRollbackSegments();
            } catch (MiniDbException e) {
                throw new RuntimeException("Failed to initialize rollback segments", e);
            }
        }

        logger.info("UndoLogManager initialized (skipSegmentInit={}): spaceId={}, rsegCount={}",
                skipSegmentInit, spaceId, numRollbackSegments);
    }

    /**
     * 初始化 Rollback Segment 对应的物理 Segment
     *
     * <p>为每个 Rollback Segment 创建或恢复一个 TableSpace Segment。</p>
     *
     * @throws MiniDbException 如果操作失败
     */
    private void initializeRollbackSegments() throws MiniDbException {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            for (int i = 0; i < numRollbackSegments; i++) {
                // 创建新的 Segment
                Segment segment = undoTableSpace.createSegment(mtr);
                if (segment == null) {
                    throw new MiniDbException("Failed to create segment for rollback segment " + i);
                }
                rollbackSegmentPhysical[i] = segment;
                rollbackSegmentIds[i] = segment.getSegmentId();

                logger.debug("Created physical segment for rseg {}: segmentId={}",
                        i, segment.getSegmentId());
            }
            mtr.commit();
        }

        logger.info("Initialized {} rollback segments with physical segments", numRollbackSegments);
    }

    /**
     * 初始化 Undo 表空间（首次启动时调用）
     *
     * <p>创建并初始化 Undo 表空间的物理结构。此方法应在数据库首次启动时调用。</p>
     *
     * @param mtr        Mini-Transaction
     * @param bufferPool Buffer Pool
     * @param spaceId    Undo 表空间 ID
     * @throws MiniDbException 如果初始化失败
     */
    public static void initializeUndoTablespace(MiniTransaction mtr, BufferPool bufferPool,
                                                 int spaceId) throws MiniDbException {
        TableSpace undoTableSpace = new TableSpace(spaceId, bufferPool);
        undoTableSpace.initializeTablespace(mtr);
        logger.info("Initialized Undo tablespace: spaceId={}", spaceId);
    }

    // ==================== Segment 分配方法 ====================

    /**
     * 为事务分配 INSERT Undo Segment
     *
     * @param trx 事务
     * @return INSERT Undo Segment
     */
    public UndoSegment assignInsertSegment(Transaction trx) {
        return assignSegment(trx, true);
    }

    /**
     * 为事务分配 UPDATE Undo Segment
     *
     * @param trx 事务
     * @return UPDATE Undo Segment
     */
    public UndoSegment assignUpdateSegment(Transaction trx) {
        return assignSegment(trx, false);
    }

    /**
     * 分配 Undo Segment
     *
     * @param trx      事务
     * @param isInsert 是否是 INSERT Undo
     * @return Undo Segment
     */
    private UndoSegment assignSegment(Transaction trx, boolean isInsert) {
        TransactionId trxId = trx.getId();
        Map<TransactionId, UndoSegment> segmentMap = isInsert ? insertSegments : updateSegments;

        // 检查是否已分配
        UndoSegment existing = segmentMap.get(trxId);
        if (existing != null) {
            return existing;
        }

        // 分配 Rollback Segment ID (轮询方式)
        int rsegId = selectRollbackSegment(trxId);

        // 创建新的 Undo Segment
        UndoSegment segment = new UndoSegment(trxId, rsegId, isInsert);
        segmentMap.put(trxId, segment);

        logger.debug("Assigned {} UndoSegment: trxId={}, rsegId={}",
                isInsert ? "INSERT" : "UPDATE", trxId, rsegId);

        return segment;
    }

    /**
     * 选择 Rollback Segment
     *
     * @param trxId 事务 ID
     * @return Rollback Segment ID
     */
    private int selectRollbackSegment(TransactionId trxId) {
        // 使用事务 ID 的哈希值选择，保证同一事务的分配稳定
        // 同时使用轮询避免热点
        int hash = (int) (trxId.getValue() % numRollbackSegments);
        return hash >= 0 ? hash : hash + numRollbackSegments;
    }

    // ==================== Undo 写入方法 ====================

    /**
     * 写入 INSERT Undo 记录
     *
     * @param mtr     Mini-Transaction
     * @param trx     事务
     * @param tableId 表 ID
     * @param pk      主键数据
     * @return RollbackPointer 指向写入的记录
     * @throws MiniDbException 如果写入失败
     */
    public RollbackPointer writeInsertUndo(MiniTransaction mtr, Transaction trx,
                                           int tableId, byte[] pk) throws MiniDbException {
        UndoSegment segment = getOrAssignInsertSegment(trx);
        InsertUndoRecord undoRecord = new InsertUndoRecord(trx.getId(), tableId, pk);

        return writeUndoRecord(mtr, segment, undoRecord);
    }

    /**
     * 写入 UPDATE Undo 记录
     *
     * @param mtr         Mini-Transaction
     * @param trx         事务
     * @param tableId     表 ID
     * @param prevRollPtr 上一版本的 ROLL_PTR
     * @param pk          主键数据
     * @param oldColumns  旧列值
     * @return RollbackPointer 指向写入的记录
     * @throws MiniDbException 如果写入失败
     */
    public RollbackPointer writeUpdateUndo(MiniTransaction mtr, Transaction trx,
                                           int tableId, RollbackPointer prevRollPtr,
                                           byte[] pk,
                                           java.util.List<UpdateUndoRecord.OldColumnValue> oldColumns)
            throws MiniDbException {
        UndoSegment segment = getOrAssignUpdateSegment(trx);
        UpdateUndoRecord undoRecord = new UpdateUndoRecord(
                trx.getId(), tableId, prevRollPtr, pk, oldColumns);

        return writeUndoRecord(mtr, segment, undoRecord);
    }

    /**
     * 写入 DELETE Undo 记录
     *
     * @param mtr         Mini-Transaction
     * @param trx         事务
     * @param tableId     表 ID
     * @param prevRollPtr 上一版本的 ROLL_PTR
     * @param pk          主键数据
     * @param oldRowData  完整旧行数据
     * @return RollbackPointer 指向写入的记录
     * @throws MiniDbException 如果写入失败
     */
    public RollbackPointer writeDeleteUndo(MiniTransaction mtr, Transaction trx,
                                           int tableId, RollbackPointer prevRollPtr,
                                           byte[] pk, byte[] oldRowData)
            throws MiniDbException {
        UndoSegment segment = getOrAssignUpdateSegment(trx);
        DeleteUndoRecord undoRecord = new DeleteUndoRecord(
                trx.getId(), tableId, prevRollPtr, pk, oldRowData);

        return writeUndoRecord(mtr, segment, undoRecord);
    }

    /**
     * 写入 Undo 记录到 Segment
     *
     * @param mtr        Mini-Transaction
     * @param segment    Undo Segment
     * @param undoRecord Undo 记录
     * @return RollbackPointer
     * @throws MiniDbException 如果写入失败
     */
    private RollbackPointer writeUndoRecord(MiniTransaction mtr, UndoSegment segment,
                                            UndoRecord undoRecord) throws MiniDbException {
        int rsegId = segment.getRsegId();

        // 获取 Rollback Segment 锁
        rsegLocks[rsegId].lock();
        try {
            // 使用 UndoSegment 的 writeUndoRecord 方法
            RollbackPointer rollPtr = segment.writeUndoRecord(
                    undoRecord,
                    (undoType, trxId, rsId) -> allocateUndoPage(mtr, undoType, trxId, rsId)
            );

            logger.trace("Wrote undo record: type={}, trxId={}, rollPtr={}",
                    undoRecord.getType(), undoRecord.getTrxId(), rollPtr);

            return rollPtr;

        } finally {
            rsegLocks[rsegId].unlock();
        }
    }

    /**
     * 分配 Undo Page
     *
     * <p>优先从 UndoPagePool 中分配页面（O(1)），如果池为空则从 Segment 分配新页。
     * 遵循 InnoDB 的 32 碎片页策略：前 32 页从碎片区分配，之后从 Extent 分配。</p>
     *
     * <h2>分配优先级</h2>
     * <ol>
     *   <li>从 UndoPagePool 的 freshPages 取（从未写过）</li>
     *   <li>从 UndoPagePool 的 reusablePages 取（已清空，可复用）</li>
     *   <li>从 Segment 分配新页（最后才扩展）</li>
     * </ol>
     *
     * @param mtr      Mini-Transaction
     * @param undoType Undo 类型
     * @param trxId    事务 ID
     * @param rsegId   Rollback Segment ID
     * @return 页面信息
     */
    private UndoSegment.UndoPageInfo allocateUndoPage(MiniTransaction mtr,
                                                       int undoType,
                                                       TransactionId trxId,
                                                       int rsegId) {
        try {
            // 优先从池中分配页面
            UndoPagePool pool = undoPagePools[rsegId];
            PageId pooledPageId = pool.allocateUndoPage();

            Page page;
            if (pooledPageId != null) {
                // 从池中获取页面
                page = bufferPool.getPage(pooledPageId);
                logger.trace("Allocated undo page from pool: pageId={}, rsegId={}", pooledPageId, rsegId);
            } else {
                // 池为空，从 Segment 分配新页
                Segment physicalSegment = rollbackSegmentPhysical[rsegId];
                if (physicalSegment == null) {
                    throw new MiniDbException("Physical segment not initialized for rseg " + rsegId);
                }

                page = physicalSegment.allocatePage(mtr);
                if (page == null) {
                    throw new MiniDbException("Failed to allocate undo page from segment");
                }

                logger.trace("Allocated new undo page from Segment: pageNo={}, rsegId={}",
                        page.getPageNo(), rsegId);
            }

            int pageNo = page.getPageNo();
            ByteBuffer buf = page.getBuffer();
            buf.order(ByteOrder.LITTLE_ENDIAN);

            // 初始化 Undo Page
            UndoPage.init(buf, undoType, trxId, rsegId);

            // 标记脏页
            mtr.markDirty(page);

            logger.debug("Allocated undo page via Segment: pageNo={}, undoType={}, trxId={}, rsegId={}",
                    pageNo, undoType, trxId, rsegId);

            return new UndoSegment.UndoPageInfo(pageNo, buf);

        } catch (MiniDbException e) {
            logger.error("Failed to allocate undo page for rseg {}", rsegId, e);
            throw new RuntimeException("Failed to allocate undo page", e);
        }
    }

    /**
     * 释放 Undo Page
     *
     * <p>将 Undo Page 放回 UndoPagePool 的 reusablePages 队列。
     * 页面已被 Purge 清空，可以被后续事务复用。
     *
     * 注意：此方法不做真实释放，只做复用。真实释放由 Segment 管理。</p>
     *
     * @param mtr    Mini-Transaction
     * @param rsegId Rollback Segment ID
     * @param pageNo 要释放的页号
     * @throws MiniDbException 如果释放失败
     */
    private void freeUndoPage(MiniTransaction mtr, int rsegId, int pageNo) throws MiniDbException {
        if (rsegId < 0 || rsegId >= numRollbackSegments) {
            throw new IllegalArgumentException("Invalid rsegId: " + rsegId);
        }

        // 将页面放回池中复用
        UndoPagePool pool = undoPagePools[rsegId];
        PageId pageId = PageId.of(spaceId, pageNo);
        pool.addReusablePage(pageId);

        logger.debug("Freed undo page to pool: pageNo={}, rsegId={}", pageNo, rsegId);
    }

    /**
     * 批量释放 Undo Page
     *
     * <p>释放指定 Rollback Segment 中的多个 Undo Page。</p>
     *
     * @param mtr     Mini-Transaction
     * @param rsegId  Rollback Segment ID
     * @param pageNos 要释放的页号列表
     * @throws MiniDbException 如果释放失败
     */
    private void freeUndoPages(MiniTransaction mtr, int rsegId, List<Integer> pageNos) throws MiniDbException {
        if (pageNos == null || pageNos.isEmpty()) {
            return;
        }

        for (int pageNo : pageNos) {
            freeUndoPage(mtr, rsegId, pageNo);
        }

        logger.debug("Freed {} undo pages for rsegId={}", pageNos.size(), rsegId);
    }

    // ==================== Undo 读取方法 ====================

    /**
     * 读取指定位置的 Undo 记录
     *
     * @param rollPtr 回滚指针
     * @return Undo 记录，如果不存在返回 null
     * @throws MiniDbException 如果读取失败
     */
    public UndoRecord readUndoRecord(RollbackPointer rollPtr) throws MiniDbException {
        if (rollPtr == null || rollPtr.isNull()) {
            return null;
        }

        PageId pageId = PageId.of(spaceId, rollPtr.getPageNo());

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            Page page = mtr.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
            ByteBuffer buf = page.getBuffer();

            UndoRecord record = UndoPage.readUndoRecord(buf, rollPtr.getOffset());
            mtr.commit();

            return record;
        }
    }

    /**
     * 创建 Undo 记录读取器 (用于 VersionChainReader)
     *
     * @return Undo 记录读取器
     */
    public cn.zhangyis.minidb.storage.transaction.mvcc.VersionChainReader.UndoRecordReader
    createUndoRecordReader() {
        return rollPtr -> {
            try {
                return readUndoRecord(rollPtr);
            } catch (MiniDbException e) {
                logger.warn("Failed to read undo record: {}", rollPtr, e);
                return null;
            }
        };
    }

    // ==================== 事务生命周期方法 ====================

    /**
     * 事务提交时调用
     *
     * <p>标记 Undo Segment 为已提交状态。
     * INSERT Undo 页面会被立即释放并归还给 Segment，供后续事务复用。</p>
     *
     * @param trx 事务
     */
    public void commitTransaction(Transaction trx) {
        TransactionId trxId = trx.getId();

        UndoSegment insertSeg = insertSegments.get(trxId);
        if (insertSeg != null) {
            insertSeg.markCommitted();

            // INSERT Undo 可以立即释放：释放所有页面
            List<Integer> pageList = insertSeg.getPageList();
            if (!pageList.isEmpty()) {
                int rsegId = insertSeg.getRsegId();
                rsegLocks[rsegId].lock();
                try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                    freeUndoPages(mtr, rsegId, pageList);
                    mtr.commit();
                    logger.debug("Freed {} INSERT undo pages for trxId={}", pageList.size(), trxId);
                } catch (MiniDbException e) {
                    logger.warn("Failed to free INSERT undo pages for trxId={}: {}", trxId, e.getMessage());
                    // 页面释放失败不影响事务提交，页面会成为"泄漏"但不影响正确性
                } finally {
                    rsegLocks[rsegId].unlock();
                }
            }

            insertSegments.remove(trxId);
            logger.debug("Committed INSERT UndoSegment: trxId={}", trxId);
        }

        UndoSegment updateSeg = updateSegments.get(trxId);
        if (updateSeg != null) {
            updateSeg.markCommitted();
            // UPDATE Undo 需要保留供 MVCC (由 Purge 线程稍后清理)
            // 添加到 History List，按提交顺序管理
            historyList.add(trxId, updateSeg.getRsegId());
            logger.debug("Committed UPDATE UndoSegment: trxId={}, added to history list", trxId);
        }
    }

    /**
     * 事务回滚时调用
     *
     * <p>返回事务的 Undo 链用于执行回滚操作。
     * 回滚完成后，INSERT 和 UPDATE Undo 页面都会被释放。</p>
     *
     * @param trx 事务
     * @return 需要回滚的 Undo 记录迭代器 (INSERT + UPDATE 混合)
     */
    public Iterable<UndoRecord> rollbackTransaction(Transaction trx) {
        TransactionId trxId = trx.getId();

        // 创建合并迭代器 (先回滚 UPDATE，再回滚 INSERT)
        List<UndoRecord> records = new ArrayList<>();

        // 收集需要释放的页面
        List<PageReleaseInfo> pagesToRelease = new ArrayList<>();

        UndoSegment updateSeg = updateSegments.get(trxId);
        if (updateSeg != null) {
            for (UndoRecord rec : updateSeg.reverseIterate(this::readPageBuffer)) {
                records.add(rec);
            }
            updateSeg.markRolledBack();
            pagesToRelease.add(new PageReleaseInfo(updateSeg.getRsegId(), updateSeg.getPageList()));
            updateSegments.remove(trxId);
        }

        UndoSegment insertSeg = insertSegments.get(trxId);
        if (insertSeg != null) {
            for (UndoRecord rec : insertSeg.reverseIterate(this::readPageBuffer)) {
                records.add(rec);
            }
            insertSeg.markRolledBack();
            pagesToRelease.add(new PageReleaseInfo(insertSeg.getRsegId(), insertSeg.getPageList()));
            insertSegments.remove(trxId);
        }

        // 释放所有 Undo 页面（回滚后可立即释放）
        for (PageReleaseInfo releaseInfo : pagesToRelease) {
            if (!releaseInfo.pageNos.isEmpty()) {
                rsegLocks[releaseInfo.rsegId].lock();
                try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                    freeUndoPages(mtr, releaseInfo.rsegId, releaseInfo.pageNos);
                    mtr.commit();
                    logger.debug("Freed {} undo pages after rollback for trxId={}",
                            releaseInfo.pageNos.size(), trxId);
                } catch (MiniDbException e) {
                    logger.warn("Failed to free undo pages after rollback for trxId={}: {}",
                            trxId, e.getMessage());
                } finally {
                    rsegLocks[releaseInfo.rsegId].unlock();
                }
            }
        }

        return records;
    }

    /**
     * 页面释放信息（内部类）
     */
    private static class PageReleaseInfo {
        final int rsegId;
        final List<Integer> pageNos;

        PageReleaseInfo(int rsegId, List<Integer> pageNos) {
            this.rsegId = rsegId;
            this.pageNos = pageNos;
        }
    }

    /**
     * 读取页面缓冲区 (供 UndoSegment 遍历使用)
     *
     * @param pageNo 页号
     * @return 页面缓冲区
     */
    private ByteBuffer readPageBuffer(int pageNo) {
        try {
            PageId pageId = PageId.of(spaceId, pageNo);
            BufferFrame frame = bufferPool.getPage(pageId, BufferPool.FetchMode.READ_EXISTING);
            return frame.buffer();
        } catch (MiniDbException e) {
            logger.error("Failed to read undo page: {}", pageNo, e);
            throw new RuntimeException("Failed to read undo page: " + pageNo, e);
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取或分配 INSERT Undo Segment
     */
    private UndoSegment getOrAssignInsertSegment(Transaction trx) {
        UndoSegment segment = insertSegments.get(trx.getId());
        if (segment == null) {
            segment = assignInsertSegment(trx);
        }
        return segment;
    }

    /**
     * 获取或分配 UPDATE Undo Segment
     */
    private UndoSegment getOrAssignUpdateSegment(Transaction trx) {
        UndoSegment segment = updateSegments.get(trx.getId());
        if (segment == null) {
            segment = assignUpdateSegment(trx);
        }
        return segment;
    }

    // ==================== Purge 支持 ====================

    /**
     * 清理已提交事务的 UPDATE Undo（供 Purge 线程调用）
     *
     * <p>当 Purge 线程确定一个 UPDATE Undo Segment 不再被任何活跃 ReadView 需要时，
     * 调用此方法释放其占用的页面。</p>
     *
     * @param trxId 事务 ID
     * @return 是否成功释放
     */
    public boolean purgeUpdateUndo(TransactionId trxId) {
        UndoSegment updateSeg = updateSegments.get(trxId);
        if (updateSeg == null) {
            return false;  // 事务不存在或已被清理
        }

        if (updateSeg.getState() != UndoSegment.State.COMMITTED) {
            logger.warn("Cannot purge uncommitted UPDATE undo for trxId={}", trxId);
            return false;
        }

        List<Integer> pageList = updateSeg.getPageList();
        if (!pageList.isEmpty()) {
            int rsegId = updateSeg.getRsegId();
            rsegLocks[rsegId].lock();
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                freeUndoPages(mtr, rsegId, pageList);
                mtr.commit();
                logger.debug("Purged {} UPDATE undo pages for trxId={}", pageList.size(), trxId);
            } catch (MiniDbException e) {
                logger.warn("Failed to purge UPDATE undo pages for trxId={}: {}", trxId, e.getMessage());
                return false;
            } finally {
                rsegLocks[rsegId].unlock();
            }
        }

        updateSeg.markPurged();
        updateSegments.remove(trxId);
        return true;
    }

    /**
     * 获取可被 Purge 的 UPDATE Undo Segment 列表
     *
     * <p>返回所有已提交且可安全清理的 UPDATE Undo Segment 的事务 ID。
     * 列表按提交顺序排列（通过 History List）。</p>
     *
     * @return 可 Purge 的事务 ID 列表（按提交顺序）
     */
    public List<TransactionId> getPurgableUpdateSegments() {
        // 优先使用 History List 获取按提交顺序的列表
        if (!historyList.isEmpty()) {
            List<TransactionId> purgable = new ArrayList<>();
            for (HistoryList.HistoryEntry entry : historyList.getPurgableEntries(
                    new TransactionId(Long.MAX_VALUE), Integer.MAX_VALUE)) {
                purgable.add(entry.getTrxId());
            }
            return purgable;
        }

        // 回退到遍历 updateSegments（无序）
        List<TransactionId> purgable = new ArrayList<>();
        for (Map.Entry<TransactionId, UndoSegment> entry : updateSegments.entrySet()) {
            if (entry.getValue().getState() == UndoSegment.State.COMMITTED) {
                purgable.add(entry.getKey());
            }
        }
        return purgable;
    }

    /**
     * 获取 History List
     *
     * <p>供 PurgeThread 使用，按提交顺序获取可清理的事务。</p>
     *
     * @return History List
     */
    public HistoryList getHistoryList() {
        return historyList;
    }

    /**
     * 获取 History List 长度
     *
     * @return 待清理的已提交事务数量
     */
    public int getHistoryListLength() {
        return historyList.size();
    }

    // ==================== 统计信息 ====================

    /**
     * 获取活跃 INSERT Segment 数量
     *
     * @return 数量
     */
    public int getActiveInsertSegmentCount() {
        return insertSegments.size();
    }

    /**
     * 获取活跃 UPDATE Segment 数量
     *
     * @return 数量
     */
    public int getActiveUpdateSegmentCount() {
        return updateSegments.size();
    }

    /**
     * 获取已分配的 Undo Page 数量（估算）
     *
     * <p>返回所有 Rollback Segment 的估算页面总数。</p>
     *
     * @return 估算的页面数量
     */
    public int getAllocatedPageCount() {
        int total = 0;
        for (Segment segment : rollbackSegmentPhysical) {
            if (segment != null) {
                total += segment.getEstimatedPageCount();
            }
        }
        return total;
    }

    /**
     * 获取 Rollback Segment 数量
     *
     * @return 数量
     */
    public int getNumRollbackSegments() {
        return numRollbackSegments;
    }

    /**
     * 获取指定 Rollback Segment 的 Undo Page 池
     *
     * <p>用于后台 refiller 线程补充页面。</p>
     *
     * @param rsegId Rollback Segment ID
     * @return Undo Page 池
     */
    public UndoPagePool getUndoPagePool(int rsegId) {
        if (rsegId < 0 || rsegId >= numRollbackSegments) {
            throw new IllegalArgumentException("Invalid rsegId: " + rsegId);
        }
        return undoPagePools[rsegId];
    }

    /**
     * 获取所有 Undo Page 池
     *
     * @return Undo Page 池数组
     */
    public UndoPagePool[] getAllUndoPagePools() {
        return undoPagePools;
    }

    /**
     * 向指定 Rollback Segment 的池中添加 freshPage
     *
     * <p>此方法由后台 refiller 线程调用，用于补充池中的页面。</p>
     *
     * @param rsegId Rollback Segment ID
     * @param pageNo 页号
     */
    public void addFreshPageToPool(int rsegId, int pageNo) {
        if (rsegId < 0 || rsegId >= numRollbackSegments) {
            throw new IllegalArgumentException("Invalid rsegId: " + rsegId);
        }
        PageId pageId = PageId.of(spaceId, pageNo);
        undoPagePools[rsegId].addFreshPage(pageId);
    }

    // ==================== 关闭方法 ====================

    /**
     * 关闭 Undo Log Manager
     *
     * <p>在数据库关闭时调用，确保所有活跃事务已处理。</p>
     */
    public void close() {
        int activeInsert = insertSegments.size();
        int activeUpdate = updateSegments.size();

        if (activeInsert > 0 || activeUpdate > 0) {
            logger.warn("UndoLogManager closing with active segments: insert={}, update={}",
                    activeInsert, activeUpdate);
        }

        insertSegments.clear();
        updateSegments.clear();

        logger.info("UndoLogManager closed: allocatedPages={}", getAllocatedPageCount());
    }

    @Override
    public String toString() {
        return String.format("UndoLogManager{spaceId=%d, rsegCount=%d, " +
                        "insertSegs=%d, updateSegs=%d, pages=%d}",
                spaceId, numRollbackSegments,
                insertSegments.size(), updateSegments.size(),
                getAllocatedPageCount());
    }
}
