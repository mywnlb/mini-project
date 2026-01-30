package cn.zhangyis.minidb.storage.btree.compress;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * 压缩页面扫描器
 *
 * <p>用于顺序扫描压缩页面中的记录，维护解压状态。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class CompressedPageScanner implements Iterator<CompressedPageScanner.DecompressedEntry>, AutoCloseable {

    /** 页面缓冲区 */
    private final ByteBuffer buffer;

    /** 总键数量 */
    private final int keyCount;

    /** 当前位置 */
    private int currentIndex;

    /** 前一个解压的键（用于解压下一个） */
    private byte[] previousKey;

    /** 是否已关闭 */
    private boolean closed;

    /**
     * 构造扫描器
     *
     * @param buffer 页面缓冲区
     */
    public CompressedPageScanner(ByteBuffer buffer) {
        this.buffer = buffer;
        this.keyCount = CompressedPage.readKeyCount(buffer);
        this.currentIndex = 0;
        this.previousKey = null;
        this.closed = false;
    }

    /**
     * 从指定位置开始扫描
     *
     * @param buffer   页面缓冲区
     * @param startIndex 起始索引
     * @return 扫描器
     */
    public static CompressedPageScanner fromIndex(ByteBuffer buffer, int startIndex) {
        CompressedPageScanner scanner = new CompressedPageScanner(buffer);

        // 跳过前面的记录，但需要解压以维护状态
        while (scanner.currentIndex < startIndex && scanner.hasNext()) {
            scanner.next();
        }

        return scanner;
    }

    @Override
    public boolean hasNext() {
        return !closed && currentIndex < keyCount;
    }

    @Override
    public DecompressedEntry next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        // 读取压缩记录
        CompressedPage.CompressedRecord record = CompressedPage.readCompressedRecord(buffer, currentIndex);

        // 解压键
        byte[] key = PrefixCompressor.decompress(record.getCompressedKey(), previousKey);

        // 更新状态
        previousKey = key;
        int index = currentIndex;
        currentIndex++;

        return new DecompressedEntry(index, key, record.getValue());
    }

    /**
     * 跳过指定数量的记录
     *
     * @param count 跳过数量
     * @return 实际跳过的数量
     */
    public int skip(int count) {
        int skipped = 0;
        while (skipped < count && hasNext()) {
            next();
            skipped++;
        }
        return skipped;
    }

    /**
     * 重置到开始位置
     */
    public void reset() {
        currentIndex = 0;
        previousKey = null;
    }

    /**
     * 获取当前位置
     */
    public int getCurrentIndex() {
        return currentIndex;
    }

    /**
     * 获取剩余记录数
     */
    public int remaining() {
        return keyCount - currentIndex;
    }

    @Override
    public void close() {
        closed = true;
    }

    /**
     * 解压后的条目
     */
    public static class DecompressedEntry {
        private final int index;
        private final byte[] key;
        private final byte[] value;

        public DecompressedEntry(int index, byte[] key, byte[] value) {
            this.index = index;
            this.key = key;
            this.value = value;
        }

        public int getIndex() {
            return index;
        }

        public byte[] getKey() {
            return key;
        }

        public byte[] getValue() {
            return value;
        }

        @Override
        public String toString() {
            return String.format("Entry{index=%d, keyLen=%d, valueLen=%d}",
                    index, key.length, value.length);
        }
    }
}
