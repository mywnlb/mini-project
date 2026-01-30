package cn.zhangyis.minidb.storage.page;

import cn.zhangyis.minidb.storage.buffer.BufferFrame;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * IndexPage - B+Tree 索引页的瘦包装器
 *
 * <p>IndexPage 是对 BufferFrame 的只读包装，提供对 INDEX 页面特有字段的便捷访问。
 * 所有读取操作通过 {@link IndexPageLayout} 实现，所有写入操作必须通过
 * {@link IndexPageOps} + MTR 进行。</p>
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li><b>只读包装</b>: IndexPage 不提供 setter 方法</li>
 *   <li><b>无状态</b>: 不保存任何物理状态副本</li>
 *   <li><b>委托读取</b>: 所有 getter 调用 IndexPageLayout</li>
 *   <li><b>强制 MTR</b>: 写操作通过 IndexPageOps + MTR</li>
 * </ul>
 *
 * <h2>使用模式</h2>
 * <pre>
 * // 从 BufferPool 获取页面
 * BufferFrame frame = bufferPool.getPage(pageId, FetchMode.READ_EXISTING);
 * frame.readLock();
 * try {
 *     // 包装为 IndexPage 以便访问 INDEX 特有字段
 *     IndexPage indexPage = new IndexPage(frame);
 *     int level = indexPage.getLevel();
 *     int recordCount = indexPage.getRecordCount();
 * } finally {
 *     frame.readUnlock();
 * }
 *
 * // 修改页面
 * frame.writeLock();
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     IndexPageOps.setLevel(frame, 1, mtr);
 *     mtr.commit();
 * } finally {
 *     frame.writeUnlock();
 * }
 * </pre>
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
 * @see IndexPageLayout
 * @see IndexPageOps
 * @see BufferFrame
 */
public final class IndexPage {

    /**
     * 底层 BufferFrame 引用
     *
     * <p>IndexPage 不保存任何物理状态副本，所有读取都通过此 frame 的 buffer 进行。</p>
     */
    private final BufferFrame frame;

    // ==================== 构造函数 ====================

