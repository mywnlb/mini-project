package cn.zhangyis.minidb.storage.btree.compress;

/**
 * 压缩统计信息
 *
 * <p>收集和报告前缀压缩的统计数据。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class CompressionStats {

    /** 总键数量 */
    private long totalKeys;

    /** 原始总大小 */
    private long originalSize;

    /** 压缩后总大小 */
    private long compressedSize;

    /** 总前缀节省字节数 */
    private long prefixSavedBytes;

    /** 最大前缀长度 */
    private int maxPrefixLength;

    /** 平均前缀长度 */
    private double avgPrefixLength;

    /** 压缩键数量（前缀长度 > 0） */
    private long compressedKeyCount;

    /**
     * 构造空的统计信息
     */
    public CompressionStats() {
        reset();
    }

    /**
     * 重置统计信息
     */
    public void reset() {
        this.totalKeys = 0;
        this.originalSize = 0;
        this.compressedSize = 0;
        this.prefixSavedBytes = 0;
        this.maxPrefixLength = 0;
        this.avgPrefixLength = 0;
        this.compressedKeyCount = 0;
    }

    /**
     * 添加压缩键的统计
     *
     * @param compressed 压缩键
     */
    public void addKey(CompressedKey compressed) {
        totalKeys++;
        originalSize += compressed.getOriginalLength();
        compressedSize += compressed.getCompressedSize();

        int prefixLen = compressed.getPrefixLength();
        if (prefixLen > 0) {
            compressedKeyCount++;
            prefixSavedBytes += prefixLen;
            maxPrefixLength = Math.max(maxPrefixLength, prefixLen);
        }

        // 更新平均前缀长度
        if (compressedKeyCount > 0) {
            avgPrefixLength = (double) prefixSavedBytes / compressedKeyCount;
        }
    }

    /**
     * 合并另一个统计信息
     *
     * @param other 另一个统计信息
     */
    public void merge(CompressionStats other) {
        this.totalKeys += other.totalKeys;
        this.originalSize += other.originalSize;
        this.compressedSize += other.compressedSize;
        this.prefixSavedBytes += other.prefixSavedBytes;
        this.maxPrefixLength = Math.max(this.maxPrefixLength, other.maxPrefixLength);
        this.compressedKeyCount += other.compressedKeyCount;

        if (compressedKeyCount > 0) {
            avgPrefixLength = (double) prefixSavedBytes / compressedKeyCount;
        }
    }

    /**
     * 获取压缩比
     *
     * @return 压缩比（压缩后大小 / 原始大小）
     */
    public double getCompressionRatio() {
        if (originalSize == 0) {
            return 1.0;
        }
        return (double) compressedSize / originalSize;
    }

    /**
     * 获取节省的空间百分比
     *
     * @return 节省百分比（0-100）
     */
    public double getSavedPercentage() {
        return (1.0 - getCompressionRatio()) * 100;
    }

    /**
     * 获取压缩键的比例
     *
     * @return 压缩键比例（0-1）
     */
    public double getCompressedKeyRatio() {
        if (totalKeys == 0) {
            return 0;
        }
        return (double) compressedKeyCount / totalKeys;
    }

    // ==================== Getters ====================

    public long getTotalKeys() {
        return totalKeys;
    }

    public long getOriginalSize() {
        return originalSize;
    }

    public long getCompressedSize() {
        return compressedSize;
    }

    public long getPrefixSavedBytes() {
        return prefixSavedBytes;
    }

    public int getMaxPrefixLength() {
        return maxPrefixLength;
    }

    public double getAvgPrefixLength() {
        return avgPrefixLength;
    }

    public long getCompressedKeyCount() {
        return compressedKeyCount;
    }

    @Override
    public String toString() {
        return String.format(
                "CompressionStats{keys=%d, original=%d, compressed=%d, ratio=%.2f%%, " +
                        "saved=%.1f%%, avgPrefix=%.1f, maxPrefix=%d}",
                totalKeys, originalSize, compressedSize,
                getCompressionRatio() * 100, getSavedPercentage(),
                avgPrefixLength, maxPrefixLength
        );
    }

    /**
     * 生成详细报告
     *
     * @return 详细报告字符串
     */
    public String toDetailedReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Compression Statistics ===\n");
        sb.append(String.format("Total Keys:          %,d\n", totalKeys));
        sb.append(String.format("Compressed Keys:     %,d (%.1f%%)\n",
                compressedKeyCount, getCompressedKeyRatio() * 100));
        sb.append(String.format("Original Size:       %,d bytes\n", originalSize));
        sb.append(String.format("Compressed Size:     %,d bytes\n", compressedSize));
        sb.append(String.format("Compression Ratio:   %.2f%%\n", getCompressionRatio() * 100));
        sb.append(String.format("Space Saved:         %.1f%% (%,d bytes)\n",
                getSavedPercentage(), originalSize - compressedSize));
        sb.append(String.format("Avg Prefix Length:   %.1f bytes\n", avgPrefixLength));
        sb.append(String.format("Max Prefix Length:   %d bytes\n", maxPrefixLength));
        return sb.toString();
    }
}
