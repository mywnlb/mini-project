package cn.zhangyis.minidb.storage.btree;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * 简单整数键比较器
 *
 * <p>用于测试和简单场景的比较器实现。假设键是 4 字节的大端序整数。</p>
 *
 * <h2>记录布局假设</h2>
 * <pre>
 * 记录头 (5 bytes) + 键 (4 bytes, int) + 值 (变长)
 * </pre>
 *
 * @author MiniDB
 * @version 1.0
 */
public class IntKeyComparator implements RecordComparator {

    /** 记录头大小 */
    private static final int RECORD_HEADER_SIZE = 5;

    /** 键大小（4 字节整数） */
    private static final int KEY_SIZE = 4;

    @Override
    public int compareKeyToRecord(byte[] searchKey, ByteBuffer pageBuffer, int recordOffset) {
        if (searchKey == null || searchKey.length != KEY_SIZE) {
            throw new IllegalArgumentException("Search key must be 4 bytes for IntKeyComparator");
        }

        // 从搜索键提取整数
        int searchInt = bytesToInt(searchKey);

        // 从记录提取整数（跳过记录头）
        int recordInt = pageBuffer.getInt(recordOffset + RECORD_HEADER_SIZE);

        return Integer.compare(searchInt, recordInt);
    }

    @Override
    public int compareRecords(ByteBuffer pageBuffer1, int recordOffset1,
                              ByteBuffer pageBuffer2, int recordOffset2) {
        int key1 = pageBuffer1.getInt(recordOffset1 + RECORD_HEADER_SIZE);
        int key2 = pageBuffer2.getInt(recordOffset2 + RECORD_HEADER_SIZE);
        return Integer.compare(key1, key2);
    }

    @Override
    public byte[] extractKey(ByteBuffer pageBuffer, int recordOffset) {
        byte[] key = new byte[KEY_SIZE];
        int keyOffset = recordOffset + RECORD_HEADER_SIZE;
        for (int i = 0; i < KEY_SIZE; i++) {
            key[i] = pageBuffer.get(keyOffset + i);
        }
        return key;
    }

    @Override
    public int getKeyLength(byte[] key) {
        return KEY_SIZE;
    }

    /**
     * 将整数转换为字节数组（大端序）
     *
     * @param value 整数值
     * @return 4 字节数组
     */
    public static byte[] intToBytes(int value) {
        return new byte[]{
                (byte) (value >> 24),
                (byte) (value >> 16),
                (byte) (value >> 8),
                (byte) value
        };
    }

    /**
     * 将字节数组转换为整数（大端序）
     *
     * @param bytes 4 字节数组
     * @return 整数值
     */
    public static int bytesToInt(byte[] bytes) {
        if (bytes.length != KEY_SIZE) {
            throw new IllegalArgumentException("Byte array must be 4 bytes");
        }
        return ((bytes[0] & 0xFF) << 24) |
                ((bytes[1] & 0xFF) << 16) |
                ((bytes[2] & 0xFF) << 8) |
                (bytes[3] & 0xFF);
    }
}
