package cn.zhangyis.minidb.storage.page;

import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;

import java.nio.ByteBuffer;

/**
 * IndexPage 操作类（所有写操作必须通过 MTR）
 *
 * <p>此类是 IndexPage 所有写操作的唯一入口。所有方法都要求：</p>
 * <ul>
 *   <li>必须持有 X-latch (写锁)</li>
 *   <li>所有写操作通过 MTR 进行</li>
 *   <li>读操作使用 IndexPageLayout</li>
 * </ul>
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li>纯静态工具类，禁止实例化</li>
 *   <li>所有写操作必须传入 MiniTransaction</li>
 *   <li>MTR 负责：写入 buffer + 生成 redo + 标记 dirty</li>
 *   <li>方法内部断言 X-latch</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>
 * BufferFrame frame = bufferPool.getPage(pageId, FetchMode.WRITE);
 * frame.writeLock();
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool, redoLogManager)) {
 *     IndexPageOps.initPage(frame, indexId, level, mtr);
 *     mtr.commit();
 * } finally {
 *     frame.writeUnlock();
 * }
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 * @see IndexPage
 * @see IndexPageLayout
 * @see MiniTransaction
 */
public final class IndexPageOps {

    // ==================== 页面初始化 ====================

    /**
     * 初始化 IndexPage
     *
     * <p>将页面初始化为空的 B+Tree 节点，包括：</p>
     * <ul>
     *   <li>设置页面类型为 FIL_PAGE_INDEX</li>
     *   <li>初始化 Page Header 各字段</li>
     *   <li>创建 Infimum 和 Supremum 虚拟记录</li>
     *   <li>初始化 Page Directory (2 个槽)</li>
     * </ul>
     *
     * @param frame   BufferFrame (必须持有 X-latch)
     * @param indexId 索引 ID
     * @param level   B+Tree 层级 (0=叶子节点)
     * @param mtr     Mini-Transaction
     * @throws IllegalStateException 如果未持有 X-latch
     */
    public static void initPage(BufferFrame frame, long indexId, int level, MiniTransaction mtr) {
        assertXLatched(frame);

        // 设置页面类型
        mtr.writeShort(frame, Page.FIL_PAGE_TYPE, (short) PageType.FIL_PAGE_INDEX.getValue());

        // 初始化 Page Header
        mtr.writeShort(frame, IndexPageLayout.PAGE_N_DIR_SLOTS, (short) 2);  // infimum + supremum
        mtr.writeShort(frame, IndexPageLayout.PAGE_HEAP_TOP, (short) IndexPageLayout.USER_RECORDS_START);
        mtr.writeShort(frame, IndexPageLayout.PAGE_N_HEAP, (short) 0x8002);  // Compact + 2 records
        mtr.writeShort(frame, IndexPageLayout.PAGE_FREE, (short) 0);
        mtr.writeShort(frame, IndexPageLayout.PAGE_GARBAGE, (short) 0);
        mtr.writeShort(frame, IndexPageLayout.PAGE_LAST_INSERT, (short) 0);
        mtr.writeShort(frame, IndexPageLayout.PAGE_DIRECTION, (short) IndexPageLayout.PAGE_NO_DIRECTION);
        mtr.writeShort(frame, IndexPageLayout.PAGE_N_DIRECTION, (short) 0);
        mtr.writeShort(frame, IndexPageLayout.PAGE_N_RECS, (short) 0);
        mtr.writeLong(frame, IndexPageLayout.PAGE_MAX_TRX_ID, 0L);
        mtr.writeShort(frame, IndexPageLayout.PAGE_LEVEL, (short) level);
        mtr.writeLong(frame, IndexPageLayout.PAGE_INDEX_ID, indexId);

        // 初始化 Infimum / Supremum
        initInfimumSupremum(frame, mtr);

        // 初始化 Page Directory
        initPageDirectory(frame, mtr);
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
     * @param frame BufferFrame (必须持有 X-latch)
     * @param mtr   Mini-Transaction
     */
    private static void initInfimumSupremum(BufferFrame frame, MiniTransaction mtr) {
        // ===== Infimum 记录 =====
        int off = IndexPageLayout.INFIMUM_OFFSET;

        // Record header: n_owned=1, heap_no=0, rec_type=2(infimum)
        mtr.writeByte(frame, off, (byte) 0x10);      // n_owned=1 (高4位)
        mtr.writeByte(frame, off + 1, (byte) 0x00);
        mtr.writeByte(frame, off + 2, (byte) 0x00);
        mtr.writeByte(frame, off + 3, (byte) 0x02);  // rec_type=2 (infimum)

        // next_record: 相对偏移指向 supremum
        mtr.writeShort(frame, off + IndexPageLayout.REC_OFF_NEXT,
                (short) (IndexPageLayout.SUPREMUM_OFFSET - IndexPageLayout.INFIMUM_OFFSET));

        // "infimum\0" (8 bytes)
        byte[] infimum = {'i', 'n', 'f', 'i', 'm', 'u', 'm', 0};
        mtr.writeBytes(frame, off + 5, infimum);

        // ===== Supremum 记录 =====
        off = IndexPageLayout.SUPREMUM_OFFSET;

        // Record header: n_owned=1, heap_no=1, rec_type=3(supremum)
        mtr.writeByte(frame, off, (byte) 0x10);      // n_owned=1
        mtr.writeByte(frame, off + 1, (byte) 0x00);
        mtr.writeByte(frame, off + 2, (byte) 0x08);  // heap_no=1
        mtr.writeByte(frame, off + 3, (byte) 0x03);  // rec_type=3 (supremum)

        // next_record: 0 表示链表结束
        mtr.writeShort(frame, off + IndexPageLayout.REC_OFF_NEXT, (short) 0);

        // "supremum" (8 bytes)
        byte[] supremum = {'s', 'u', 'p', 'r', 'e', 'm', 'u', 'm'};
        mtr.writeBytes(frame, off + 5, supremum);
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
     *
     * @param frame BufferFrame (必须持有 X-latch)
     * @param mtr   Mini-Transaction
     */
    private static void initPageDirectory(BufferFrame frame, MiniTransaction mtr) {
        // Slot 0: supremum offset (页尾第一个槽)
        mtr.writeShort(frame, IndexPageLayout.slotOffset(0), (short) IndexPageLayout.SUPREMUM_OFFSET);

        // Slot 1: infimum offset
        mtr.writeShort(frame, IndexPageLayout.slotOffset(1), (short) IndexPageLayout.INFIMUM_OFFSET);
    }

    // ==================== Page Header 修改 ====================

    /**
     * 设置槽数量
     *
     * @param frame BufferFrame (必须持有 X-latch)
     * @param count 槽数量
     * @param mtr   Mini-Transaction
     */
    public static void setSlotCount(BufferFrame frame, int count, MiniTransaction mtr) {
        assertXLatched(frame);
        mtr.writeShort(frame, IndexPageLayout.PAGE_N_DIR_SLOTS, (short) count);
    }

    /**
     * 设置堆顶位置
     *
     * @param frame  BufferFrame (必须持有 X-latch)
     * @param offset 堆顶偏移
     * @param mtr    Mini-Transaction
     */
    public static void setHeapTop(BufferFrame frame, int offset, MiniTransaction mtr) {
        assertXLatched(frame);
        mtr.writeShort(frame, IndexPageLayout.PAGE_HEAP_TOP, (short) offset);
    }

    /**
     * 设置用户记录数
     *
     * @param frame BufferFrame (必须持有 X-latch)
     * @param count 记录数
     * @param mtr   Mini-Transaction
     */
    public static void setRecordCount(BufferFrame frame, int count, MiniTransaction mtr) {
        assertXLatched(frame);
        mtr.writeShort(frame, IndexPageLayout.PAGE_N_RECS, (short) count);
    }

    /**
     * 设置堆中记录数
     *
     * @param frame   BufferFrame (必须持有 X-latch)
     * @param count   记录数
     * @param compact 是否 Compact 格式
     * @param mtr     Mini-Transaction
     */
    public static void setHeapRecordCount(BufferFrame frame, int count, boolean compact, MiniTransaction mtr) {
        assertXLatched(frame);
        int value = compact ? (0x8000 | (count & 0x7FFF)) : (count & 0x7FFF);
        mtr.writeShort(frame, IndexPageLayout.PAGE_N_HEAP, (short) value);
    }

    /**
     * 设置空闲链表头
     *
     * @param frame  BufferFrame (必须持有 X-latch)
     * @param offset 空闲记录偏移
     * @param mtr    Mini-Transaction
     */
    public static void setFreeListHead(BufferFrame frame, int offset, MiniTransaction mtr) {
        assertXLatched(frame);
        mtr.writeShort(frame, IndexPageLayout.PAGE_FREE, (short) offset);
    }

    /**
     * 设置垃圾空间大小
     *
     * @param frame BufferFrame (必须持有 X-latch)
     * @param size  垃圾字节数
     * @param mtr   Mini-Transaction
     */
    public static void setGarbageSize(BufferFrame frame, int size, MiniTransaction mtr) {
        assertXLatched(frame);
        mtr.writeShort(frame, IndexPageLayout.PAGE_GARBAGE, (short) size);
    }

    /**
     * 设置最后插入位置
     *
     * @param frame  BufferFrame (必须持有 X-latch)
     * @param offset 记录偏移
     * @param mtr    Mini-Transaction
     */
    public static void setLastInsertOffset(BufferFrame frame, int offset, MiniTransaction mtr) {
        assertXLatched(frame);
        mtr.writeShort(frame, IndexPageLayout.PAGE_LAST_INSERT, (short) offset);
    }

    /**
     * 设置插入方向
     *
     * @param frame     BufferFrame (必须持有 X-latch)
     * @param direction 插入方向
     * @param mtr       Mini-Transaction
     */
    public static void setDirection(BufferFrame frame, int direction, MiniTransaction mtr) {
        assertXLatched(frame);
        mtr.writeShort(frame, IndexPageLayout.PAGE_DIRECTION, (short) direction);
    }

    /**
     * 设置同方向连续插入次数
     *
     * @param frame BufferFrame (必须持有 X-latch)
     * @param count 次数
     * @param mtr   Mini-Transaction
     */
    public static void setDirectionCount(BufferFrame frame, int count, MiniTransaction mtr) {
        assertXLatched(frame);
        mtr.writeShort(frame, IndexPageLayout.PAGE_N_DIRECTION, (short) count);
    }

    /**
     * 设置最大事务 ID
     *
     * @param frame BufferFrame (必须持有 X-latch)
     * @param trxId 事务 ID
     * @param mtr   Mini-Transaction
     */
    public static void setMaxTrxId(BufferFrame frame, long trxId, MiniTransaction mtr) {
        assertXLatched(frame);
        mtr.writeLong(frame, IndexPageLayout.PAGE_MAX_TRX_ID, trxId);
    }

    /**
     * 设置 B+Tree 层级
     *
     * @param frame BufferFrame (必须持有 X-latch)
     * @param level 层级
     * @param mtr   Mini-Transaction
     */
    public static void setLevel(BufferFrame frame, int level, MiniTransaction mtr) {
        assertXLatched(frame);
        mtr.writeShort(frame, IndexPageLayout.PAGE_LEVEL, (short) level);
    }

    /**
     * 设置索引 ID
     *
     * @param frame   BufferFrame (必须持有 X-latch)
     * @param indexId 索引 ID
     * @param mtr     Mini-Transaction
     */
    public static void setIndexId(BufferFrame frame, long indexId, MiniTransaction mtr) {
        assertXLatched(frame);
        mtr.writeLong(frame, IndexPageLayout.PAGE_INDEX_ID, indexId);
    }

    // ==================== Page Directory Slot 操作 ====================

    /**
     * 设置指定槽的记录偏移
     *
     * @param frame     BufferFrame (必须持有 X-latch)
     * @param slotNo    槽号
     * @param recOffset 记录偏移
     * @param mtr       Mini-Transaction
     */
    public static void setSlotValue(BufferFrame frame, int slotNo, int recOffset, MiniTransaction mtr) {
        assertXLatched(frame);
        int slotOff = IndexPageLayout.slotOffset(slotNo);
        mtr.writeShort(frame, slotOff, (short) recOffset);
    }

    /**
     * 插入新槽（Page Directory 扩展）
     *
     * <p>在指定位置插入一个新槽，需要将现有槽向下移动。</p>
     *
     * @param frame  BufferFrame (必须持有 X-latch)
     * @param slotNo 新槽位置
     * @param mtr    Mini-Transaction
     */
    public static void insertSlot(BufferFrame frame, int slotNo, MiniTransaction mtr) {
        assertXLatched(frame);

        ByteBuffer buf = frame.buffer();
        int slotCount = IndexPageLayout.readSlotCount(buf);

        // 计算移动范围
        int srcOff = IndexPageLayout.slotOffset(slotCount - 1);
        int dstOff = IndexPageLayout.slotOffset(slotCount);
        int len = (slotCount - slotNo) * IndexPageLayout.PAGE_DIR_SLOT_SIZE;

        // memmove 现有 slots
        if (len > 0) {
            mtr.memmove(frame, dstOff, srcOff, len);
        }

        // 更新 slot count
        mtr.writeShort(frame, IndexPageLayout.PAGE_N_DIR_SLOTS, (short) (slotCount + 1));
    }

    /**
     * 删除指定槽
     *
     * @param frame  BufferFrame (必须持有 X-latch)
     * @param slotNo 要删除的槽号
     * @param mtr    Mini-Transaction
     */
    public static void deleteSlot(BufferFrame frame, int slotNo, MiniTransaction mtr) {
        assertXLatched(frame);

        ByteBuffer buf = frame.buffer();
        int slotCount = IndexPageLayout.readSlotCount(buf);

        if (slotNo >= slotCount - 1) {
            // 删除最后一个槽，只需减少计数
            mtr.writeShort(frame, IndexPageLayout.PAGE_N_DIR_SLOTS, (short) (slotCount - 1));
            return;
        }

        // 计算移动范围：将 slotNo+1 到 slotCount-1 的槽向上移动
        int srcOff = IndexPageLayout.slotOffset(slotCount - 1);
        int dstOff = IndexPageLayout.slotOffset(slotCount - 2);
        int len = (slotCount - slotNo - 1) * IndexPageLayout.PAGE_DIR_SLOT_SIZE;

        // memmove 覆盖被删除的槽
        if (len > 0) {
            mtr.memmove(frame, dstOff, srcOff, len);
        }

        // 更新 slot count
        mtr.writeShort(frame, IndexPageLayout.PAGE_N_DIR_SLOTS, (short) (slotCount - 1));
    }

    // ==================== 记录链表操作 ====================

    /**
     * 设置记录的下一条记录偏移
     *
     * @param frame      BufferFrame (必须持有 X-latch)
     * @param recOffset  当前记录偏移
     * @param nextOffset 下一条记录偏移，0 表示链表结束
     * @param mtr        Mini-Transaction
     */
    public static void setRecordNext(BufferFrame frame, int recOffset, int nextOffset, MiniTransaction mtr) {
        assertXLatched(frame);
        short relOffset = (short) (nextOffset == 0 ? 0 : nextOffset - recOffset);
        mtr.writeShort(frame, recOffset + IndexPageLayout.REC_OFF_NEXT, relOffset);
    }

    /**
     * 设置记录的 n_owned 值
     *
     * <p>n_owned 表示此记录在 Page Directory 中"拥有"多少条记录。</p>
     *
     * @param frame     BufferFrame (必须持有 X-latch)
     * @param recOffset 记录偏移
     * @param owned     n_owned 值 (0-15)
     * @param mtr       Mini-Transaction
     */
    public static void setRecordOwned(BufferFrame frame, int recOffset, int owned, MiniTransaction mtr) {
        assertXLatched(frame);
        ByteBuffer buf = frame.buffer();
        byte b = buf.get(recOffset + IndexPageLayout.REC_OFF_N_OWNED);
        mtr.writeByte(frame, recOffset + IndexPageLayout.REC_OFF_N_OWNED,
                (byte) ((b & 0x0F) | ((owned & 0x0F) << 4)));
    }

    // ==================== 记录写入 ====================

    /**
     * 在指定偏移写入记录数据
     *
     * <p>此方法只写入数据，不更新链表或计数。
     * 完整的记录插入应使用 insertRecord。</p>
     *
     * @param frame      BufferFrame (必须持有 X-latch)
     * @param offset     写入偏移
     * @param recordData 记录数据
     * @param mtr        Mini-Transaction
     */
    public static void writeRecordData(BufferFrame frame, int offset, byte[] recordData, MiniTransaction mtr) {
        assertXLatched(frame);
        mtr.writeBytes(frame, offset, recordData);
    }

    /**
     * 插入记录（完整流程）
     *
     * <p>在指定位置插入一条新记录，包括：</p>
     * <ul>
     *   <li>在堆顶分配空间</li>
     *   <li>写入记录数据</li>
     *   <li>更新记录链表</li>
     *   <li>更新各种计数</li>
     * </ul>
     *
     * @param frame       BufferFrame (必须持有 X-latch)
     * @param recordData  记录数据（含 record header）
     * @param insertAfter 插入位置（在此记录之后插入）
     * @param mtr         Mini-Transaction
     * @return 新记录的偏移
     */
    public static int insertRecord(BufferFrame frame, byte[] recordData, int insertAfter, MiniTransaction mtr) {
        assertXLatched(frame);

        ByteBuffer buf = frame.buffer();
        int recordSize = recordData.length;

        // 1. 检查空间
        int freeSpace = IndexPageLayout.freeSpace(buf);
        if (freeSpace < recordSize + IndexPageLayout.PAGE_DIR_SLOT_SIZE) {
            throw new IllegalStateException("Not enough space for record: need " +
                    (recordSize + IndexPageLayout.PAGE_DIR_SLOT_SIZE) + ", have " + freeSpace);
        }

        // 2. 分配空间（堆顶）
        int heapTop = IndexPageLayout.readHeapTop(buf);
        int newRecOffset = heapTop;

        // 3. 写入记录数据
        mtr.writeBytes(frame, newRecOffset, recordData);

        // 4. 更新 heap top
        mtr.writeShort(frame, IndexPageLayout.PAGE_HEAP_TOP, (short) (heapTop + recordSize));

        // 5. 更新记录链表
        int nextRec = IndexPageLayout.readRecordNext(buf, insertAfter);
        setRecordNext(frame, insertAfter, newRecOffset, mtr);
        setRecordNext(frame, newRecOffset, nextRec, mtr);

        // 6. 更新用户记录计数
        int nRecs = IndexPageLayout.readRecordCount(buf);
        mtr.writeShort(frame, IndexPageLayout.PAGE_N_RECS, (short) (nRecs + 1));

        // 7. 更新堆记录数
        int nHeap = IndexPageLayout.readHeapRecordCount(buf);
        boolean compact = IndexPageLayout.isCompactFormat(buf);
        setHeapRecordCount(frame, nHeap + 1, compact, mtr);

        // 8. 更新 last insert offset
        mtr.writeShort(frame, IndexPageLayout.PAGE_LAST_INSERT, (short) newRecOffset);

        return newRecOffset;
    }

    /**
     * 删除记录（标记删除）
     *
     * <p>将记录从链表中移除并加入空闲链表。
     * 注意：这是物理层面的删除操作，不涉及 MVCC。</p>
     *
     * @param frame     BufferFrame (必须持有 X-latch)
     * @param prevRec   被删除记录的前一条记录偏移
     * @param recOffset 被删除记录的偏移
     * @param recSize   记录大小
     * @param mtr       Mini-Transaction
     */
    public static void deleteRecord(BufferFrame frame, int prevRec, int recOffset, int recSize, MiniTransaction mtr) {
        assertXLatched(frame);

        ByteBuffer buf = frame.buffer();

        // 1. 从链表中移除
        int nextRec = IndexPageLayout.readRecordNext(buf, recOffset);
        setRecordNext(frame, prevRec, nextRec, mtr);

        // 2. 加入空闲链表
        int freeHead = IndexPageLayout.readFreeListHead(buf);
        setRecordNext(frame, recOffset, freeHead, mtr);
        mtr.writeShort(frame, IndexPageLayout.PAGE_FREE, (short) recOffset);

        // 3. 更新垃圾空间计数
        int garbage = IndexPageLayout.readGarbageSize(buf);
        mtr.writeShort(frame, IndexPageLayout.PAGE_GARBAGE, (short) (garbage + recSize));

        // 4. 更新用户记录计数
        int nRecs = IndexPageLayout.readRecordCount(buf);
        mtr.writeShort(frame, IndexPageLayout.PAGE_N_RECS, (short) (nRecs - 1));
    }

    // ==================== 辅助方法 ====================

    /**
     * 断言 BufferFrame 已持有 X-latch
     *
     * @param frame BufferFrame
     * @throws IllegalStateException 如果未持有 X-latch
     */
    private static void assertXLatched(BufferFrame frame) {
        if (!frame.isWriteLatched()) {
            throw new IllegalStateException(
                    "Must hold X-latch on frame " + frame.getFrameId() +
                            " (pageId=" + frame.getPageId() + ")");
        }
    }

    // ==================== 私有构造函数 ====================

    /**
     * 禁止实例化
     */
    private IndexPageOps() {
        throw new UnsupportedOperationException("IndexPageOps is a utility class and cannot be instantiated");
    }
}
