package cn.zhangyis.minidb.storage.btree;

/**
 * 重复键异常
 *
 * <p>当尝试向唯一索引插入重复键时抛出。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class DuplicateKeyException extends RuntimeException {

    /** 重复的键 */
    private final byte[] key;

    /** 索引 ID */
    private final long indexId;

    /**
     * 构造重复键异常
     *
     * @param key     重复的键
     * @param indexId 索引 ID
     */
    public DuplicateKeyException(byte[] key, long indexId) {
        super(String.format("Duplicate key in index %d: %s", indexId, keyToString(key)));
        this.key = key;
        this.indexId = indexId;
    }

    /**
     * 构造重复键异常（带消息）
     *
     * @param message 错误消息
     * @param key     重复的键
     * @param indexId 索引 ID
     */
    public DuplicateKeyException(String message, byte[] key, long indexId) {
        super(message);
        this.key = key;
        this.indexId = indexId;
    }

    public byte[] getKey() {
        return key;
    }

    public long getIndexId() {
        return indexId;
    }

    /**
     * 获取键的整数值（如果适用）
     *
     * @return 整数键值
     */
    public int getKeyAsInt() {
        if (key != null && key.length >= 4) {
            return IntKeyComparator.bytesToInt(key);
        }
        return 0;
    }

    private static String keyToString(byte[] key) {
        if (key == null) {
            return "null";
        }
        if (key.length >= 4) {
            return String.valueOf(IntKeyComparator.bytesToInt(key));
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < key.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(key[i] & 0xFF);
        }
        sb.append("]");
        return sb.toString();
    }
}
