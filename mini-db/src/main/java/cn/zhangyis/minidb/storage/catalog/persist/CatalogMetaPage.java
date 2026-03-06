package cn.zhangyis.minidb.storage.catalog.persist;

import cn.zhangyis.minidb.common.exception.PageCorruptedException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.catalog.DatabaseDescriptor;
import cn.zhangyis.minidb.storage.catalog.IdGenerator;
import cn.zhangyis.minidb.storage.constants.StorageConstants;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.page.PageType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Catalog 元数据页面
 *
 * <p>用于持久化 Catalog 全局信息，包括 ID 生成器计数器和数据库列表。
 * 位于系统表空间 (spaceId=0) 的 Page 3。</p>
 *
 * <h2>页面布局</h2>
 * <pre>
 * +---------------------------+
 * | Page Header (38)          |
 * +---------------------------+
 * | Catalog Header (44)       |
 * |  - magic (4)              |  0x43415441 ("CATA")
 * |  - version (4)            |
 * |  - nextTableId (8)        |
 * |  - nextColumnId (8)       |
 * |  - nextIndexId (8)        |
 * |  - nextDatabaseId (4)     |
 * |  - databaseCount (4)      |
 * |  - firstTableMetaPage (4) |
 * +---------------------------+
 * | Database Entry 1          |
 * |  - dbId (4)               |
 * |  - nameLen (4)            |
 * |  - name (var)             |
 * |  - charsetLen (4)         |
 * |  - charset (var)          |
 * |  - createTime (8)         |
 * |  - tableCount (4)         |
 * +---------------------------+
 * | Database Entry 2 ...      |
 * +---------------------------+
 * </pre>
 *
 * <h3>Invariants</h3>
 * <ul>
 *   <li>I1: magic 必须为 {@link #MAGIC}，读取时必须验证</li>
 *   <li>I2: nextXxxId 值必须 >= IdGenerator 中已分配的最大 ID + 1</li>
 *   <li>I3: databaseCount 必须等于实际存储的 DatabaseEntry 数量</li>
 *   <li>I5: 所有 entry 必须在 PAGE_SIZE 范围内</li>
 *   <li>I7: 字符串使用 4字节len + UTF-8 bytes 编码</li>
 * </ul>
 *
 * <h3>并发契约</h3>
 * <p>调用方必须持有 BufferFrame 的 X-latch（写操作）或 S-latch（读操作）。</p>
 */
public final class CatalogMetaPage {

    /** 页面大小 */
    private static final int PAGE_SIZE = StorageConstants.PAGE_SIZE;

    /** 魔数 "CATA" */
    public static final int MAGIC = 0x43415441;

    /** 当前版本 */
    private static final int VERSION = 1;

    /** FIL Header 大小 */
    private static final int PAGE_HEADER_SIZE = StorageConstants.FIL_HEADER_SIZE;

    // ==================== Catalog Header 偏移（相对于页面起始）====================

    /** magic 偏移 */
    private static final int OFF_MAGIC = PAGE_HEADER_SIZE;

    /** version 偏移 */
    private static final int OFF_VERSION = OFF_MAGIC + 4;

    /** nextTableId 偏移 */
    private static final int OFF_NEXT_TABLE_ID = OFF_VERSION + 4;

    /** nextColumnId 偏移 */
    private static final int OFF_NEXT_COLUMN_ID = OFF_NEXT_TABLE_ID + 8;

    /** nextIndexId 偏移 */
    private static final int OFF_NEXT_INDEX_ID = OFF_NEXT_COLUMN_ID + 8;

    /** nextDatabaseId 偏移（存储为 int，4 字节） */
    private static final int OFF_NEXT_DB_ID = OFF_NEXT_INDEX_ID + 8;

    /** databaseCount 偏移 */
    private static final int OFF_DB_COUNT = OFF_NEXT_DB_ID + 4;

    /** firstTableMetaPage 偏移 */
    private static final int OFF_FIRST_TABLE_META_PAGE = OFF_DB_COUNT + 4;

    /** Catalog Header 总大小（相对于 FIL Header 之后） */
    public static final int CATALOG_HEADER_SIZE = OFF_FIRST_TABLE_META_PAGE + 4 - PAGE_HEADER_SIZE;

    /** Database Entry 数据区起始偏移 */
    private static final int DATA_OFFSET = OFF_FIRST_TABLE_META_PAGE + 4;

    /** 系统表空间中 CatalogMetaPage 的固定页号 */
    public static final int CATALOG_META_PAGE_NO = 3;

    // ==================== 初始化 ====================

    /**
     * 初始化 Catalog 元数据页面
     *
     * <p>将页面格式化为空白 CatalogMetaPage，所有 ID 计数器初始化为 1。</p>
     *
     * @param frame BufferFrame（必须持有 X-latch）
     */
    public static void initPage(BufferFrame frame) {
        ByteBuffer buf = frame.buffer();

        zeroRange(buf, DATA_OFFSET, PAGE_SIZE);
        frame.getPage().setPageType(PageType.FIL_PAGE_CATALOG_META);

        buf.putInt(OFF_MAGIC, MAGIC);
        buf.putInt(OFF_VERSION, VERSION);
        buf.putLong(OFF_NEXT_TABLE_ID, 1);
        buf.putLong(OFF_NEXT_COLUMN_ID, 1);
        buf.putLong(OFF_NEXT_INDEX_ID, 1);
        buf.putInt(OFF_NEXT_DB_ID, 1);
        buf.putInt(OFF_DB_COUNT, 0);
        buf.putInt(OFF_FIRST_TABLE_META_PAGE, 0);

        frame.setDirty(true);
    }

    // ==================== 验证 ====================

    /**
     * 检查是否为有效的 CatalogMetaPage
     *
     * @param frame BufferFrame
     * @return true 如果 magic 匹配
     */
    public static boolean isValid(BufferFrame frame) {
        return frame.buffer().getInt(OFF_MAGIC) == MAGIC;
    }

    /**
     * 验证页面有效性，无效时抛异常
     *
     * @param frame BufferFrame
     * @throws IllegalStateException 如果 magic 不匹配
     */
    public static void assertValid(BufferFrame frame) {
        if (!isValid(frame)) {
            throw new IllegalStateException(
                    "Invalid CatalogMetaPage: magic mismatch, expected 0x"
                            + Integer.toHexString(MAGIC));
        }
    }

    // ==================== ID Generator 读写 ====================

    /**
     * 从页面恢复 IdGenerator
     *
     * @param frame BufferFrame
     * @return 恢复的 IdGenerator 实例
     */
    public static IdGenerator readIdGenerator(BufferFrame frame) {
        assertValid(frame);
        ByteBuffer buf = frame.buffer();

        long nextTableId = buf.getLong(OFF_NEXT_TABLE_ID);
        long nextColumnId = buf.getLong(OFF_NEXT_COLUMN_ID);
        long nextIndexId = buf.getLong(OFF_NEXT_INDEX_ID);
        long nextDatabaseId = Integer.toUnsignedLong(buf.getInt(OFF_NEXT_DB_ID));

        return new IdGenerator(nextTableId, nextColumnId, nextIndexId, nextDatabaseId);
    }

    /**
     * 将 IdGenerator 的当前计数器快照写入页面
     *
     * <p>调用方必须保证此操作在 MTR 内完成。</p>
     *
     * @param frame       BufferFrame（必须持有 X-latch）
     * @param idGenerator 要持久化的 IdGenerator
     */
    public static void writeIdGenerator(BufferFrame frame, IdGenerator idGenerator) {
        assertValid(frame);
        ByteBuffer buf = frame.buffer();

        buf.putLong(OFF_NEXT_TABLE_ID, idGenerator.getNextTableId());
        buf.putLong(OFF_NEXT_COLUMN_ID, idGenerator.getNextColumnId());
        buf.putLong(OFF_NEXT_INDEX_ID, idGenerator.getNextIndexId());
        buf.putInt(OFF_NEXT_DB_ID, (int) idGenerator.getNextDatabaseId());

        frame.setDirty(true);
    }

    // ==================== FirstTableMetaPage 读写 ====================

    /**
     * 读取第一个 TableMetaPage 的页号
     *
     * @param frame BufferFrame
     * @return 页号，0 表示无 TableMetaPage
     */
    public static int readFirstTableMetaPage(BufferFrame frame) {
        assertValid(frame);
        return frame.buffer().getInt(OFF_FIRST_TABLE_META_PAGE);
    }

    /**
     * 写入第一个 TableMetaPage 的页号
     *
     * @param frame  BufferFrame（必须持有 X-latch）
     * @param pageNo 页号
     */
    public static void writeFirstTableMetaPage(BufferFrame frame, int pageNo) {
        assertValid(frame);
        frame.buffer().putInt(OFF_FIRST_TABLE_META_PAGE, pageNo);
        frame.setDirty(true);
    }

    // ==================== Database Entry 读写 ====================

    /**
     * 读取数据库数量
     *
     * @param frame BufferFrame
     * @return 数据库数量
     */
    public static int readDatabaseCount(BufferFrame frame) {
        assertValid(frame);
        return frame.buffer().getInt(OFF_DB_COUNT);
    }

    /**
     * 读取所有 DatabaseEntry
     *
     * <p>返回的 DatabaseDescriptor 的 tableNameToId 映射为空，
     * 需要后续通过 TableMetaPage 恢复。</p>
     *
     * @param frame BufferFrame
     * @return 数据库描述符列表
     * @throws PageCorruptedException 如果页面布局损坏
     */
    public static List<DatabaseDescriptor> readAllDatabases(BufferFrame frame)
            throws PageCorruptedException {
        assertValid(frame);
        ByteBuffer buf = frame.buffer();
        PageId pageId = frame.getPageId();

        int count = buf.getInt(OFF_DB_COUNT);
        if (count < 0) {
            throw PageCorruptedException.invalidFormat(pageId,
                    "databaseCount is negative: " + count);
        }

        List<DatabaseDescriptor> databases = new ArrayList<>(count);
        int offset = DATA_OFFSET;

        for (int i = 0; i < count; i++) {
            ensureRange(pageId, offset, 4, "databaseId");
            int dbId = buf.getInt(offset);
            offset += 4;

            DecodedString name = readString(buf, pageId, offset, "databaseName");
            offset += name.totalBytes();

            DecodedString charset = readString(buf, pageId, offset, "databaseCharset");
            offset += charset.totalBytes();

            ensureRange(pageId, offset, 8, "databaseCreateTime");
            long createTime = buf.getLong(offset);
            offset += 8;

            ensureRange(pageId, offset, 4, "databaseTableCount");
            int tableCount = buf.getInt(offset);
            if (tableCount < 0) {
                throw PageCorruptedException.invalidFormat(pageId,
                        "tableCount is negative for databaseId=" + dbId);
            }
            offset += 4;

            databases.add(new DatabaseDescriptor(dbId, name.value(), charset.value(), createTime));
        }

        return databases;
    }

    /**
     * 追加一个 Database Entry
     *
     * @param frame    BufferFrame（必须持有 X-latch）
     * @param database 数据库描述符
     * @return true 写入成功，false 空间不足
     * @throws PageCorruptedException 如果页面布局已损坏
     */
    public static boolean writeDatabase(BufferFrame frame, DatabaseDescriptor database)
            throws PageCorruptedException {
        assertValid(frame);
        ByteBuffer buf = frame.buffer();

        int currentOffset = calculateDatabaseEndOffset(frame);
        byte[] nameBytes = database.getDatabaseName().getBytes(StandardCharsets.UTF_8);
        byte[] charsetBytes = database.getCharset().getBytes(StandardCharsets.UTF_8);
        int entrySize = calculateEntrySize(nameBytes, charsetBytes);

        if (currentOffset + entrySize > PAGE_SIZE) {
            return false;
        }

        int nextOffset = writeDatabaseAt(buf, currentOffset, database, nameBytes, charsetBytes);
        buf.putInt(OFF_DB_COUNT, buf.getInt(OFF_DB_COUNT) + 1);
        zeroRange(buf, nextOffset, PAGE_SIZE);

        frame.setDirty(true);
        return true;
    }

    /**
     * 重写所有 Database Entry（用于删除操作后压缩）
     *
     * @param frame     BufferFrame（必须持有 X-latch）
     * @param databases 完整的数据库列表
     * @return true 写入成功
     */
    public static boolean rewriteAllDatabases(BufferFrame frame, List<DatabaseDescriptor> databases) {
        assertValid(frame);
        ByteBuffer pageBuf = frame.buffer();

        byte[] image = snapshotPage(pageBuf);
        Arrays.fill(image, DATA_OFFSET, PAGE_SIZE, (byte) 0);

        ByteBuffer scratch = ByteBuffer.wrap(image).order(pageBuf.order());
        scratch.putInt(OFF_DB_COUNT, 0);

        int offset = DATA_OFFSET;
        for (DatabaseDescriptor db : databases) {
            byte[] nameBytes = db.getDatabaseName().getBytes(StandardCharsets.UTF_8);
            byte[] charsetBytes = db.getCharset().getBytes(StandardCharsets.UTF_8);
            int entrySize = calculateEntrySize(nameBytes, charsetBytes);
            if (offset + entrySize > PAGE_SIZE) {
                return false;
            }
            offset = writeDatabaseAt(scratch, offset, db, nameBytes, charsetBytes);
        }

        scratch.putInt(OFF_DB_COUNT, databases.size());
        writePageImage(pageBuf, image);
        frame.setDirty(true);
        return true;
    }

    /**
     * 更新指定数据库的 tableCount 字段
     *
     * @param frame      BufferFrame（必须持有 X-latch）
     * @param databaseId 数据库 ID
     * @param tableCount 新的表数量
     * @return true 找到并更新，false 未找到
     * @throws PageCorruptedException 如果页面布局损坏
     */
    public static boolean updateDatabaseTableCount(BufferFrame frame, int databaseId, int tableCount)
            throws PageCorruptedException {
        assertValid(frame);
        ByteBuffer buf = frame.buffer();
        PageId pageId = frame.getPageId();

        int count = buf.getInt(OFF_DB_COUNT);
        if (count < 0) {
            throw PageCorruptedException.invalidFormat(pageId,
                    "databaseCount is negative: " + count);
        }

        int offset = DATA_OFFSET;
        for (int i = 0; i < count; i++) {
            ensureRange(pageId, offset, 4, "databaseId");
            int dbId = buf.getInt(offset);
            offset += 4;

            offset = skipString(buf, pageId, offset, "databaseName");
            offset = skipString(buf, pageId, offset, "databaseCharset");

            ensureRange(pageId, offset, 8, "databaseCreateTime");
            offset += 8;

            ensureRange(pageId, offset, 4, "databaseTableCount");
            if (dbId == databaseId) {
                buf.putInt(offset, tableCount);
                frame.setDirty(true);
                return true;
            }

            offset += 4;
        }

        return false;
    }

    // ==================== 内部工具方法 ====================

    /**
     * 计算 Database 数据区的末尾偏移
     */
    private static int calculateDatabaseEndOffset(BufferFrame frame) throws PageCorruptedException {
        ByteBuffer buf = frame.buffer();
        PageId pageId = frame.getPageId();

        int count = buf.getInt(OFF_DB_COUNT);
        if (count < 0) {
            throw PageCorruptedException.invalidFormat(pageId,
                    "databaseCount is negative: " + count);
        }

        int offset = DATA_OFFSET;
        for (int i = 0; i < count; i++) {
            ensureRange(pageId, offset, 4, "databaseId");
            offset += 4;
            offset = skipString(buf, pageId, offset, "databaseName");
            offset = skipString(buf, pageId, offset, "databaseCharset");
            ensureRange(pageId, offset, 8, "databaseCreateTime");
            offset += 8;
            ensureRange(pageId, offset, 4, "databaseTableCount");
            offset += 4;
        }

        return offset;
    }

    private static int calculateEntrySize(byte[] nameBytes, byte[] charsetBytes) {
        return 4
                + 4 + nameBytes.length
                + 4 + charsetBytes.length
                + 8
                + 4;
    }

    private static int writeDatabaseAt(ByteBuffer buf, int offset, DatabaseDescriptor database,
                                       byte[] nameBytes, byte[] charsetBytes) {
        buf.putInt(offset, database.getDatabaseId());
        offset += 4;

        writeString(buf, offset, nameBytes);
        offset += 4 + nameBytes.length;

        writeString(buf, offset, charsetBytes);
        offset += 4 + charsetBytes.length;

        buf.putLong(offset, database.getCreateTime());
        offset += 8;

        buf.putInt(offset, database.getTableCount());
        return offset + 4;
    }

    private static DecodedString readString(ByteBuffer buf, PageId pageId, int offset, String fieldName)
            throws PageCorruptedException {
        ensureRange(pageId, offset, 4, fieldName + ".length");
        int len = buf.getInt(offset);
        if (len < 0) {
            throw PageCorruptedException.invalidFormat(pageId,
                    fieldName + " length is negative: " + len);
        }
        ensureRange(pageId, offset + 4, len, fieldName + ".bytes");

        byte[] bytes = new byte[len];
        ByteBuffer duplicate = buf.duplicate();
        duplicate.position(offset + 4);
        duplicate.get(bytes);
        return new DecodedString(new String(bytes, StandardCharsets.UTF_8), 4 + len);
    }

    private static int skipString(ByteBuffer buf, PageId pageId, int offset, String fieldName)
            throws PageCorruptedException {
        ensureRange(pageId, offset, 4, fieldName + ".length");
        int len = buf.getInt(offset);
        if (len < 0) {
            throw PageCorruptedException.invalidFormat(pageId,
                    fieldName + " length is negative: " + len);
        }
        ensureRange(pageId, offset + 4, len, fieldName + ".bytes");
        return offset + 4 + len;
    }

    private static void writeString(ByteBuffer buf, int offset, byte[] utf8Bytes) {
        buf.putInt(offset, utf8Bytes.length);
        ByteBuffer duplicate = buf.duplicate();
        duplicate.position(offset + 4);
        duplicate.put(utf8Bytes);
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

    private record DecodedString(String value, int totalBytes) {}

    // 禁止实例化
    private CatalogMetaPage() {
        throw new UnsupportedOperationException("CatalogMetaPage is a utility class");
    }
}
