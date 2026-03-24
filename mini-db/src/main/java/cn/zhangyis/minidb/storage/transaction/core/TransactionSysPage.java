package cn.zhangyis.minidb.storage.transaction.core;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.constants.StorageConstants;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 事务系统页 (Transaction System Page)
 *
 * <p>事务系统页存储事务子系统的全局元数据，
 * 包括下一个可分配的事务 ID、Rollback Segment 头信息等。</p>
 *
 * <p>Catalog 固定占用 system space 的 page 3/4/5，其中 page 5 是 DDL log head。
 * 因此 TRX_SYS 不能再固定复用 page 5，而是从 page 6 开始扫描已有页；
 * 若不存在，则在 page 6 之后追加分配一个专用页面。</p>
 *
 * <h2>页面布局</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                     FIL Header (38 bytes)                       │
 * │  page_type = FIL_PAGE_TRX_SYS (7)                               │
 * ├─────────────────────────────────────────────────────────────────┤
 * │                    TRX_SYS Header (64 bytes)                    │
 * │  ┌──────────────────┬────────────────┬────────────────────────┐ │
 * │  │ TRX_SYS_TRX_ID   │ TRX_SYS_FSEG   │ TRX_SYS_RSEGS          │ │
 * │  │ (8B)             │ (10B)          │ (128*8=1024B)          │ │
 * │  │ 下一个 TRX_ID    │ 预留           │ Rseg 槽位数组          │ │
 * │  └──────────────────┴────────────────┴────────────────────────┘ │
 * ├─────────────────────────────────────────────────────────────────┤
 * │                     Reserved Space                              │
 * ├─────────────────────────────────────────────────────────────────┤
 * │                     FIL Trailer (8 bytes)                       │
 * └─────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Rseg 槽位格式 (每个 8 bytes)</h2>
 * <pre>
 * ┌──────────────┬──────────────┐
 * │ space_id     │ page_no      │
 * │ (4B)         │ (4B)         │
 * └──────────────┴──────────────┘
 * </pre>
 *
 * <h2>设计约束 (Invariants)</h2>
 * <ul>
 *   <li><b>T1</b>: TRX_ID 全局递增，永不复用</li>
 *   <li><b>TM4</b>: TRX_ID 分配后必须持久化才能使用</li>
 *   <li><b>TM5</b>: 崩溃恢复时从系统页恢复 TRX_ID</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 初始化系统页
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     Page sysPage = mtr.newPage(spaceId);
 *     TransactionSysPage.init(sysPage.getBuffer(), spaceId);
 *     mtr.markDirty(sysPage);
 *     mtr.commit();
 * }
 *
 * // 分配事务 ID
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     Page sysPage = mtr.getPage(sysPageId);
 *     TransactionId trxId = TransactionSysPage.allocateTrxId(sysPage.getBuffer());
 *     mtr.markDirty(sysPage);
 *     mtr.commit();
 * }
 * }</pre>
 *
 * @author MiniDB
 * @version 1.0
 * @see TransactionId
 */
public final class TransactionSysPage {

    // ==================== 页面类型 ====================

    /**
     * 事务系统页类型
     */
    public static final int PAGE_TYPE_TRX_SYS = 7;

    // ==================== 偏移常量 ====================

    /**
     * TRX_SYS Header 起始偏移 (紧跟 FIL Header)
     */
    public static final int TRX_SYS_HEADER_OFFSET = StorageConstants.FIL_HEADER_SIZE;

    /**
     * 下一个 TRX_ID 字段偏移 (8 bytes)
     *
     * <p>存储下一个要分配的事务 ID。
     * 注意：只使用低 48 位，高 16 位保留。</p>
     */
    public static final int TRX_SYS_TRX_ID_OFFSET = TRX_SYS_HEADER_OFFSET + 0;

    /**
     * 文件段头偏移 (10 bytes, 预留)
     */
    public static final int TRX_SYS_FSEG_OFFSET = TRX_SYS_HEADER_OFFSET + 8;

    /**
     * Rollback Segment 数组偏移
     */
    public static final int TRX_SYS_RSEGS_OFFSET = TRX_SYS_HEADER_OFFSET + 18;

    /**
     * 每个 Rseg 槽位大小 (8 bytes)
     */
    public static final int RSEG_SLOT_SIZE = 8;

    /**
     * 最大 Rseg 数量
     */
    public static final int MAX_RSEGS = 128;

    /**
     * TRX_SYS Header 总大小
     */
    public static final int TRX_SYS_HEADER_SIZE = 18 + MAX_RSEGS * RSEG_SLOT_SIZE;

    /**
     * 初始 TRX_ID 值 (从 1 开始)
     */
    public static final long INITIAL_TRX_ID = 1L;

    /**
     * 默认系统页号
     */
    public static final int DEFAULT_PAGE_NO = 6;

