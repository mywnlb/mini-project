package cn.zhangyis.minidb.storage.page;


import cn.zhangyis.minidb.storage.constants.StorageConstants;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * InnoDB B+Tree 索引页 (INDEX Page)
 * 
 * <p>IndexPage 是 InnoDB 中最重要的页面类型，用于存储 B+Tree 索引的节点数据。
 * 包括聚簇索引（存储完整行数据）和二级索引（存储索引列+主键）。</p>
 * 
 * <h2>页面物理布局</h2>
 * <pre>
 * +-------------------------------------------+  Offset 0
 * |           FIL Header (38 bytes)           |  (继承自 Page)
 * +-------------------------------------------+  Offset 38
 * |          Page Header (56 bytes)           |  INDEX 页特有
 * |  +-------------------------------------+  |
 * |  | PAGE_N_DIR_SLOTS   (2) 槽数量       |  |
 * |  | PAGE_HEAP_TOP      (2) 堆顶位置     |  |
 * |  | PAGE_N_HEAP        (2) 堆中记录数   |  |
 * |  | PAGE_FREE          (2) 空闲链表头   |  |
 * |  | PAGE_GARBAGE       (2) 垃圾字节数   |  |
 * |  | PAGE_LAST_INSERT   (2) 最后插入位置 |  |
 * |  | PAGE_DIRECTION     (2) 插入方向     |  |
 * |  | PAGE_N_DIRECTION   (2) 同方向次数   |  |
 * |  | PAGE_N_RECS        (2) 用户记录数   |  |
 * |  | PAGE_MAX_TRX_ID    (8) 最大事务ID   |  |
 * |  | PAGE_LEVEL         (2) B+Tree层级   |  |
 * |  | PAGE_INDEX_ID      (8) 索引ID       |  |
 * |  | PAGE_BTR_SEG_LEAF  (10) 叶段信息    |  |
 * |  | PAGE_BTR_SEG_TOP   (10) 非叶段信息  |  |
 * |  +-------------------------------------+  |
 * +-------------------------------------------+  Offset 94
 * |         Infimum Record (13 bytes)         |  虚拟最小记录
 * +-------------------------------------------+  Offset 107
 * |         Supremum Record (13 bytes)        |  虚拟最大记录
 * +-------------------------------------------+  Offset 120
 * |                                           |
 * |          User Records (向下增长)          |  实际数据记录
 * |                 ↓                         |
 * +- - - - - - - - - - - - - - - - - - - - - -+
 * |              Free Space                   |
 * +- - - - - - - - - - - - - - - - - - - - - -+
 * |                 ↑                         |
 * |        Page Directory (向上增长)          |  槽数组
 * |                                           |
 * +-------------------------------------------+  Offset 16376
 * |           FIL Trailer (8 bytes)           |
 * +-------------------------------------------+  Offset 16384
 * </pre>
 * 
 * <h2>记录组织</h2>
 * <p>页内记录通过单向链表连接：Infimum → 用户记录1 → 用户记录2 → ... → Supremum</p>
 * <p>记录按主键顺序排列，便于范围查询。</p>
 * 
 * <h2>Page Directory</h2>
 * <p>为了加速页内查找，每隔 4-8 条记录设置一个槽 (slot)，形成稀疏索引。
 * 查找时先二分查找槽，再在槽内顺序扫描。</p>
 * 
 * <h2>InnoDB 源码参考</h2>
 * <ul>
 *   <li>page0page.h - 页面结构定义</li>
 *   <li>page0cur.h - 页内游标操作</li>
 * </ul>
 * 
 * @author MiniDB
 * @version 1.0
 * @see Page
 */
public class IndexPage extends Page {

    // ==================== Page Header 偏移量 ====================
    // 注意：这些常量已迁移到 IndexPageLayout，此处保留供向后兼容

    /** Page Header 起始位置 (紧接 FIL Header 之后)
     * @deprecated 使用 {@link IndexPageLayout#PAGE_HEADER_START} 代替
     */
    @Deprecated
    private static final int PAGE_HEADER_START = StorageConstants.FIL_HEADER_SIZE;

