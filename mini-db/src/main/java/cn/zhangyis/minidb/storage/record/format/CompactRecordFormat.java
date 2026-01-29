package cn.zhangyis.minidb.storage.record.format;

import cn.zhangyis.minidb.storage.record.RecordHeader;
import cn.zhangyis.minidb.storage.record.logical.DataField;
import cn.zhangyis.minidb.storage.record.logical.DataTuple;
import cn.zhangyis.minidb.storage.record.physical.SystemLayout;
import cn.zhangyis.minidb.storage.record.schema.ColumnDescriptor;
import cn.zhangyis.minidb.storage.record.schema.RecordSchema;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Compact 行格式实现
 *
 * <p>MySQL 5.0 引入的默认行格式，优化存储空间。</p>
 *
 * <h2>布局</h2>
 * <pre>
 * ◄─────────── 向左增长 ───────────►
 * ┌───────────────┬─────────────┬────────────────┬────────────────────┐
 * │ varlen list   │ NULL bitmap │ Header (5B)    │ Data               │
 * │ (逆序)        │             │                │                    │
 * └───────────────┴─────────────┴────────────────┴────────────────────┘
 *                               ↑
 *                            recStart
 * </pre>
 *
 * <h2>变长字段长度编码</h2>
 * <ul>
 *   <li>长度 < 128: 1 字节, 最高位 = 0</li>
 *   <li>长度 >= 128: 2 字节, 最高位 = 1, 大端序</li>
 * </ul>
 *
 * @author MiniDB
 * @version 1.0
 */
public class CompactRecordFormat implements RecordFormat {

    @Override
    public RecordFormatType getType() {
        return RecordFormatType.COMPACT;
    }

    @Override
    public int peekRowVersion(ByteBuffer buffer, int recStart) {
        // dataStart = recStart + 5
        // ROW_VER at dataStart + 13
        int rowVerOffset = recStart + RecordHeader.SIZE + SystemLayout.OFF_ROW_VER;
        return buffer.getShort(rowVerOffset) & 0xFFFF;
    }

    @Override
    public FieldOffsets parseOffsets(ByteBuffer buffer, int recStart,
                                      RecordSchema schema, SystemLayout layout) {
        int columnCount = schema.getColumnCount();
        int userColumnsOffset = layout.userColumnsOffset();

        FieldOffsets.Builder builder = FieldOffsets.builder(columnCount, userColumnsOffset);

        // 1. 解析 NULL bitmap（在 recStart 之前）
        int nullBitmapBytes = schema.getNullBitmapBytes();
        boolean[] nullFlags = new boolean[columnCount];

        if (nullBitmapBytes > 0) {
            int[] nullableOrdinals = schema.getNullableColumnOrdinals();
            int nullBitmapStart = recStart - nullBitmapBytes;

            for (int i = 0; i < nullableOrdinals.length; i++) {
                int byteIndex = i / 8;
                int bitIndex = i % 8;
                byte b = buffer.get(nullBitmapStart + byteIndex);
                nullFlags[nullableOrdinals[i]] = ((b >> bitIndex) & 1) == 1;
            }
        }

        // 2. 解析变长字段长度列表（在 NULL bitmap 之前，逆序存储）
        int[] varOrdinals = schema.getVariableColumnOrdinals();
        int[] varLengths = new int[varOrdinals.length];
        int varlenBytesUsed = 0;

        // varlen list 紧跟在 null bitmap 之前
        int varlenPos = recStart - nullBitmapBytes;

        for (int i = 0; i < varOrdinals.length; i++) {
            int ordinal = varOrdinals[i];

            // 如果是 NULL，长度为 0，不在 varlen list 中
            if (nullFlags[ordinal]) {
                varLengths[i] = 0;
                continue;
            }

            // 读取长度（从后向前）
            varlenPos--;
            int b0 = buffer.get(varlenPos) & 0xFF;

            if ((b0 & 0x80) == 0) {
                // 1 字节编码
                varLengths[i] = b0;
                varlenBytesUsed++;
            } else {
                // 2 字节编码
                varlenPos--;
                int b1 = buffer.get(varlenPos) & 0xFF;
                varLengths[i] = ((b0 & 0x3F) << 8) | b1;
                varlenBytesUsed += 2;
            }
        }

        builder.setVarlenListBytes(varlenBytesUsed);
        builder.setNullBitmapBytes(nullBitmapBytes);

        // 3. 计算各字段偏移
        int currentOffset = 0; // 相对用户列起始

        // 建立 varOrdinal → varLengths index 的映射
        int[] varLengthByOrdinal = new int[columnCount];
        Arrays.fill(varLengthByOrdinal, -1);
        for (int i = 0; i < varOrdinals.length; i++) {
            varLengthByOrdinal[varOrdinals[i]] = varLengths[i];
        }

        for (int i = 0; i < columnCount; i++) {
            ColumnDescriptor col = schema.getColumn(i);
            boolean isNull = nullFlags[i];

            int length;
            if (isNull) {
                length = 0;
            } else if (col.isVariable()) {
                length = varLengthByOrdinal[i];
            } else {
                length = col.getLength();
            }

            builder.setField(i, currentOffset, length, isNull);

            if (!isNull) {
                currentOffset += length;
            }
        }

        return builder.build();
    }

