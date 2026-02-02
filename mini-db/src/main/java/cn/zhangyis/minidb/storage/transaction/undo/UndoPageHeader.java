package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.constants.StorageConstants;
import cn.zhangyis.minidb.storage.transaction.core.TransactionId;

import java.nio.ByteBuffer;

/**
 * Undo Page Header 布局和操作
 *
 * <h2>Undo Page 完整布局</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                     FIL Header (38 bytes)                       │
 * │  page_type = FIL_PAGE_UNDO_LOG (2)                             │
 * ├─────────────────────────────────────────────────────────────────┤
 * │                    Undo Page Header (28 bytes)                  │
 * │  ┌──────────────┬──────────┬──────────┬───────────────────────┐│
 * │  │ undo_type    │ last_log │ free_off │ log_start | trx_id   ││
 * │  │ (2B)         │ offset   │ (2B)     │ (2B)      | (6B)     ││
 * │  │              │ (2B)     │          │           |          ││
 * │  └──────────────┴──────────┴──────────┴───────────────────────┘│
 * │  ┌───────────┬───────────┬───────────────────────────────────┐ │
 * │  │ rseg_id   │ page_list │ next_page  | prev_page            │ │
 * │  │ (1B)      │ (12B)     │ (4B)       | (4B) [=FIL_HEADER]   │ │
 * │  └───────────┴───────────┴───────────────────────────────────┘ │
 * ├─────────────────────────────────────────────────────────────────┤
 * │                    Undo Records (variable)                      │
 * │                         ...                                     │
 * ├─────────────────────────────────────────────────────────────────┤
 * │                     Free Space                                  │
 * ├─────────────────────────────────────────────────────────────────┤
 * │                     FIL Trailer (8 bytes)                       │
 * └─────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Undo Page Header 字段</h2>
 * <ul>
 *   <li><b>undo_type</b>: Undo 类型 (INSERT=1, UPDATE=2)</li>
 *   <li><b>last_log_offset</b>: 最后一条 Undo 记录的偏移</li>
 *   <li><b>free_offset</b>: 空闲空间起始偏移</li>
 *   <li><b>log_start</b>: 第一条 Undo 记录的偏移</li>
 *   <li><b>trx_id</b>: 使用此页面的事务 ID</li>
 *   <li><b>rseg_id</b>: 所属 Rollback Segment ID</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public final class UndoPageHeader {

    // ==================== 页头常量 ====================

    /**
     * Undo Page Header 起始偏移 (紧跟 FIL Header)
     */
    public static final int HEADER_OFFSET = StorageConstants.FIL_HEADER_SIZE;

    /**
     * Undo 类型字段偏移 (2 bytes)
     * <p>值: 1=INSERT, 2=UPDATE</p>
     */
    public static final int OFF_UNDO_TYPE = 0;

    /**
     * 最后一条 Undo 记录偏移字段 (2 bytes)
     */
    public static final int OFF_LAST_LOG = 2;

    /**
     * 空闲空间起始偏移字段 (2 bytes)
     */
    public static final int OFF_FREE = 4;

    /**
     * 第一条 Undo 记录偏移字段 (2 bytes)
     */
    public static final int OFF_LOG_START = 6;

    /**
     * 事务 ID 字段 (6 bytes)
     */
    public static final int OFF_TRX_ID = 8;

    /**
     * Rollback Segment ID 字段 (1 byte)
     */
    public static final int OFF_RSEG_ID = 14;

    /**
     * 页面状态字段 (1 byte)
     * <p>值: 1=ACTIVE, 2=CACHED, 3=TO_FREE, 4=TO_PURGE</p>
     */
    public static final int OFF_STATE = 15;

    /**
     * Undo Page Header 大小 (16 bytes 核心字段)
     */
    public static final int HEADER_SIZE = 16;

    /**
     * Undo 记录起始偏移
     */
    public static final int UNDO_LOG_START = HEADER_OFFSET + HEADER_SIZE;

    /**
     * 页面可用空间 (扣除 header 和 trailer)
     */
    public static final int USABLE_SPACE = StorageConstants.PAGE_SIZE
            - StorageConstants.FIL_HEADER_SIZE
            - HEADER_SIZE
            - StorageConstants.FIL_TRAILER_SIZE;

    // ==================== Undo 类型常量 ====================

    /** INSERT Undo 页面类型 */
    public static final int UNDO_INSERT = 1;

    /** UPDATE/DELETE Undo 页面类型 */
    public static final int UNDO_UPDATE = 2;

    // ==================== 页面状态常量 ====================

    /** 活跃状态：正在被事务使用 */
    public static final int STATE_ACTIVE = 1;

    /** 缓存状态：可以被重用 */
    public static final int STATE_CACHED = 2;

    /** 待释放状态：等待 Purge 后释放 */
    public static final int STATE_TO_FREE = 3;

    /** 待清理状态：需要 Purge 处理 */
    public static final int STATE_TO_PURGE = 4;

    // ==================== 私有构造函数 ====================

    private UndoPageHeader() {
        throw new UnsupportedOperationException("Utility class");
    }

    // ==================== 读取方法 ====================

    /**
     * 读取 Undo 类型
     *
     * @param buf 页面缓冲区
     * @return Undo 类型 (1=INSERT, 2=UPDATE)
     */
    public static int getUndoType(ByteBuffer buf) {
        return buf.getShort(HEADER_OFFSET + OFF_UNDO_TYPE) & 0xFFFF;
    }

    /**
     * 读取最后一条 Undo 记录偏移
     *
     * @param buf 页面缓冲区
     * @return 偏移量
     */
    public static int getLastLogOffset(ByteBuffer buf) {
        return buf.getShort(HEADER_OFFSET + OFF_LAST_LOG) & 0xFFFF;
    }

    /**
     * 读取空闲空间起始偏移
     *
     * @param buf 页面缓冲区
     * @return 偏移量
     */
    public static int getFreeOffset(ByteBuffer buf) {
        return buf.getShort(HEADER_OFFSET + OFF_FREE) & 0xFFFF;
    }

    /**
     * 读取第一条 Undo 记录偏移
     *
     * @param buf 页面缓冲区
     * @return 偏移量
     */
    public static int getLogStart(ByteBuffer buf) {
        return buf.getShort(HEADER_OFFSET + OFF_LOG_START) & 0xFFFF;
    }

    /**
     * 读取事务 ID
     *
     * @param buf 页面缓冲区
     * @return 事务 ID
     */
    public static TransactionId getTrxId(ByteBuffer buf) {
        return TransactionId.readFrom(buf, HEADER_OFFSET + OFF_TRX_ID);
    }

    /**
     * 读取 Rollback Segment ID
     *
     * @param buf 页面缓冲区
     * @return rseg_id
     */
    public static int getRsegId(ByteBuffer buf) {
        return buf.get(HEADER_OFFSET + OFF_RSEG_ID) & 0xFF;
    }

    /**
     * 读取页面状态
     *
     * @param buf 页面缓冲区
     * @return 状态值
     */
    public static int getState(ByteBuffer buf) {
        return buf.get(HEADER_OFFSET + OFF_STATE) & 0xFF;
    }

    /**
     * 获取剩余空闲空间
     *
     * @param buf 页面缓冲区
     * @return 剩余字节数
     */
    public static int getFreeSpace(ByteBuffer buf) {
        int freeOffset = getFreeOffset(buf);
        int maxOffset = StorageConstants.PAGE_SIZE - StorageConstants.FIL_TRAILER_SIZE;
        return maxOffset - freeOffset;
    }

    /**
     * 检查是否有足够空间
     *
     * @param buf        页面缓冲区
     * @param recordSize 记录大小
     * @return true 如果有足够空间
     */
    public static boolean hasSpace(ByteBuffer buf, int recordSize) {
        return getFreeSpace(buf) >= recordSize;
    }

    // ==================== 写入方法 ====================

    /**
     * 写入 Undo 类型
     *
     * @param buf      页面缓冲区
     * @param undoType Undo 类型
     */
    public static void setUndoType(ByteBuffer buf, int undoType) {
        buf.putShort(HEADER_OFFSET + OFF_UNDO_TYPE, (short) undoType);
    }

    /**
     * 写入最后一条 Undo 记录偏移
     *
     * @param buf    页面缓冲区
     * @param offset 偏移量
     */
    public static void setLastLogOffset(ByteBuffer buf, int offset) {
        buf.putShort(HEADER_OFFSET + OFF_LAST_LOG, (short) offset);
    }

    /**
     * 写入空闲空间起始偏移
     *
     * @param buf    页面缓冲区
     * @param offset 偏移量
     */
    public static void setFreeOffset(ByteBuffer buf, int offset) {
        buf.putShort(HEADER_OFFSET + OFF_FREE, (short) offset);
    }

    /**
     * 写入第一条 Undo 记录偏移
     *
     * @param buf    页面缓冲区
     * @param offset 偏移量
     */
    public static void setLogStart(ByteBuffer buf, int offset) {
        buf.putShort(HEADER_OFFSET + OFF_LOG_START, (short) offset);
    }

    /**
     * 写入事务 ID
     *
     * @param buf   页面缓冲区
     * @param trxId 事务 ID
     */
    public static void setTrxId(ByteBuffer buf, TransactionId trxId) {
        trxId.writeTo(buf, HEADER_OFFSET + OFF_TRX_ID);
    }

    /**
     * 写入 Rollback Segment ID
     *
     * @param buf    页面缓冲区
     * @param rsegId rseg_id
     */
    public static void setRsegId(ByteBuffer buf, int rsegId) {
        buf.put(HEADER_OFFSET + OFF_RSEG_ID, (byte) rsegId);
    }

    /**
     * 写入页面状态
     *
     * @param buf   页面缓冲区
     * @param state 状态值
     */
    public static void setState(ByteBuffer buf, int state) {
        buf.put(HEADER_OFFSET + OFF_STATE, (byte) state);
    }

    // ==================== 初始化方法 ====================

    /**
     * 初始化 Undo Page Header
     *
     * @param buf      页面缓冲区
     * @param undoType Undo 类型 (INSERT=1, UPDATE=2)
     * @param trxId    事务 ID
     * @param rsegId   Rollback Segment ID
     */
    public static void init(ByteBuffer buf, int undoType, TransactionId trxId, int rsegId) {
        setUndoType(buf, undoType);
        setLastLogOffset(buf, 0);
        setFreeOffset(buf, UNDO_LOG_START);
        setLogStart(buf, UNDO_LOG_START);
        setTrxId(buf, trxId);
        setRsegId(buf, rsegId);
        setState(buf, STATE_ACTIVE);
    }

    // ==================== 工具方法 ====================

    /**
     * 获取 Undo 类型名称
     *
     * @param undoType Undo 类型值
     * @return 类型名称
     */
    public static String getUndoTypeName(int undoType) {
        return switch (undoType) {
            case UNDO_INSERT -> "INSERT";
            case UNDO_UPDATE -> "UPDATE";
            default -> "UNKNOWN(" + undoType + ")";
        };
    }

    /**
     * 获取状态名称
     *
     * @param state 状态值
     * @return 状态名称
     */
    public static String getStateName(int state) {
        return switch (state) {
            case STATE_ACTIVE -> "ACTIVE";
            case STATE_CACHED -> "CACHED";
            case STATE_TO_FREE -> "TO_FREE";
            case STATE_TO_PURGE -> "TO_PURGE";
            default -> "UNKNOWN(" + state + ")";
        };
    }

    /**
     * 打印 Undo Page Header 信息
     *
     * @param buf 页面缓冲区
     * @return 格式化的信息字符串
     */
    public static String dump(ByteBuffer buf) {
        return String.format(
                "UndoPageHeader{type=%s, lastLog=%d, free=%d, logStart=%d, " +
                        "trxId=%s, rsegId=%d, state=%s, freeSpace=%d}",
                getUndoTypeName(getUndoType(buf)),
                getLastLogOffset(buf),
                getFreeOffset(buf),
                getLogStart(buf),
                getTrxId(buf),
                getRsegId(buf),
                getStateName(getState(buf)),
                getFreeSpace(buf)
        );
    }
}