    /**
     * Page Directory 槽数量 (2 bytes)
     * @deprecated 使用 {@link IndexPageLayout#PAGE_N_DIR_SLOTS} 代替
     */
    @Deprecated
    public static final int PAGE_N_DIR_SLOTS = PAGE_HEADER_START;

    /**
     * 堆顶位置 (2 bytes)
     * @deprecated 使用 {@link IndexPageLayout#PAGE_HEAP_TOP} 代替
     */
    @Deprecated
    public static final int PAGE_HEAP_TOP = PAGE_HEADER_START + 2;

    /**
     * 堆中记录数 (2 bytes)
     * @deprecated 使用 {@link IndexPageLayout#PAGE_N_HEAP} 代替
     */
    @Deprecated
    public static final int PAGE_N_HEAP = PAGE_HEADER_START + 4;

    /**
     * 空闲链表头 (2 bytes)
     * @deprecated 使用 {@link IndexPageLayout#PAGE_FREE} 代替
     */
    @Deprecated
    public static final int PAGE_FREE = PAGE_HEADER_START + 6;

    /**
     * 垃圾空间大小 (2 bytes)
     * @deprecated 使用 {@link IndexPageLayout#PAGE_GARBAGE} 代替
     */
    @Deprecated
    public static final int PAGE_GARBAGE = PAGE_HEADER_START + 8;

    /**
     * 最后插入位置 (2 bytes)
     * @deprecated 使用 {@link IndexPageLayout#PAGE_LAST_INSERT} 代替
     */
    @Deprecated
    public static final int PAGE_LAST_INSERT = PAGE_HEADER_START + 10;

    /**
     * 插入方向 (2 bytes)
     * @deprecated 使用 {@link IndexPageLayout#PAGE_DIRECTION} 代替
     */
    @Deprecated
    public static final int PAGE_DIRECTION = PAGE_HEADER_START + 12;

    /**
     * 同方向连续插入次数 (2 bytes)
     * @deprecated 使用 {@link IndexPageLayout#PAGE_N_DIRECTION} 代替
     */
    @Deprecated
    public static final int PAGE_N_DIRECTION = PAGE_HEADER_START + 14;

    /**
     * 用户记录数 (2 bytes)
     * @deprecated 使用 {@link IndexPageLayout#PAGE_N_RECS} 代替
     */
    @Deprecated
    public static final int PAGE_N_RECS = PAGE_HEADER_START + 16;

    /**
     * 最大事务 ID (8 bytes)
     * @deprecated 使用 {@link IndexPageLayout#PAGE_MAX_TRX_ID} 代替
     */
    @Deprecated
    public static final int PAGE_MAX_TRX_ID = PAGE_HEADER_START + 18;

    /**
     * B+Tree 层级 (2 bytes)
     * @deprecated 使用 {@link IndexPageLayout#PAGE_LEVEL} 代替
     */
    @Deprecated
    public static final int PAGE_LEVEL = PAGE_HEADER_START + 26;

    /**
     * 索引 ID (8 bytes)
     * @deprecated 使用 {@link IndexPageLayout#PAGE_INDEX_ID} 代替
     */
    @Deprecated
    public static final int PAGE_INDEX_ID = PAGE_HEADER_START + 28;

    /**
     * 叶子段信息 (10 bytes)
     * @deprecated 使用 {@link IndexPageLayout#PAGE_BTR_SEG_LEAF} 代替
     */
    @Deprecated
    public static final int PAGE_BTR_SEG_LEAF = PAGE_HEADER_START + 36;

    /**
     * 非叶子段信息 (10 bytes)
     * @deprecated 使用 {@link IndexPageLayout#PAGE_BTR_SEG_TOP} 代替
     */
    @Deprecated
    public static final int PAGE_BTR_SEG_TOP = PAGE_HEADER_START + 46;

    /**
     * Page Header 总大小 (56 bytes)
     * @deprecated 使用 {@link IndexPageLayout#PAGE_HEADER_SIZE} 代替
     */
    @Deprecated
    public static final int INDEX_PAGE_HEADER_SIZE = 56;