    @Override
    public int encodeTo(ByteBuffer buffer, int recStart, DataTuple tuple,
                        RecordSchema schema, SystemLayout layout,
                        long trxId, long rollPtr, int rowVersion, long rowId) {

        int columnCount = schema.getColumnCount();
        int dataStart = recStart + RecordHeader.SIZE;

        // 1. 写入系统列
        // TRX_ID (6 bytes, 大端序)
        writeTrxId(buffer, dataStart + SystemLayout.OFF_TRX_ID, trxId);

        // ROLL_PTR (7 bytes)
        writeRollPtr(buffer, dataStart + SystemLayout.OFF_ROLL_PTR, rollPtr);

        // ROW_VERSION (2 bytes)
        buffer.putShort(dataStart + SystemLayout.OFF_ROW_VER, (short) rowVersion);

        // ROW_ID (如果有)
        if (layout.hasRowId()) {
            writeRowId(buffer, dataStart + layout.offRowId(), rowId);
        }

        // 2. 构建 NULL bitmap
        int nullBitmapBytes = schema.getNullBitmapBytes();
        byte[] nullBitmap = new byte[nullBitmapBytes];
        int[] nullableOrdinals = schema.getNullableColumnOrdinals();

        for (int i = 0; i < nullableOrdinals.length; i++) {
            int ordinal = nullableOrdinals[i];
            DataField field = tuple.getField(ordinal);
            if (field != null && field.isNull()) {
                int byteIndex = i / 8;
                int bitIndex = i % 8;
                nullBitmap[byteIndex] |= (byte) (1 << bitIndex);
            }
        }

        // 写入 NULL bitmap（在 recStart 之前）
        for (int i = 0; i < nullBitmapBytes; i++) {
            buffer.put(recStart - nullBitmapBytes + i, nullBitmap[i]);
        }

        // 3. 构建变长字段长度列表（逆序）
        int[] varOrdinals = schema.getVariableColumnOrdinals();
        byte[] varlenList = new byte[varOrdinals.length * 2]; // 最大可能大小
        int varlenPos = 0;

        for (int ordinal : varOrdinals) {
            DataField field = tuple.getField(ordinal);
            if (field == null || field.isNull()) {
                continue; // NULL 字段不写入长度
            }

            int length = field.getLength();
            if (length < 128) {
                varlenList[varlenPos++] = (byte) length;
            } else {
                // 2 字节编码：低字节在前（逆序存储时会翻转）
                varlenList[varlenPos++] = (byte) (length & 0xFF);
                varlenList[varlenPos++] = (byte) (0x80 | ((length >> 8) & 0x3F));
            }
        }

        // 写入 varlen list（在 NULL bitmap 之前，逆序）
        int varlenStart = recStart - nullBitmapBytes - varlenPos;
        for (int i = 0; i < varlenPos; i++) {
            buffer.put(varlenStart + i, varlenList[varlenPos - 1 - i]);
        }

        // 4. 写入用户列数据
        int userDataStart = dataStart + layout.userColumnsOffset();
        int writePos = userDataStart;

        for (int i = 0; i < columnCount; i++) {
            DataField field = tuple.getField(i);
            if (field == null || field.isNull()) {
                continue;
            }

            byte[] data = field.getData();
            if (data != null) {
                for (byte b : data) {
                    buffer.put(writePos++, b);
                }
            }
        }

        // 返回记录头之前的额外空间
        return varlenPos + nullBitmapBytes;
    }

    @Override
    public DataTuple decode(ByteBuffer buffer, int recStart, FieldOffsets offsets,
                            RecordSchema schema, SystemLayout layout) {

        int columnCount = schema.getColumnCount();
        int dataStart = recStart + RecordHeader.SIZE;
        int userDataStart = dataStart + layout.userColumnsOffset();

        DataField[] fields = new DataField[columnCount];

        for (int i = 0; i < columnCount; i++) {
            if (offsets.isNull(i)) {
                fields[i] = DataField.nullField(schema.getColumnType(i));
            } else {
                int offset = offsets.getOffset(i);
                int length = offsets.getLength(i);

                byte[] data = new byte[length];
                for (int j = 0; j < length; j++) {
                    data[j] = buffer.get(userDataStart + offset + j);
                }

                fields[i] = DataField.fromBytes(schema.getColumnType(i), data);
            }
        }

        return DataTuple.of(fields);
    }

    @Override
    public int calculateSize(DataTuple tuple, RecordSchema schema, SystemLayout layout) {
        int extraBytes = calculateExtraBytes(tuple, schema);
        int headerSize = RecordHeader.SIZE;
        int sysBytes = layout.fixedSysBytes();
        int userDataBytes = calculateUserDataBytes(tuple, schema);

        return extraBytes + headerSize + sysBytes + userDataBytes;
    }

