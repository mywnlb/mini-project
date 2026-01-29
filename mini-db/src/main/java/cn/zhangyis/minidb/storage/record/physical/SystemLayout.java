package cn.zhangyis.minidb.storage.record.physical;

/**
 * 系统列布局
 *
 * <p>描述聚簇索引记录中系统列的布局，由 index 元数据决定。</p>
 *
 * <h2>布局 (相对 dataStart)</h2>
 * <pre>
 * ┌──────────┬───────────┬───────────┬──────────┬─────────────┐
 * │ TRX_ID   │ ROLL_PTR  │ ROW_VER   │ [ROW_ID] │ user cols   │
 * │ (6B)     │ (7B)      │ (2B)      │ (6B)     │             │
 * ├──────────┼───────────┼───────────┼──────────┼─────────────┤
 * │ +0       │ +6        │ +13       │ +15      │ +15 or +21  │
 * └──────────┴───────────┴───────────┴──────────┴─────────────┘
 * </pre>
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>I3: TRX_ID/ROLL_PTR 在 dataStart 后的位置恒定 (+0, +6)</li>
 *   <li>I7: hasRowId 是 index 级元数据，创建后不可变</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public record SystemLayout(boolean hasRowId) {

    // ==================== 系统列大小 ====================

    /** TRX_ID 大小 (6 bytes) */
    public static final int TRX_ID_SIZE = 6;

    /** ROLL_PTR 大小 (7 bytes) */
    public static final int ROLL_PTR_SIZE = 7;

    /** ROW_VERSION 大小 (2 bytes, u16) */
    public static final int ROW_VER_SIZE = 2;

    /** ROW_ID 大小 (6 bytes) */
    public static final int ROW_ID_SIZE = 6;

    // ==================== 固定偏移（相对 dataStart）====================

    /** TRX_ID 偏移 (恒定) */
    public static final int OFF_TRX_ID = 0;

    /** ROLL_PTR 偏移 (恒定) */
    public static final int OFF_ROLL_PTR = 6;

    /** ROW_VERSION 偏移 (恒定) */
    public static final int OFF_ROW_VER = 13;

    // ==================== 预定义布局 ====================

    /** 有显式主键的布局（无 ROW_ID） */
    public static final SystemLayout WITH_PK = new SystemLayout(false);

    /** 无显式主键的布局（有 ROW_ID） */
    public static final SystemLayout WITHOUT_PK = new SystemLayout(true);

    // ==================== 计算方法 ====================

    /**
     * ROW_ID 偏移
     *
     * @return ROW_ID 偏移，如果不存在返回 -1
     */
    public int offRowId() {
        return hasRowId ? 15 : -1;
    }

    /**
     * 系统列总字节数
     *
     * @return 固定系统列占用的字节数
     */
    public int fixedSysBytes() {
        // TRX_ID(6) + ROLL_PTR(7) + ROW_VER(2) = 15
        // + ROW_ID(6) = 21 (如果有)
        return hasRowId ? 21 : 15;
    }

    /**
     * 用户列起始偏移（相对 dataStart）
     *
     * @return 第一个用户列的偏移
     */
    public int userColumnsOffset() {
        return fixedSysBytes();
    }

    /**
     * 计算 TRX_ID 的绝对偏移
     *
     * @param dataStart 数据区起始位置
     * @return TRX_ID 的绝对偏移
     */
    public int trxIdOffset(int dataStart) {
        return dataStart + OFF_TRX_ID;
    }

    /**
     * 计算 ROLL_PTR 的绝对偏移
     *
     * @param dataStart 数据区起始位置
     * @return ROLL_PTR 的绝对偏移
     */
    public int rollPtrOffset(int dataStart) {
        return dataStart + OFF_ROLL_PTR;
    }

    /**
     * 计算 ROW_VERSION 的绝对偏移
     *
     * @param dataStart 数据区起始位置
     * @return ROW_VERSION 的绝对偏移
     */
    public int rowVerOffset(int dataStart) {
        return dataStart + OFF_ROW_VER;
    }

    /**
     * 计算 ROW_ID 的绝对偏移
     *
     * @param dataStart 数据区起始位置
     * @return ROW_ID 的绝对偏移，如果不存在返回 -1
     */
    public int rowIdOffset(int dataStart) {
        return hasRowId ? dataStart + 15 : -1;
    }

    /**
     * 计算用户列的绝对起始偏移
     *
     * @param dataStart 数据区起始位置
     * @return 第一个用户列的绝对偏移
     */
    public int userColumnsAbsoluteOffset(int dataStart) {
        return dataStart + userColumnsOffset();
    }

    @Override
    public String toString() {
        return String.format("SystemLayout{hasRowId=%b, sysBytes=%d}", hasRowId, fixedSysBytes());
    }
}