    // ==================== Infimum/Supremum ====================

    /**
     * Infimum 记录偏移
     * @deprecated 使用 {@link IndexPageLayout#INFIMUM_OFFSET} 代替
     */
    @Deprecated
    public static final int INFIMUM_OFFSET = PAGE_HEADER_START + INDEX_PAGE_HEADER_SIZE;

    /**
     * Supremum 记录偏移
     * @deprecated 使用 {@link IndexPageLayout#SUPREMUM_OFFSET} 代替
     */
    @Deprecated
    public static final int SUPREMUM_OFFSET = INFIMUM_OFFSET + 13;

    /**
     * 用户记录起始位置
     * @deprecated 使用 {@link IndexPageLayout#USER_RECORDS_START} 代替
     */
    @Deprecated
    public static final int USER_RECORDS_START = SUPREMUM_OFFSET + 13;

    // ==================== Page Directory ====================

    /**
     * 每个槽的大小 (2 bytes)
     * @deprecated 使用 {@link IndexPageLayout#PAGE_DIR_SLOT_SIZE} 代替
     */
    @Deprecated
    public static final int PAGE_DIR_SLOT_SIZE = 2;

    // ==================== 插入方向常量 ====================

    /**
     * 向左插入
     * @deprecated 使用 {@link IndexPageLayout#PAGE_LEFT} 代替
     */
    @Deprecated
    public static final int PAGE_LEFT = 1;

    /**
     * 向右插入
     * @deprecated 使用 {@link IndexPageLayout#PAGE_RIGHT} 代替
     */
    @Deprecated
    public static final int PAGE_RIGHT = 2;

    /**
     * 在同一记录位置插入
     * @deprecated 使用 {@link IndexPageLayout#PAGE_SAME_REC} 代替
     */
    @Deprecated
    public static final int PAGE_SAME_REC = 3;

    /**
     * 在同一页面插入
     * @deprecated 使用 {@link IndexPageLayout#PAGE_SAME_PAGE} 代替
     */
    @Deprecated
    public static final int PAGE_SAME_PAGE = 4;

    /**
     * 无方向 (首次插入)
     * @deprecated 使用 {@link IndexPageLayout#PAGE_NO_DIRECTION} 代替
     */
    @Deprecated
    public static final int PAGE_NO_DIRECTION = 5;
    
    // ==================== 构造函数 ====================
    
    /**
     * 创建新的 IndexPage
     * 
     * <p>初始化一个空的 B+Tree 页面，包含 Page Header、
     * Infimum/Supremum 记录和 Page Directory。</p>
     * 
     * <h3>初始化步骤</h3>
     * <ol>
     *   <li>调用父类构造函数初始化 FIL Header</li>
     *   <li>设置页类型为 FIL_PAGE_INDEX</li>
     *   <li>初始化 Page Header 各字段</li>
     *   <li>创建 Infimum 和 Supremum 记录</li>
     *   <li>初始化 Page Directory (2 个槽)</li>
     * </ol>
     * 
     * @param pageId 页面标识
     */
    public IndexPage(PageId pageId) {
        super(pageId);
        initializeIndexPage();
    }
    
    /**
     * 从已有数据创建 IndexPage
     * 
     * <p>用于从磁盘读取 INDEX 页面后的重建。</p>
     * 
     * @param pageId 页面标识
     * @param data   从磁盘读取的原始数据
     */
    public IndexPage(PageId pageId, ByteBuffer data) {
        super(pageId, data);
    }
    
