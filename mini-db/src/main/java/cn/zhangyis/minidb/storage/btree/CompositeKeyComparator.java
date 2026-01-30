package cn.zhangyis.minidb.storage.btree;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 复合键比较器
 *
 * <p>实现复合键的比较逻辑，支持前缀匹配。</p>
 *
 * @author MiniDB
 * @version 1.0
 */
public class CompositeKeyComparator implements RecordComparator {

    /** 键定义 */
    private final CompositeKeyDef keyDef;

    /** 记录头部大小 */
    private static final int RECORD_HEADER_SIZE = SimpleRecordBuilder.RECORD_HEADER_SIZE;

    /**
     * 构造复合键比较器
     *
     * @param keyDef 键定义
     */
    public CompositeKeyComparator(CompositeKeyDef keyDef) {
        this.keyDef = keyDef;
    }

    @Override
    public int compareKeyToRecord(byte[] searchKey, ByteBuffer buf, int recordOffset) {
        // 从记录中提取键
        byte[] recordKey = extractKey(buf, recordOffset);
        return compareKeys(searchKey, recordKey);
    }

    @Override
    public int compareRecords(ByteBuffer pageBuffer1, int recordOffset1,
                              ByteBuffer pageBuffer2, int recordOffset2) {
        byte[] key1 = extractKey(pageBuffer1, recordOffset1);
        byte[] key2 = extractKey(pageBuffer2, recordOffset2);
        return compareKeys(key1, key2);
    }

    @Override
    public byte[] extractKey(ByteBuffer buf, int recordOffset) {
        // 跳过记录头部，读取键数据
        int keyStart = recordOffset + RECORD_HEADER_SIZE;

        // 计算键的实际长度
        int keyLength = calculateKeyLength(buf, keyStart);

        byte[] key = new byte[keyLength];
        int oldPos = buf.position();
        buf.position(keyStart);
        buf.get(key);
        buf.position(oldPos);

        return key;
    }

    /**
     * 比较两个编码后的键
     *
     * @param key1 键1
     * @param key2 键2
     * @return 比较结果
     */
    public int compareKeys(byte[] key1, byte[] key2) {
        ByteBuffer buf1 = ByteBuffer.wrap(key1);
        ByteBuffer buf2 = ByteBuffer.wrap(key2);

        for (int i = 0; i < keyDef.getColumnCount(); i++) {
            // 如果任一键已经读完，则较短的键较小（前缀匹配）
            if (!buf1.hasRemaining() && !buf2.hasRemaining()) {
                return 0; // 两个键都结束，相等
            }
            if (!buf1.hasRemaining()) {
                return -1; // key1 是 key2 的前缀
            }
            if (!buf2.hasRemaining()) {
                return 1; // key2 是 key1 的前缀
            }

            KeyColumn column = keyDef.getColumn(i);
            int cmp = compareColumn(buf1, buf2, column);

            if (cmp != 0) {
                return cmp;
            }
        }

        return 0;
    }

    /**
     * 比较单个列
     */
    private int compareColumn(ByteBuffer buf1, ByteBuffer buf2, KeyColumn column) {
        // 处理 NULL
        if (column.isNullable()) {
            byte null1 = buf1.get();
            byte null2 = buf2.get();

            if (null1 == 0 && null2 == 0) {
                return 0; // 都是 NULL
            }
            if (null1 == 0) {
                return -1; // NULL 排在前面
            }
            if (null2 == 0) {
                return 1;
            }
        }

        int cmp;
        switch (column.getType()) {
            case INT:
                int int1 = buf1.getInt();
                int int2 = buf2.getInt();
                // 降序时已经取反，直接比较即可
                cmp = Integer.compare(int1, int2);
                break;

            case BIGINT:
                long long1 = buf1.getLong();
                long long2 = buf2.getLong();
                cmp = Long.compare(long1, long2);
                break;

            case VARCHAR:
            case VARBINARY:
                int len1 = buf1.getShort() & 0xFFFF;
                int len2 = buf2.getShort() & 0xFFFF;
                cmp = compareBytes(buf1, len1, buf2, len2);
                break;

            case CHAR:
            case BINARY:
                cmp = compareBytes(buf1, column.getMaxLength(), buf2, column.getMaxLength());
                break;

            default:
                cmp = 0;
        }

        return cmp;
    }

    /**
     * 比较字节序列
     */
    private int compareBytes(ByteBuffer buf1, int len1, ByteBuffer buf2, int len2) {
        int minLen = Math.min(len1, len2);

        for (int i = 0; i < minLen; i++) {
            int b1 = buf1.get() & 0xFF;
            int b2 = buf2.get() & 0xFF;
            if (b1 != b2) {
                // 跳过剩余字节
                skipBytes(buf1, len1 - i - 1);
                skipBytes(buf2, len2 - i - 1);
                return Integer.compare(b1, b2);
            }
        }

        // 跳过剩余字节
        skipBytes(buf1, len1 - minLen);
        skipBytes(buf2, len2 - minLen);

        return Integer.compare(len1, len2);
    }

    /**
     * 跳过指定字节数
     */
    private void skipBytes(ByteBuffer buf, int count) {
        if (count > 0 && buf.remaining() >= count) {
            buf.position(buf.position() + count);
        }
    }

    /**
     * 计算键的实际长度
     */
    private int calculateKeyLength(ByteBuffer buf, int keyStart) {
        int pos = keyStart;
        int oldPos = buf.position();

        try {
            buf.position(pos);

            for (int i = 0; i < keyDef.getColumnCount() && buf.hasRemaining(); i++) {
                KeyColumn column = keyDef.getColumn(i);

                // 处理 NULL 标记
                if (column.isNullable()) {
                    byte nullFlag = buf.get();
                    if (nullFlag == 0) {
                        continue; // NULL 值，没有数据
                    }
                }

                // 跳过列数据
                switch (column.getType()) {
                    case INT:
                        buf.position(buf.position() + 4);
                        break;
                    case BIGINT:
                        buf.position(buf.position() + 8);
                        break;
                    case VARCHAR:
                    case VARBINARY:
                        int varLen = buf.getShort() & 0xFFFF;
                        buf.position(buf.position() + varLen);
                        break;
                    case CHAR:
                    case BINARY:
                        buf.position(buf.position() + column.getMaxLength());
                        break;
                }
            }

            return buf.position() - keyStart;
        } finally {
            buf.position(oldPos);
        }
    }

    /**
     * 检查是否为前缀匹配
     *
     * @param prefixKey 前缀键
     * @param fullKey   完整键
     * @return 如果 prefixKey 是 fullKey 的前缀返回 true
     */
    public boolean isPrefixMatch(byte[] prefixKey, byte[] fullKey) {
        if (prefixKey.length > fullKey.length) {
            return false;
        }

        ByteBuffer prefixBuf = ByteBuffer.wrap(prefixKey);
        ByteBuffer fullBuf = ByteBuffer.wrap(fullKey);

        for (int i = 0; i < keyDef.getColumnCount(); i++) {
            if (!prefixBuf.hasRemaining()) {
                return true; // 前缀键已经结束，匹配成功
            }

            KeyColumn column = keyDef.getColumn(i);
            int cmp = compareColumn(prefixBuf, fullBuf, column);

            if (cmp != 0) {
                return false;
            }
        }

        return true;
    }

    public CompositeKeyDef getKeyDef() {
        return keyDef;
    }

    @Override
    public int getKeyLength(byte[] key) {
        return key != null ? key.length : 0;
    }
}
