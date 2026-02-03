package cn.zhangyis.minidb.storage.transaction.undo;

import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.constants.StorageConstants;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.transaction.core.TransactionId;
import cn.zhangyis.minidb.storage.transaction.pointer.RollbackPointer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Undo 页面操作
 *
 * <p>提供 Undo Page 的读写操作，包括写入 Undo 记录、读取 Undo 记录、
 * 管理空闲空间等。所有写操作必须在 MTR 保护下进行。</p>
 *
 * <h2>Undo Page 布局</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                     FIL Header (38 bytes)                       │
 * │  page_type = FIL_PAGE_UNDO_LOG                                  │
 * ├─────────────────────────────────────────────────────────────────┤
 * │                    Undo Page Header (16 bytes)                  │
 * │  undo_type(2) | last_log(2) | free(2) | log_start(2) |          │
 * │  trx_id(6) | rseg_id(1) | state(1)                              │
 * ├─────────────────────────────────────────────────────────────────┤
 * │                    Undo Records (variable)                      │
 * │  record_1 | record_2 | ... | record_n                          │
 * ├─────────────────────────────────────────────────────────────────┤
 * │                     Free Space                                  │
 * ├─────────────────────────────────────────────────────────────────┤
 * │                     FIL Trailer (8 bytes)                       │
 * └─────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>设计约束 (Invariants)</h2>
 * <ul>
 *   <li><b>U1</b>: 所有写操作必须在 MTR 保护下</li>
 *   <li><b>U2</b>: 写入后必须更新 free_offset 和 last_log_offset</li>
 *   <li><b>U3</b>: free_offset 必须始终指向可用空间起始位置</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
 *     Page page = mtr.getPage(undoPageId);
 *     ByteBuffer buf = page.getBuffer();
 *
 *     // 写入 Undo 记录
 *     int offset = UndoPage.writeUndoRecord(buf, undoRecord, mtr);
 *     if (offset < 0) {
 *         // 页面空间不足，需要分配新页
 *     }
 *
 *     // 创建 RollbackPointer
 *     RollbackPointer rollPtr = RollbackPointer.forInsert(rsegId, pageNo, offset);
 *
 *     mtr.markDirty(page);
 *     mtr.commit();
 * }
 * }</pre>
 *
 * @author MiniDB
 * @version 1.0
 * @see UndoPageHeader
 * @see UndoRecord
 */
public final class UndoPage {

    // ==================== 页面类型常量 ====================

    /**
     * Undo Log 页面类型标识
     */
    public static final int PAGE_TYPE_UNDO_LOG = 2;

    // ==================== 私有构造函数 ====================

    private UndoPage() {
        throw new UnsupportedOperationException("Utility class");
    }

    // ==================== 初始化方法 ====================

    /**
     * 初始化 Undo Page
     *
     * <p>设置页面类型和 Undo Page Header 初始值。</p>
     *
     * @param buf      页面缓冲区
     * @param undoType Undo 类型 (UndoPageHeader.UNDO_INSERT 或 UNDO_UPDATE)
     * @param trxId    事务 ID
     * @param rsegId   Rollback Segment ID
     */
    public static void init(ByteBuffer buf, int undoType, TransactionId trxId, int rsegId) {
        // 设置页面类型 (FIL Header offset 24)
        buf.putShort(24, (short) PAGE_TYPE_UNDO_LOG);

        // 初始化 Undo Page Header
        UndoPageHeader.init(buf, undoType, trxId, rsegId);
    }

    /**
     * 初始化 Undo Page (使用 Page 对象)
     *
     * @param page     页面对象
     * @param undoType Undo 类型
     * @param trxId    事务 ID
     * @param rsegId   Rollback Segment ID
     */
    public static void init(Page page, int undoType, TransactionId trxId, int rsegId) {
        init(page.getBuffer(), undoType, trxId, rsegId);
    }

    // ==================== 写入方法 ====================

    /**
     * 写入 Undo 记录到页面
     *
     * <p>将 Undo 记录写入页面的空闲空间，并更新页面头部。</p>
     *
     * <h3>写入步骤</h3>
     * <ol>
     *   <li>检查空闲空间是否足够</li>
     *   <li>在 free_offset 位置写入记录</li>
     *   <li>更新 last_log_offset 为新记录位置</li>
     *   <li>更新 free_offset 为记录末尾</li>
     * </ol>
     *
     * @param buf        页面缓冲区
     * @param undoRecord Undo 记录
     * @return 记录写入的偏移量，如果空间不足返回 -1
     */
    public static int writeUndoRecord(ByteBuffer buf, UndoRecord undoRecord) {
        int recordSize = undoRecord.calculateSize();

        // 检查空间是否足够
        if (!UndoPageHeader.hasSpace(buf, recordSize)) {
            return -1;
        }

        // 获取当前空闲位置
        int freeOffset = UndoPageHeader.getFreeOffset(buf);

        // 写入记录
        undoRecord.writeTo(buf, freeOffset);

        // 更新头部
        UndoPageHeader.setLastLogOffset(buf, freeOffset);
        UndoPageHeader.setFreeOffset(buf, freeOffset + recordSize);

        return freeOffset;
    }

