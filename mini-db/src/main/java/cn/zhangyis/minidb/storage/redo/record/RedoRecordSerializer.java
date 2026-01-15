package cn.zhangyis.minidb.storage.redo.record;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Redo Record 序列化/反序列化器
 *
 * <p>统一的序列化接口，支持将 RedoRecord 列表序列化为字节数组，
 * 以及从字节数组反序列化为 RedoRecord 列表。</p>
 *
 * <h2>序列化格式</h2>
 * <pre>
 * [record1_bytes] [record2_bytes] ... [recordN_bytes] [MLOG_MULTI_REC_END]
 * </pre>
 *
 * <h2>注意事项</h2>
 * <ul>
 *   <li>序列化时会自动追加 MLOG_MULTI_REC_END 作为组结束标记</li>
 *   <li>反序列化时遇到 MLOG_MULTI_REC_END 表示一个完整的 redo group</li>
 *   <li>所有多字节数值使用 Little Endian 编码</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class RedoRecordSerializer {

    private static final Logger logger = LoggerFactory.getLogger(RedoRecordSerializer.class);

    // ==================== 序列化 ====================

    /**
     * 将 redo records 序列化为字节数组
     *
     * <p>自动追加 MLOG_MULTI_REC_END 作为组结束标记。</p>
     *
     * @param records 要序列化的记录列表
     * @return 序列化后的字节数组
     */
    public static byte[] serialize(List<RedoRecord> records) {
        if (records == null || records.isEmpty()) {
            // 空记录列表，只返回 END 标记
            return new MultiRecEndRecord().serialize();
        }

        // 计算总大小
        int totalSize = 0;
        for (RedoRecord record : records) {
            totalSize += record.getSize();
        }
        // 加上 END 标记的大小
        totalSize += 1;

        // 分配缓冲区
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);

        // 写入每条记录
        for (RedoRecord record : records) {
            buffer.put(record.serialize());
        }

        // 写入 END 标记
        buffer.put(new MultiRecEndRecord().serialize());

        return buffer.array();
    }

    /**
     * 将单条 redo record 序列化为字节数组（不含 END 标记）
     *
     * @param record 要序列化的记录
     * @return 序列化后的字节数组
     */
    public static byte[] serializeSingle(RedoRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record cannot be null");
        }
        return record.serialize();
    }

    // ==================== 反序列化 ====================

    /**
     * 从字节数组反序列化 redo records
     *
     * <p>解析直到遇到 MLOG_MULTI_REC_END 或缓冲区结束。</p>
     *
     * @param data 序列化的字节数组
     * @return 反序列化后的记录列表（不含 END 标记）
     * @throws RedoRecordParseException 如果解析失败
     */
    public static List<RedoRecord> deserialize(byte[] data) throws RedoRecordParseException {
        if (data == null || data.length == 0) {
            return new ArrayList<>();
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);
        return deserialize(buffer);
    }

    /**
     * 从 ByteBuffer 反序列化 redo records
     *
     * <p>解析直到遇到 MLOG_MULTI_REC_END 或缓冲区结束。
     * 会消费 buffer 中相应的字节。</p>
     *
     * @param buffer 包含序列化数据的缓冲区
     * @return 反序列化后的记录列表（不含 END 标记）
     * @throws RedoRecordParseException 如果解析失败
     */
    public static List<RedoRecord> deserialize(ByteBuffer buffer) throws RedoRecordParseException {
        List<RedoRecord> records = new ArrayList<>();

        while (buffer.hasRemaining()) {
            int startPos = buffer.position();

            try {
                // 读取 type 字节
                byte typeByte = buffer.get();
                int typeValue = typeByte & 0xFF;

                RedoRecordType type;
                try {
                    type = RedoRecordType.fromValue(typeValue);
                } catch (IllegalArgumentException e) {
                    throw new RedoRecordParseException(
                            "Unknown redo record type: " + typeValue + " at position " + startPos, e);
                }

                // 根据类型反序列化
                buffer.position(startPos);  // 重置位置，让具体的 deserialize 方法读取 type

                RedoRecord record = switch (type) {
                    case MLOG_WRITE_BYTES -> WriteBytesRecord.deserialize(buffer);
                    case MLOG_MULTI_REC_END -> {
                        // 读取 END 标记后，返回当前收集的记录
                        MultiRecEndRecord.deserialize(buffer);
                        logger.trace("Parsed redo group with {} records", records.size());
                        yield null;  // 信号：组结束
                    }
                    case MLOG_FULL_PAGE -> FullPageRecord.deserialize(buffer);
                };

                if (record == null) {
                    // 遇到 END 标记，返回
                    break;
                }

                records.add(record);
                logger.trace("Parsed record: {}", record);

            } catch (Exception e) {
                if (e instanceof RedoRecordParseException) {
                    throw (RedoRecordParseException) e;
                }
                throw new RedoRecordParseException(
                        "Failed to parse redo record at position " + startPos, e);
            }
        }

        return records;
    }

    /**
     * 尝试反序列化单条记录
     *
     * <p>不会消费 END 标记。</p>
     *
     * @param buffer 包含序列化数据的缓冲区
     * @return 反序列化后的记录，如果遇到 END 标记返回 null
     * @throws RedoRecordParseException 如果解析失败
     */
    public static RedoRecord deserializeSingle(ByteBuffer buffer) throws RedoRecordParseException {
        if (!buffer.hasRemaining()) {
            return null;
        }

        int startPos = buffer.position();

        try {
            // 读取 type 字节
            byte typeByte = buffer.get();
            int typeValue = typeByte & 0xFF;

            RedoRecordType type;
            try {
                type = RedoRecordType.fromValue(typeValue);
            } catch (IllegalArgumentException e) {
                throw new RedoRecordParseException(
                        "Unknown redo record type: " + typeValue + " at position " + startPos, e);
            }

            // 根据类型反序列化
            buffer.position(startPos);

            return switch (type) {
                case MLOG_WRITE_BYTES -> WriteBytesRecord.deserialize(buffer);
                case MLOG_MULTI_REC_END -> {
                    MultiRecEndRecord.deserialize(buffer);
                    yield null;
                }
                case MLOG_FULL_PAGE -> FullPageRecord.deserialize(buffer);
            };

        } catch (Exception e) {
            if (e instanceof RedoRecordParseException) {
                throw (RedoRecordParseException) e;
            }
            throw new RedoRecordParseException(
                    "Failed to parse redo record at position " + startPos, e);
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 计算记录列表的总序列化大小（含 END 标记）
     *
     * @param records 记录列表
     * @return 总字节数
     */
    public static int calculateSize(List<RedoRecord> records) {
        if (records == null || records.isEmpty()) {
            return 1;  // 只有 END 标记
        }

        int size = 0;
        for (RedoRecord record : records) {
            size += record.getSize();
        }
        size += 1;  // END 标记

        return size;
    }

    /**
     * 检查数据是否以 MLOG_MULTI_REC_END 结尾
     *
     * @param data 序列化数据
     * @return true 如果最后一个字节是 END 标记
     */
    public static boolean endsWithMultiRecEnd(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        return (data[data.length - 1] & 0xFF) == RedoRecordType.MLOG_MULTI_REC_END.getValue();
    }

    // ==================== 异常类 ====================

    /**
     * Redo Record 解析异常
     */
    public static class RedoRecordParseException extends Exception {
        public RedoRecordParseException(String message) {
            super(message);
        }

        public RedoRecordParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
