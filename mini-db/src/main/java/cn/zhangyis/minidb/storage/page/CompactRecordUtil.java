package cn.zhangyis.minidb.storage.page;

import java.nio.ByteBuffer;

/**
 * Compact 行格式工具类（最小实现）
 *
 * <p>此类提供 Compact 行格式的基础读取方法。
 * 所有偏移相对于 origin（记录数据起始位置）。</p>
 *
 * <h2>Compact 记录头布局</h2>
 * <pre>
 * 相对 origin 的负偏移:
 *   -5: n_owned (4 bits) + delete_mask (1 bit) + min_rec (1 bit) + n_null高位 (2 bits)
 *   -4: n_null 低位 (8 bits)
 *   -3: n_fields高位 (2 bits) + rec_type (3 bits) + heap_no高位 (3 bits)
 *   -2: heap_no 低位 (8 bits) [与下面组合为 next_record 的高字节]
 *   -1~0: next_record (2 bytes，相对偏移，从 origin 开始)
 *
 * 简化视角 (Compact 格式):
 *   origin - 5: info_bits (n_owned=4, delete=1, min_rec=1)
 *   origin - 3: rec_type (3 bits)
 *   origin - 2 ~ origin: next_record (2 bytes)
 * </pre>
 *
 * <h2>Origin 定义</h2>
 * <p><b>origin</b> 是记录数据的起始位置（不含变长字段列表和 NULL 位图）。
 * 对于 Infimum/Supremum，origin 就是它们的起始偏移（94/107）。</p>
 *
 * @author MiniDB
 * @version 1.0
 * @see IndexPageLayout
 */
public final class CompactRecordUtil {

    // ==================== Record Header 相对 origin 的负偏移 ====================

    /** next_record 字段偏移 (2 bytes，相对 origin) - 实际在 origin-2 ~ origin 之间 */
    public static final int OFF_NEXT_REC = -2;

    /** rec_type 字段所在字节偏移 (3 bits in this byte) */
    public static final int OFF_REC_TYPE = -3;

    /** n_owned 和 delete_mask 所在字节偏移 */
    public static final int OFF_N_OWNED = -5;

    /** 删除标记掩码 (在 OFF_N_OWNED 字节中) */
    public static final int DELETE_MASK = 0x20;

    /** min_rec 标记掩码 (在 OFF_N_OWNED 字节中) */
    public static final int MIN_REC_MASK = 0x10;

    // ==================== 记录类型常量 ====================

    /** 普通记录 */
    public static final int REC_TYPE_ORDINARY = 0;

    /** 非叶子节点记录（包含子页号） */
    public static final int REC_TYPE_NODE_PTR = 1;

    /** Infimum 虚拟记录 */
    public static final int REC_TYPE_INFIMUM = 2;

    /** Supremum 虚拟记录 */
    public static final int REC_TYPE_SUPREMUM = 3;

    // ==================== 读取方法 ====================

    /**
     * 读取记录的下一条记录偏移
     *
     * <p>记录头中存储的是相对偏移（相对 origin），本方法转换为绝对偏移。</p>
     *
     * @param buf    页面 ByteBuffer
     * @param origin 当前记录的 origin 位置
     * @return 下一条记录的 origin 偏移，0 表示链表结束
     */
    public static int nextRecOffset(ByteBuffer buf, int origin) {
        // next_record 存储在 origin-2 ~ origin 的 2 字节
        // 注意：InnoDB 实际格式中 next_record 存储在 origin+OFF_NEXT_REC 处
        // 但对于简化实现，我们使用 IndexPageLayout.REC_OFF_NEXT = 3
        // 这里与 IndexPageLayout 保持一致
        short rel = buf.getShort(origin + IndexPageLayout.REC_OFF_NEXT);
        if (rel == 0) {
            return 0;
        }
        return origin + rel;
    }

    /**
     * 检查记录是否被删除标记
     *
     * @param buf    页面 ByteBuffer
     * @param origin 记录的 origin 位置
     * @return 如果设置了删除标记返回 true
     */
    public static boolean isDeleted(ByteBuffer buf, int origin) {
        // 简化实现：检查记录头中的 delete flag
        // 在实际 Compact 格式中，delete flag 在 origin-5 字节的第 5 位
        byte infoBits = buf.get(origin + IndexPageLayout.REC_OFF_N_OWNED);
        return (infoBits & DELETE_MASK) != 0;
    }

    /**
     * 获取记录类型
     *
     * @param buf    页面 ByteBuffer
     * @param origin 记录的 origin 位置
     * @return 记录类型 (0=ordinary, 1=node_ptr, 2=infimum, 3=supremum)
     */
    public static int getRecType(ByteBuffer buf, int origin) {
        // rec_type 在 origin+3 字节（与 IndexPageLayout 保持一致）
        return buf.get(origin + 3) & 0x07;
    }

    /**
     * 获取记录的 n_owned 值
     *
     * <p>n_owned 表示此记录在 Page Directory 中"拥有"多少条记录。</p>
     *
     * @param buf    页面 ByteBuffer
     * @param origin 记录的 origin 位置
     * @return n_owned 值 (0-15)
     */
    public static int getNOwned(ByteBuffer buf, int origin) {
        return (buf.get(origin + IndexPageLayout.REC_OFF_N_OWNED) >> 4) & 0x0F;
    }

    /**
     * 检查记录是否为 Infimum
     *
     * @param buf    页面 ByteBuffer
     * @param origin 记录的 origin 位置
     * @return 如果是 Infimum 返回 true
     */
    public static boolean isInfimum(ByteBuffer buf, int origin) {
        return getRecType(buf, origin) == REC_TYPE_INFIMUM;
    }

    /**
     * 检查记录是否为 Supremum
     *
     * @param buf    页面 ByteBuffer
     * @param origin 记录的 origin 位置
     * @return 如果是 Supremum 返回 true
     */
    public static boolean isSupremum(ByteBuffer buf, int origin) {
        return getRecType(buf, origin) == REC_TYPE_SUPREMUM;
    }

    /**
     * 检查记录是否为用户记录（非虚拟记录）
     *
     * @param buf    页面 ByteBuffer
     * @param origin 记录的 origin 位置
     * @return 如果是用户记录返回 true
     */
    public static boolean isUserRecord(ByteBuffer buf, int origin) {
        int recType = getRecType(buf, origin);
        return recType == REC_TYPE_ORDINARY || recType == REC_TYPE_NODE_PTR;
    }

    // ==================== 私有构造函数 ====================

    /**
     * 禁止实例化
     */
    private CompactRecordUtil() {
        throw new UnsupportedOperationException("CompactRecordUtil is a utility class and cannot be instantiated");
    }
}
