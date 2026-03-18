package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.storage.record.RecordHeader;

import java.nio.ByteBuffer;

/**
 * 聚簇索引主键比较器。
 *
 * <p>叶子记录使用 compact 行格式时，主键列位于系统列之后；
 * 非叶子节点记录仍沿用简单的 node-ptr 布局。</p>
 */
public class ClusteredPrimaryKeyComparator extends CompositeKeyComparator {

    private final int leafUserColumnsOffset;

    public ClusteredPrimaryKeyComparator(CompositeKeyDef keyDef, int leafUserColumnsOffset) {
        super(keyDef);
        this.leafUserColumnsOffset = leafUserColumnsOffset;
    }

    @Override
    public int compareKeyToRecord(byte[] searchKey, ByteBuffer buf, int recordOffset) {
        return compareKeys(searchKey, extractKey(buf, recordOffset));
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
        RecordHeader header = RecordHeader.readFrom(buf, recordOffset);
        if (header.isNodePtr() || header.isInfimum() || header.isSupremum()) {
            return super.extractKey(buf, recordOffset);
        }

        int keyStart = recordOffset + RecordHeader.SIZE + leafUserColumnsOffset;
        int keyLength = calculateKeyLength(buf, keyStart);

        byte[] key = new byte[keyLength];
        int oldPos = buf.position();
        buf.position(keyStart);
        buf.get(key);
        buf.position(oldPos);
        return key;
    }

    private int calculateKeyLength(ByteBuffer buf, int keyStart) {
        int pos = keyStart;
        int oldPos = buf.position();

        try {
            buf.position(pos);

            for (int i = 0; i < getKeyDef().getColumnCount() && buf.hasRemaining(); i++) {
                KeyColumn column = getKeyDef().getColumn(i);

                if (column.isNullable()) {
                    byte nullFlag = buf.get();
                    if (nullFlag == 0) {
                        continue;
                    }
                }

                switch (column.getType()) {
                    case INT -> buf.position(buf.position() + 4);
                    case BIGINT -> buf.position(buf.position() + 8);
                    case VARCHAR, VARBINARY -> {
                        int varLen = buf.getShort() & 0xFFFF;
                        buf.position(buf.position() + varLen);
                    }
                    case CHAR, BINARY -> buf.position(buf.position() + column.getMaxLength());
                    default -> throw new IllegalStateException("Unsupported key type: " + column.getType());
                }
            }

            return buf.position() - keyStart;
        } finally {
            buf.position(oldPos);
        }
    }
}
