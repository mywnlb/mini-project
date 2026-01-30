package cn.zhangyis.minidb.storage.btree.compress;

/**
 * 前缀压缩工具类
 *
 * <p>提供键的前缀压缩和解压功能。</p>
 *
 * <h2>压缩原理</h2>
 * <p>对于有序的键序列，相邻的键通常有共同的前缀。
 * 前缀压缩只存储与前一个键不同的后缀部分，从而节省空间。</p>
 *
 * <h2>存储格式</h2>
 * <pre>
 * +------------------+
 * | prefixLen (2B)   |  与前一个键共享的前缀长度
 * +------------------+
 * | suffix (变长)     |  后缀数据
 * +------------------+
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class PrefixCompressor {

    /** 最大前缀长度（使用2字节存储） */
    public static final int MAX_PREFIX_LENGTH = 65535;

    /**
     * 计算两个键的公共前缀长度
     *
     * @param key1 键1
     * @param key2 键2
     * @return 公共前缀长度
     */
    public static int commonPrefixLength(byte[] key1, byte[] key2) {
        if (key1 == null || key2 == null) {
            return 0;
        }

        int minLen = Math.min(key1.length, key2.length);
        int prefixLen = 0;

        for (int i = 0; i < minLen; i++) {
            if (key1[i] == key2[i]) {
                prefixLen++;
            } else {
                break;
            }
        }

        return Math.min(prefixLen, MAX_PREFIX_LENGTH);
    }

    /**
     * 压缩键（相对于前一个键）
     *
     * @param currentKey  当前键
     * @param previousKey 前一个键（第一个键时为 null）
     * @return 压缩结果
     */
    public static CompressedKey compress(byte[] currentKey, byte[] previousKey) {
        if (currentKey == null) {
            throw new IllegalArgumentException("Current key cannot be null");
        }

        if (previousKey == null) {
            // 第一个键，不压缩
            return CompressedKey.uncompressed(currentKey);
        }

        int prefixLen = commonPrefixLength(currentKey, previousKey);
        int suffixLen = currentKey.length - prefixLen;

        byte[] suffix = new byte[suffixLen];
        System.arraycopy(currentKey, prefixLen, suffix, 0, suffixLen);

        return new CompressedKey(prefixLen, suffix, currentKey.length);
    }

    /**
     * 解压键
     *
     * @param compressed  压缩的键
     * @param previousKey 前一个键（用于提供前缀）
     * @return 解压后的完整键
     */
    public static byte[] decompress(CompressedKey compressed, byte[] previousKey) {
        if (compressed.getPrefixLength() == 0) {
            // 未压缩，直接返回后缀
            return compressed.getSuffix().clone();
        }

        if (previousKey == null) {
            throw new IllegalArgumentException("Previous key required for decompression");
        }

        int prefixLen = compressed.getPrefixLength();
        byte[] suffix = compressed.getSuffix();
        byte[] result = new byte[prefixLen + suffix.length];

        // 复制前缀
        System.arraycopy(previousKey, 0, result, 0, prefixLen);
        // 复制后缀
        System.arraycopy(suffix, 0, result, prefixLen, suffix.length);

        return result;
    }

    /**
     * 计算压缩一组键后的总大小
     *
     * @param keys 键数组（已排序）
     * @return 压缩后的总大小
     */
    public static int calculateCompressedSize(byte[][] keys) {
        if (keys == null || keys.length == 0) {
            return 0;
        }

        int totalSize = 0;
        byte[] previousKey = null;

        for (byte[] key : keys) {
            CompressedKey compressed = compress(key, previousKey);
            totalSize += compressed.getCompressedSize();
            previousKey = key;
        }

        return totalSize;
    }

    /**
     * 计算原始大小
     *
     * @param keys 键数组
     * @return 原始总大小
     */
    public static int calculateOriginalSize(byte[][] keys) {
        if (keys == null || keys.length == 0) {
            return 0;
        }

        int totalSize = 0;
        for (byte[] key : keys) {
            totalSize += key.length;
        }
        return totalSize;
    }

    /**
     * 计算压缩比
     *
     * @param keys 键数组（已排序）
     * @return 压缩比（压缩后大小 / 原始大小）
     */
    public static double calculateCompressionRatio(byte[][] keys) {
        int originalSize = calculateOriginalSize(keys);
        if (originalSize == 0) {
            return 1.0;
        }

        int compressedSize = calculateCompressedSize(keys);
        return (double) compressedSize / originalSize;
    }

    /**
     * 判断是否值得压缩
     *
     * @param keys      键数组
     * @param threshold 压缩比阈值（低于此值才压缩）
     * @return 如果值得压缩返回 true
     */
    public static boolean isWorthCompressing(byte[][] keys, double threshold) {
        return calculateCompressionRatio(keys) < threshold;
    }

    // 禁止实例化
    private PrefixCompressor() {
        throw new UnsupportedOperationException("Utility class");
    }
}