    /**
     * 初始化 IndexPage 特有结构
     */
    private void initializeIndexPage() {
        // 设置页类型
        setPageType(PageType.FIL_PAGE_INDEX);

        // 初始化 Page Header
        setSlotCount(2);                                      // infimum + supremum
        setHeapTop(IndexPageLayout.USER_RECORDS_START);       // 堆顶指向用户记录区
        setHeapRecordCount(2);                                // infimum + supremum
        setCompactFormat(true);                               // 使用 Compact 格式
        setFreeListHead(0);                                   // 无空闲记录
        setGarbageSize(0);                                    // 无垃圾空间
        setLastInsertOffset(0);                               // 无最后插入
        setDirection(IndexPageLayout.PAGE_NO_DIRECTION);      // 无插入方向
        setDirectionCount(0);
        setRecordCount(0);                                    // 无用户记录
        setMaxTrxId(0);
        setLevel(0);                                          // 默认叶子节点
        setIndexId(0);

        // 初始化 Infimum 和 Supremum
        initInfimumSupremum();

        // 初始化 Page Directory
        initPageDirectory();
    }

    /**
     * 初始化 Infimum 和 Supremum 虚拟记录
     *
     * <p>这两条虚拟记录是每个 INDEX 页面必有的：</p>
     * <ul>
     *   <li><b>Infimum</b>: 最小记录，是链表起点，next 指向第一条用户记录或 Supremum</li>
     *   <li><b>Supremum</b>: 最大记录，是链表终点，next 为 0</li>
     * </ul>
     */
    private void initInfimumSupremum() {
        // ===== Infimum 记录 =====
        int offset = IndexPageLayout.INFIMUM_OFFSET;

        // Record header: n_owned=1, heap_no=0, rec_type=2(infimum)
        buffer.put(offset, (byte) 0x10);      // n_owned=1 (高4位)
        buffer.put(offset + 1, (byte) 0x00);
        buffer.put(offset + 2, (byte) 0x00);
        buffer.put(offset + 3, (byte) 0x02);  // rec_type=2 (infimum)

        // next_record: 相对偏移指向 supremum
        buffer.putShort(offset + IndexPageLayout.REC_OFF_NEXT,
                (short) (IndexPageLayout.SUPREMUM_OFFSET - IndexPageLayout.INFIMUM_OFFSET));

        // "infimum\0" (8 bytes)
        byte[] infimum = {'i', 'n', 'f', 'i', 'm', 'u', 'm', 0};
        for (int i = 0; i < 8; i++) {
            buffer.put(offset + 5 + i, infimum[i]);
        }

        // ===== Supremum 记录 =====
        offset = IndexPageLayout.SUPREMUM_OFFSET;

        // Record header: n_owned=1, heap_no=1, rec_type=3(supremum)
        buffer.put(offset, (byte) 0x10);      // n_owned=1
        buffer.put(offset + 1, (byte) 0x00);
        buffer.put(offset + 2, (byte) 0x08);  // heap_no=1
        buffer.put(offset + 3, (byte) 0x03);  // rec_type=3 (supremum)

        // next_record: 0 表示链表结束
        buffer.putShort(offset + IndexPageLayout.REC_OFF_NEXT, (short) 0);

        // "supremum" (8 bytes)
        byte[] supremum = {'s', 'u', 'p', 'r', 'e', 'm', 'u', 'm'};
        for (int i = 0; i < 8; i++) {
            buffer.put(offset + 5 + i, supremum[i]);
        }
    }

    /**
     * 初始化 Page Directory
     *
     * <p>Page Directory 是一个从页尾向上增长的槽数组，每个槽存储一条"拥有者"记录的偏移。
     * 初始状态有 2 个槽：</p>
     * <ul>
     *   <li>Slot 0 (最右): 指向 Supremum</li>
     *   <li>Slot 1: 指向 Infimum</li>
     * </ul>
     */
    private void initPageDirectory() {
        // Slot 0: supremum offset (页尾第一个槽)
        buffer.putShort(IndexPageLayout.slotOffset(0), (short) IndexPageLayout.SUPREMUM_OFFSET);

        // Slot 1: infimum offset
        buffer.putShort(IndexPageLayout.slotOffset(1), (short) IndexPageLayout.INFIMUM_OFFSET);
    }
    
    // ==================== Page Header Getters/Setters ====================

    /**
     * 获取 Page Directory 槽数量
     *
     * @return 槽数量 (最少 2)
     */
    public int getSlotCount() {
        return IndexPageLayout.readSlotCount(buffer);
    }

