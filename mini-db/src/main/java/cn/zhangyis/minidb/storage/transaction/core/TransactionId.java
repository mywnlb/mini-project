package cn.zhangyis.minidb.storage.transaction.core;

import cn.zhangyis.minidb.storage.constants.StorageConstants;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * 事务ID (Transaction ID)
 *
 * <p>事务ID是一个48位(6字节)的全局递增序列号，用于标识每个事务。</p>
 *
 * <h2>编码格式</h2>
 * <pre>
 * ┌────────────────────────────────────────────────┐
 * │              TRX_ID (48 bits / 6 bytes)        │
 * │          全局递增事务序列号 (Big-Endian)         │
 * │          范围: 0 ~ 2^48 - 1 ≈ 281 万亿          │
 * └────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>设计约束 (Invariants)</h2>
 * <ul>
 *   <li><b>T1</b>: TRX_ID 全局递增，永不复用</li>
 *   <li><b>T2</b>: 有效的 TRX_ID > 0 (0 表示无效)</li>
 *   <li><b>T3</b>: 存储为 Big-Endian 格式，便于比较</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 创建事务ID
 * TransactionId trxId = new TransactionId(12345L);
 *
 * // 写入到 ByteBuffer
 * ByteBuffer buf = ByteBuffer.allocate(16);
 * trxId.writeTo(buf, 0);
 *
 * // 从 ByteBuffer 读取
 * TransactionId restored = TransactionId.readFrom(buf, 0);
 * assert trxId.equals(restored);
 * }</pre>
 *
 * <h2>InnoDB 对应</h2>
 * <p>对应 InnoDB 的 trx_id_t 类型，存储在记录的 TRX_ID 列中。</p>
 *
 * @author MiniDB
 * @version 1.0
 * @see StorageConstants#TRX_ID_SIZE
 */
public final class TransactionId implements Comparable<TransactionId> {

    // ==================== 常量 ====================

    /**
     * TRX_ID 大小 (6 bytes = 48 bits)
     */
    public static final int SIZE = StorageConstants.TRX_ID_SIZE;

    /**
     * 最大值 (2^48 - 1)
     */
    public static final long MAX_VALUE = (1L << 48) - 1;

    /**
     * 无效的事务ID (0)
     */
    public static final TransactionId INVALID = new TransactionId(0);

    /**
     * 最大事务ID
     */
    public static final TransactionId MAX = new TransactionId(MAX_VALUE);

    // ==================== 字段 ====================

    /**
     * 事务ID值 (仅使用低48位)
     */
    private final long value;

    // ==================== 构造函数 ====================

    /**
     * 创建事务ID
     *
     * @param value 事务ID值 (0 ~ 2^48-1)
     * @throws IllegalArgumentException 如果值超出范围
     */
    public TransactionId(long value) {
        if (value < 0 || value > MAX_VALUE) {
            throw new IllegalArgumentException(
                    String.format("TRX_ID out of range: %d (valid: 0 ~ %d)", value, MAX_VALUE));
        }
        this.value = value;
    }

    // ==================== 序列化方法 ====================

    /**
     * 写入 6 字节到 ByteBuffer (Big-Endian)
     *
     * <p>Big-Endian 格式便于按字节比较大小。</p>
     *
     * @param buf    目标缓冲区
     * @param offset 写入偏移
     */
    public void writeTo(ByteBuffer buf, int offset) {
        buf.put(offset,     (byte) ((value >> 40) & 0xFF));
        buf.put(offset + 1, (byte) ((value >> 32) & 0xFF));
        buf.put(offset + 2, (byte) ((value >> 24) & 0xFF));
        buf.put(offset + 3, (byte) ((value >> 16) & 0xFF));
        buf.put(offset + 4, (byte) ((value >> 8) & 0xFF));
        buf.put(offset + 5, (byte) (value & 0xFF));
    }

    /**
     * 写入到字节数组
     *
     * @param bytes  目标数组
     * @param offset 写入偏移
     */
    public void writeTo(byte[] bytes, int offset) {
        bytes[offset]     = (byte) ((value >> 40) & 0xFF);
        bytes[offset + 1] = (byte) ((value >> 32) & 0xFF);
        bytes[offset + 2] = (byte) ((value >> 24) & 0xFF);
        bytes[offset + 3] = (byte) ((value >> 16) & 0xFF);
        bytes[offset + 4] = (byte) ((value >> 8) & 0xFF);
        bytes[offset + 5] = (byte) (value & 0xFF);
    }

    /**
     * 从 ByteBuffer 读取 6 字节 (Big-Endian)
     *
     * @param buf    源缓冲区
     * @param offset 读取偏移
     * @return 事务ID
     */
    public static TransactionId readFrom(ByteBuffer buf, int offset) {
        long v = ((long) (buf.get(offset) & 0xFF) << 40)
                | ((long) (buf.get(offset + 1) & 0xFF) << 32)
                | ((long) (buf.get(offset + 2) & 0xFF) << 24)
                | ((long) (buf.get(offset + 3) & 0xFF) << 16)
                | ((long) (buf.get(offset + 4) & 0xFF) << 8)
                | ((long) (buf.get(offset + 5) & 0xFF));
        return new TransactionId(v);
    }

    /**
     * 从字节数组读取
     *
     * @param bytes  源数组
     * @param offset 读取偏移
     * @return 事务ID
     */
    public static TransactionId readFrom(byte[] bytes, int offset) {
        long v = ((long) (bytes[offset] & 0xFF) << 40)
                | ((long) (bytes[offset + 1] & 0xFF) << 32)
                | ((long) (bytes[offset + 2] & 0xFF) << 24)
                | ((long) (bytes[offset + 3] & 0xFF) << 16)
                | ((long) (bytes[offset + 4] & 0xFF) << 8)
                | ((long) (bytes[offset + 5] & 0xFF));
        return new TransactionId(v);
    }

    // ==================== 访问方法 ====================

    /**
     * 获取事务ID值
     *
     * @return 事务ID值
     */
    public long getValue() {
        return value;
    }

    /**
     * 是否是有效的事务ID
     *
     * <p>事务ID为0表示无效或未分配。</p>
     *
     * @return true 如果有效
     */
    public boolean isValid() {
        return value > 0;
    }

    /**
     * 获取下一个事务ID
     *
     * @return 下一个事务ID
     * @throws IllegalStateException 如果已达到最大值
     */
    public TransactionId next() {
        if (value >= MAX_VALUE) {
            throw new IllegalStateException("TRX_ID overflow");
        }
        return new TransactionId(value + 1);
    }

    // ==================== Comparable 实现 ====================

    @Override
    public int compareTo(TransactionId other) {
        return Long.compare(this.value, other.value);
    }

    /**
     * 是否小于另一个事务ID
     *
     * @param other 另一个事务ID
     * @return true 如果小于
     */
    public boolean isBefore(TransactionId other) {
        return this.value < other.value;
    }

    /**
     * 是否大于等于另一个事务ID
     *
     * @param other 另一个事务ID
     * @return true 如果大于等于
     */
    public boolean isAfterOrEqual(TransactionId other) {
        return this.value >= other.value;
    }

    // ==================== Object 方法 ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TransactionId that = (TransactionId) o;
        return value == that.value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return String.format("TrxId(%d)", value);
    }
}
