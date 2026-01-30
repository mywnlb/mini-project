package cn.zhangyis.minidb.storage.btree.compress;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 压缩页面
 *
 * <p>支持前缀压缩的 B+Tree 叶子页面。</p>
 *
 * <h2>页面布局</h2>
 * <pre>
 * +----------------------+
 * | Page Header (38)     |
 * +----------------------+
 * | Compression Header   |
 * |  - flags (2)         |
 * |  - keyCount (2)      |
 * |  - baseKeyOffset (2) |
 * |  - dataOffset (2)    |
 * +----------------------+
 * | Slot Directory       |
 * |  - slot[0]           |
 * |  - slot[1]           |
 * |  - ...               |
 * +----------------------+
 * | Free Space           |
 * +----------------------+
 * | Compressed Records   |
 * |  (grows upward)      |
 * +----------------------+
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class CompressedPage {

    /** 页面大小 */
    public static final int PAGE_SIZE = 16 * 1024;

    /** 页面头部大小 */
    public static final int PAGE_HEADER_SIZE = 38;

    /** 压缩头部大小 */
    public static final int COMPRESSION_HEADER_SIZE = 8;

    /** 槽目录条目大小 */
    public static final int SLOT_SIZE = 4;

    /** 标志：启用压缩 */
    public static final short FLAG_COMPRESSED = 0x0001;

    /** 标志：有基准键 */
    public static final short FLAG_HAS_BASE_KEY = 0x0002;

    // 压缩头部偏移
    private static final int FLAGS_OFFSET = PAGE_HEADER_SIZE;
    private static final int KEY_COUNT_OFFSET = PAGE_HEADER_SIZE + 2;
    private static final int BASE_KEY_OFFSET_OFFSET = PAGE_HEADER_SIZE + 4;
    private static final int DATA_OFFSET_OFFSET = PAGE_HEADER_SIZE + 6;

    // 槽目录起始位置
    private static final int SLOT_DIRECTORY_START = PAGE_HEADER_SIZE + COMPRESSION_HEADER_SIZE;

    /**
     * 初始化压缩页面
     *
     * @param buffer 页面缓冲区
     */
    public static void initPage(ByteBuffer buffer) {
        buffer.putShort(FLAGS_OFFSET, FLAG_COMPRESSED);
        buffer.putShort(KEY_COUNT_OFFSET, (short) 0);
        buffer.putShort(BASE_KEY_OFFSET_OFFSET, (short) 0);
        buffer.putShort(DATA_OFFSET_OFFSET, (short) PAGE_SIZE);
    }

    /**
     * 读取标志
     */
    public static short readFlags(ByteBuffer buffer) {
        return buffer.getShort(FLAGS_OFFSET);
    }

    /**
     * 写入标志
     */
    public static void writeFlags(ByteBuffer buffer, short flags) {
        buffer.putShort(FLAGS_OFFSET, flags);
    }

    /**
     * 读取键数量
     */
    public static int readKeyCount(ByteBuffer buffer) {
        return buffer.getShort(KEY_COUNT_OFFSET) & 0xFFFF;
    }

    /**
     * 写入键数量
     */
    public static void writeKeyCount(ByteBuffer buffer, int count) {
        buffer.putShort(KEY_COUNT_OFFSET, (short) count);
    }

    /**
     * 读取数据区偏移
     */
    public static int readDataOffset(ByteBuffer buffer) {
        return buffer.getShort(DATA_OFFSET_OFFSET) & 0xFFFF;
    }

    /**
     * 写入数据区偏移
     */
    public static void writeDataOffset(ByteBuffer buffer, int offset) {
        buffer.putShort(DATA_OFFSET_OFFSET, (short) offset);
    }

    /**
     * 计算槽目录结束位置
     */
    public static int getSlotDirectoryEnd(ByteBuffer buffer) {
        int keyCount = readKeyCount(buffer);
        return SLOT_DIRECTORY_START + keyCount * SLOT_SIZE;
    }

    /**
     * 计算可用空间
     */
    public static int getFreeSpace(ByteBuffer buffer) {
        int slotEnd = getSlotDirectoryEnd(buffer);
        int dataOffset = readDataOffset(buffer);
        return dataOffset - slotEnd;
    }

    /**
     * 读取槽信息
     *
     * @param buffer 页面缓冲区
     * @param slotNo 槽号
     * @return 槽信息（记录偏移）
     */
    public static int readSlot(ByteBuffer buffer, int slotNo) {
        int slotOffset = SLOT_DIRECTORY_START + slotNo * SLOT_SIZE;
        return buffer.getInt(slotOffset);
    }

    /**
     * 写入槽信息
     */
    public static void writeSlot(ByteBuffer buffer, int slotNo, int recordOffset) {
        int slotOffset = SLOT_DIRECTORY_START + slotNo * SLOT_SIZE;
        buffer.putInt(slotOffset, recordOffset);
    }

    /**
     * 插入压缩记录
     *
     * @param buffer       页面缓冲区
     * @param slotNo       插入位置
     * @param compressedKey 压缩键
     * @param value        值数据
     * @return 如果成功返回 true
     */
    public static boolean insertCompressedRecord(ByteBuffer buffer, int slotNo,
                                                  CompressedKey compressedKey, byte[] value) {
        int keyCount = readKeyCount(buffer);

        // 计算记录大小
        int recordSize = calculateRecordSize(compressedKey, value);

        // 检查空间
        int requiredSpace = recordSize + SLOT_SIZE;
        if (getFreeSpace(buffer) < requiredSpace) {
            return false;
        }

        // 分配空间
        int dataOffset = readDataOffset(buffer);
        int newRecordOffset = dataOffset - recordSize;

        // 写入记录
        writeCompressedRecord(buffer, newRecordOffset, compressedKey, value);

        // 移动槽目录
        for (int i = keyCount; i > slotNo; i--) {
            int prevSlot = readSlot(buffer, i - 1);
            writeSlot(buffer, i, prevSlot);
        }

        // 写入新槽
        writeSlot(buffer, slotNo, newRecordOffset);

        // 更新头部
        writeKeyCount(buffer, keyCount + 1);
        writeDataOffset(buffer, newRecordOffset);

        return true;
    }

    /**
     * 计算记录大小
     */
    private static int calculateRecordSize(CompressedKey compressedKey, byte[] value) {
        // prefixLen(2) + suffixLen(2) + suffix + valueLen(2) + value
        return 2 + 2 + compressedKey.getSuffix().length + 2 + value.length;
    }

    /**
     * 写入压缩记录
     */
    private static void writeCompressedRecord(ByteBuffer buffer, int offset,
                                               CompressedKey compressedKey, byte[] value) {
        int pos = offset;

        // 写入前缀长度
        buffer.putShort(pos, (short) compressedKey.getPrefixLength());
        pos += 2;

        // 写入后缀长度和数据
        byte[] suffix = compressedKey.getSuffix();
        buffer.putShort(pos, (short) suffix.length);
        pos += 2;

        int oldPos = buffer.position();
        buffer.position(pos);
        buffer.put(suffix);
        pos += suffix.length;

        // 写入值长度和数据
        buffer.putShort(pos, (short) value.length);
        pos += 2;
        buffer.position(pos);
        buffer.put(value);

        buffer.position(oldPos);
    }

    /**
     * 读取压缩记录
     *
     * @param buffer 页面缓冲区
     * @param slotNo 槽号
     * @return 压缩记录
     */
    public static CompressedRecord readCompressedRecord(ByteBuffer buffer, int slotNo) {
        int recordOffset = readSlot(buffer, slotNo);
        int pos = recordOffset;

        // 读取前缀长度
        int prefixLen = buffer.getShort(pos) & 0xFFFF;
        pos += 2;

        // 读取后缀
        int suffixLen = buffer.getShort(pos) & 0xFFFF;
        pos += 2;

        byte[] suffix = new byte[suffixLen];
        int oldPos = buffer.position();
        buffer.position(pos);
        buffer.get(suffix);
        pos += suffixLen;

        // 读取值
        int valueLen = buffer.getShort(pos) & 0xFFFF;
        pos += 2;

        byte[] value = new byte[valueLen];
        buffer.position(pos);
        buffer.get(value);

        buffer.position(oldPos);

        CompressedKey compressedKey = new CompressedKey(prefixLen, suffix, prefixLen + suffixLen);
        return new CompressedRecord(compressedKey, value);
    }

    /**
     * 删除记录
     *
     * @param buffer 页面缓冲区
     * @param slotNo 槽号
     */
    public static void deleteRecord(ByteBuffer buffer, int slotNo) {
        int keyCount = readKeyCount(buffer);

        if (slotNo < 0 || slotNo >= keyCount) {
            throw new IndexOutOfBoundsException("Invalid slot: " + slotNo);
        }

        // 移动槽目录
        for (int i = slotNo; i < keyCount - 1; i++) {
            int nextSlot = readSlot(buffer, i + 1);
            writeSlot(buffer, i, nextSlot);
        }

        // 更新键数量
        writeKeyCount(buffer, keyCount - 1);

        // 注意：这里不回收空间，需要定期压缩页面
    }

    /**
     * 压缩页面（回收碎片空间）
     *
     * @param buffer 页面缓冲区
     */
    public static void compactPage(ByteBuffer buffer) {
        int keyCount = readKeyCount(buffer);
        if (keyCount == 0) {
            writeDataOffset(buffer, PAGE_SIZE);
            return;
        }

        // 读取所有记录
        List<CompressedRecord> records = new ArrayList<>();
        for (int i = 0; i < keyCount; i++) {
            records.add(readCompressedRecord(buffer, i));
        }

        // 重写记录
        int dataOffset = PAGE_SIZE;
        for (int i = 0; i < keyCount; i++) {
            CompressedRecord record = records.get(i);
            int recordSize = calculateRecordSize(record.getCompressedKey(), record.getValue());
            dataOffset -= recordSize;

            writeCompressedRecord(buffer, dataOffset, record.getCompressedKey(), record.getValue());
            writeSlot(buffer, i, dataOffset);
        }

        writeDataOffset(buffer, dataOffset);
    }

    /**
     * 重新压缩页面中的所有键
     *
     * <p>当插入或删除导致压缩效果变差时调用。</p>
     *
     * @param buffer 页面缓冲区
     */
    public static void recompressPage(ByteBuffer buffer) {
        int keyCount = readKeyCount(buffer);
        if (keyCount <= 1) {
            return;
        }

        // 读取所有记录并解压键
        List<byte[]> keys = new ArrayList<>();
        List<byte[]> values = new ArrayList<>();

        byte[] previousKey = null;
        for (int i = 0; i < keyCount; i++) {
            CompressedRecord record = readCompressedRecord(buffer, i);
            byte[] key = PrefixCompressor.decompress(record.getCompressedKey(), previousKey);
            keys.add(key);
            values.add(record.getValue());
            previousKey = key;
        }

        // 清空页面
        writeKeyCount(buffer, 0);
        writeDataOffset(buffer, PAGE_SIZE);

        // 重新插入（重新压缩）
        previousKey = null;
        for (int i = 0; i < keys.size(); i++) {
            byte[] key = keys.get(i);
            byte[] value = values.get(i);
            CompressedKey compressedKey = PrefixCompressor.compress(key, previousKey);
            insertCompressedRecord(buffer, i, compressedKey, value);
            previousKey = key;
        }
    }

    /**
     * 压缩记录
     */
    public static class CompressedRecord {
        private final CompressedKey compressedKey;
        private final byte[] value;

        public CompressedRecord(CompressedKey compressedKey, byte[] value) {
            this.compressedKey = compressedKey;
            this.value = value;
        }

        public CompressedKey getCompressedKey() {
            return compressedKey;
        }

        public byte[] getValue() {
            return value;
        }
    }

    // 禁止实例化
    private CompressedPage() {
        throw new UnsupportedOperationException("Utility class");
    }
}
