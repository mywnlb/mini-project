package cn.zhangyis.minidb.storage.btree;

import cn.zhangyis.minidb.storage.record.RecordHeader;
import cn.zhangyis.minidb.storage.record.physical.SystemLayout;
import cn.zhangyis.minidb.storage.record.schema.ColumnDescriptor;
import cn.zhangyis.minidb.storage.record.schema.RecordSchema;

import java.nio.ByteBuffer;

/**
 * 聚簇索引主键比较器。
 *
 * <p>叶子记录使用 compact 行格式时，主键列位于系统列之后；
 * 非叶子节点记录仍沿用简单的 node-ptr 布局。</p>
 *
 * <p>当 {@code schema} 和 {@code layout} 非 null 时，支持通过
 * {@link #readFullRecord(ByteBuffer, int)} 从页面完整读取 Compact 记录
 * （含 varlen list + NULL bitmap 前缀），用于页面分裂场景。</p>
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>INV-S1: readFullRecord 返回 [extraBytes | Header | Data] 完整连续区间</li>
 *   <li>INV-S2: recordHeaderOffset == extraBytes 字节数</li>
 * </ul>
 */
public class ClusteredPrimaryKeyComparator extends CompositeKeyComparator {

    private final int leafUserColumnsOffset;

    /** 记录 Schema（可为 null，null 时 readFullRecord 回退默认实现） */
    private final RecordSchema schema;

    /** 系统列布局（可为 null） */
    private final SystemLayout layout;

    /**
     * 完整构造函数（支持页面分裂）。
     *
     * @param keyDef                键定义
     * @param leafUserColumnsOffset 叶子记录中用户列起始偏移（相对 dataStart）
     * @param schema                记录 Schema
     * @param layout                系统列布局
     */
    public ClusteredPrimaryKeyComparator(CompositeKeyDef keyDef, int leafUserColumnsOffset,
                                         RecordSchema schema, SystemLayout layout) {
        super(keyDef);
        this.leafUserColumnsOffset = leafUserColumnsOffset;
        this.schema = schema;
        this.layout = layout;
    }