    /**
     * 从 BufferFrame 创建 IndexPage 包装器
     *
     * <p>这是推荐的构造方式。调用者从 BufferPool 获取 BufferFrame 后，
     * 可以包装为 IndexPage 以便访问 INDEX 页面特有字段。</p>
     *
     * <p><b>注意</b>: 调用者必须确保 frame 包含有效的 INDEX 页面数据。</p>
     *
     * @param frame BufferFrame 引用（必须持有适当的 latch）
     * @throws IllegalArgumentException 如果 frame 为 null
     */
    public IndexPage(BufferFrame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("BufferFrame cannot be null");
        }
        this.frame = frame;
    }

    // ==================== Frame 访问 ====================

    /**
     * 获取底层 BufferFrame
     *
     * <p>用于需要直接访问 frame 的场景，如 IndexPageOps 操作。</p>
     *
     * @return BufferFrame 引用
     */
    public BufferFrame frame() {
        return frame;
    }

    /**
     * 获取页面的 ByteBuffer
     *
     * <p>包可见，供 IndexPageOps 等内部组件使用。</p>
     *
     * <p><b>警告</b>: 不要直接通过此 buffer 进行写操作，
     * 所有写操作应通过 MTR 进行以确保 WAL 正确性。</p>
     *
     * @return 页面的 ByteBuffer
     */
    ByteBuffer buffer() {
        return frame.buffer();
    }

    /**
     * 获取页面标识
     *
     * @return PageId
     */
    public PageId getPageId() {
        return frame.getPageId();
    }

    // ==================== Page Header Getters ====================

    /**
     * 获取 Page Directory 槽数量
     *
     * @return 槽数量 (最少 2)
     */
    public int getSlotCount() {
        return IndexPageLayout.readSlotCount(buffer());
    }

    /**
     * 获取堆顶位置
     *
     * <p>下一个新记录将从此位置开始分配空间。</p>
     *
     * @return 堆顶偏移
     */
    public int getHeapTop() {
        return IndexPageLayout.readHeapTop(buffer());
    }

    /**
     * 获取堆中记录数 (含 infimum/supremum 和已删除记录)
     *
     * @return 记录数 (低 15 位)
     */
    public int getHeapRecordCount() {
        return IndexPageLayout.readHeapRecordCount(buffer());
    }

    /**
     * 检查是否使用 Compact 行格式
     *
     * @return 如果使用 Compact 格式返回 true
     */
    public boolean isCompactFormat() {
        return IndexPageLayout.isCompactFormat(buffer());
    }

    /**
     * 获取用户记录数 (不含 infimum/supremum 和已删除)
     *
     * @return 有效用户记录数
     */
    public int getRecordCount() {
        return IndexPageLayout.readRecordCount(buffer());
    }

    /**
     * 获取 B+Tree 层级
     *
     * @return 层级 (0=叶子节点)
     */
    public int getLevel() {
        return IndexPageLayout.readLevel(buffer());
    }

    /**
     * 判断是否为叶子节点
     *
     * @return 如果 level=0 返回 true
     */
    public boolean isLeaf() {
        return IndexPageLayout.isLeaf(buffer());
    }

    /**
     * 获取索引 ID
     *
     * @return 索引 ID
     */
    public long getIndexId() {
        return IndexPageLayout.readIndexId(buffer());
    }

    /**
     * 获取空闲链表头
     *
     * @return 第一条空闲记录的偏移，0 表示无空闲记录
     */
    public int getFreeListHead() {
        return IndexPageLayout.readFreeListHead(buffer());
    }

    /**
     * 获取垃圾空间大小
     *
     * @return 已删除记录占用的字节数
     */
    public int getGarbageSize() {
        return IndexPageLayout.readGarbageSize(buffer());
    }

    /**
     * 获取最后插入位置
     *
     * @return 最后插入记录的偏移
     */
    public int getLastInsertOffset() {
        return IndexPageLayout.readLastInsertOffset(buffer());
    }

    /**
     * 获取插入方向
     *
     * @return 插入方向常量
     */
    public int getDirection() {
        return IndexPageLayout.readDirection(buffer());
    }

    /**
     * 获取同方向连续插入次数
     *
     * @return 连续插入次数
     */
    public int getDirectionCount() {
        return IndexPageLayout.readDirectionCount(buffer());
    }

    /**
     * 获取修改此页面的最大事务 ID
     *
     * @return 最大事务 ID
     */
    public long getMaxTrxId() {
        return IndexPageLayout.readMaxTrxId(buffer());
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
        return IndexPageLayout.freeSpace(buffer());
    }

    /**
     * 获取 Page Directory 底部位置
     *
     * <p>Page Directory 从页尾向上增长，底部是最后一个槽之后的位置。</p>
     *
     * @return 偏移量
     */
    public int getPageDirectoryEnd() {
        return IndexPageLayout.pageDirectoryEnd(buffer());
    }

    // ==================== Page Directory 读取 ====================

    /**
     * 获取指定槽中存储的记录偏移
     *
     * <p>槽从 0 开始编号，槽 0 在页面最末尾。</p>
     *
     * @param slotNo 槽号 (0 = 最右边的槽，指向 supremum)
     * @return 记录偏移
     */
    public int getSlotValue(int slotNo) {
        return IndexPageLayout.readSlotValue(buffer(), slotNo);
    }

    // ==================== 记录链表遍历（只读）====================

    /**
     * 获取第一条用户记录的偏移
     *
     * <p>即 Infimum 的下一条记录。如果页面为空，返回 Supremum 的偏移。</p>
     *
     * @return 第一条用户记录偏移，或 Supremum 偏移 (页面为空时)
     */
    public int getFirstUserRecordOffset() {
        return IndexPageLayout.readFirstUserRecordOffset(buffer());
    }

    /**
     * 获取记录的下一条记录偏移
     *
     * <p>记录头中存储的是相对偏移，本方法转换为绝对偏移。</p>
     *
     * @param recOffset 当前记录的偏移
     * @return 下一条记录的偏移，0 表示链表结束
     */
    public int getRecordNext(int recOffset) {
        return IndexPageLayout.readRecordNext(buffer(), recOffset);
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
        return IndexPageLayout.readRecordOwned(buffer(), recOffset);
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

    // ==================== toString ====================

    /**
     * 返回 IndexPage 的字符串表示
     *
     * @return 包含关键信息的字符串
     */
    @Override
    public String toString() {
        return String.format("IndexPage{pageId=%s, level=%d, records=%d, freeSpace=%d, indexId=%d}",
                getPageId(), getLevel(), getRecordCount(), getFreeSpace(), getIndexId());
    }
}