    /**
     * 写入 Undo 记录并返回 RollbackPointer
     *
     * @param buf        页面缓冲区
     * @param undoRecord Undo 记录
     * @param rsegId     Rollback Segment ID
     * @param pageNo     页号
     * @return RollbackPointer，如果空间不足返回 null
     */
    public static RollbackPointer writeUndoRecordWithPointer(ByteBuffer buf,
                                                              UndoRecord undoRecord,
                                                              int rsegId,
                                                              int pageNo) {
        int offset = writeUndoRecord(buf, undoRecord);
        if (offset < 0) {
            return null;
        }

        // 根据记录类型创建 RollbackPointer
        boolean isInsert = undoRecord.getType() == UndoRecordType.INSERT;
        return new RollbackPointer(isInsert, rsegId, pageNo, offset);
    }

    // ==================== 读取方法 ====================

    /**
     * 读取指定偏移的 Undo 记录
     *
     * @param buf    页面缓冲区
     * @param offset 记录偏移
     * @return Undo 记录
     */
    public static UndoRecord readUndoRecord(ByteBuffer buf, int offset) {
        return UndoRecord.readFrom(buf, offset);
    }

    /**
     * 读取最后一条 Undo 记录
     *
     * @param buf 页面缓冲区
     * @return 最后一条 Undo 记录，如果没有返回 null
     */
    public static UndoRecord readLastUndoRecord(ByteBuffer buf) {
        int lastOffset = UndoPageHeader.getLastLogOffset(buf);
        if (lastOffset == 0) {
            return null;  // 没有记录
        }
        return readUndoRecord(buf, lastOffset);
    }

    /**
     * 仅读取 Undo 记录头部
     *
     * @param buf    页面缓冲区
     * @param offset 记录偏移
     * @return 记录头部信息
     */
    public static UndoRecord.UndoRecordHeader readUndoRecordHeader(ByteBuffer buf, int offset) {
        return UndoRecord.readHeader(buf, offset);
    }

    // ==================== 查询方法 ====================

    /**
     * 获取页面剩余空间
     *
     * @param buf 页面缓冲区
     * @return 剩余字节数
     */
    public static int getFreeSpace(ByteBuffer buf) {
        return UndoPageHeader.getFreeSpace(buf);
    }

    /**
     * 检查页面是否有足够空间写入记录
     *
     * @param buf        页面缓冲区
     * @param recordSize 记录大小
     * @return true 如果有足够空间
     */
    public static boolean hasSpace(ByteBuffer buf, int recordSize) {
        return UndoPageHeader.hasSpace(buf, recordSize);
    }

    /**
     * 获取 Undo 类型
     *
     * @param buf 页面缓冲区
     * @return Undo 类型 (1=INSERT, 2=UPDATE)
     */
    public static int getUndoType(ByteBuffer buf) {
        return UndoPageHeader.getUndoType(buf);
    }

    /**
     * 获取事务 ID
     *
     * @param buf 页面缓冲区
     * @return 事务 ID
     */
    public static TransactionId getTrxId(ByteBuffer buf) {
        return UndoPageHeader.getTrxId(buf);
    }

    /**
     * 获取 Rollback Segment ID
     *
     * @param buf 页面缓冲区
     * @return rseg_id
     */
    public static int getRsegId(ByteBuffer buf) {
        return UndoPageHeader.getRsegId(buf);
    }

    /**
     * 获取页面状态
     *
     * @param buf 页面缓冲区
     * @return 状态值
     */
    public static int getState(ByteBuffer buf) {
        return UndoPageHeader.getState(buf);
    }

    /**
     * 设置页面状态
     *
     * @param buf   页面缓冲区
     * @param state 状态值
     */
    public static void setState(ByteBuffer buf, int state) {
        UndoPageHeader.setState(buf, state);
    }

    /**
     * 检查页面是否为空（没有 Undo 记录）
     *
     * @param buf 页面缓冲区
     * @return true 如果没有记录
     */
    public static boolean isEmpty(ByteBuffer buf) {
        return UndoPageHeader.getLastLogOffset(buf) == 0;
    }

