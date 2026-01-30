package cn.zhangyis.minidb.storage.btree.compress;

/**
 * 前缀压缩结果
 *
 * <p>表示一个键经过前缀压缩后的结果。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class CompressedKey {

    /** 与前一个键共享的前缀长度 */
    private final int prefixLength;

    /** 后缀数据（不同的部分） */
    private final byte[] suffix;

    /** 原始键长度 */
    private final int originalLength;

    /**
     * 构造压缩键
     *
     * @param prefixLength   前缀长度
     * @param suffix         后缀数据
     * @param originalLength 原始键长度
     */
    public CompressedKey(int prefixLength, byte[] suffix, int originalLength) {
        this.prefixLength = prefixLength;
        this.suffix = suffix;
        this.originalLength = originalLength;
    }

    /**
     * 创建未压缩的键（第一个键）
     *
     * @param key 原始键
     * @return 压缩键（前缀长度为0）
     */
    public static CompressedKey uncompressed(byte[] key) {
        return new CompressedKey(0, key.clone(), key.length);
    }

    /**
     * 获取压缩后的存储大小
     *
     * @return 存储大小（2字节前缀长度 + 后缀数据）
     */
    public int getCompressedSize() {
        return 2 + suffix.length; // 2 bytes for prefix length
    }

    /**
     * 获取节省的字节数
     *
     * @return 节省的字节数
     */
    public int getSavedBytes() {
        return originalLength - getCompressedSize();
    }

    /**
     * 获取压缩比
     *
     * @return 压缩比（0-1，越小越好）
     */
    public double getCompressionRatio() {
        if (originalLength == 0) {
            return 1.0;
        }
        return (double) getCompressedSize() / originalLength;
    }

    // ==================== Getters ====================

    public int getPrefixLength() {
        return prefixLength;
    }

    public byte[] getSuffix() {
        return suffix;
    }

    public int getOriginalLength() {
        return originalLength;
    }

    @Override
    public String toString() {
        return String.format("CompressedKey{prefix=%d, suffix=%d, original=%d, ratio=%.2f}",
                prefixLength, suffix.length, originalLength, getCompressionRatio());
    }
}
