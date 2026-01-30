package cn.zhangyis.minidb.storage.page;

import cn.zhangyis.minidb.storage.constants.StorageConstants;

import java.nio.ByteBuffer;

/**
 * IndexPage 布局定义（纯静态，只读）
 *
 * <p>此类是 IndexPage 所有偏移常量和读取方法的唯一权威来源。
 * 所有偏移计算必须通过此类进行，禁止在其他地方重复计算。</p>
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li>纯静态类，禁止实例化</li>
 *   <li>只提供读取方法，不提供写入方法</li>
 *   <li>所有写入操作应通过 IndexPageOps + MTR 进行</li>
 *   <li>slot 偏移计算公式唯一实现在此类</li>
 * </ul>
 *
 * <h2>页面物理布局</h2>
 * <pre>
 * +-------------------------------------------+  Offset 0
 * |           FIL Header (38 bytes)           |
 * +-------------------------------------------+  Offset 38
 * |          Page Header (56 bytes)           |
 * +-------------------------------------------+  Offset 94
 * |         Infimum Record (13 bytes)         |
 * +-------------------------------------------+  Offset 107
 * |         Supremum Record (13 bytes)        |
 * +-------------------------------------------+  Offset 120
 * |          User Records (向下增长)          |
 * +- - - - - - - - - - - - - - - - - - - - - -+
 * |              Free Space                   |
 * +- - - - - - - - - - - - - - - - - - - - - -+
 * |        Page Directory (向上增长)          |
 * +-------------------------------------------+  Offset 16376
 * |           FIL Trailer (8 bytes)           |
 * +-------------------------------------------+  Offset 16384
 * </pre>
 *
 * @author MiniDB
 * @version 2.0
 * @see IndexPage
 * @see cn.zhangyis.minidb.storage.buffer.BufferFrame
 */
public final class IndexPageLayout {

    // ==================== 页面基本常量 ====================

    /** 页面大小 (16KB) */
    public static final int PAGE_SIZE = StorageConstants.PAGE_SIZE;

    /** FIL Header 大小 (38 bytes) */
    public static final int FIL_HEADER_SIZE = StorageConstants.FIL_HEADER_SIZE;

    /** FIL Trailer 大小 (8 bytes) */
    public static final int FIL_TRAILER_SIZE = StorageConstants.FIL_TRAILER_SIZE;

    // ==================== Page Header 偏移量 ====================

    /** Page Header 起始位置 (紧接 FIL Header 之后) */
    public static final int PAGE_HEADER_START = FIL_HEADER_SIZE;

    /** Page Directory 槽数量 (2 bytes) - 最少 2 个槽：infimum 和 supremum */
    public static final int PAGE_N_DIR_SLOTS = PAGE_HEADER_START;

    /** 堆顶位置 (2 bytes) - 指向下一个可用空间的起始位置 */
    public static final int PAGE_HEAP_TOP = PAGE_HEADER_START + 2;

    /** 堆中记录数 (2 bytes) - 含 infimum/supremum 和已删除记录，最高位表示 Compact 格式 */
    public static final int PAGE_N_HEAP = PAGE_HEADER_START + 4;

    /** 空闲链表头 (2 bytes) - 已删除记录形成的空闲链表，0 表示无空闲记录 */
    public static final int PAGE_FREE = PAGE_HEADER_START + 6;

    /** 垃圾空间大小 (2 bytes) - 已删除记录占用的总字节数 */
    public static final int PAGE_GARBAGE = PAGE_HEADER_START + 8;

    /** 最后插入位置 (2 bytes) - 最后一条插入记录的偏移 */
    public static final int PAGE_LAST_INSERT = PAGE_HEADER_START + 10;

    /** 插入方向 (2 bytes) - 记录最近的插入方向 */
    public static final int PAGE_DIRECTION = PAGE_HEADER_START + 12;

    /** 同方向连续插入次数 (2 bytes) */
    public static final int PAGE_N_DIRECTION = PAGE_HEADER_START + 14;

    /** 用户记录数 (2 bytes) - 不包含 infimum/supremum 和已删除记录 */
    public static final int PAGE_N_RECS = PAGE_HEADER_START + 16;

    /** 最大事务 ID (8 bytes) - 修改过此页面的最大事务 ID */
    public static final int PAGE_MAX_TRX_ID = PAGE_HEADER_START + 18;

    /** B+Tree 层级 (2 bytes) - 0 = 叶子节点，1+ = 非叶子节点 */
    public static final int PAGE_LEVEL = PAGE_HEADER_START + 26;

    /** 索引 ID (8 bytes) - 此页面所属的索引的 ID */
    public static final int PAGE_INDEX_ID = PAGE_HEADER_START + 28;