    /**
     * 设置槽数量
     *
     * @param count 槽数量
     * @deprecated 所有写入应通过 IndexPageOps + MTR 进行
     */
    @Deprecated
    public void setSlotCount(int count) {
        buffer.putShort(IndexPageLayout.PAGE_N_DIR_SLOTS, (short) count);
        markDirty();
    }

    /**
     * 获取堆顶位置
     *
     * <p>下一个新记录将从此位置开始分配空间。</p>
     *
     * @return 堆顶偏移
     */
    public int getHeapTop() {
        return IndexPageLayout.readHeapTop(buffer);
    }

    /**
     * 设置堆顶位置
     *
     * @param offset 新的堆顶偏移
     * @deprecated 所有写入应通过 IndexPageOps + MTR 进行
     */
    @Deprecated
    public void setHeapTop(int offset) {
        buffer.putShort(IndexPageLayout.PAGE_HEAP_TOP, (short) offset);
        markDirty();
    }

    /**
     * 获取堆中记录数 (含 infimum/supremum 和已删除记录)
     *
     * @return 记录数 (低 15 位)
     */
    public int getHeapRecordCount() {
        return IndexPageLayout.readHeapRecordCount(buffer);
    }

    /**
     * 设置堆中记录数
     *
     * @param count 记录数
     * @deprecated 所有写入应通过 IndexPageOps + MTR 进行
     */
    @Deprecated
    public void setHeapRecordCount(int count) {
        int flag = buffer.getShort(IndexPageLayout.PAGE_N_HEAP) & 0x8000;
        buffer.putShort(IndexPageLayout.PAGE_N_HEAP, (short) (flag | (count & 0x7FFF)));
        markDirty();
    }

    /**
     * 检查是否使用 Compact 行格式
     *
     * @return 如果使用 Compact 格式返回 true
     */
    public boolean isCompactFormat() {
        return IndexPageLayout.isCompactFormat(buffer);
    }

    /**
     * 设置行格式标志
     *
     * @param compact 是否使用 Compact 格式
     * @deprecated 所有写入应通过 IndexPageOps + MTR 进行
     */
    @Deprecated
    public void setCompactFormat(boolean compact) {
        int count = getHeapRecordCount();
        buffer.putShort(IndexPageLayout.PAGE_N_HEAP, (short) (compact ? (0x8000 | count) : count));
        markDirty();
    }

    /**
     * 获取空闲链表头
     *
     * @return 第一条空闲记录的偏移，0 表示无空闲记录
     */
    public int getFreeListHead() {
        return IndexPageLayout.readFreeListHead(buffer);
    }

    /**
     * 设置空闲链表头
     *
     * @param offset 空闲记录偏移
     * @deprecated 所有写入应通过 IndexPageOps + MTR 进行
     */
    @Deprecated
    public void setFreeListHead(int offset) {
        buffer.putShort(IndexPageLayout.PAGE_FREE, (short) offset);
        markDirty();
    }

    /**
     * 获取垃圾空间大小
     *
     * @return 已删除记录占用的字节数
     */
    public int getGarbageSize() {
        return IndexPageLayout.readGarbageSize(buffer);
    }

    /**
     * 设置垃圾空间大小
     *
     * @param size 垃圾字节数
     * @deprecated 所有写入应通过 IndexPageOps + MTR 进行
     */
    @Deprecated
    public void setGarbageSize(int size) {
        buffer.putShort(IndexPageLayout.PAGE_GARBAGE, (short) size);
        markDirty();
    }

    /**
     * 获取最后插入位置
     *
     * @return 最后插入记录的偏移
     */
    public int getLastInsertOffset() {
        return IndexPageLayout.readLastInsertOffset(buffer);
    }

    /**
     * 设置最后插入位置
     *
     * @param offset 记录偏移
     * @deprecated 所有写入应通过 IndexPageOps + MTR 进行
     */
    @Deprecated
    public void setLastInsertOffset(int offset) {
        buffer.putShort(IndexPageLayout.PAGE_LAST_INSERT, (short) offset);
        markDirty();
    }

