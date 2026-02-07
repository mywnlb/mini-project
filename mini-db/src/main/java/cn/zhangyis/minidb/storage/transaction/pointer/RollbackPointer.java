package cn.zhangyis.minidb.storage.transaction.pointer;

import cn.zhangyis.minidb.storage.constants.StorageConstants;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * 回滚指针 (Rollback Pointer / ROLL_PTR)
 *
 * <p>回滚指针是一个56位(7字节)的结构，指向Undo Log中该行的上一个版本。
 * 用于事务回滚和MVCC版本链遍历。</p>
 *
 * <h2>编码格式 (InnoDB 兼容)</h2>
 * <pre>
 * ┌────────┬────────────┬───────────────────┬────────────────┐
 * │ 1 bit  │  7 bits    │     32 bits       │    16 bits     │
 * │is_insert│ rseg_id   │    page_no        │    offset      │
 * └────────┴────────────┴───────────────────┴────────────────┘
 * Byte 0: [is_insert(1) | rseg_id(7)]
 * Byte 1-4: page_no (Big-Endian)
 * Byte 5-6: offset (Big-Endian)
 * </pre>
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li><b>is_insert</b>: 1 bit - 是否是 INSERT 类型的 Undo</li>
 *   <li><b>rseg_id</b>: 7 bits - Rollback Segment ID (0-127)</li>
 *   <li><b>page_no</b>: 32 bits - Undo Log 所在的页号</li>
 *   <li><b>offset</b>: 16 bits - 页内偏移 (0-65535)</li>
 * </ul>
 *
 * <h2>设计约束 (Invariants)</h2>
 * <ul>
 *   <li><b>T5</b>: is_insert 必须正确标记，用于区分回滚操作类型</li>
 *   <li><b>T6</b>: 版本链终止于 INSERT Undo 或 NULL (page_no=0, offset=0)</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 创建指向 INSERT Undo 的指针
 * RollbackPointer ptr = new RollbackPointer(true, 5, 1234, 100);
 *
 * // 写入到 ByteBuffer
 * ByteBuffer buf = ByteBuffer.allocate(16);
 * ptr.writeTo(buf, 0);
 *
 * // 从 ByteBuffer 读取
 * RollbackPointer restored = RollbackPointer.readFrom(buf, 0);
 * assert ptr.equals(restored);
 *
 * // 检查是否为空
 * assert !ptr.isNull();
 * assert RollbackPointer.NULL.isNull();
 * }</pre>
 *
 * <h2>InnoDB 对应</h2>
 * <p>对应 InnoDB 的 roll_ptr_t 类型，存储在记录的 ROLL_PTR 列中。</p>
 *
 * @author MiniDB
 * @version 1.0
 * @see StorageConstants#ROLL_PTR_SIZE
 */
public final class RollbackPointer {

    // ==================== 常量 ====================

    /**
     * ROLL_PTR 大小 (7 bytes = 56 bits)
     */
    public static final int SIZE = StorageConstants.ROLL_PTR_SIZE;

    /**
     * Rollback Segment ID 最大值 (127)
     */
    public static final int MAX_RSEG_ID = 127;

    /**
     * 页内偏移最大值 (65535)
     */
    public static final int MAX_OFFSET = 0xFFFF;

    /**
     * 空指针 (表示版本链终点)
     */
    public static final RollbackPointer NULL = new RollbackPointer(false, 0, 0, 0);

    // ==================== 字段 ====================

    /**
     * 是否是 INSERT 类型的 Undo
     *
     * <p>INSERT Undo 表示该记录之前不存在，回滚时需要删除。
     * UPDATE/DELETE Undo 表示该记录之前存在其他版本。</p>
     */
    private final boolean isInsert;

    /**
     * Rollback Segment ID (0-127)
     *
     * <p>标识 Undo Log 所属的回滚段。
     * 不同事务可能分配到不同的回滚段以减少锁争用。</p>
     */
    private final int rsegId;

    /**
     * Undo Log 所在的页号
     */
    private final int pageNo;

    /**
     * 页内偏移 (0-65535)
     *
     * <p>指向 Undo Page 中具体 Undo Record 的起始位置。</p>
     */
    private final int offset;

    // ==================== 构造函数 ====================