    /** 叶子段信息 (10 bytes) - 仅根页面有效 */
    public static final int PAGE_BTR_SEG_LEAF = PAGE_HEADER_START + 36;

    /** 非叶子段信息 (10 bytes) - 仅根页面有效 */
    public static final int PAGE_BTR_SEG_TOP = PAGE_HEADER_START + 46;

    /** Page Header 总大小 (56 bytes) */
    public static final int PAGE_HEADER_SIZE = 56;

    // ==================== Infimum/Supremum 常量 ====================

    /** Infimum 记录偏移 (虚拟最小记录，链表起点) */
    public static final int INFIMUM_OFFSET = PAGE_HEADER_START + PAGE_HEADER_SIZE;  // 94

    /** Supremum 记录偏移 (虚拟最大记录，链表终点) */
    public static final int SUPREMUM_OFFSET = INFIMUM_OFFSET + 13;  // 107

    /** 用户记录起始位置 */
    public static final int USER_RECORDS_START = SUPREMUM_OFFSET + 13;  // 120

    // ==================== Page Directory 常量 ====================

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

    // ==================== Record Header 常量 ====================

    /** next_record 字段在记录头中的偏移 (相对记录起始) */
    public static final int REC_OFF_NEXT = 3;

    /** n_owned 字段在记录头中的偏移 */
    public static final int REC_OFF_N_OWNED = 0;

    // ==================== 唯一 Slot 偏移公式 ====================

    /**
     * 计算 Page Directory 槽的偏移（唯一实现，禁止其他地方计算）
     *
     * <p>Page Directory 从页尾向上增长，槽 0 在最末尾。</p>
     *
     * <pre>
     * offset = PAGE_SIZE - FIL_TRAILER_SIZE - (slotNo + 1) * PAGE_DIR_SLOT_SIZE
     * </pre>
     *
     * @param slotNo 槽号 (0 = 最右边的槽，指向 supremum)
     * @return 槽在页面中的字节偏移
     */
    public static int slotOffset(int slotNo) {
        return PAGE_SIZE - FIL_TRAILER_SIZE - (slotNo + 1) * PAGE_DIR_SLOT_SIZE;
    }

    // ==================== Page Header 读取方法 ====================

    /**
     * 读取 Page Directory 槽数量
     *
     * @param buf 页面 ByteBuffer
     * @return 槽数量 (最少 2)
     */
    public static int readSlotCount(ByteBuffer buf) {
        return buf.getShort(PAGE_N_DIR_SLOTS) & 0xFFFF;
    }

    /**
     * 读取指定槽中存储的记录偏移
     *
     * @param buf    页面 ByteBuffer
     * @param slotNo 槽号
     * @return 记录偏移
     */
    public static int readSlotValue(ByteBuffer buf, int slotNo) {
        return buf.getShort(slotOffset(slotNo)) & 0xFFFF;
    }

    /**
     * 读取堆顶位置
     *
     * @param buf 页面 ByteBuffer
     * @return 堆顶偏移
     */
    public static int readHeapTop(ByteBuffer buf) {
        return buf.getShort(PAGE_HEAP_TOP) & 0xFFFF;
    }

    /**
     * 读取堆中记录数 (含 infimum/supremum 和已删除记录)
     *
     * @param buf 页面 ByteBuffer
     * @return 记录数 (低 15 位)
     */
    public static int readHeapRecordCount(ByteBuffer buf) {
        return buf.getShort(PAGE_N_HEAP) & 0x7FFF;
    }

    /**
     * 检查是否使用 Compact 行格式
     *
     * @param buf 页面 ByteBuffer
     * @return 如果使用 Compact 格式返回 true
     */
    public static boolean isCompactFormat(ByteBuffer buf) {
        return (buf.getShort(PAGE_N_HEAP) & 0x8000) != 0;
    }

    /**
     * 读取用户记录数 (不含 infimum/supremum 和已删除)
     *
     * @param buf 页面 ByteBuffer
     * @return 有效用户记录数
     */
    public static int readRecordCount(ByteBuffer buf) {
        return buf.getShort(PAGE_N_RECS) & 0xFFFF;
    }

    /**
     * 读取 B+Tree 层级
     *
     * @param buf 页面 ByteBuffer
     * @return 层级 (0=叶子节点)
     */
    public static int readLevel(ByteBuffer buf) {
        return buf.getShort(PAGE_LEVEL) & 0xFFFF;
    }

    /**
     * 读取索引 ID
     *
     * @param buf 页面 ByteBuffer
     * @return 索引 ID
     */
    public static long readIndexId(ByteBuffer buf) {
        return buf.getLong(PAGE_INDEX_ID);
    }

