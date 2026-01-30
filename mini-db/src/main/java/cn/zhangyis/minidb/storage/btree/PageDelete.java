package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.common.exception.MtrStateException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.IndexPageLayout;
import cn.zhangyis.minidb.storage.page.IndexPageOps;

import java.nio.ByteBuffer;

/**
 * 页内删除操作
 *
 * <p>实现 B+Tree 单页内的记录删除，包括：</p>
 * <ul>
 *   <li>物理删除（从链表移除，加入空闲链表）</li>
 *   <li>Page Directory 维护</li>
 * </ul>
 *
 * <h2>删除策略</h2>
 * <p>当前实现为物理删除（purge），直接从链表移除记录。
 * 后续 MVCC 实现时会增加逻辑删除（delete-mark）。</p>
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>I1: 记录链完整性 - 删除后链表仍然完整</li>
 *   <li>I2: PAGE_N_RECS 准确性 - 删除后计数 -1</li>
 *   <li>I3: Page Directory 有序性 - 可能需要合并 slot</li>
 *   <li>I4: n_owned 约束 - 每个 slot 拥有至少 1 条记录</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class PageDelete {

    /** 每个 slot 最小拥有记录数（除了 infimum slot） */
    private static final int MIN_N_OWNED = 4;

    /**
     * 删除指定键的记录
     *
     * <p>完整的删除流程：</p>
     * <ol>
     *   <li>查找记录位置</li>
     *   <li>从链表中移除</li>
     *   <li>加入空闲链表</li>
     *   <li>更新 Page Directory</li>
     * </ol>
     *
     * @param frame      BufferFrame (必须持有 X-latch)
     * @param searchKey  要删除的键
     * @param recordSize 记录大小（用于更新垃圾空间计数）
     * @param comparator 记录比较器
     * @param mtr        Mini-Transaction
     * @return 如果成功删除返回 true，如果键不存在返回 false
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static boolean deleteRecord(BufferFrame frame, byte[] searchKey, int recordSize,
                                       RecordComparator comparator, MiniTransaction mtr)
            throws MiniDbException {
        ByteBuffer buf = frame.buffer();

        // 1. 查找记录
        PageSearchResult searchResult = PageSearch.search(buf, searchKey, comparator);
        if (!searchResult.isExactMatch()) {
            return false; // 键不存在
        }

        int recOffset = searchResult.getRecordOffset();

        // 2. 找到前一条记录
        int prevOffset = findPreviousRecord(buf, recOffset);
        if (prevOffset < 0) {
            throw new IllegalStateException("Cannot find previous record for offset " + recOffset);
        }

        // 3. 使用 IndexPageOps 删除记录
        IndexPageOps.deleteRecord(frame, prevOffset, recOffset, recordSize, mtr);

        // 4. 更新 Page Directory
        updatePageDirectoryAfterDelete(frame, recOffset, searchResult.getSlotNo(), mtr);

        return true;
    }

    /**
     * 在指定位置删除记录（不查找位置）
     *
     * <p>当调用者已知记录位置时使用。</p>
     *
     * @param frame      BufferFrame (必须持有 X-latch)
     * @param prevOffset 被删除记录的前一条记录偏移
     * @param recOffset  被删除记录的偏移
     * @param recordSize 记录大小
     * @param slotNo     记录所在的 slot 号
     * @param mtr        Mini-Transaction
     * @throws MiniDbException 如果 MTR 不在 ACTIVE 状态
     */
    public static void deleteRecordAt(BufferFrame frame, int prevOffset, int recOffset,
                                      int recordSize, int slotNo, MiniTransaction mtr)
            throws MiniDbException {
        // 1. 使用 IndexPageOps 删除记录
        IndexPageOps.deleteRecord(frame, prevOffset, recOffset, recordSize, mtr);

        // 2. 更新 Page Directory
        updatePageDirectoryAfterDelete(frame, recOffset, slotNo, mtr);
    }

    /**
     * 查找指定记录的前一条记录
     *
     * @param buf       页面 ByteBuffer
     * @param recOffset 目标记录偏移
     * @return 前一条记录的偏移，未找到返回 -1
     */
    private static int findPreviousRecord(ByteBuffer buf, int recOffset) {
        int current = IndexPageLayout.INFIMUM_OFFSET;

        while (current != 0 && current != IndexPageLayout.SUPREMUM_OFFSET) {
            int next = IndexPageLayout.readRecordNext(buf, current);
            if (next == recOffset) {
                return current;
            }
            current = next;
        }

        return -1;
    }

    /**
     * 删除后更新 Page Directory
     *
     * <p>删除记录后，需要更新对应 slot 的 n_owned。
     * 如果被删除的记录是 slot 的 owned record，需要特殊处理。</p>
     *
     * @param frame     BufferFrame
     * @param recOffset 被删除记录的偏移
     * @param slotNo    记录所在的 slot 号
     * @param mtr       Mini-Transaction
     */
    private static void updatePageDirectoryAfterDelete(BufferFrame frame, int recOffset,
                                                       int slotNo, MiniTransaction mtr)
            throws MiniDbException {
        ByteBuffer buf = frame.buffer();

        // 获取 slot 指向的记录
        int ownedRecOffset = IndexPageLayout.readSlotValue(buf, slotNo);

        if (ownedRecOffset == recOffset) {
            // 被删除的记录是 slot 的 owned record
            // 需要将 slot 指向前一条记录，并转移 n_owned
            handleOwnedRecordDeletion(frame, slotNo, mtr);
        } else {
            // 被删除的记录不是 owned record，只需减少 n_owned
            int nOwned = IndexPageLayout.readRecordOwned(buf, ownedRecOffset);
            if (nOwned > 1) {
                IndexPageOps.setRecordOwned(frame, ownedRecOffset, nOwned - 1, mtr);
            }
            // 如果 n_owned 变得太小，可能需要合并 slot（简化实现暂不处理）
        }
    }

    /**
     * 处理 owned record 被删除的情况
     *
     * <p>当 slot 指向的记录被删除时：</p>
     * <ol>
     *   <li>找到该 slot 范围内的前一条记录</li>
     *   <li>将 slot 指向该记录</li>
     *   <li>设置新 owned record 的 n_owned</li>
     * </ol>
     *
     * @param frame  BufferFrame
     * @param slotNo slot 号
     * @param mtr    Mini-Transaction
     */
    private static void handleOwnedRecordDeletion(BufferFrame frame, int slotNo,
                                                  MiniTransaction mtr) throws MiniDbException {
        ByteBuffer buf = frame.buffer();
        int slotCount = IndexPageLayout.readSlotCount(buf);

        // 获取当前 slot 的 owned record 信息
        int oldOwnedOffset = IndexPageLayout.readSlotValue(buf, slotNo);
        int oldNOwned = IndexPageLayout.readRecordOwned(buf, oldOwnedOffset);

        // 找到 slot 范围内的前一条记录
        int startOffset;
        if (slotNo >= slotCount - 1) {
            startOffset = IndexPageLayout.INFIMUM_OFFSET;
        } else {
            startOffset = IndexPageLayout.readSlotValue(buf, slotNo + 1);
        }

        // 从 startOffset 开始找到 oldOwnedOffset 的前一条记录
        int prevOffset = startOffset;
        int current = IndexPageLayout.readRecordNext(buf, startOffset);

        while (current != oldOwnedOffset && current != 0 && current != IndexPageLayout.SUPREMUM_OFFSET) {
            prevOffset = current;
            current = IndexPageLayout.readRecordNext(buf, current);
        }

        if (prevOffset == startOffset && slotNo < slotCount - 1) {
            // slot 范围内只有被删除的这一条记录，需要删除这个 slot
            // 将 n_owned 转移给下一个 slot
            if (slotNo > 0) {
                int nextSlotOwned = IndexPageLayout.readSlotValue(buf, slotNo - 1);
                int nextNOwned = IndexPageLayout.readRecordOwned(buf, nextSlotOwned);
                IndexPageOps.setRecordOwned(frame, nextSlotOwned, nextNOwned + oldNOwned - 1, mtr);
                IndexPageOps.deleteSlot(frame, slotNo, mtr);
            }
        } else {
            // 将 slot 指向前一条记录
            IndexPageOps.setSlotValue(frame, slotNo, prevOffset, mtr);
            IndexPageOps.setRecordOwned(frame, prevOffset, oldNOwned - 1, mtr);
        }
    }

    /**
     * 标记记录为已删除（逻辑删除，用于 MVCC）
     *
     * <p>设置记录头中的 delete flag，但不从链表中移除。
     * 后续由 purge 线程进行物理删除。</p>
     *
     * @param frame     BufferFrame (必须持有 X-latch)
     * @param recOffset 记录偏移
     * @param mtr       Mini-Transaction
     */
    public static void markDeleted(BufferFrame frame, int recOffset, MiniTransaction mtr)
            throws MiniDbException {
        ByteBuffer buf = frame.buffer();

        // 读取当前 info_bits
        byte infoBits = buf.get(recOffset + IndexPageLayout.REC_OFF_N_OWNED);

        // 设置 delete flag (bit 5)
        infoBits |= 0x20;

        mtr.writeByte(frame, recOffset + IndexPageLayout.REC_OFF_N_OWNED, infoBits);
    }

    /**
     * 检查记录是否被标记为已删除
     *
     * @param buf       页面 ByteBuffer
     * @param recOffset 记录偏移
     * @return 如果设置了删除标记返回 true
     */
    public static boolean isMarkedDeleted(ByteBuffer buf, int recOffset) {
        byte infoBits = buf.get(recOffset + IndexPageLayout.REC_OFF_N_OWNED);
        return (infoBits & 0x20) != 0;
    }

    // 禁止实例化
    private PageDelete() {
        throw new UnsupportedOperationException("PageDelete is a utility class");
    }
}