    /**
     * 旧版本将 TRX_SYS 固定写在 page 5。
     *
     * <p>Catalog bootstrap 会在升级路径中识别该 legacy 布局，
     * 先迁移 nextTrxId，再把 page 5 改造成 DDL log head。</p>
     */
    public static final int LEGACY_PAGE_NO = 5;

    // ==================== 私有构造函数 ====================

    private TransactionSysPage() {
        throw new UnsupportedOperationException("Utility class");
    }

    // ==================== 初始化方法 ====================

    /**
     * 初始化事务系统页
     *
     * @param buf     页面缓冲区
     * @param spaceId 表空间 ID
     */
    public static void init(ByteBuffer buf, int spaceId) {
        // 设置页面类型 (FIL Header offset 24)
        buf.putShort(24, (short) PAGE_TYPE_TRX_SYS);

        // 设置表空间 ID (FIL Header offset 34)
        buf.putInt(34, spaceId);

        // 初始化下一个 TRX_ID
        setNextTrxId(buf, INITIAL_TRX_ID);

        // 初始化 Rseg 槽位 (全部设为无效)
        for (int i = 0; i < MAX_RSEGS; i++) {
            setRsegSlot(buf, i, StorageConstants.FIL_NULL, StorageConstants.FIL_NULL);
        }
    }

    /**
     * 初始化事务系统页 (使用 Page 对象)
     *
     * @param page    页面对象
     * @param spaceId 表空间 ID
     */
    public static void init(Page page, int spaceId) {
        init(page.getBuffer(), spaceId);
    }

    // ==================== TRX_ID 操作 ====================

    /**
     * 获取下一个 TRX_ID (不分配)
     *
     * @param buf 页面缓冲区
     * @return 下一个 TRX_ID 值
     */
    public static long getNextTrxId(ByteBuffer buf) {
        return buf.getLong(TRX_SYS_TRX_ID_OFFSET);
    }

    /**
     * 设置下一个 TRX_ID
     *
     * @param buf   页面缓冲区
     * @param trxId 新的 TRX_ID 值
     */
    public static void setNextTrxId(ByteBuffer buf, long trxId) {
        buf.putLong(TRX_SYS_TRX_ID_OFFSET, trxId);
    }

    /**
     * 分配一个新的 TRX_ID
     *
     * <p>原子地获取并递增 TRX_ID。调用者必须确保在 MTR 中
     * 标记页面为脏并提交，以持久化分配的 ID。</p>
     *
     * <h3>重要</h3>
     * <p>此方法不是线程安全的！调用者必须通过 MTR/锁
     * 或其他方式确保串行访问。</p>
     *
     * @param buf 页面缓冲区
     * @return 分配的 TRX_ID
     * @throws IllegalStateException 如果 TRX_ID 溢出
     */
    public static TransactionId allocateTrxId(ByteBuffer buf) {
        long currentId = getNextTrxId(buf);

        if (currentId > TransactionId.MAX_VALUE) {
            throw new IllegalStateException("TRX_ID overflow: " + currentId);
        }

        // 递增
        setNextTrxId(buf, currentId + 1);

        return new TransactionId(currentId);
    }

    /**
     * 批量分配 TRX_ID
     *
     * <p>一次分配多个 TRX_ID，减少系统页修改次数。</p>
     *
     * @param buf   页面缓冲区
     * @param count 分配数量
     * @return 第一个分配的 TRX_ID
     */
    public static TransactionId allocateTrxIdBatch(ByteBuffer buf, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }

        long currentId = getNextTrxId(buf);

        if (currentId + count - 1 > TransactionId.MAX_VALUE) {
            throw new IllegalStateException("TRX_ID batch allocation would overflow");
        }

        // 递增
        setNextTrxId(buf, currentId + count);

