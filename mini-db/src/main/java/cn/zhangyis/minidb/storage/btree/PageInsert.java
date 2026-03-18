package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.IndexPageLayout;
import cn.zhangyis.minidb.storage.page.IndexPageOps;

import java.nio.ByteBuffer;

/**
 * 页内插入操作
 *
 * <p>实现 B+Tree 单页内的记录插入，包括：</p>
 * <ul>
 *   <li>空间检查</li>
 *   <li>记录写入</li>
 *   <li>链表维护</li>
 *   <li>Page Directory 维护</li>
 * </ul>
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>I1: 记录链完整性 - 插入后链表仍然有序且完整</li>
 *   <li>I2: PAGE_N_RECS 准确性 - 插入后计数 +1</li>
 *   <li>I3: Page Directory 有序性 - 可能需要分裂 slot</li>
 *   <li>I4: n_owned 约束 - 每个 slot 拥有 1-8 条记录</li>
 * </ul>
 *
 * <h2>Page Directory 维护规则</h2>
 * <p>当一个 slot 的 n_owned 超过 8 时，需要分裂该 slot：</p>
 * <ol>
 *   <li>在当前 slot 之后插入新 slot</li>
 *   <li>新 slot 指向中间记录</li>
 *   <li>调整两个 slot 的 n_owned</li>
 * </ol>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class PageInsert {

    /** 每个 slot 最大拥有记录数 */
    private static final int MAX_N_OWNED = 8;

    /** slot 分裂时，原 slot 保留的记录数 */
    private static final int SPLIT_N_OWNED = 4;

    /**
     * 在页内插入记录
     *
     * <p>完整的插入流程：</p>
     * <ol>
     *   <li>检查空间是否足够</li>
     *   <li>查找插入位置</li>
     *   <li>分配空间并写入记录</li>
     *   <li>更新记录链表</li>
     *   <li>更新 Page Directory</li>
     * </ol>
     *
     * @param frame       BufferFrame (必须持有 X-latch)
     * @param recordData  完整的记录数据（含记录头）
     * @param searchKey   记录的键（用于查找插入位置）
     * @param comparator  记录比较器
     * @param mtr         Mini-Transaction
     * @return 新记录的偏移，如果空间不足返回 -1
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static int insertRecord(BufferFrame frame, byte[] recordData, byte[] searchKey,
                                   RecordComparator comparator, MiniTransaction mtr)
            throws MiniDbException {
        return insertRecord(frame, recordData, 0, searchKey, comparator, mtr);
    }

    public static int insertRecord(BufferFrame frame, byte[] recordData, int recordHeaderOffset,
                                   byte[] searchKey, RecordComparator comparator, MiniTransaction mtr)
            throws MiniDbException {
        ByteBuffer buf = frame.buffer();

        // 1. 检查空间
        int requiredSpace = recordData.length + IndexPageLayout.PAGE_DIR_SLOT_SIZE;
        int freeSpace = IndexPageLayout.freeSpace(buf);
        if (freeSpace < requiredSpace) {
            return -1; // 空间不足，需要分裂
        }

        // 2. 查找插入位置
        PageSearchResult searchResult = PageSearch.search(buf, searchKey, comparator);
        int insertAfter = searchResult.getRecordOffset();

        // 3. 使用 IndexPageOps 插入记录（更新链表和计数）
        int newRecOffset = IndexPageOps.insertRecord(frame, recordData, recordHeaderOffset, insertAfter, mtr);

        // 4. 更新 Page Directory
        updatePageDirectory(frame, newRecOffset, searchResult.getSlotNo(), mtr);

        return newRecOffset;
    }

    /**
     * 在指定位置插入记录（不查找位置）
     *
     * <p>当调用者已知插入位置时使用。</p>
     *
     * @param frame       BufferFrame (必须持有 X-latch)
     * @param recordData  完整的记录数据（含记录头）
     * @param insertAfter 插入位置（在此记录之后插入）
     * @param slotNo      目标 slot 号
     * @param mtr         Mini-Transaction
     * @return 新记录的偏移，如果空间不足返回 -1
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static int insertRecordAt(BufferFrame frame, byte[] recordData, int insertAfter,
                                     int slotNo, MiniTransaction mtr)
            throws MiniDbException {
        return insertRecordAt(frame, recordData, 0, insertAfter, slotNo, mtr);
    }

    public static int insertRecordAt(BufferFrame frame, byte[] recordData, int recordHeaderOffset,
                                     int insertAfter, int slotNo, MiniTransaction mtr)
            throws MiniDbException {
        ByteBuffer buf = frame.buffer();

        // 1. 检查空间
        int requiredSpace = recordData.length + IndexPageLayout.PAGE_DIR_SLOT_SIZE;
        int freeSpace = IndexPageLayout.freeSpace(buf);
        if (freeSpace < requiredSpace) {
            return -1;
        }

        // 2. 插入记录
        int newRecOffset = IndexPageOps.insertRecord(frame, recordData, recordHeaderOffset, insertAfter, mtr);

        // 3. 更新 Page Directory
        updatePageDirectory(frame, newRecOffset, slotNo, mtr);

        return newRecOffset;
    }

    /**
     * 更新 Page Directory
     *
     * <p>插入记录后，需要更新对应 slot 的 n_owned。
     * 如果 n_owned 超过 MAX_N_OWNED，需要分裂 slot。</p>
     *
     * @param frame        BufferFrame
     * @param newRecOffset 新记录偏移
     * @param slotNo       目标 slot 号
     * @param mtr          Mini-Transaction
     */
    private static void updatePageDirectory(BufferFrame frame, int newRecOffset, int slotNo,
                                            MiniTransaction mtr) throws MiniDbException {
        ByteBuffer buf = frame.buffer();

        // 获取 slot 指向的记录（owned record）
        int ownedRecOffset = IndexPageLayout.readSlotValue(buf, slotNo);

        // 增加 n_owned
        int nOwned = IndexPageLayout.readRecordOwned(buf, ownedRecOffset);
        nOwned++;

        if (nOwned <= MAX_N_OWNED) {
            // 不需要分裂，直接更新 n_owned
            IndexPageOps.setRecordOwned(frame, ownedRecOffset, nOwned, mtr);
        } else {
            // 需要分裂 slot
            splitSlot(frame, slotNo, mtr);
        }
    }

    /**
     * 分裂 Page Directory slot
     *
     * <p>当一个 slot 的 n_owned 超过 MAX_N_OWNED 时，需要分裂：</p>
     * <ol>
     *   <li>找到 slot 范围内的中间记录</li>
     *   <li>在当前 slot 之后插入新 slot，指向中间记录</li>
     *   <li>设置新 slot 的 n_owned = SPLIT_N_OWNED</li>
     *   <li>设置原 slot 的 n_owned = 原值 - SPLIT_N_OWNED + 1</li>
     * </ol>
     *
     * @param frame  BufferFrame
     * @param slotNo 要分裂的 slot 号
     * @param mtr    Mini-Transaction
     */
    private static void splitSlot(BufferFrame frame, int slotNo, MiniTransaction mtr)
            throws MiniDbException {
        ByteBuffer buf = frame.buffer();
        int slotCount = IndexPageLayout.readSlotCount(buf);

        // 获取当前 slot 指向的记录
        int ownedRecOffset = IndexPageLayout.readSlotValue(buf, slotNo);
        int nOwned = IndexPageLayout.readRecordOwned(buf, ownedRecOffset);

        // 找到中间记录（从上一个 slot 开始数 SPLIT_N_OWNED 条）
        int startOffset;
        if (slotNo >= slotCount - 1) {
            startOffset = IndexPageLayout.INFIMUM_OFFSET;
        } else {
            startOffset = IndexPageLayout.readSlotValue(buf, slotNo + 1);
        }

        // 从 startOffset 的下一条开始，数 SPLIT_N_OWNED 条
        int midRecOffset = startOffset;
        for (int i = 0; i < SPLIT_N_OWNED; i++) {
            midRecOffset = IndexPageLayout.readRecordNext(buf, midRecOffset);
            if (midRecOffset == 0 || midRecOffset == IndexPageLayout.SUPREMUM_OFFSET) {
                break;
            }
        }

        // 插入新 slot
        IndexPageOps.insertSlot(frame, slotNo + 1, mtr);

        // 设置新 slot 指向中间记录
        IndexPageOps.setSlotValue(frame, slotNo + 1, midRecOffset, mtr);

        // 设置中间记录的 n_owned
        IndexPageOps.setRecordOwned(frame, midRecOffset, SPLIT_N_OWNED, mtr);

        // 更新原 slot 的 n_owned
        int newNOwned = nOwned - SPLIT_N_OWNED + 1;
        IndexPageOps.setRecordOwned(frame, ownedRecOffset, newNOwned, mtr);
    }

    /**
     * 检查是否有足够空间插入记录
     *
     * @param pageBuffer 页面 ByteBuffer
     * @param recordSize 记录大小
     * @return 如果空间足够返回 true
     */
    public static boolean hasSpaceFor(ByteBuffer pageBuffer, int recordSize) {
        int requiredSpace = recordSize + IndexPageLayout.PAGE_DIR_SLOT_SIZE;
        int freeSpace = IndexPageLayout.freeSpace(pageBuffer);
        return freeSpace >= requiredSpace;
    }

    /**
     * 计算页面可容纳的最大记录大小
     *
     * @param pageBuffer 页面 ByteBuffer
     * @return 最大记录大小
     */
    public static int maxRecordSize(ByteBuffer pageBuffer) {
        int freeSpace = IndexPageLayout.freeSpace(pageBuffer);
        return Math.max(0, freeSpace - IndexPageLayout.PAGE_DIR_SLOT_SIZE);
    }

    // 禁止实例化
    private PageInsert() {
        throw new UnsupportedOperationException("PageInsert is a utility class");
    }
}