    /**
     * 向后兼容构造函数（不支持 readFullRecord）。
     */
    @Deprecated
    public ClusteredPrimaryKeyComparator(CompositeKeyDef keyDef, int leafUserColumnsOffset) {
        this(keyDef, leafUserColumnsOffset, null, null);
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

    @Override
    public int compareExtractedKeys(byte[] key1, byte[] key2) {
        return compareKeys(key1, key2);
    }

    /**
     * 从页面读取完整 Compact 记录。
     *
     * <p>对于 REC_ORDINARY 叶子记录，需要向 recStart 前方读取 extraBytes
     * （NULL bitmap + varlen list），以保证记录完整性。</p>
     *
     * <p>对于 REC_NODE_PTR 非叶子记录，使用简单布局（header + key + childPageNo）。</p>
     *
     * @param buf      页面 ByteBuffer
     * @param recStart 记录头起始偏移
     * @return 完整记录字节封装
     */
    @Override
    public RecordBytes readFullRecord(ByteBuffer buf, int recStart) {
        if (schema == null || layout == null) {
            return super.readFullRecord(buf, recStart);
        }

        RecordHeader header = RecordHeader.readFrom(buf, recStart);

        if (header.isNodePtr()) {
            return readNodePtrRecord(buf, recStart);
        }

        // REC_ORDINARY 叶子记录：需要读取完整 Compact 格式
        return readCompactLeafRecord(buf, recStart);
    }

    /**
     * 读取非叶子节点记录（node ptr）。
     *
     * <p>布局: RecordHeader(5B) + Key(变长) + ChildPageNo(4B)</p>
     */
    private RecordBytes readNodePtrRecord(ByteBuffer buf, int recStart) {
        // 提取 key 以计算 key 长度
        byte[] key = super.extractKey(buf, recStart);
        int totalSize = RecordHeader.SIZE + key.length + 4; // header + key + pageNo

        byte[] data = new byte[totalSize];
        for (int i = 0; i < totalSize; i++) {
            data[i] = buf.get(recStart + i);
        }
        return new RecordBytes(data, 0);
    }

    /**
     * 读取 Compact 格式的叶子记录。
     *
     * <h2>页面布局</h2>
     * <pre>
     * ... [varlen list (逆序)] [NULL bitmap] [RecordHeader 5B] [SysCols] [UserCols] ...
     *                                         ↑ recStart
     * </pre>
     *
     * <h2>算法</h2>
     * <ol>
     *   <li>解析 NULL bitmap（recStart 之前）确定哪些列为 NULL</li>
     *   <li>解析 varlen list（NULL bitmap 之前）获取各变长列实际长度</li>
     *   <li>计算 extraBytes = varlenBytesUsed + nullBitmapBytes</li>
     *   <li>计算 data 部分长度 = sysBytes + 各用户列数据长度</li>
     *   <li>从 recStart - extraBytes 开始复制完整记录</li>
     * </ol>
     */
    private RecordBytes readCompactLeafRecord(ByteBuffer buf, int recStart) {
        int nullBitmapBytes = schema.getNullBitmapBytes();

        // 1. 解析 NULL bitmap
        boolean[] nullFlags = new boolean[schema.getColumnCount()];
        if (nullBitmapBytes > 0) {
            int[] nullableOrdinals = schema.getNullableColumnOrdinals();
            int nullBitmapStart = recStart - nullBitmapBytes;
            for (int i = 0; i < nullableOrdinals.length; i++) {
                int byteIndex = i / 8;
                int bitIndex = i % 8;
                byte b = buf.get(nullBitmapStart + byteIndex);
                nullFlags[nullableOrdinals[i]] = ((b >> bitIndex) & 1) == 1;
            }
        }

        // 2. 解析 varlen list（在 NULL bitmap 之前，逆序存储）
        int[] varOrdinals = schema.getVariableColumnOrdinals();
        int[] varLengths = new int[varOrdinals.length];
        int varlenBytesUsed = 0;
        int varlenPos = recStart - nullBitmapBytes;

        for (int i = 0; i < varOrdinals.length; i++) {
            int ordinal = varOrdinals[i];
            if (nullFlags[ordinal]) {
                varLengths[i] = 0;
                continue;
            }
            varlenPos--;
            int b0 = buf.get(varlenPos) & 0xFF;
            if ((b0 & 0x80) == 0) {
                // 1 字节长度
                varLengths[i] = b0;
                varlenBytesUsed++;
            } else {
                // 2 字节长度
                varlenPos--;
                int b1 = buf.get(varlenPos) & 0xFF;
                varLengths[i] = ((b0 & 0x3F) << 8) | b1;
                varlenBytesUsed += 2;
            }
        }

        int extraBytes = varlenBytesUsed + nullBitmapBytes;

        // 3. 计算 data 部分长度：sysBytes + 各用户列数据长度
        int sysBytes = layout.fixedSysBytes();
        int userDataBytes = calculateUserDataBytes(nullFlags, varOrdinals, varLengths);
        int dataAfterHeader = sysBytes + userDataBytes;

        // 4. 总记录大小
        int totalSize = extraBytes + RecordHeader.SIZE + dataAfterHeader;

        // 5. 从 recStart - extraBytes 开始复制
        int copyStart = recStart - extraBytes;
        byte[] data = new byte[totalSize];
        for (int i = 0; i < totalSize; i++) {
            data[i] = buf.get(copyStart + i);
        }

        return new RecordBytes(data, extraBytes);
    }

    /**
     * 计算用户列数据总字节数。
     *
     * <p>遍历 schema 中的每一列：</p>
     * <ul>
     *   <li>NULL 列：0 字节</li>
     *   <li>定长列：取 {@code FieldType.getLength()}</li>
     *   <li>变长列：取 varlen list 中解析出的实际长度</li>
     * </ul>
     */
    private int calculateUserDataBytes(boolean[] nullFlags, int[] varOrdinals, int[] varLengths) {
        int total = 0;

        // 构建 varOrdinal → varIndex 映射
        int varIdx = 0;

        for (int col = 0; col < schema.getColumnCount(); col++) {
            if (nullFlags[col]) {
                // NULL 列在 varOrdinals 中也可能存在，需要同步 varIdx
                if (isVarOrdinal(varOrdinals, col)) {
                    // varLengths 对应位置已是 0，varIdx 需要推进
                    varIdx = advanceVarIdx(varOrdinals, varIdx, col);
                }
                continue;
            }

            ColumnDescriptor colDesc = schema.getColumn(col);
            if (colDesc.isVariable()) {
                // 变长列：从 varLengths 获取实际长度
                int vi = findVarIndex(varOrdinals, col);
                if (vi >= 0) {
                    total += varLengths[vi];
                }
            } else {
                // 定长列
                total += colDesc.getType().getLength();
            }
        }

        return total;
    }

    /**
     * 检查指定 ordinal 是否在 varOrdinals 数组中
     */
    private boolean isVarOrdinal(int[] varOrdinals, int ordinal) {
        for (int vo : varOrdinals) {
            if (vo == ordinal) return true;
        }
        return false;
    }

    /**
     * 在 varOrdinals（逆序）中找到指定 ordinal 的索引
     */
    private int findVarIndex(int[] varOrdinals, int ordinal) {
        for (int i = 0; i < varOrdinals.length; i++) {
            if (varOrdinals[i] == ordinal) return i;
        }
        return -1;
    }

    /**
     * 推进 varIdx 跳过当前 NULL 变长列
     */
    private int advanceVarIdx(int[] varOrdinals, int currentVarIdx, int ordinal) {
        for (int i = currentVarIdx; i < varOrdinals.length; i++) {
            if (varOrdinals[i] == ordinal) return i + 1;
        }
        return currentVarIdx;
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
