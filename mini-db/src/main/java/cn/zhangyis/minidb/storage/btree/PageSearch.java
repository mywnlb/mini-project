package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.storage.page.CompactRecordUtil;
import cn.zhangyis.minidb.storage.page.IndexPageLayout;

import java.nio.ByteBuffer;

/**
 * 页内搜索算法
 *
 * <p>实现 B+Tree 单页内的二分查找和线性扫描。</p>
 *
 * <h2>搜索算法</h2>
 * <ol>
 *   <li>使用 Page Directory 进行二分查找，定位到目标 slot 范围</li>
 *   <li>在 slot 范围内进行线性扫描，找到精确位置</li>
 * </ol>
 *
 * <h2>Page Directory 结构</h2>
 * <pre>
 * slot[n-1] → infimum (最小)
 * slot[n-2] → 某条用户记录
 * ...
 * slot[1]   → 某条用户记录
 * slot[0]   → supremum (最大)
 * </pre>
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>I1: slot[i] 指向的记录 key <= slot[i-1] 指向的记录 key（slot 号越小，key 越大）</li>
 *   <li>I2: 每个 slot 拥有 1-8 条记录（n_owned）</li>
 *   <li>I3: 记录链表有序：Infimum → rec1 → rec2 → ... → Supremum</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class PageSearch {

    /**
     * 在页内搜索指定键
     *
     * <p>使用 Page Directory 二分查找 + 线性扫描的两阶段算法。</p>
     *
     * @param pageBuffer 页面 ByteBuffer
     * @param searchKey  搜索键
     * @param comparator 记录比较器
     * @return 搜索结果
     */
    public static PageSearchResult search(ByteBuffer pageBuffer, byte[] searchKey,
                                          RecordComparator comparator) {
        // 阶段 1: Page Directory 二分查找
        int slotCount = IndexPageLayout.readSlotCount(pageBuffer);

        // 二分查找边界：
        // low = 0 (supremum slot)
        // high = slotCount - 1 (infimum slot)
        int low = 0;
        int high = slotCount - 1;

        // 二分查找：找到 key 所在的 slot 范围
        // 目标：找到最大的 slot 号，使得 slot 指向的记录 <= searchKey
        while (low < high) {
            int mid = low + (high - low + 1) / 2;
            int midRecOffset = IndexPageLayout.readSlotValue(pageBuffer, mid);

            // 跳过 infimum（它没有实际的键值）
            if (midRecOffset == IndexPageLayout.INFIMUM_OFFSET) {
                high = mid - 1;
                continue;
            }

            int cmp = comparator.compareKeyToRecord(searchKey, pageBuffer, midRecOffset);

            if (cmp >= 0) {
                // searchKey >= slot[mid] 的记录，继续向高 slot 号搜索
                low = mid;
            } else {
                // searchKey < slot[mid] 的记录，向低 slot 号搜索
                high = mid - 1;
            }
        }

        // 阶段 2: 在 slot 范围内线性扫描
        // low 现在指向 searchKey 所在的 slot
        // 需要从 slot[low+1] 指向的记录开始，扫描到 slot[low] 指向的记录
        return linearSearchInSlot(pageBuffer, searchKey, comparator, low);
    }

    /**
     * 在指定 slot 范围内线性扫描
     *
     * <p>从 slot[slotNo+1] 的下一条记录开始，扫描到 slot[slotNo] 指向的记录。</p>
     *
     * @param pageBuffer 页面 ByteBuffer
     * @param searchKey  搜索键
     * @param comparator 记录比较器
     * @param slotNo     目标 slot 号
     * @return 搜索结果
     */
    private static PageSearchResult linearSearchInSlot(ByteBuffer pageBuffer, byte[] searchKey,
                                                       RecordComparator comparator, int slotNo) {
        int slotCount = IndexPageLayout.readSlotCount(pageBuffer);

        // 确定扫描起点
        int startOffset;
        if (slotNo >= slotCount - 1) {
            // 从 infimum 开始
            startOffset = IndexPageLayout.INFIMUM_OFFSET;
        } else {
            // 从上一个 slot 指向的记录开始
            startOffset = IndexPageLayout.readSlotValue(pageBuffer, slotNo + 1);
        }

        // 确定扫描终点（slot[slotNo] 指向的记录）
        int endOffset = IndexPageLayout.readSlotValue(pageBuffer, slotNo);

        // 线性扫描
        int prevOffset = startOffset;
        int currentOffset = IndexPageLayout.readRecordNext(pageBuffer, startOffset);

        while (currentOffset != 0 && currentOffset != IndexPageLayout.SUPREMUM_OFFSET) {
            // 检查是否到达终点
            if (prevOffset == endOffset) {
                break;
            }

            // 跳过 infimum（它没有实际键值）
            if (currentOffset == IndexPageLayout.INFIMUM_OFFSET) {
                prevOffset = currentOffset;
                currentOffset = IndexPageLayout.readRecordNext(pageBuffer, currentOffset);
                continue;
            }

            // 比较键值
            int cmp = comparator.compareKeyToRecord(searchKey, pageBuffer, currentOffset);

            if (cmp == 0) {
                // 精确匹配
                return new PageSearchResult(currentOffset, true, slotNo);
            } else if (cmp < 0) {
                // searchKey < currentRecord，应插入在 prevOffset 之后
                return new PageSearchResult(prevOffset, false, slotNo);
            }

            // searchKey > currentRecord，继续扫描
            prevOffset = currentOffset;
            currentOffset = IndexPageLayout.readRecordNext(pageBuffer, currentOffset);
        }

        // 扫描完成，searchKey 大于所有扫描过的记录
        // 应插入在 prevOffset 之后（但在 supremum 之前）
        return new PageSearchResult(prevOffset, false, slotNo);
    }

    /**
     * 查找插入位置
     *
     * <p>找到新记录应该插入的位置（在哪条记录之后）。</p>
     *
     * @param pageBuffer 页面 ByteBuffer
     * @param searchKey  要插入的键
     * @param comparator 记录比较器
     * @return 应插入位置的前一条记录偏移
     */
    public static int findInsertPosition(ByteBuffer pageBuffer, byte[] searchKey,
                                         RecordComparator comparator) {
        PageSearchResult result = search(pageBuffer, searchKey, comparator);
        return result.getRecordOffset();
    }

    /**
     * 查找指定键的记录
     *
     * <p>如果找到精确匹配，返回记录偏移；否则返回 -1。</p>
     *
     * @param pageBuffer 页面 ByteBuffer
     * @param searchKey  搜索键
     * @param comparator 记录比较器
     * @return 记录偏移，未找到返回 -1
     */
    public static int findRecord(ByteBuffer pageBuffer, byte[] searchKey,
                                 RecordComparator comparator) {
        PageSearchResult result = search(pageBuffer, searchKey, comparator);
        return result.isExactMatch() ? result.getRecordOffset() : -1;
    }

    /**
     * 检查键是否存在
     *
     * @param pageBuffer 页面 ByteBuffer
     * @param searchKey  搜索键
     * @param comparator 记录比较器
     * @return 如果键存在返回 true
     */
    public static boolean containsKey(ByteBuffer pageBuffer, byte[] searchKey,
                                      RecordComparator comparator) {
        return findRecord(pageBuffer, searchKey, comparator) >= 0;
    }

    // 禁止实例化
    private PageSearch() {
        throw new UnsupportedOperationException("PageSearch is a utility class");
    }
}
