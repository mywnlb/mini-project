package cn.zhangyis.minidb.storage.btree;

/**
 * 批量加载记录
 *
 * <p>用于批量加载的记录封装。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class BulkLoadRecord {

    /** 记录键 */
    private final byte[] key;

    /** 完整记录数据 */
    private final byte[] data;

    /**
     * 构造批量加载记录
     *
     * @param key  记录键
     * @param data 完整记录数据
     */
    public BulkLoadRecord(byte[] key, byte[] data) {
        this.key = key;
        this.data = data;
    }

    /**
     * 从整数键和值创建记录
     *
     * @param key   整数键
     * @param value 值
     * @return 批量加载记录
     */
    public static BulkLoadRecord of(int key, byte[] value) {
        byte[] keyBytes = IntKeyComparator.intToBytes(key);
        byte[] data = SimpleRecordBuilder.buildRecord(key, value, 2);
        return new BulkLoadRecord(keyBytes, data);
    }

    /**
     * 从整数键创建记录（值为键的字节表示）
     *
     * @param key 整数键
     * @return 批量加载记录
     */
    public static BulkLoadRecord of(int key) {
        return of(key, new byte[]{(byte) key});
    }

    public byte[] getKey() {
        return key;
    }

    public byte[] getData() {
        return data;
    }

    /**
     * 获取键的整数值
     *
     * @return 整数键
     */
    public int getKeyAsInt() {
        return IntKeyComparator.bytesToInt(key);
    }
}
