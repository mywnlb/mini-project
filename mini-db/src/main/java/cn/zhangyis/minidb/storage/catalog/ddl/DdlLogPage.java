package cn.zhangyis.minidb.storage.catalog.ddl;

import cn.zhangyis.minidb.common.exception.PageCorruptedException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.constants.StorageConstants;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.page.PageType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * DDL Log 页面格式（持久化层）。
 *
 * <p>位于系统表空间 (spaceId=0) 的固定页链，head page 为 page 5。</p>
 */
public final class DdlLogPage {

    private static final int PAGE_SIZE = StorageConstants.PAGE_SIZE;
    private static final int PAGE_HEADER_SIZE = StorageConstants.FIL_HEADER_SIZE;
    private static final int META_HEADER_OFFSET = PAGE_HEADER_SIZE;
    private static final int META_HEADER_SIZE = 16;
    private static final int OFF_MAGIC = META_HEADER_OFFSET;
    private static final int OFF_VERSION = OFF_MAGIC + 4;
    private static final int OFF_COUNT = OFF_VERSION + 4;
    private static final int OFF_NEXT_PAGE = OFF_COUNT + 4;
    private static final int DATA_OFFSET = META_HEADER_OFFSET + META_HEADER_SIZE;

    /** 魔数 "DDLL" */
    public static final int MAGIC = 0x44444C4C;

    /** 当前布局版本 */
    public static final int VERSION = 2;

    /** 系统表空间中 DDL Log head page 的固定页号。 */
    public static final int DDL_LOG_PAGE_NO = 5;

    /** 单页最大 entry 数量。 */
    public static final int MAX_ENTRIES = (PAGE_SIZE - DATA_OFFSET) / DdlLogRecord.SERIALIZED_SIZE;

    private DdlLogPage() {
        throw new UnsupportedOperationException("DdlLogPage is a utility class");
    }

    public static void initPage(BufferFrame frame) {
        ByteBuffer buf = frame.buffer();
        zeroRange(buf, DATA_OFFSET, PAGE_SIZE);
        frame.getPage().setPageType(PageType.FIL_PAGE_DDL_LOG);
        buf.putInt(OFF_MAGIC, MAGIC);
        buf.putInt(OFF_VERSION, VERSION);
        buf.putInt(OFF_COUNT, 0);
        buf.putInt(OFF_NEXT_PAGE, 0);
        frame.setDirty(true);
    }

    public static boolean isValid(BufferFrame frame) {
        ByteBuffer buf = frame.buffer();
        return buf.getInt(OFF_MAGIC) == MAGIC && buf.getInt(OFF_VERSION) == VERSION;
    }

    public static void assertValid(BufferFrame frame) throws PageCorruptedException {
        if (!isValid(frame)) {
            throw PageCorruptedException.invalidFormat(
                    frame.getPageId(),
                    "Invalid DDL Log page header");
        }
    }

    public static int readCount(BufferFrame frame) throws PageCorruptedException {
        assertValid(frame);
        int count = frame.buffer().getInt(OFF_COUNT);
        if (count < 0 || count > MAX_ENTRIES) {
            throw PageCorruptedException.invalidFormat(
                    frame.getPageId(),
                    "Invalid DDL Log count: " + count);
        }
        return count;
    }

    public static int readNextPage(BufferFrame frame) throws PageCorruptedException {
        assertValid(frame);
        int nextPage = frame.buffer().getInt(OFF_NEXT_PAGE);
        if (nextPage < 0) {
            throw PageCorruptedException.invalidFormat(
                    frame.getPageId(),
                    "Invalid DDL Log nextPage: " + nextPage);
        }
        return nextPage;
    }

    public static void writeNextPage(BufferFrame frame, int nextPageNo) throws PageCorruptedException {
        assertValid(frame);
        if (nextPageNo < 0) {
            throw new IllegalArgumentException("nextPageNo must be >= 0");
        }
        frame.buffer().putInt(OFF_NEXT_PAGE, nextPageNo);
        frame.setDirty(true);
    }