    /**
     * 读取空闲链表头
     *
     * @param buf 页面 ByteBuffer
     * @return 第一条空闲记录的偏移，0 表示无空闲记录
     */
    public static int readFreeListHead(ByteBuffer buf) {
        return buf.getShort(PAGE_FREE) & 0xFFFF;
    }

    /**
     * 读取垃圾空间大小
     *
     * @param buf 页面 ByteBuffer
     * @return 已删除记录占用的字节数
     */
    public static int readGarbageSize(ByteBuffer buf) {
        return buf.getShort(PAGE_GARBAGE) & 0xFFFF;
    }

    /**
     * 读取最后插入位置
     *
     * @param buf 页面 ByteBuffer
     * @return 最后插入记录的偏移
     */
    public static int readLastInsertOffset(ByteBuffer buf) {
        return buf.getShort(PAGE_LAST_INSERT) & 0xFFFF;
    }

    /**
     * 读取插入方向
     *
     * @param buf 页面 ByteBuffer
     * @return 插入方向常量
     */
    public static int readDirection(ByteBuffer buf) {
        return buf.getShort(PAGE_DIRECTION) & 0xFFFF;
    }

    /**
     * 读取同方向连续插入次数
     *
     * @param buf 页面 ByteBuffer
     * @return 连续插入次数
     */
    public static int readDirectionCount(ByteBuffer buf) {
        return buf.getShort(PAGE_N_DIRECTION) & 0xFFFF;
    }

    /**
     * 读取最大事务 ID
     *
     * @param buf 页面 ByteBuffer
     * @return 最大事务 ID
     */
    public static long readMaxTrxId(ByteBuffer buf) {
        return buf.getLong(PAGE_MAX_TRX_ID);
    }

    // ==================== 空间计算方法 ====================

    /**
     * 计算 Page Directory 底部位置
     *
     * <p>Page Directory 从页尾向上增长，底部是最后一个槽之后的位置。</p>
     *
     * @param buf 页面 ByteBuffer
     * @return 偏移量
     */
    public static int pageDirectoryEnd(ByteBuffer buf) {
        int slotCount = readSlotCount(buf);
        return PAGE_SIZE - FIL_TRAILER_SIZE - (slotCount * PAGE_DIR_SLOT_SIZE);
    }

    /**
     * 计算页面剩余可用空间
     *
     * <p>可用空间 = Page Directory 底部 - 堆顶</p>
     *
     * @param buf 页面 ByteBuffer
     * @return 可用字节数
     */
    public static int freeSpace(ByteBuffer buf) {
        return pageDirectoryEnd(buf) - readHeapTop(buf);
    }

    // ==================== 记录链表读取方法 ====================

    /**
     * 读取记录的下一条记录偏移
     *
     * <p>记录头中存储的是相对偏移，本方法转换为绝对偏移。</p>
     *
     * @param buf       页面 ByteBuffer
     * @param recOffset 当前记录的偏移
     * @return 下一条记录的偏移，0 表示链表结束
     */
    public static int readRecordNext(ByteBuffer buf, int recOffset) {
        short relOffset = buf.getShort(recOffset + REC_OFF_NEXT);
        if (relOffset == 0) {
            return 0; // 链表结束
        }
        return recOffset + relOffset;
    }

    /**
     * 读取记录的 n_owned 值
     *
     * <p>n_owned 表示此记录在 Page Directory 中"拥有"多少条记录。</p>
     *
     * @param buf       页面 ByteBuffer
     * @param recOffset 记录偏移
     * @return n_owned 值 (0-15)
     */
    public static int readRecordOwned(ByteBuffer buf, int recOffset) {
        return (buf.get(recOffset + REC_OFF_N_OWNED) >> 4) & 0x0F;
    }

    /**
     * 读取第一条用户记录的偏移
     *
     * <p>即 Infimum 的下一条记录。如果页面为空，返回 Supremum 的偏移。</p>
     *
     * @param buf 页面 ByteBuffer
     * @return 第一条用户记录偏移，或 Supremum 偏移 (页面为空时)
     */
    public static int readFirstUserRecordOffset(ByteBuffer buf) {
        return readRecordNext(buf, INFIMUM_OFFSET);
    }

    /**
     * 检查是否为叶子节点
     *
     * @param buf 页面 ByteBuffer
     * @return 如果 level=0 返回 true
     */
    public static boolean isLeaf(ByteBuffer buf) {
        return readLevel(buf) == 0;
    }

    // ==================== 私有构造函数 ====================

    /**
     * 禁止实例化
     */
    private IndexPageLayout() {
        throw new UnsupportedOperationException("IndexPageLayout is a utility class and cannot be instantiated");
    }
}
