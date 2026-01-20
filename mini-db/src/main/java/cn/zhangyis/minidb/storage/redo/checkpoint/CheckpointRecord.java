package cn.zhangyis.minidb.storage.redo.checkpoint;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.io.IOException;
import java.util.zip.CRC32;

/**
 * Checkpoint 记录
 *
 * <p>存储 checkpoint 的关键信息，用于确定崩溃恢复的起始点。
 * Checkpoint 信息写入 redo log 文件头的保留区域 (前 2KB)。</p>
 *
 * <h2>格式 (64 bytes)</h2>
 * <pre>
 * ┌─────────────────┬────────┬─────────────────────────────────────┐
 * │ Field           │ Size   │ Description                         │
 * ├─────────────────┼────────┼─────────────────────────────────────┤
 * │ magic           │ 8B     │ 魔数 (0x1B581E51)                   │
 * │ checkpointLsn   │ 8B     │ checkpoint 位置 (恢复起始 LSN)       │
 * │ checkpointNo    │ 8B     │ checkpoint 序号 (递增)              │
 * │ flushedLsn      │ 8B     │ 写入时的 flushed LSN                │
 * │ logFileSize     │ 4B     │ 日志文件大小                        │
 * │ reserved        │ 4B     │ 保留字段                            │
 * │ timestamp       │ 8B     │ 创建时间 (毫秒)                     │
 * │ checksum        │ 8B     │ CRC32 校验和                        │
 * │ padding         │ 8B     │ 填充到 64B 对齐                     │
 * └─────────────────┴────────┴─────────────────────────────────────┘
 * </pre>
 *
 * <h2>存储位置</h2>
 * <ul>
 *   <li>ib_logfile0: offset 0-63 (checkpoint slot 0)</li>
 *   <li>ib_logfile1: offset 0-63 (checkpoint slot 1)</li>
 *   <li>交替写入，使用 checkpointNo % 2 决定</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class CheckpointRecord {

    // ==================== 常量 ====================

    /** 魔数 (用于验证 checkpoint 有效性) */
    public static final long CHECKPOINT_MAGIC = 0x1B581E51L;

    /** Checkpoint 记录大小 (64 bytes) */
    public static final int CHECKPOINT_RECORD_SIZE = 64;

    // ==================== 字段 ====================

    /** Checkpoint LSN (恢复起始点) */
    private long checkpointLsn;

    /** Checkpoint 序号 (递增) */
    private long checkpointNo;

    /** 写入时的 flushed LSN */
    private long flushedLsn;

    /** 日志文件大小 */
    private int logFileSize;

    /** 创建时间戳 (毫秒) */
    private long timestamp;

    /** CRC32 校验和 */
    private long checksum;

    // ==================== 构造函数 ====================

    /**
     * 创建空的 CheckpointRecord
     */
    public CheckpointRecord() {
    }

    /**
     * 创建 CheckpointRecord
     *
     * @param checkpointLsn checkpoint LSN
     * @param checkpointNo  checkpoint 序号
     * @param flushedLsn    flushed LSN
     * @param logFileSize   日志文件大小
     */
    public CheckpointRecord(long checkpointLsn, long checkpointNo, long flushedLsn, int logFileSize) {
        this.checkpointLsn = checkpointLsn;
        this.checkpointNo = checkpointNo;
        this.flushedLsn = flushedLsn;
        this.logFileSize = logFileSize;
        this.timestamp = System.currentTimeMillis();
    }

    // ==================== 序列化 ====================

    /**
     * 序列化为字节数组
     *
     * @return 64 字节的序列化数据
     */
    public byte[] serialize() {
        ByteBuffer buf = ByteBuffer.allocate(CHECKPOINT_RECORD_SIZE);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // 写入字段 (不含 checksum)
        buf.putLong(CHECKPOINT_MAGIC);
        buf.putLong(checkpointLsn);
        buf.putLong(checkpointNo);
        buf.putLong(flushedLsn);
        buf.putInt(logFileSize);
        buf.putInt(0);  // reserved
        buf.putLong(timestamp);

        // 计算 checksum (前 48 字节)
        buf.flip();
        CRC32 crc = new CRC32();
        byte[] dataForChecksum = new byte[48];
        buf.get(dataForChecksum);
        crc.update(dataForChecksum);
        this.checksum = crc.getValue();

        // 重新写入完整数据
        buf.clear();
        buf.putLong(CHECKPOINT_MAGIC);
        buf.putLong(checkpointLsn);
        buf.putLong(checkpointNo);
        buf.putLong(flushedLsn);
        buf.putInt(logFileSize);
        buf.putInt(0);  // reserved
        buf.putLong(timestamp);
        buf.putLong(checksum);
        buf.putLong(0);  // padding

        return buf.array();
    }

    /**
     * 写入文件
     *
     * @param channel 文件通道
     * @param offset  写入偏移
     * @throws IOException 如果写入失败
     */
    public void writeToFile(FileChannel channel, long offset) throws IOException {
        byte[] data = serialize();
        ByteBuffer buf = ByteBuffer.wrap(data);
        channel.write(buf, offset);
        channel.force(true);  // fsync
    }

    // ==================== 反序列化 ====================

    /**
     * 从字节数组反序列化
     *
     * @param data 64 字节的数据
     * @return CheckpointRecord 或 null (如果无效)
     */
    public static CheckpointRecord deserialize(byte[] data) {
        if (data == null || data.length < CHECKPOINT_RECORD_SIZE) {
            return null;
        }

        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // 验证魔数
        long magic = buf.getLong();
        if (magic != CHECKPOINT_MAGIC) {
            return null;
        }

        // 读取字段
        CheckpointRecord record = new CheckpointRecord();
        record.checkpointLsn = buf.getLong();
        record.checkpointNo = buf.getLong();
        record.flushedLsn = buf.getLong();
        record.logFileSize = buf.getInt();
        buf.getInt();  // reserved
        record.timestamp = buf.getLong();
        record.checksum = buf.getLong();

        // 验证 checksum
        if (!record.isValid(data)) {
            return null;
        }

        return record;
    }

    /**
     * 从文件读取
     *
     * @param channel 文件通道
     * @param offset  读取偏移
     * @return CheckpointRecord 或 null (如果无效)
     * @throws IOException 如果读取失败
     */
    public static CheckpointRecord readFromFile(FileChannel channel, long offset) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(CHECKPOINT_RECORD_SIZE);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        int bytesRead = channel.read(buf, offset);
        if (bytesRead < CHECKPOINT_RECORD_SIZE) {
            return null;
        }

        return deserialize(buf.array());
    }

    // ==================== 校验 ====================

    /**
     * 验证 checksum
     *
     * @param data 原始数据
     * @return true 如果校验和有效
     */
    private boolean isValid(byte[] data) {
        // 计算前 48 字节的 checksum
        CRC32 crc = new CRC32();
        crc.update(data, 0, 48);
        return crc.getValue() == checksum;
    }

    // ==================== Getters/Setters ====================

    public long getCheckpointLsn() {
        return checkpointLsn;
    }

    public void setCheckpointLsn(long checkpointLsn) {
        this.checkpointLsn = checkpointLsn;
    }

    public long getCheckpointNo() {
        return checkpointNo;
    }

    public void setCheckpointNo(long checkpointNo) {
        this.checkpointNo = checkpointNo;
    }

    public long getFlushedLsn() {
        return flushedLsn;
    }

    public void setFlushedLsn(long flushedLsn) {
        this.flushedLsn = flushedLsn;
    }

    public int getLogFileSize() {
        return logFileSize;
    }

    public void setLogFileSize(int logFileSize) {
        this.logFileSize = logFileSize;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public long getChecksum() {
        return checksum;
    }

    @Override
    public String toString() {
        return String.format("CheckpointRecord{lsn=%d, no=%d, flushedLsn=%d, timestamp=%d}",
                checkpointLsn, checkpointNo, flushedLsn, timestamp);
    }
}