    /**
     * 创建回滚指针
     *
     * @param isInsert 是否是 INSERT 类型的 Undo
     * @param rsegId   Rollback Segment ID (0-127)
     * @param pageNo   Undo Log 所在的页号
     * @param offset   页内偏移 (0-65535)
     * @throws IllegalArgumentException 如果参数超出范围
     */
    public RollbackPointer(boolean isInsert, int rsegId, int pageNo, int offset) {
        if (rsegId < 0 || rsegId > MAX_RSEG_ID) {
            throw new IllegalArgumentException(
                    String.format("rseg_id out of range: %d (valid: 0 ~ %d)", rsegId, MAX_RSEG_ID));
        }
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new IllegalArgumentException(
                    String.format("offset out of range: %d (valid: 0 ~ %d)", offset, MAX_OFFSET));
        }
        this.isInsert = isInsert;
        this.rsegId = rsegId;
        this.pageNo = pageNo;
        this.offset = offset;
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建 INSERT Undo 指针
     *
     * @param rsegId Rollback Segment ID
     * @param pageNo 页号
     * @param offset 页内偏移
     * @return 回滚指针
     */
    public static RollbackPointer forInsert(int rsegId, int pageNo, int offset) {
        return new RollbackPointer(true, rsegId, pageNo, offset);
    }

    /**
     * 创建 UPDATE/DELETE Undo 指针
     *
     * @param rsegId Rollback Segment ID
     * @param pageNo 页号
     * @param offset 页内偏移
     * @return 回滚指针
     */
    public static RollbackPointer forUpdate(int rsegId, int pageNo, int offset) {
        return new RollbackPointer(false, rsegId, pageNo, offset);
    }

    // ==================== 序列化方法 ====================

    /**
     * 写入 7 字节到 ByteBuffer
     *
     * <pre>
     * Byte 0: [is_insert(1) | rseg_id(7)]
     * Byte 1-4: page_no (Big-Endian)
     * Byte 5-6: offset (Big-Endian)
     * </pre>
     *
     * @param buf    目标缓冲区
     * @param off    写入偏移
     */
    public void writeTo(ByteBuffer buf, int off) {
        // Byte 0: [is_insert(1) | rseg_id(7)]
        byte b0 = (byte) ((isInsert ? 0x80 : 0) | (rsegId & 0x7F));
        buf.put(off, b0);

        // Byte 1-4: page_no (Big-Endian)
        buf.put(off + 1, (byte) ((pageNo >> 24) & 0xFF));
        buf.put(off + 2, (byte) ((pageNo >> 16) & 0xFF));
        buf.put(off + 3, (byte) ((pageNo >> 8) & 0xFF));
        buf.put(off + 4, (byte) (pageNo & 0xFF));

        // Byte 5-6: offset (Big-Endian)
        buf.put(off + 5, (byte) ((offset >> 8) & 0xFF));
        buf.put(off + 6, (byte) (offset & 0xFF));
    }

    /**
     * 写入到字节数组
     *
     * @param bytes  目标数组
     * @param off    写入偏移
     */
    public void writeTo(byte[] bytes, int off) {
        // Byte 0: [is_insert(1) | rseg_id(7)]
        bytes[off] = (byte) ((isInsert ? 0x80 : 0) | (rsegId & 0x7F));

        // Byte 1-4: page_no (Big-Endian)
        bytes[off + 1] = (byte) ((pageNo >> 24) & 0xFF);
        bytes[off + 2] = (byte) ((pageNo >> 16) & 0xFF);
        bytes[off + 3] = (byte) ((pageNo >> 8) & 0xFF);
        bytes[off + 4] = (byte) (pageNo & 0xFF);

        // Byte 5-6: offset (Big-Endian)
        bytes[off + 5] = (byte) ((offset >> 8) & 0xFF);
        bytes[off + 6] = (byte) (offset & 0xFF);
    }

    /**
     * 从 ByteBuffer 读取 7 字节
     *
     * @param buf 源缓冲区
     * @param off 读取偏移
     * @return 回滚指针
     */
    public static RollbackPointer readFrom(ByteBuffer buf, int off) {
        byte b0 = buf.get(off);
        boolean isInsert = (b0 & 0x80) != 0;
        int rsegId = b0 & 0x7F;

        int pageNo = ((buf.get(off + 1) & 0xFF) << 24)
                | ((buf.get(off + 2) & 0xFF) << 16)
                | ((buf.get(off + 3) & 0xFF) << 8)
                | (buf.get(off + 4) & 0xFF);

        int offset = ((buf.get(off + 5) & 0xFF) << 8)
                | (buf.get(off + 6) & 0xFF);

        return new RollbackPointer(isInsert, rsegId, pageNo, offset);
    }