    @Override
    public int calculateExtraBytes(DataTuple tuple, RecordSchema schema) {
        int varlenBytes = 0;
        int[] varOrdinals = schema.getVariableColumnOrdinals();

        for (int ordinal : varOrdinals) {
            DataField field = tuple.getField(ordinal);
            if (field == null || field.isNull()) {
                continue;
            }

            int length = field.getLength();
            varlenBytes += (length < 128) ? 1 : 2;
        }

        int nullBitmapBytes = schema.getNullBitmapBytes();

        return varlenBytes + nullBitmapBytes;
    }

    /**
     * 计算用户数据字节数
     */
    private int calculateUserDataBytes(DataTuple tuple, RecordSchema schema) {
        int total = 0;
        for (int i = 0; i < schema.getColumnCount(); i++) {
            DataField field = tuple.getField(i);
            if (field != null && !field.isNull()) {
                total += field.getLength();
            }
        }
        return total;
    }

    // ==================== 系统列读写辅助方法 ====================

    /**
     * 写入 TRX_ID (6 bytes, 大端序)
     */
    private void writeTrxId(ByteBuffer buffer, int offset, long trxId) {
        buffer.put(offset, (byte) ((trxId >> 40) & 0xFF));
        buffer.put(offset + 1, (byte) ((trxId >> 32) & 0xFF));
        buffer.put(offset + 2, (byte) ((trxId >> 24) & 0xFF));
        buffer.put(offset + 3, (byte) ((trxId >> 16) & 0xFF));
        buffer.put(offset + 4, (byte) ((trxId >> 8) & 0xFF));
        buffer.put(offset + 5, (byte) (trxId & 0xFF));
    }

    /**
     * 读取 TRX_ID (6 bytes, 大端序)
     */
    public static long readTrxId(ByteBuffer buffer, int offset) {
        long value = 0;
        for (int i = 0; i < 6; i++) {
            value = (value << 8) | (buffer.get(offset + i) & 0xFF);
        }
        return value;
    }

    /**
     * 写入 ROLL_PTR (7 bytes)
     */
    private void writeRollPtr(ByteBuffer buffer, int offset, long rollPtr) {
        buffer.put(offset, (byte) ((rollPtr >> 48) & 0xFF));
        buffer.put(offset + 1, (byte) ((rollPtr >> 40) & 0xFF));
        buffer.put(offset + 2, (byte) ((rollPtr >> 32) & 0xFF));
        buffer.put(offset + 3, (byte) ((rollPtr >> 24) & 0xFF));
        buffer.put(offset + 4, (byte) ((rollPtr >> 16) & 0xFF));
        buffer.put(offset + 5, (byte) ((rollPtr >> 8) & 0xFF));
        buffer.put(offset + 6, (byte) (rollPtr & 0xFF));
    }

    /**
     * 读取 ROLL_PTR (7 bytes)
     */
    public static long readRollPtr(ByteBuffer buffer, int offset) {
        long value = 0;
        for (int i = 0; i < 7; i++) {
            value = (value << 8) | (buffer.get(offset + i) & 0xFF);
        }
        return value;
    }

    /**
     * 写入 ROW_ID (6 bytes)
     */
    private void writeRowId(ByteBuffer buffer, int offset, long rowId) {
        buffer.put(offset, (byte) ((rowId >> 40) & 0xFF));
        buffer.put(offset + 1, (byte) ((rowId >> 32) & 0xFF));
        buffer.put(offset + 2, (byte) ((rowId >> 24) & 0xFF));
        buffer.put(offset + 3, (byte) ((rowId >> 16) & 0xFF));
        buffer.put(offset + 4, (byte) ((rowId >> 8) & 0xFF));
        buffer.put(offset + 5, (byte) (rowId & 0xFF));
    }

    /**
     * 读取 ROW_ID (6 bytes)
     */
    public static long readRowId(ByteBuffer buffer, int offset) {
        long value = 0;
        for (int i = 0; i < 6; i++) {
            value = (value << 8) | (buffer.get(offset + i) & 0xFF);
        }
        return value;
    }

    @Override
    public long[] readSystemColumns(ByteBuffer buffer, int recStart, SystemLayout layout) {
        int dataStart = recStart + RecordHeader.SIZE;

        long trxId = readTrxId(buffer, dataStart + SystemLayout.OFF_TRX_ID);
        long rollPtr = readRollPtr(buffer, dataStart + SystemLayout.OFF_ROLL_PTR);
        int rowVersion = buffer.getShort(dataStart + SystemLayout.OFF_ROW_VER) & 0xFFFF;
        long rowId = layout.hasRowId()
            ? readRowId(buffer, dataStart + layout.offRowId())
            : 0L;

        return new long[]{trxId, rollPtr, rowVersion, rowId};
    }
}