        return new TransactionId(currentId);
    }

    // ==================== Rseg 槽位操作 ====================

    /**
     * 获取 Rseg 槽位
     *
     * @param buf    页面缓冲区
     * @param rsegId Rseg ID (0-127)
     * @return 槽位信息 [spaceId, pageNo]
     */
    public static int[] getRsegSlot(ByteBuffer buf, int rsegId) {
        validateRsegId(rsegId);
        int offset = TRX_SYS_RSEGS_OFFSET + rsegId * RSEG_SLOT_SIZE;
        int spaceId = buf.getInt(offset);
        int pageNo = buf.getInt(offset + 4);
        return new int[]{spaceId, pageNo};
    }

    /**
     * 设置 Rseg 槽位
     *
     * @param buf     页面缓冲区
     * @param rsegId  Rseg ID (0-127)
     * @param spaceId 表空间 ID
     * @param pageNo  页号
     */
    public static void setRsegSlot(ByteBuffer buf, int rsegId, int spaceId, int pageNo) {
        validateRsegId(rsegId);
        int offset = TRX_SYS_RSEGS_OFFSET + rsegId * RSEG_SLOT_SIZE;
        buf.putInt(offset, spaceId);
        buf.putInt(offset + 4, pageNo);
    }

    /**
     * 检查 Rseg 槽位是否有效
     *
     * @param buf    页面缓冲区
     * @param rsegId Rseg ID
     * @return true 如果槽位有效
     */
    public static boolean isRsegSlotValid(ByteBuffer buf, int rsegId) {
        int[] slot = getRsegSlot(buf, rsegId);
        return slot[0] != StorageConstants.FIL_NULL && slot[1] != StorageConstants.FIL_NULL;
    }

    /**
     * 获取有效的 Rseg 数量
     *
     * @param buf 页面缓冲区
     * @return 有效 Rseg 数量
     */
    public static int getValidRsegCount(ByteBuffer buf) {
        int count = 0;
        for (int i = 0; i < MAX_RSEGS; i++) {
            if (isRsegSlotValid(buf, i)) {
                count++;
            }
        }
        return count;
    }

    // ==================== 辅助方法 ====================

    /**
     * 验证 Rseg ID 有效性
     *
     * @param rsegId Rseg ID
     */
    private static void validateRsegId(int rsegId) {
        if (rsegId < 0 || rsegId >= MAX_RSEGS) {
            throw new IllegalArgumentException(
                    "Invalid rsegId: " + rsegId + " (valid: 0-" + (MAX_RSEGS - 1) + ")");
        }
    }

    /**
     * 检查页面是否是事务系统页
     *
     * @param buf 页面缓冲区
     * @return true 如果是事务系统页
     */
    public static boolean isTrxSysPage(ByteBuffer buf) {
        short pageType = buf.getShort(24);
        return pageType == PAGE_TYPE_TRX_SYS;
    }

    /**
     * 复制 TRX_SYS 持久化头部。
     *
     * <p>用于 page 5 legacy TRX_SYS 迁移：目标页保留自己的 FIL Header
     * （尤其是 page_no / space_id），只覆盖 TRX_SYS header 区域。</p>
     *
     * @param source 源 TRX_SYS 页面
     * @param target 目标 TRX_SYS 页面
     */
    public static void copyHeader(ByteBuffer source, ByteBuffer target) {
        if (source == null || target == null) {
            throw new NullPointerException("source/target cannot be null");
        }
        if (!isTrxSysPage(source)) {
            throw new IllegalArgumentException("source is not a TRX_SYS page");
        }
        if (!isTrxSysPage(target)) {
            throw new IllegalArgumentException("target is not a TRX_SYS page");
        }

        ByteBuffer sourceCopy = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer targetCopy = target.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        byte[] header = new byte[TRX_SYS_HEADER_SIZE];

        sourceCopy.position(TRX_SYS_HEADER_OFFSET);
        sourceCopy.get(header);
        targetCopy.position(TRX_SYS_HEADER_OFFSET);
        targetCopy.put(header);
    }

    // ==================== 调试方法 ====================

    /**
     * 转储系统页信息
     *
     * @param buf 页面缓冲区
     * @return 格式化的信息字符串
     */
    public static String dump(ByteBuffer buf) {
        StringBuilder sb = new StringBuilder();
        sb.append("TransactionSysPage{\n");
        sb.append("  nextTrxId: ").append(getNextTrxId(buf)).append("\n");
        sb.append("  validRsegs: ").append(getValidRsegCount(buf)).append("/").append(MAX_RSEGS).append("\n");

        // 列出有效的 Rseg
        for (int i = 0; i < MAX_RSEGS; i++) {
            if (isRsegSlotValid(buf, i)) {
                int[] slot = getRsegSlot(buf, i);
                sb.append("  rseg[").append(i).append("]: space=")
                        .append(slot[0]).append(", page=").append(slot[1]).append("\n");
            }
        }

        sb.append("}");
        return sb.toString();
    }

    // ==================== 页面 ID 工具方法 ====================

    /**
     * 获取事务系统页默认候选页的 PageId
     *
     * @param spaceId 表空间 ID
     * @return PageId
     */
    public static PageId getPageId(int spaceId) {
        return PageId.of(spaceId, DEFAULT_PAGE_NO);
    }

    /**
     * 获取默认表空间的事务系统页默认候选 PageId
     *
     * @return PageId
     */
    public static PageId getDefaultPageId() {
        return getPageId(0);
    }

    /**
     * 获取 legacy 布局中 page 5 的 PageId。
     *
     * @param spaceId 表空间 ID
     * @return legacy PageId
     */
    public static PageId getLegacyPageId(int spaceId) {
        return PageId.of(spaceId, LEGACY_PAGE_NO);
    }
}