    /**
     * 获取记录数量 (通过遍历计算)
     *
     * <p>注意：这是一个 O(n) 操作，仅用于调试。</p>
     *
     * @param buf 页面缓冲区
     * @return 记录数量
     */
    public static int getRecordCount(ByteBuffer buf) {
        int count = 0;
        int offset = UndoPageHeader.getLogStart(buf);
        int freeOffset = UndoPageHeader.getFreeOffset(buf);

        while (offset < freeOffset) {
            count++;
            int recordLen = UndoRecord.peekLength(buf, offset);
            if (recordLen <= 0) {
                break;  // 防止无限循环
            }
            offset += recordLen;
        }

        return count;
    }

    // ==================== 遍历方法 ====================

    /**
     * 创建 Undo 记录迭代器 (从第一条到最后一条)
     *
     * @param buf 页面缓冲区
     * @return 迭代器
     */
    public static UndoRecordIterator iterator(ByteBuffer buf) {
        return new UndoRecordIterator(buf);
    }

    /**
     * 创建反向 Undo 记录迭代器 (从最后一条到第一条)
     *
     * <p>用于事务回滚时逆序遍历。</p>
     *
     * @param buf 页面缓冲区
     * @return 反向迭代器
     */
    public static ReverseUndoRecordIterator reverseIterator(ByteBuffer buf) {
        return new ReverseUndoRecordIterator(buf);
    }

    // ==================== 调试方法 ====================

    /**
     * 转储页面信息
     *
     * @param buf 页面缓冲区
     * @return 格式化的信息字符串
     */
    public static String dump(ByteBuffer buf) {
        StringBuilder sb = new StringBuilder();
        sb.append("UndoPage{\n");
        sb.append("  ").append(UndoPageHeader.dump(buf)).append("\n");
        sb.append("  recordCount=").append(getRecordCount(buf)).append("\n");
        sb.append("}");
        return sb.toString();
    }

    // ==================== 迭代器类 ====================

    /**
     * Undo 记录正向迭代器
     */
    public static class UndoRecordIterator implements java.util.Iterator<UndoRecord> {
        private final ByteBuffer buf;
        private int currentOffset;
        private final int endOffset;

        UndoRecordIterator(ByteBuffer buf) {
            this.buf = buf;
            this.currentOffset = UndoPageHeader.getLogStart(buf);
            this.endOffset = UndoPageHeader.getFreeOffset(buf);
        }

        @Override
        public boolean hasNext() {
            return currentOffset < endOffset;
        }

        @Override
        public UndoRecord next() {
            if (!hasNext()) {
                throw new java.util.NoSuchElementException();
            }

            UndoRecord record = UndoRecord.readFrom(buf, currentOffset);
            currentOffset += record.calculateSize();
            return record;
        }

        /**
         * 获取当前偏移
         *
         * @return 偏移量
         */
        public int getCurrentOffset() {
            return currentOffset;
        }
    }

    /**
     * Undo 记录反向迭代器
     *
     * <p>通过 prev_undo 指针从最后一条记录向前遍历。</p>
     */
    public static class ReverseUndoRecordIterator implements java.util.Iterator<UndoRecord> {
        private final ByteBuffer buf;
        private int currentOffset;
        private final int startOffset;

        ReverseUndoRecordIterator(ByteBuffer buf) {
            this.buf = buf;
            this.currentOffset = UndoPageHeader.getLastLogOffset(buf);
            this.startOffset = UndoPageHeader.getLogStart(buf);
        }

        @Override
        public boolean hasNext() {
            return currentOffset >= startOffset && currentOffset != 0;
        }

        @Override
        public UndoRecord next() {
            if (!hasNext()) {
                throw new java.util.NoSuchElementException();
            }

            UndoRecord record = UndoRecord.readFrom(buf, currentOffset);

            // 需要找到前一条记录的位置
            // 由于没有存储前一条记录的页内偏移，这里使用正向遍历找到
            // 注意：这是一个简化实现，生产环境应该在页面中维护反向链接
            currentOffset = findPreviousRecordOffset(currentOffset);

            return record;
        }

        /**
         * 找到前一条记录的偏移
         *
         * @param currentOffset 当前记录偏移
         * @return 前一条记录偏移，如果是第一条返回 0
         */
        private int findPreviousRecordOffset(int currentOffset) {
            if (currentOffset <= startOffset) {
                return 0;
            }

            // 从头遍历找到前一条
            int prevOffset = 0;
            int offset = startOffset;

            while (offset < currentOffset) {
                int recordLen = UndoRecord.peekLength(buf, offset);
                if (recordLen <= 0) {
                    break;
                }
                if (offset + recordLen >= currentOffset) {
                    break;
                }
                prevOffset = offset;
                offset += recordLen;
            }

            return prevOffset;
        }

        /**
         * 获取当前偏移
         *
         * @return 偏移量
         */
        public int getCurrentOffset() {
            return currentOffset;
        }
    }
}
