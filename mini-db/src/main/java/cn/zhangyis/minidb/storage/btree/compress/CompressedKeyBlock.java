package cn.zhangyis.minidb.storage.btree.compress;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 压缩键块
 *
 * <p>管理一组压缩键的存储和访问。</p>
 *
 * <h2>块格式</h2>
 * <pre>
 * +------------------+
 * | Header (8 bytes) |
 * |  - keyCount (4)  |
 * |  - flags (2)     |
 * |  - reserved (2)  |
 * +------------------+
 * | Base Key         |
 * |  - length (2)    |
 * |  - data (变长)    |
 * +------------------+
 * | Compressed Key 1 |
 * |  - prefixLen (2) |
 * |  - suffixLen (2) |
 * |  - suffix (变长)  |
 * +------------------+
 * | Compressed Key 2 |
 * +------------------+
 * | ...              |
 * +------------------+
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class CompressedKeyBlock {

    /** 块头部大小 */
    public static final int HEADER_SIZE = 8;

    /** 标志：启用压缩 */
    public static final short FLAG_COMPRESSED = 0x0001;

    /** 键数量 */
    private int keyCount;

    /** 标志 */
    private short flags;

    /** 基准键（第一个键，未压缩） */
    private byte[] baseKey;

    /** 压缩键列表 */
    private final List<CompressedKey> compressedKeys;

    /** 原始键缓存（用于快速访问） */
    private List<byte[]> keyCache;

    /**
     * 构造空的压缩键块
     */
    public CompressedKeyBlock() {
        this.keyCount = 0;
        this.flags = FLAG_COMPRESSED;
        this.baseKey = null;
        this.compressedKeys = new ArrayList<>();
        this.keyCache = null;
    }

    /**
     * 从键数组构建压缩块
     *
     * @param keys 已排序的键数组
     * @return 压缩键块
     */
    public static CompressedKeyBlock fromKeys(byte[][] keys) {
        CompressedKeyBlock block = new CompressedKeyBlock();

        if (keys == null || keys.length == 0) {
            return block;
        }

        // 第一个键作为基准键
        block.baseKey = keys[0].clone();
        block.keyCount = keys.length;

        // 压缩后续键
        byte[] previousKey = block.baseKey;
        for (int i = 1; i < keys.length; i++) {
            CompressedKey compressed = PrefixCompressor.compress(keys[i], previousKey);
            block.compressedKeys.add(compressed);
            previousKey = keys[i];
        }

        return block;
    }

    /**
     * 添加键
     *
     * @param key 要添加的键
     */
    public void addKey(byte[] key) {
        if (keyCount == 0) {
            baseKey = key.clone();
        } else {
            byte[] previousKey = getKey(keyCount - 1);
            CompressedKey compressed = PrefixCompressor.compress(key, previousKey);
            compressedKeys.add(compressed);
        }
        keyCount++;
        keyCache = null; // 清除缓存
    }

    /**
     * 获取指定位置的键
     *
     * @param index 索引
     * @return 解压后的键
     */
    public byte[] getKey(int index) {
        if (index < 0 || index >= keyCount) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + keyCount);
        }

        // 使用缓存
        if (keyCache != null && keyCache.size() > index) {
            return keyCache.get(index);
        }

        // 解压
        if (index == 0) {
            return baseKey.clone();
        }

        // 需要从头解压到目标位置
        byte[] previousKey = baseKey;
        for (int i = 0; i < index; i++) {
            if (i < compressedKeys.size()) {
                previousKey = PrefixCompressor.decompress(compressedKeys.get(i), previousKey);
            }
        }

        return previousKey;
    }

    /**
     * 获取所有键
     *
     * @return 解压后的所有键
     */
    public byte[][] getAllKeys() {
        if (keyCount == 0) {
            return new byte[0][];
        }

        byte[][] keys = new byte[keyCount][];
        keys[0] = baseKey.clone();

        byte[] previousKey = baseKey;
        for (int i = 1; i < keyCount; i++) {
            keys[i] = PrefixCompressor.decompress(compressedKeys.get(i - 1), previousKey);
            previousKey = keys[i];
        }

        return keys;
    }

    /**
     * 构建键缓存（用于频繁访问）
     */
    public void buildCache() {
        if (keyCache != null) {
            return;
        }

        keyCache = new ArrayList<>(keyCount);
        if (keyCount == 0) {
            return;
        }

        keyCache.add(baseKey.clone());

        byte[] previousKey = baseKey;
        for (int i = 0; i < compressedKeys.size(); i++) {
            byte[] key = PrefixCompressor.decompress(compressedKeys.get(i), previousKey);
            keyCache.add(key);
            previousKey = key;
        }
    }

    /**
     * 清除缓存
     */
    public void clearCache() {
        keyCache = null;
    }

    /**
     * 二分搜索键
     *
     * @param searchKey 搜索键
     * @return 找到返回索引，未找到返回 -(insertionPoint + 1)
     */
    public int binarySearch(byte[] searchKey) {
        buildCache(); // 确保缓存存在

        int low = 0;
        int high = keyCount - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            byte[] midKey = keyCache.get(mid);
            int cmp = compareKeys(midKey, searchKey);

            if (cmp < 0) {
                low = mid + 1;
            } else if (cmp > 0) {
                high = mid - 1;
            } else {
                return mid;
            }
        }

        return -(low + 1);
    }

    /**
     * 比较两个键
     */
    private int compareKeys(byte[] key1, byte[] key2) {
        int minLen = Math.min(key1.length, key2.length);
        for (int i = 0; i < minLen; i++) {
            int cmp = (key1[i] & 0xFF) - (key2[i] & 0xFF);
            if (cmp != 0) {
                return cmp;
            }
        }
        return key1.length - key2.length;
    }

    // ==================== 序列化 ====================

    /**
     * 序列化为字节数组
     *
     * @return 序列化后的字节数组
     */
    public byte[] serialize() {
        int size = calculateSerializedSize();
        ByteBuffer buffer = ByteBuffer.allocate(size);

        // 写入头部
        buffer.putInt(keyCount);
        buffer.putShort(flags);
        buffer.putShort((short) 0); // reserved

        if (keyCount > 0) {
            // 写入基准键
            buffer.putShort((short) baseKey.length);
            buffer.put(baseKey);

            // 写入压缩键
            for (CompressedKey compressed : compressedKeys) {
                buffer.putShort((short) compressed.getPrefixLength());
                buffer.putShort((short) compressed.getSuffix().length);
                buffer.put(compressed.getSuffix());
            }
        }

        return buffer.array();
    }

    /**
     * 从字节数组反序列化
     *
     * @param data 字节数组
     * @return 压缩键块
     */
    public static CompressedKeyBlock deserialize(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);

        CompressedKeyBlock block = new CompressedKeyBlock();
        block.keyCount = buffer.getInt();
        block.flags = buffer.getShort();
        buffer.getShort(); // reserved

        if (block.keyCount > 0) {
            // 读取基准键
            int baseKeyLen = buffer.getShort() & 0xFFFF;
            block.baseKey = new byte[baseKeyLen];
            buffer.get(block.baseKey);

            // 读取压缩键
            for (int i = 1; i < block.keyCount; i++) {
                int prefixLen = buffer.getShort() & 0xFFFF;
                int suffixLen = buffer.getShort() & 0xFFFF;
                byte[] suffix = new byte[suffixLen];
                buffer.get(suffix);

                block.compressedKeys.add(new CompressedKey(prefixLen, suffix, prefixLen + suffixLen));
            }
        }

        return block;
    }

    /**
     * 计算序列化大小
     *
     * @return 序列化后的大小
     */
    public int calculateSerializedSize() {
        int size = HEADER_SIZE;

        if (keyCount > 0) {
            // 基准键
            size += 2 + baseKey.length;

            // 压缩键
            for (CompressedKey compressed : compressedKeys) {
                size += 4 + compressed.getSuffix().length; // prefixLen(2) + suffixLen(2) + suffix
            }
        }

        return size;
    }

    /**
     * 计算原始大小（未压缩）
     *
     * @return 原始大小
     */
    public int calculateOriginalSize() {
        if (keyCount == 0) {
            return 0;
        }

        int size = baseKey.length;
        for (CompressedKey compressed : compressedKeys) {
            size += compressed.getOriginalLength();
        }
        return size;
    }

    /**
     * 获取压缩比
     *
     * @return 压缩比
     */
    public double getCompressionRatio() {
        int originalSize = calculateOriginalSize();
        if (originalSize == 0) {
            return 1.0;
        }
        return (double) calculateSerializedSize() / originalSize;
    }

    // ==================== Getters ====================

    public int getKeyCount() {
        return keyCount;
    }

    public short getFlags() {
        return flags;
    }

    public boolean isCompressed() {
        return (flags & FLAG_COMPRESSED) != 0;
    }

    public byte[] getBaseKey() {
        return baseKey;
    }

    public List<CompressedKey> getCompressedKeys() {
        return compressedKeys;
    }

    @Override
    public String toString() {
        return String.format("CompressedKeyBlock{keys=%d, compressed=%b, ratio=%.2f}",
                keyCount, isCompressed(), getCompressionRatio());
    }
}
