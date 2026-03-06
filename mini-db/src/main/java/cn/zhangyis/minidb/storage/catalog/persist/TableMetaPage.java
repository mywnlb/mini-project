package cn.zhangyis.minidb.storage.catalog.persist;

import cn.zhangyis.minidb.common.exception.PageCorruptedException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.catalog.ColumnMeta;
import cn.zhangyis.minidb.storage.catalog.TableDescriptor;
import cn.zhangyis.minidb.storage.constants.StorageConstants;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.page.PageType;
import cn.zhangyis.minidb.storage.record.logical.DataField;
import cn.zhangyis.minidb.storage.record.schema.FieldKind;
import cn.zhangyis.minidb.storage.record.schema.FieldType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 表元数据页面
 *
 * <p>用于持久化表定义信息，支持链式存储。
 * 位于系统表空间 (spaceId=0) 的 Page 4+。</p>
 *
 * <h2>页面布局</h2>
 * <pre>
 * +------------------+
 * | Page Header (38) |
 * +------------------+
 * | Meta Header (16) |
 * |  - magic (4)     |  0x5441424C ("TABL")
 * |  - version (4)   |
 * |  - count (4)     |
 * |  - nextPage (4)  |
 * +------------------+
 * | Table Entry 1    |
 * +------------------+
 * | Table Entry 2    |
 * +------------------+
 * | ...              |
 * +------------------+
 * </pre>
 *
 * <h3>Table Entry 格式</h3>
 * <pre>
 * entryLen (4)      — 后续字节数（不含此 4 字节）
 * tableId (8)
 * dbId (4)
 * spaceId (4)
 * nameLen (4) + name (var)   — UTF-8
 * columnCount (4)
 *   [per column]
 *     columnId (8)
 *     colNameLen (4) + colName (var)
 *     kindOrdinal (4)
 *     length (4)
 *     nullable (1)
 *     ordinal (4)
 *     hasDefault (1)
 *     [if hasDefault]
 *       defaultLen (4) + defaultBytes (var)
 * primaryIndexId (8)
 * secondaryIndexCount (4)
 *   [per secondaryIndex] indexId (8)
 * createTime (8)
 * lastUpdateTime (8)
 * state (1)           — ordinal of TableState
 * </pre>
 *
 * <h3>Invariants</h3>
 * <ul>
 *   <li>I1: magic 必须为 {@link #MAGIC}</li>
 *   <li>I2: count 必须等于本页中实际存储的 table entry 数量</li>
 *   <li>I3: 所有 entry 必须在 PAGE_SIZE 范围内</li>
 * </ul>
 *
 * <h3>并发契约</h3>
 * <p>调用方必须持有 BufferFrame 的 X-latch（写操作）或 S-latch（读操作）。</p>
 */
public final class TableMetaPage {

    private static final int PAGE_SIZE = StorageConstants.PAGE_SIZE;

    public static final int MAGIC = 0x5441424C;

    private static final int VERSION = 1;

    private static final int PAGE_HEADER_SIZE = StorageConstants.FIL_HEADER_SIZE;

    private static final int META_HEADER_OFFSET = PAGE_HEADER_SIZE;

    private static final int META_HEADER_SIZE = 16;

    private static final int DATA_OFFSET = META_HEADER_OFFSET + META_HEADER_SIZE;

    /** 系统表空间中第一个 TableMetaPage 的固定页号。 */
    public static final int FIRST_TABLE_META_PAGE_NO = CatalogMetaPage.CATALOG_META_PAGE_NO + 1;

    // ==================== 初始化 ====================

    public static void initPage(BufferFrame frame) {
        ByteBuffer buf = frame.buffer();

        zeroRange(buf, DATA_OFFSET, PAGE_SIZE);
        frame.getPage().setPageType(PageType.FIL_PAGE_TABLE_META);

        buf.putInt(META_HEADER_OFFSET, MAGIC);
        buf.putInt(META_HEADER_OFFSET + 4, VERSION);
        buf.putInt(META_HEADER_OFFSET + 8, 0);
        buf.putInt(META_HEADER_OFFSET + 12, 0);

        frame.setDirty(true);
    }

    // ==================== 验证 ====================

    public static boolean isValid(BufferFrame frame) {
        return frame.buffer().getInt(META_HEADER_OFFSET) == MAGIC;
    }

    public static void assertValid(BufferFrame frame) {
        if (!isValid(frame)) {
            throw new IllegalStateException(
                    "Invalid TableMetaPage: magic mismatch, expected 0x"
                            + Integer.toHexString(MAGIC));
        }
    }

    // ==================== count / nextPage ====================

    public static int readTableCount(BufferFrame frame) {
        assertValid(frame);
        return frame.buffer().getInt(META_HEADER_OFFSET + 8);
    }

    public static int readNextPage(BufferFrame frame) {
        assertValid(frame);
        return frame.buffer().getInt(META_HEADER_OFFSET + 12);
    }

    public static void writeNextPage(BufferFrame frame, int nextPageNo) {
        assertValid(frame);
        frame.buffer().putInt(META_HEADER_OFFSET + 12, nextPageNo);
        frame.setDirty(true);
    }

    // ==================== 读取所有表 entry ====================

    public static List<TableEntry> readAllEntries(BufferFrame frame) throws PageCorruptedException {
        assertValid(frame);
        ByteBuffer buf = frame.buffer();
        PageId pageId = frame.getPageId();

        int count = buf.getInt(META_HEADER_OFFSET + 8);
        if (count < 0) {
            throw PageCorruptedException.invalidFormat(pageId,
                    "tableCount is negative: " + count);
        }

        List<TableEntry> entries = new ArrayList<>(count);
        int offset = DATA_OFFSET;

        for (int i = 0; i < count; i++) {
            DecodedEntry decoded = readSingleEntry(buf, pageId, offset);
            entries.add(decoded.entry());
            offset += decoded.totalBytes();
        }

        return entries;
    }

    // ==================== 写入单个表 entry ====================

    public static boolean writeEntry(BufferFrame frame, TableEntry entry) throws PageCorruptedException {
        assertValid(frame);
        ByteBuffer buf = frame.buffer();

        byte[] serialized = serializeEntry(entry);
        int totalSize = 4 + serialized.length;

        int currentOffset = calculateCurrentOffset(frame);
        if (currentOffset + totalSize > PAGE_SIZE) {
            return false;
        }

        buf.putInt(currentOffset, serialized.length);
        putBytes(buf, currentOffset + 4, serialized);
        buf.putInt(META_HEADER_OFFSET + 8, buf.getInt(META_HEADER_OFFSET + 8) + 1);

        frame.setDirty(true);
        return true;
    }

    // ==================== 更新表 entry ====================

    public static boolean updateEntry(BufferFrame frame, TableEntry updatedEntry)
            throws PageCorruptedException {
        assertValid(frame);
        ByteBuffer buf = frame.buffer();
        PageId pageId = frame.getPageId();

        int count = buf.getInt(META_HEADER_OFFSET + 8);
        if (count < 0) {
            throw PageCorruptedException.invalidFormat(pageId,
                    "tableCount is negative: " + count);
        }

        int offset = DATA_OFFSET;
        for (int i = 0; i < count; i++) {
            DecodedEntry decoded = readSingleEntry(buf, pageId, offset);
            if (decoded.entry().tableId() == updatedEntry.tableId()) {
                byte[] newData = serializeEntry(updatedEntry);
                if (newData.length == decoded.payloadLength()) {
                    putBytes(buf, offset + 4, newData);
                    frame.setDirty(true);
                    return true;
                }
                return rewritePage(frame, updatedEntry);
            }
            offset += decoded.totalBytes();
        }

        return false;
    }

    // ==================== 删除表 entry ====================

    public static boolean deleteEntry(BufferFrame frame, long tableId) throws PageCorruptedException {
        assertValid(frame);
        ByteBuffer buf = frame.buffer();
        PageId pageId = frame.getPageId();

        int count = buf.getInt(META_HEADER_OFFSET + 8);
        if (count < 0) {
            throw PageCorruptedException.invalidFormat(pageId,
                    "tableCount is negative: " + count);
        }

        List<TableEntry> remaining = new ArrayList<>(Math.max(count - 1, 0));
        int offset = DATA_OFFSET;
        boolean found = false;

        for (int i = 0; i < count; i++) {
            DecodedEntry decoded = readSingleEntry(buf, pageId, offset);
            if (decoded.entry().tableId() == tableId) {
                found = true;
            } else {
                remaining.add(decoded.entry());
            }
            offset += decoded.totalBytes();
        }

        return found && rewriteEntries(frame, remaining);
    }

    // ==================== 查找表 entry ====================

    public static TableEntry findEntry(BufferFrame frame, long tableId) throws PageCorruptedException {
        assertValid(frame);
        ByteBuffer buf = frame.buffer();
        PageId pageId = frame.getPageId();

        int count = buf.getInt(META_HEADER_OFFSET + 8);
        if (count < 0) {
            throw PageCorruptedException.invalidFormat(pageId,
                    "tableCount is negative: " + count);
        }

        int offset = DATA_OFFSET;
        for (int i = 0; i < count; i++) {
            DecodedEntry decoded = readSingleEntry(buf, pageId, offset);
            if (decoded.entry().tableId() == tableId) {
                return decoded.entry();
            }
            offset += decoded.totalBytes();
        }

        return null;
    }

    // ==================== TableEntry DTO ====================

    public record TableEntry(
            long tableId,
            String tableName,
            int databaseId,
            int spaceId,
            List<ColumnMeta> columns,
            long primaryIndexId,
            List<Long> secondaryIndexIds,
            long createTime,
            long lastUpdateTime,
            TableDescriptor.TableState state
    ) {}

    // ==================== 序列化 ====================

    private static byte[] serializeEntry(TableEntry entry) {
        byte[] nameBytes = entry.tableName().getBytes(StandardCharsets.UTF_8);

        int size = 8
                + 4
                + 4
                + 4 + nameBytes.length
                + 4;

        List<byte[]> colNameBytesList = new ArrayList<>();
        List<byte[]> defaultBytesList = new ArrayList<>();
        for (ColumnMeta col : entry.columns()) {
            byte[] colNameB = col.getName().getBytes(StandardCharsets.UTF_8);
            colNameBytesList.add(colNameB);

            byte[] defB = null;
            if (col.getDefaultValue() != null && !col.getDefaultValue().isNull()) {
                defB = col.getDefaultValue().getData();
            }
            defaultBytesList.add(defB);

            size += 8
                    + 4 + colNameB.length
                    + 4
                    + 4
                    + 1
                    + 4
                    + 1;
            if (defB != null) {
                size += 4 + defB.length;
            }
        }

        size += 8;
        size += 4;
        size += entry.secondaryIndexIds().size() * 8;
        size += 8;
        size += 8;
        size += 1;

        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);

        buf.putLong(entry.tableId());
        buf.putInt(entry.databaseId());
        buf.putInt(entry.spaceId());

        buf.putInt(nameBytes.length);
        buf.put(nameBytes);

        buf.putInt(entry.columns().size());
        for (int i = 0; i < entry.columns().size(); i++) {
            ColumnMeta col = entry.columns().get(i);
            byte[] colNameB = colNameBytesList.get(i);
            byte[] defB = defaultBytesList.get(i);

            buf.putLong(col.getColumnId());
            buf.putInt(colNameB.length);
            buf.put(colNameB);
            buf.putInt(col.getKind().ordinal());
            buf.putInt(col.getType().getLength());
            buf.put((byte) (col.isNullable() ? 1 : 0));
            buf.putInt(col.getOrdinal());
            buf.put((byte) (defB != null ? 1 : 0));
            if (defB != null) {
                buf.putInt(defB.length);
                buf.put(defB);
            }
        }

        buf.putLong(entry.primaryIndexId());
        buf.putInt(entry.secondaryIndexIds().size());
        for (long secId : entry.secondaryIndexIds()) {
            buf.putLong(secId);
        }

        buf.putLong(entry.createTime());
        buf.putLong(entry.lastUpdateTime());
        buf.put((byte) entry.state().ordinal());

        return buf.array();
    }

    // ==================== 内部工具 ====================

    private static int calculateCurrentOffset(BufferFrame frame) throws PageCorruptedException {
        ByteBuffer buf = frame.buffer();
        PageId pageId = frame.getPageId();

        int count = buf.getInt(META_HEADER_OFFSET + 8);
        if (count < 0) {
            throw PageCorruptedException.invalidFormat(pageId,
                    "tableCount is negative: " + count);
        }

        int offset = DATA_OFFSET;
        for (int i = 0; i < count; i++) {
            offset += readSingleEntry(buf, pageId, offset).totalBytes();
        }

        return offset;
    }

    private static boolean rewritePage(BufferFrame frame, TableEntry updatedEntry)
            throws PageCorruptedException {
        List<TableEntry> entries = readAllEntries(frame);
        List<TableEntry> updated = new ArrayList<>(entries.size());
        for (TableEntry entry : entries) {
            updated.add(entry.tableId() == updatedEntry.tableId() ? updatedEntry : entry);
        }
        return rewriteEntries(frame, updated);
    }

    private static boolean rewriteEntries(BufferFrame frame, List<TableEntry> entries) {
        ByteBuffer pageBuf = frame.buffer();
        byte[] image = snapshotPage(pageBuf);
        Arrays.fill(image, DATA_OFFSET, PAGE_SIZE, (byte) 0);

        ByteBuffer scratch = ByteBuffer.wrap(image).order(pageBuf.order());
        scratch.putInt(META_HEADER_OFFSET, MAGIC);
        scratch.putInt(META_HEADER_OFFSET + 4, pageBuf.getInt(META_HEADER_OFFSET + 4));
        scratch.putInt(META_HEADER_OFFSET + 8, entries.size());
        scratch.putInt(META_HEADER_OFFSET + 12, pageBuf.getInt(META_HEADER_OFFSET + 12));

        int offset = DATA_OFFSET;
        for (TableEntry entry : entries) {
            byte[] serialized = serializeEntry(entry);
            int totalSize = 4 + serialized.length;
            if (offset + totalSize > PAGE_SIZE) {
                return false;
            }
            scratch.putInt(offset, serialized.length);
            putBytes(scratch, offset + 4, serialized);
            offset += totalSize;
        }

        writePageImage(pageBuf, image);
        frame.setDirty(true);
        return true;
    }

    private static DecodedEntry readSingleEntry(ByteBuffer buf, PageId pageId, int offset)
            throws PageCorruptedException {
        ensureRange(pageId, offset, 4, "tableEntry.length");
        int entryLen = buf.getInt(offset);
        if (entryLen <= 0 || entryLen > PAGE_SIZE - offset - 4) {
            throw PageCorruptedException.invalidFormat(pageId,
                    "invalid table entry length at offset " + offset + ": " + entryLen);
        }

        int cursor = offset + 4;
        int entryEnd = cursor + entryLen;

        ensureRange(pageId, cursor, 8, entryEnd, "tableId");
        long tableId = buf.getLong(cursor);
        cursor += 8;

        ensureRange(pageId, cursor, 4, entryEnd, "databaseId");
        int dbId = buf.getInt(cursor);
        cursor += 4;

        ensureRange(pageId, cursor, 4, entryEnd, "spaceId");
        int spaceId = buf.getInt(cursor);
        cursor += 4;

        DecodedString tableName = readString(buf, pageId, cursor, entryEnd, "tableName");
        cursor += tableName.totalBytes();

        ensureRange(pageId, cursor, 4, entryEnd, "columnCount");
        int columnCount = buf.getInt(cursor);
        if (columnCount < 0) {
            throw PageCorruptedException.invalidFormat(pageId,
                    "columnCount is negative for tableId=" + tableId);
        }
        cursor += 4;

        List<ColumnMeta> columns = new ArrayList<>(columnCount);
        for (int c = 0; c < columnCount; c++) {
            ensureRange(pageId, cursor, 8, entryEnd, "columnId");
            long columnId = buf.getLong(cursor);
            cursor += 8;

            DecodedString colName = readString(buf, pageId, cursor, entryEnd, "columnName");
            cursor += colName.totalBytes();

            ensureRange(pageId, cursor, 4, entryEnd, "fieldKind");
            int kindOrd = buf.getInt(cursor);
            if (kindOrd < 0 || kindOrd >= FieldKind.values().length) {
                throw PageCorruptedException.invalidFormat(pageId,
                        "invalid FieldKind ordinal: " + kindOrd);
            }
            cursor += 4;

            ensureRange(pageId, cursor, 4, entryEnd, "fieldLength");
            int length = buf.getInt(cursor);
            if (length < 0) {
                throw PageCorruptedException.invalidFormat(pageId,
                        "negative field length for columnId=" + columnId);
            }
            cursor += 4;

            ensureRange(pageId, cursor, 1, entryEnd, "fieldNullable");
            boolean nullable = buf.get(cursor) != 0;
            cursor += 1;

            ensureRange(pageId, cursor, 4, entryEnd, "columnOrdinal");
            int ordinal = buf.getInt(cursor);
            cursor += 4;

            FieldType fieldType = FieldType.of(FieldKind.values()[kindOrd], length, nullable);

            ensureRange(pageId, cursor, 1, entryEnd, "hasDefault");
            boolean hasDefault = buf.get(cursor) != 0;
            cursor += 1;

            byte[] defaultBytes = null;
            if (hasDefault) {
                ensureRange(pageId, cursor, 4, entryEnd, "defaultLength");
                int defLen = buf.getInt(cursor);
                if (defLen < 0) {
                    throw PageCorruptedException.invalidFormat(pageId,
                            "negative default length for columnId=" + columnId);
                }
                cursor += 4;
                defaultBytes = readBytes(buf, pageId, cursor, defLen, entryEnd, "defaultBytes");
                cursor += defLen;
            }

            DataField defaultValue = hasDefault ? DataField.fromBytes(fieldType, defaultBytes) : null;
            columns.add(new ColumnMeta(columnId, colName.value(), fieldType, ordinal, defaultValue));
        }

        ensureRange(pageId, cursor, 8, entryEnd, "primaryIndexId");
        long primaryIndexId = buf.getLong(cursor);
        cursor += 8;

        ensureRange(pageId, cursor, 4, entryEnd, "secondaryIndexCount");
        int secCount = buf.getInt(cursor);
        if (secCount < 0) {
            throw PageCorruptedException.invalidFormat(pageId,
                    "secondaryIndexCount is negative for tableId=" + tableId);
        }
        cursor += 4;

        List<Long> secondaryIndexIds = new ArrayList<>(secCount);
        for (int s = 0; s < secCount; s++) {
            ensureRange(pageId, cursor, 8, entryEnd, "secondaryIndexId");
            secondaryIndexIds.add(buf.getLong(cursor));
            cursor += 8;
        }

        ensureRange(pageId, cursor, 8, entryEnd, "createTime");
        long createTime = buf.getLong(cursor);
        cursor += 8;

        ensureRange(pageId, cursor, 8, entryEnd, "lastUpdateTime");
        long lastUpdateTime = buf.getLong(cursor);
        cursor += 8;

        ensureRange(pageId, cursor, 1, entryEnd, "tableState");
        int stateOrd = buf.get(cursor) & 0xFF;
        if (stateOrd >= TableDescriptor.TableState.values().length) {
            throw PageCorruptedException.invalidFormat(pageId,
                    "invalid TableState ordinal: " + stateOrd);
        }
        cursor += 1;

        if (cursor != entryEnd) {
            throw PageCorruptedException.invalidFormat(pageId,
                    String.format("table entry length mismatch: expected end=%d, actual=%d",
                            entryEnd, cursor));
        }

        return new DecodedEntry(
                new TableEntry(
                        tableId,
                        tableName.value(),
                        dbId,
                        spaceId,
                        columns,
                        primaryIndexId,
                        secondaryIndexIds,
                        createTime,
                        lastUpdateTime,
                        TableDescriptor.TableState.values()[stateOrd]),
                4 + entryLen,
                entryLen);
    }

    private static DecodedString readString(ByteBuffer buf, PageId pageId, int offset, int limitExclusive,
                                            String fieldName) throws PageCorruptedException {
        ensureRange(pageId, offset, 4, limitExclusive, fieldName + ".length");
        int len = buf.getInt(offset);
        if (len < 0) {
            throw PageCorruptedException.invalidFormat(pageId,
                    fieldName + " length is negative: " + len);
        }
        ensureRange(pageId, offset + 4, len, limitExclusive, fieldName + ".bytes");

        byte[] bytes = new byte[len];
        ByteBuffer duplicate = buf.duplicate();
        duplicate.position(offset + 4);
        duplicate.get(bytes);
        return new DecodedString(new String(bytes, StandardCharsets.UTF_8), 4 + len);
    }

    private static byte[] readBytes(ByteBuffer buf, PageId pageId, int offset, int length, int limitExclusive,
                                    String fieldName) throws PageCorruptedException {
        ensureRange(pageId, offset, length, limitExclusive, fieldName);
        byte[] bytes = new byte[length];
        ByteBuffer duplicate = buf.duplicate();
        duplicate.position(offset);
        duplicate.get(bytes);
        return bytes;
    }

    private static void putBytes(ByteBuffer buf, int offset, byte[] data) {
        if (data.length == 0) {
            return;
        }
        ByteBuffer duplicate = buf.duplicate();
        duplicate.position(offset);
        duplicate.put(data);
    }

    private static void ensureRange(PageId pageId, int offset, int length, String fieldName)
            throws PageCorruptedException {
        ensureRange(pageId, offset, length, PAGE_SIZE, fieldName);
    }

    private static void ensureRange(PageId pageId, int offset, int length, int limitExclusive,
                                    String fieldName) throws PageCorruptedException {
        if (offset < 0 || length < 0 || limitExclusive > PAGE_SIZE || offset > limitExclusive
                || length > limitExclusive - offset) {
            throw PageCorruptedException.invalidFormat(pageId,
                    String.format("%s out of range: offset=%d, length=%d, limit=%d",
                            fieldName, offset, length, limitExclusive));
        }
    }

    private static byte[] snapshotPage(ByteBuffer buf) {
        byte[] image = new byte[PAGE_SIZE];
        ByteBuffer duplicate = buf.duplicate();
        duplicate.position(0);
        duplicate.get(image);
        return image;
    }

    private static void writePageImage(ByteBuffer buf, byte[] image) {
        ByteBuffer duplicate = buf.duplicate();
        duplicate.position(0);
        duplicate.put(image);
    }

    private static void zeroRange(ByteBuffer buf, int fromInclusive, int toExclusive) {
        ByteBuffer duplicate = buf.duplicate();
        duplicate.position(fromInclusive);
        duplicate.put(new byte[toExclusive - fromInclusive]);
    }

    private record DecodedEntry(TableEntry entry, int totalBytes, int payloadLength) {}

    private record DecodedString(String value, int totalBytes) {}

    private TableMetaPage() {
        throw new UnsupportedOperationException("TableMetaPage is a utility class");
    }
}