    public static List<DdlLogRecord> readAllRecords(BufferFrame frame) throws PageCorruptedException {
        int count = readCount(frame);
        ByteBuffer buf = frame.buffer();
        PageId pageId = frame.getPageId();
        List<DdlLogRecord> records = new ArrayList<>(count);
        int offset = DATA_OFFSET;
        for (int i = 0; i < count; i++) {
            ensureRange(pageId, offset, DdlLogRecord.SERIALIZED_SIZE);
            records.add(readSingleRecord(buf, offset));
            offset += DdlLogRecord.SERIALIZED_SIZE;
        }
        return records;
    }

    public static boolean writeRecord(BufferFrame frame, DdlLogRecord record) throws PageCorruptedException {
        int count = readCount(frame);
        int offset = DATA_OFFSET + count * DdlLogRecord.SERIALIZED_SIZE;
        if (offset + DdlLogRecord.SERIALIZED_SIZE > PAGE_SIZE) {
            return false;
        }
        writeSingleRecord(frame.buffer(), offset, record);
        frame.buffer().putInt(OFF_COUNT, count + 1);
        frame.setDirty(true);
        return true;
    }

    public static void rewritePage(BufferFrame frame, List<DdlLogRecord> records, int nextPageNo)
            throws PageCorruptedException {
        assertValid(frame);
        if (records.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("records.size exceeds MAX_ENTRIES: " + records.size());
        }
        if (nextPageNo < 0) {
            throw new IllegalArgumentException("nextPageNo must be >= 0");
        }

        ByteBuffer buf = frame.buffer();
        zeroRange(buf, DATA_OFFSET, PAGE_SIZE);

        int offset = DATA_OFFSET;
        for (DdlLogRecord record : records) {
            writeSingleRecord(buf, offset, record);
            offset += DdlLogRecord.SERIALIZED_SIZE;
        }

        buf.putInt(OFF_COUNT, records.size());
        buf.putInt(OFF_NEXT_PAGE, nextPageNo);
        frame.setDirty(true);
    }

    private static DdlLogRecord readSingleRecord(ByteBuffer buf, int offset) {
        int entryLen = buf.getInt(offset);
        if (entryLen != DdlLogRecord.PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Unexpected DDL Log entryLen: " + entryLen);
        }
        offset += 4;

        long ddlOpId = buf.getLong(offset);
        offset += 8;

        DdlLogType logType = DdlLogType.fromCode(buf.get(offset));
        offset += 1;

        DdlReplayGuard replayGuard = DdlReplayGuard.fromCode(buf.get(offset));
        offset += 1;

        offset += 2; // reserved

        int spaceId = buf.getInt(offset);
        offset += 4;

        long tableId = buf.getLong(offset);
        offset += 8;

        long indexId = buf.getLong(offset);

        return new DdlLogRecord(ddlOpId, logType, replayGuard, spaceId, tableId, indexId);
    }

    private static void writeSingleRecord(ByteBuffer buf, int offset, DdlLogRecord record) {
        buf.putInt(offset, DdlLogRecord.PAYLOAD_SIZE);
        offset += 4;

        buf.putLong(offset, record.ddlOpId());
        offset += 8;

        buf.put(offset, record.logType().getCode());
        offset += 1;

        buf.put(offset, record.replayGuard().getCode());
        offset += 1;

        buf.putShort(offset, (short) 0);
        offset += 2;

        buf.putInt(offset, record.spaceId());
        offset += 4;

        buf.putLong(offset, record.tableId());
        offset += 8;

        buf.putLong(offset, record.indexId());
    }

    private static void ensureRange(PageId pageId, int offset, int length) throws PageCorruptedException {
        if (offset < 0 || length < 0 || offset + length > PAGE_SIZE) {
            throw PageCorruptedException.invalidFormat(
                    pageId,
                    String.format("DDL Log entry out of range: offset=%d, length=%d", offset, length));
        }
    }

    private static void zeroRange(ByteBuffer buf, int fromInclusive, int toExclusive) {
        ByteBuffer duplicate = buf.duplicate();
        duplicate.position(fromInclusive);
        duplicate.put(new byte[toExclusive - fromInclusive]);
    }
}
