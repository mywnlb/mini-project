package cn.zhangyis.minidb.storage.page;

import com.minidb.storage.StorageConstants;

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
    
    /** Page Header 起始位置 (紧接 FIL Header 之后) */
    private static final int PAGE_HEADER_START = StorageConstants.FIL_HEADER_SIZE;
    
    /**
     * Page Directory 槽数量 (2 bytes)
     * <p>最少 2 个槽：infimum 和 supremum</p>
     */
    public static final int PAGE_N_DIR_SLOTS = PAGE_HEADER_START;
    
    /**
     * 堆顶位置 (2 bytes)
     * <p>指向下一个可用空间的起始位置，新记录从这里分配空间。
     * 堆从 USER_RECORDS_START 开始向下增长。</p>
     */
    public static final int PAGE_HEAP_TOP = PAGE_HEADER_START + 2;
    
    /**
     * 堆中记录数 (2 bytes)
     * <p>包含 infimum、supremum 和所有用户记录（含已删除的）。
     * 最高位 (bit 15) 表示是否为 Compact 格式。</p>
     */
    public static final int PAGE_N_HEAP = PAGE_HEADER_START + 4;
    
    /**
     * 空闲链表头 (2 bytes)
     * <p>已删除记录形成的空闲链表，新插入可以复用这些空间。
     * 0 表示没有空闲记录。</p>
     */
    public static final int PAGE_FREE = PAGE_HEADER_START + 6;
    
    /**
     * 垃圾空间大小 (2 bytes)
     * <p>已删除记录占用的总字节数，用于判断是否需要页面重组。</p>
     */
    public static final int PAGE_GARBAGE = PAGE_HEADER_START + 8;
    
    /**
     * 最后插入位置 (2 bytes)
     * <p>最后一条插入记录的偏移，用于优化顺序插入。</p>
     */
    public static final int PAGE_LAST_INSERT = PAGE_HEADER_START + 10;
    
    /**
     * 插入方向 (2 bytes)
     * <p>记录最近的插入方向，用于优化 B+Tree 分裂策略。</p>
     * @see #PAGE_LEFT
     * @see #PAGE_RIGHT
     */
    public static final int PAGE_DIRECTION = PAGE_HEADER_START + 12;
    
    /**
     * 同方向连续插入次数 (2 bytes)
     * <p>连续向同一方向插入的次数，超过阈值后触发优化分裂。</p>
     */
    public static final int PAGE_N_DIRECTION = PAGE_HEADER_START + 14;
    
    /**
     * 用户记录数 (2 bytes)
     * <p>不包含 infimum、supremum 和已删除记录。</p>
     */
    public static final int PAGE_N_RECS = PAGE_HEADER_START + 16;
    
    /**
     * 最大事务 ID (8 bytes)
     * <p>修改过此页面的最大事务 ID，用于 MVCC。</p>
     */
    public static final int PAGE_MAX_TRX_ID = PAGE_HEADER_START + 18;
    
    /**
     * B+Tree 层级 (2 bytes)
     * <p>0 = 叶子节点，1+ = 非叶子节点。
     * 根节点的层级等于树的高度 - 1。</p>
     */
    public static final int PAGE_LEVEL = PAGE_HEADER_START + 26;
    
    /**
     * 索引 ID (8 bytes)
     * <p>此页面所属的索引的 ID。</p>
     */
    public static final int PAGE_INDEX_ID = PAGE_HEADER_START + 28;
    
    /**
     * 叶子段信息 (10 bytes)
     * <p>叶子节点所属段的文件段头 (仅根页面有效)。</p>
     */
    public static final int PAGE_BTR_SEG_LEAF = PAGE_HEADER_START + 36;
    
    /**
     * 非叶子段信息 (10 bytes)
     * <p>非叶子节点所属段的文件段头 (仅根页面有效)。</p>
     */
    public static final int PAGE_BTR_SEG_TOP = PAGE_HEADER_START + 46;
    
    /** Page Header 总大小 (56 bytes) */
    public static final int INDEX_PAGE_HEADER_SIZE = 56;
    
    // ==================== Infimum/Supremum ====================
    
    /**
     * Infimum 记录偏移
     * <p>虚拟的最小记录，是记录链表的起点。
     * 任何用户记录都比 Infimum "大"。</p>
     */
    public static final int INFIMUM_OFFSET = PAGE_HEADER_START + INDEX_PAGE_HEADER_SIZE;
    
    /**
     * Supremum 记录偏移
     * <p>虚拟的最大记录，是记录链表的终点。
     * 任何用户记录都比 Supremum "小"。</p>
     */
    public static final int SUPREMUM_OFFSET = INFIMUM_OFFSET + 13;
    
    /**
     * 用户记录起始位置
     * <p>第一条用户记录从这里开始分配。</p>
     */
    public static final int USER_RECORDS_START = SUPREMUM_OFFSET + 13;
    
    // ==================== Page Directory ====================
    
    /** 每个槽的大小 (2 bytes，存储记录偏移) */
    public static final int PAGE_DIR_SLOT_SIZE = 2;
    
    // ==================== 插入方向常量 ====================
    
    /** 向左插入 (比上一条记录小) */
    public static final int PAGE_LEFT = 1;
    
    /** 向右插入 (比上一条记录大) */
    public static final int PAGE_RIGHT = 2;
    
    /** 在同一记录位置插入 */
    public static final int PAGE_SAME_REC = 3;
    
    /** 在同一页面插入 */
    public static final int PAGE_SAME_PAGE = 4;
    
    /** 无方向 (首次插入) */
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
        setSlotCount(2);                    // infimum + supremum
        setHeapTop(USER_RECORDS_START);     // 堆顶指向用户记录区
        setHeapRecordCount(2);              // infimum + supremum
        setCompactFormat(true);             // 使用 Compact 格式
        setFreeListHead(0);                 // 无空闲记录
        setGarbageSize(0);                  // 无垃圾空间
        setLastInsertOffset(0);             // 无最后插入
        setDirection(PAGE_NO_DIRECTION);    // 无插入方向
        setDirectionCount(0);
        setRecordCount(0);                  // 无用户记录
        setMaxTrxId(0);
        setLevel(0);                        // 默认叶子节点
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
     * 
     * <h3>记录格式 (各 13 字节)</h3>
     * <pre>
     * Record Header (5 bytes):
     *   - info_bits + n_owned (1 byte)
     *   - heap_no high bits (1 byte)  
     *   - heap_no low + rec_type (1 byte)
     *   - next_record (2 bytes) - 相对偏移
     * Record Data (8 bytes):
     *   - "infimum\0" 或 "supremum"
     * </pre>
     */
    private void initInfimumSupremum() {
        // ===== Infimum 记录 =====
        int offset = INFIMUM_OFFSET;
        
        // Record header: n_owned=1, heap_no=0, rec_type=2(infimum)
        buffer.put(offset, (byte) 0x10);      // n_owned=1 (高4位)
        buffer.put(offset + 1, (byte) 0x00);
        buffer.put(offset + 2, (byte) 0x00);
        buffer.put(offset + 3, (byte) 0x02);  // rec_type=2 (infimum)
        
        // next_record: 相对偏移指向 supremum
        buffer.putShort(offset + 3, (short) (SUPREMUM_OFFSET - INFIMUM_OFFSET));
        
        // "infimum\0" (8 bytes)
        byte[] infimum = {'i', 'n', 'f', 'i', 'm', 'u', 'm', 0};
        for (int i = 0; i < 8; i++) {
            buffer.put(offset + 5 + i, infimum[i]);
        }
        
        // ===== Supremum 记录 =====
        offset = SUPREMUM_OFFSET;
        
        // Record header: n_owned=1, heap_no=1, rec_type=3(supremum)
        buffer.put(offset, (byte) 0x10);      // n_owned=1
        buffer.put(offset + 1, (byte) 0x00);
        buffer.put(offset + 2, (byte) 0x08);  // heap_no=1
        buffer.put(offset + 3, (byte) 0x03);  // rec_type=3 (supremum)
        
        // next_record: 0 表示链表结束
        buffer.putShort(offset + 3, (short) 0);
        
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
        int dirStart = StorageConstants.PAGE_SIZE - StorageConstants.FIL_TRAILER_SIZE;
        
        // Slot 0: supremum offset (页尾第一个槽)
        buffer.putShort(dirStart - 2, (short) SUPREMUM_OFFSET);
        
        // Slot 1: infimum offset
        buffer.putShort(dirStart - 4, (short) INFIMUM_OFFSET);
    }
    
    // ==================== Page Header Getters/Setters ====================
    
    /**
     * 获取 Page Directory 槽数量
     * 
     * @return 槽数量 (最少 2)
     */
    public int getSlotCount() {
        return buffer.getShort(PAGE_N_DIR_SLOTS) & 0xFFFF;
    }
    
    /**
     * 设置槽数量
     * 
     * @param count 槽数量
     */
    public void setSlotCount(int count) {
        buffer.putShort(PAGE_N_DIR_SLOTS, (short) count);
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
        return buffer.getShort(PAGE_HEAP_TOP) & 0xFFFF;
    }
    
    /**
     * 设置堆顶位置
     * 
     * @param offset 新的堆顶偏移
     */
    public void setHeapTop(int offset) {
        buffer.putShort(PAGE_HEAP_TOP, (short) offset);
        markDirty();
    }
    
    /**
     * 获取堆中记录数 (含 infimum/supremum 和已删除记录)
     * 
     * @return 记录数 (低 15 位)
     */
    public int getHeapRecordCount() {
        return buffer.getShort(PAGE_N_HEAP) & 0x7FFF;
    }
    
    /**
     * 设置堆中记录数
     * 
     * @param count 记录数
     */
    public void setHeapRecordCount(int count) {
        int flag = buffer.getShort(PAGE_N_HEAP) & 0x8000; // 保留 Compact 标志
        buffer.putShort(PAGE_N_HEAP, (short) (flag | (count & 0x7FFF)));
        markDirty();
    }
    
    /**
     * 检查是否使用 Compact 行格式
     * 
     * @return 如果使用 Compact 格式返回 true
     */
    public boolean isCompactFormat() {
        return (buffer.getShort(PAGE_N_HEAP) & 0x8000) != 0;
    }
    
    /**
     * 设置行格式标志
     * 
     * @param compact 是否使用 Compact 格式
     */
    public void setCompactFormat(boolean compact) {
        int count = getHeapRecordCount();
        buffer.putShort(PAGE_N_HEAP, (short) (compact ? (0x8000 | count) : count));
        markDirty();
    }
    
    /**
     * 获取空闲链表头
     * 
     * @return 第一条空闲记录的偏移，0 表示无空闲记录
     */
    public int getFreeListHead() {
        return buffer.getShort(PAGE_FREE) & 0xFFFF;
    }
    
    /**
     * 设置空闲链表头
     * 
     * @param offset 空闲记录偏移
     */
    public void setFreeListHead(int offset) {
        buffer.putShort(PAGE_FREE, (short) offset);
        markDirty();
    }
    
    /**
     * 获取垃圾空间大小
     * 
     * @return 已删除记录占用的字节数
     */
    public int getGarbageSize() {
        return buffer.getShort(PAGE_GARBAGE) & 0xFFFF;
    }
    
    /**
     * 设置垃圾空间大小
     * 
     * @param size 垃圾字节数
     */
    public void setGarbageSize(int size) {
        buffer.putShort(PAGE_GARBAGE, (short) size);
        markDirty();
    }
    
    /**
     * 获取最后插入位置
     * 
     * @return 最后插入记录的偏移
     */
    public int getLastInsertOffset() {
        return buffer.getShort(PAGE_LAST_INSERT) & 0xFFFF;
    }
    
    /**
     * 设置最后插入位置
     * 
     * @param offset 记录偏移
     */
    public void setLastInsertOffset(int offset) {
        buffer.putShort(PAGE_LAST_INSERT, (short) offset);
        markDirty();
    }
    
    /**
     * 获取插入方向
     * 
     * @return 插入方向常量
     */
    public int getDirection() {
        return buffer.getShort(PAGE_DIRECTION) & 0xFFFF;
    }
    
    /**
     * 设置插入方向
     * 
     * @param direction 插入方向
     */
    public void setDirection(int direction) {
        buffer.putShort(PAGE_DIRECTION, (short) direction);
        markDirty();
    }
    
    /**
     * 获取同方向连续插入次数
     * 
     * @return 连续插入次数
     */
    public int getDirectionCount() {
        return buffer.getShort(PAGE_N_DIRECTION) & 0xFFFF;
    }
    
    /**
     * 设置同方向连续插入次数
     * 
     * @param count 次数
     */
    public void setDirectionCount(int count) {
        buffer.putShort(PAGE_N_DIRECTION, (short) count);
        markDirty();
    }
    
    /**
     * 获取用户记录数 (不含 infimum/supremum 和已删除)
     * 
     * @return 有效用户记录数
     */
    public int getRecordCount() {
        return buffer.getShort(PAGE_N_RECS) & 0xFFFF;
    }
    
    /**
     * 设置用户记录数
     * 
     * @param count 记录数
     */
    public void setRecordCount(int count) {
        buffer.putShort(PAGE_N_RECS, (short) count);
        markDirty();
    }
    
    /**
     * 获取修改此页面的最大事务 ID
     * 
     * @return 最大事务 ID
     */
    public long getMaxTrxId() {
        return buffer.getLong(PAGE_MAX_TRX_ID);
    }
    
    /**
     * 设置最大事务 ID
     * 
     * @param trxId 事务 ID
     */
    public void setMaxTrxId(long trxId) {
        buffer.putLong(PAGE_MAX_TRX_ID, trxId);
        markDirty();
    }
    
    /**
     * 获取 B+Tree 层级
     * 
     * @return 层级 (0=叶子节点)
     */
    public int getLevel() {
        return buffer.getShort(PAGE_LEVEL) & 0xFFFF;
    }
    
    /**
     * 设置 B+Tree 层级
     * 
     * @param level 层级
     */
    public void setLevel(int level) {
        buffer.putShort(PAGE_LEVEL, (short) level);
        markDirty();
    }
    
    /**
     * 判断是否为叶子节点
     * 
     * @return 如果 level=0 返回 true
     */
    public boolean isLeaf() {
        return getLevel() == 0;
    }
    
    /**
     * 获取索引 ID
     * 
     * @return 索引 ID
     */
    public long getIndexId() {
        return buffer.getLong(PAGE_INDEX_ID);
    }
    
    /**
     * 设置索引 ID
     * 
     * @param indexId 索引 ID
     */
    public void setIndexId(long indexId) {
        buffer.putLong(PAGE_INDEX_ID, indexId);
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
        int heapTop = getHeapTop();
        int dirEnd = getPageDirectoryEnd();
        return dirEnd - heapTop;
    }
    
    /**
     * 获取 Page Directory 底部位置
     * 
     * <p>Page Directory 从页尾向上增长，底部是最后一个槽之后的位置。</p>
     * 
     * @return 偏移量
     */
    public int getPageDirectoryEnd() {
        int slotCount = getSlotCount();
        return StorageConstants.PAGE_SIZE - StorageConstants.FIL_TRAILER_SIZE
               - (slotCount * PAGE_DIR_SLOT_SIZE);
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
        int slotPos = StorageConstants.PAGE_SIZE - StorageConstants.FIL_TRAILER_SIZE
                      - ((slotNo + 1) * PAGE_DIR_SLOT_SIZE);
        return buffer.getShort(slotPos) & 0xFFFF;
    }
    
    /**
     * 设置指定槽的记录偏移
     * 
     * @param slotNo 槽号
     * @param offset 记录偏移
     */
    public void setSlotOffset(int slotNo, int offset) {
        int slotPos = StorageConstants.PAGE_SIZE - StorageConstants.FIL_TRAILER_SIZE
                      - ((slotNo + 1) * PAGE_DIR_SLOT_SIZE);
        buffer.putShort(slotPos, (short) offset);
        markDirty();
    }
    
    // ==================== 记录链表操作 ====================
    
    /**
     * 获取记录的下一条记录偏移
     * 
     * <p>记录头中存储的是相对偏移，本方法转换为绝对偏移。</p>
     * 
     * <h3>计算方法</h3>
     * <pre>
     * next_absolute = current_offset + relative_offset
     * </pre>
     * 
     * @param recOffset 当前记录的偏移
     * @return 下一条记录的偏移，0 表示链表结束
     */
    public int getRecordNext(int recOffset) {
        // next_record 字段在记录头的 offset+3 位置，占 2 字节
        short relOffset = buffer.getShort(recOffset + 3);
        if (relOffset == 0) {
            return 0; // 链表结束
        }
        return recOffset + relOffset;
    }
    
    /**
     * 设置记录的下一条记录偏移
     * 
     * @param recOffset  当前记录偏移
     * @param nextOffset 下一条记录偏移，0 表示链表结束
     */
    public void setRecordNext(int recOffset, int nextOffset) {
        short relOffset = (short) (nextOffset == 0 ? 0 : nextOffset - recOffset);
        buffer.putShort(recOffset + 3, relOffset);
        markDirty();
    }
    
    /**
     * 获取记录的 n_owned 值
     * 
     * <p>n_owned 表示此记录在 Page Directory 中"拥有"多少条记录。
     * 只有槽指向的记录 (拥有者) 的 n_owned > 0。</p>
     * 
     * @param recOffset 记录偏移
     * @return n_owned 值 (0-15)
     */
    public int getRecordOwned(int recOffset) {
        return (buffer.get(recOffset) >> 4) & 0x0F;
    }
    
    /**
     * 设置记录的 n_owned 值
     * 
     * @param recOffset 记录偏移
     * @param owned     n_owned 值
     */
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
        return getRecordNext(INFIMUM_OFFSET);
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
        
        while (current != 0 && current != SUPREMUM_OFFSET) {
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