    /**
     * 获取插入方向
     *
     * @return 插入方向常量
     */
    public int getDirection() {
        return IndexPageLayout.readDirection(buffer);
    }

    /**
     * 设置插入方向
     *
     * @param direction 插入方向
     * @deprecated 所有写入应通过 IndexPageOps + MTR 进行
     */
    @Deprecated
    public void setDirection(int direction) {
        buffer.putShort(IndexPageLayout.PAGE_DIRECTION, (short) direction);
        markDirty();
    }

    /**
     * 获取同方向连续插入次数
     *
     * @return 连续插入次数
     */
    public int getDirectionCount() {
        return IndexPageLayout.readDirectionCount(buffer);
    }

    /**
     * 设置同方向连续插入次数
     *
     * @param count 次数
     * @deprecated 所有写入应通过 IndexPageOps + MTR 进行
     */
    @Deprecated
    public void setDirectionCount(int count) {
        buffer.putShort(IndexPageLayout.PAGE_N_DIRECTION, (short) count);
        markDirty();
    }

    /**
     * 获取用户记录数 (不含 infimum/supremum 和已删除)
     *
     * @return 有效用户记录数
     */
    public int getRecordCount() {
        return IndexPageLayout.readRecordCount(buffer);
    }

    /**
     * 设置用户记录数
     *
     * @param count 记录数
     * @deprecated 所有写入应通过 IndexPageOps + MTR 进行
     */
    @Deprecated
    public void setRecordCount(int count) {
        buffer.putShort(IndexPageLayout.PAGE_N_RECS, (short) count);
        markDirty();
    }

    /**
     * 获取修改此页面的最大事务 ID
     *
     * @return 最大事务 ID
     */
    public long getMaxTrxId() {
        return IndexPageLayout.readMaxTrxId(buffer);
    }

    /**
     * 设置最大事务 ID
     *
     * @param trxId 事务 ID
     * @deprecated 所有写入应通过 IndexPageOps + MTR 进行
     */
    @Deprecated
    public void setMaxTrxId(long trxId) {
        buffer.putLong(IndexPageLayout.PAGE_MAX_TRX_ID, trxId);
        markDirty();
    }

    /**
     * 获取 B+Tree 层级
     *
     * @return 层级 (0=叶子节点)
     */
    public int getLevel() {
        return IndexPageLayout.readLevel(buffer);
    }

    /**
     * 设置 B+Tree 层级
     *
     * @param level 层级
     * @deprecated 所有写入应通过 IndexPageOps + MTR 进行
     */
    @Deprecated
    public void setLevel(int level) {
        buffer.putShort(IndexPageLayout.PAGE_LEVEL, (short) level);
        markDirty();
    }

    /**
     * 判断是否为叶子节点
     *
     * @return 如果 level=0 返回 true
     */
    public boolean isLeaf() {
        return IndexPageLayout.isLeaf(buffer);
    }

    /**
     * 获取索引 ID
     *
     * @return 索引 ID
     */
    public long getIndexId() {
        return IndexPageLayout.readIndexId(buffer);
    }

    /**
     * 设置索引 ID
     *
     * @param indexId 索引 ID
     * @deprecated 所有写入应通过 IndexPageOps + MTR 进行
     */
    @Deprecated
    public void setIndexId(long indexId) {
        buffer.putLong(IndexPageLayout.PAGE_INDEX_ID, indexId);
        markDirty();
    }

    // ==================== 空间计算 ====================

    /**
     * 计算页面剩余可用空间
     *
     * <p>可用空间 = Page Directory 底部 - 堆顶</p>
     *
     * @return 可用字节数
     */
    public int getFreeSpace() {
        return IndexPageLayout.freeSpace(buffer);
    }

    /**
     * 获取 Page Directory 底部位置
     *
     * <p>Page Directory 从页尾向上增长，底部是最后一个槽之后的位置。</p>
     *
     * @return 偏移量
     */
    public int getPageDirectoryEnd() {
        return IndexPageLayout.pageDirectoryEnd(buffer);
    }