    /**
     * 从字节数组读取
     *
     * @param bytes 源数组
     * @param off   读取偏移
     * @return 回滚指针
     */
    public static RollbackPointer readFrom(byte[] bytes, int off) {
        byte b0 = bytes[off];
        boolean isInsert = (b0 & 0x80) != 0;
        int rsegId = b0 & 0x7F;

        int pageNo = ((bytes[off + 1] & 0xFF) << 24)
                | ((bytes[off + 2] & 0xFF) << 16)
                | ((bytes[off + 3] & 0xFF) << 8)
                | (bytes[off + 4] & 0xFF);

        int offset = ((bytes[off + 5] & 0xFF) << 8)
                | (bytes[off + 6] & 0xFF);

        return new RollbackPointer(isInsert, rsegId, pageNo, offset);
    }

    // ==================== 访问方法 ====================

    /**
     * 编码为 56 位 (7 字节) 的 long 值
     *
     * <p>用于将 RollbackPointer 存储到记录的 ROLL_PTR 字段中。</p>
     *
     * <pre>
     * Bits 55-49: [is_insert(1) | rseg_id(7)]
     * Bits 48-17: page_no (32 bits)
     * Bits 16-1:  offset (16 bits)
     * Bit 0:      unused (always 0)
     * </pre>
     *
     * @return 编码后的 long 值
     */
    public long encode() {
        long result = 0;

        // Bit 55: is_insert
        if (isInsert) {
            result |= (1L << 55);
        }

        // Bits 54-48: rseg_id (7 bits)
        result |= ((long) (rsegId & 0x7F)) << 48;

        // Bits 47-16: page_no (32 bits)
        result |= ((long) pageNo & 0xFFFFFFFFL) << 16;

        // Bits 15-0: offset (16 bits)
        result |= (offset & 0xFFFF);

        return result;
    }

    /**
     * 从 56 位 long 值解码
     *
     * @param encoded 编码后的 long 值
     * @return 解码后的 RollbackPointer
     */
    public static RollbackPointer decode(long encoded) {
        // Bit 55: is_insert
        boolean isInsert = (encoded & (1L << 55)) != 0;

        // Bits 54-48: rseg_id (7 bits)
        int rsegId = (int) ((encoded >> 48) & 0x7F);

        // Bits 47-16: page_no (32 bits)
        int pageNo = (int) ((encoded >> 16) & 0xFFFFFFFFL);

        // Bits 15-0: offset (16 bits)
        int offset = (int) (encoded & 0xFFFF);

        return new RollbackPointer(isInsert, rsegId, pageNo, offset);
    }

    /**
     * 是否是 INSERT 类型的 Undo
     *
     * @return true 如果是 INSERT Undo
     */
    public boolean isInsert() {
        return isInsert;
    }

    /**
     * 是否是 UPDATE/DELETE 类型的 Undo
     *
     * @return true 如果是 UPDATE/DELETE Undo
     */
    public boolean isUpdate() {
        return !isInsert;
    }

    /**
     * 获取 Rollback Segment ID
     *
     * @return rseg_id (0-127)
     */
    public int getRsegId() {
        return rsegId;
    }

    /**
     * 获取 Undo Log 页号
     *
     * @return 页号
     */
    public int getPageNo() {
        return pageNo;
    }

    /**
     * 获取页内偏移
     *
     * @return 偏移 (0-65535)
     */
    public int getOffset() {
        return offset;
    }

    /**
     * 是否是空指针 (版本链终点)
     *
     * <p>当 page_no=0 且 offset=0 时，表示版本链到达终点。</p>
     *
     * @return true 如果是空指针
     */
    public boolean isNull() {
        return pageNo == 0 && offset == 0;
    }

    // ==================== Object 方法 ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RollbackPointer that = (RollbackPointer) o;
        return isInsert == that.isInsert
                && rsegId == that.rsegId
                && pageNo == that.pageNo
                && offset == that.offset;
    }

    @Override
    public int hashCode() {
        return Objects.hash(isInsert, rsegId, pageNo, offset);
    }

    @Override
    public String toString() {
        if (isNull()) {
            return "RollPtr(NULL)";
        }
        return String.format("RollPtr(%s, rseg=%d, page=%d, off=%d)",
                isInsert ? "INSERT" : "UPDATE", rsegId, pageNo, offset);
    }
}