    // ==================== Page Directory 操作 ====================

    /**
     * 获取指定槽中存储的记录偏移
     *
     * <p>槽从 0 开始编号，槽 0 在页面最末尾。</p>
     *
     * @param slotNo 槽号 (0 = 最右边的槽，指向 supremum)
     * @return 记录偏移
     */
    public int getSlotOffset(int slotNo) {
        return IndexPageLayout.readSlotValue(buffer, slotNo);
    }

    /**
     * 设置指定槽的记录偏移
     *
     * @param slotNo 槽号
     * @param offset 记录偏移
     * @deprecated 所有写入应通过 IndexPageOps + MTR 进行
     */
    @Deprecated
    public void setSlotOffset(int slotNo, int offset) {
        int slotPos = IndexPageLayout.slotOffset(slotNo);
        buffer.putShort(slotPos, (short) offset);
        markDirty();
    }

    // ==================== 记录链表操作 ====================

    /**
     * 获取记录的下一条记录偏移
     *
     * <p>记录头中存储的是相对偏移，本方法转换为绝对偏移。</p>
     *
     * @param recOffset 当前记录的偏移
     * @return 下一条记录的偏移，0 表示链表结束
     */
    public int getRecordNext(int recOffset) {
        return IndexPageLayout.readRecordNext(buffer, recOffset);
    }

    /**
     * 设置记录的下一条记录偏移
     *
     * @param recOffset  当前记录偏移
     * @param nextOffset 下一条记录偏移，0 表示链表结束
     * @deprecated 所有写入应通过 IndexPageOps + MTR 进行
     */
    @Deprecated
    public void setRecordNext(int recOffset, int nextOffset) {
        short relOffset = (short) (nextOffset == 0 ? 0 : nextOffset - recOffset);
        buffer.putShort(recOffset + IndexPageLayout.REC_OFF_NEXT, relOffset);
        markDirty();
    }

    /**
     * 获取记录的 n_owned 值
     *
     * <p>n_owned 表示此记录在 Page Directory 中"拥有"多少条记录。</p>
     *
     * @param recOffset 记录偏移
     * @return n_owned 值 (0-15)
     */
    public int getRecordOwned(int recOffset) {
        return IndexPageLayout.readRecordOwned(buffer, recOffset);
    }

    /**
     * 设置记录的 n_owned 值
     *
     * @param recOffset 记录偏移
     * @param owned     n_owned 值
     * @deprecated 所有写入应通过 IndexPageOps + MTR 进行
     */
    @Deprecated
    public void setRecordOwned(int recOffset, int owned) {
        byte b = buffer.get(recOffset);
        buffer.put(recOffset, (byte) ((b & 0x0F) | ((owned & 0x0F) << 4)));
        markDirty();
    }

    /**
     * 获取第一条用户记录的偏移
     *
     * <p>即 Infimum 的下一条记录。如果页面为空，返回 Supremum 的偏移。</p>
     *
     * @return 第一条用户记录偏移，或 Supremum 偏移 (页面为空时)
     */
    public int getFirstUserRecordOffset() {
        return IndexPageLayout.readFirstUserRecordOffset(buffer);
    }
    
    /**
     * 遍历获取所有用户记录的偏移
     *
     * <p>沿着记录链表遍历，不包含 Infimum 和 Supremum。</p>
     *
     * @return 用户记录偏移列表
     */
    public List<Integer> getAllUserRecordOffsets() {
        List<Integer> offsets = new ArrayList<>();
        int current = getFirstUserRecordOffset();

        while (current != 0 && current != IndexPageLayout.SUPREMUM_OFFSET) {
            offsets.add(current);
            current = getRecordNext(current);
        }

        return offsets;
    }
    
    /**
     * 返回 IndexPage 的字符串表示
     * 
     * @return 包含关键信息的字符串
     */
    @Override
    public String toString() {
        return String.format("IndexPage{id=%s, level=%d, records=%d, freeSpace=%d, indexId=%d}",
            pageId, getLevel(), getRecordCount(), getFreeSpace(), getIndexId());
    }
}
