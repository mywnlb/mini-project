package cn.zhangyis.minidb.storage.catalog.ddl;

import cn.zhangyis.minidb.common.exception.MiniDbException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.CatalogException;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.page.PageType;
import cn.zhangyis.minidb.storage.page.Page;
import cn.zhangyis.minidb.storage.catalog.TableDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DDL Log 管理器。
 *
 * <p>提供 append + force、scan、replay、compact 的闭环。首版只支持 DELETE_SPACE intent。</p>
 */
public class DdlLogManager {

    private static final Logger log = LoggerFactory.getLogger(DdlLogManager.class);
    private static final int SYSTEM_SPACE_ID = 0;

    private final BufferPool bufferPool;
    private final DiskManager diskManager;
    private final DdlLogReplay replay;
    private final AtomicLong nextDdlOpId = new AtomicLong(1);

    public DdlLogManager(BufferPool bufferPool) {
        this.bufferPool = bufferPool;
        this.diskManager = bufferPool.getDiskManager();
        this.replay = new DdlLogReplay(diskManager);
    }

    public void refreshNextOpIdSeed() throws CatalogException {
        long maxSeen = 0;
        for (DdlLogRecord record : scanAllRecords()) {
            maxSeen = Math.max(maxSeen, record.ddlOpId());
        }
        long next = maxSeen + 1;
        nextDdlOpId.accumulateAndGet(next, Math::max);
    }

    public long appendDeleteSpaceIntentAndForce(int spaceId, long tableId) throws CatalogException {
        long ddlOpId = nextDdlOpId.getAndIncrement();
        appendIntentAndForce(DdlLogRecord.deleteSpace(ddlOpId, spaceId, tableId));
        return ddlOpId;
    }

    public void appendIntentAndForce(DdlLogRecord record) throws CatalogException {
        List<PageId> touchedPages = appendRecord(record);
        forceTouchedPages(touchedPages);
    }

    public List<DdlLogRecord> scanAllRecords() throws CatalogException {
        List<DdlLogRecord> records = new ArrayList<>();
        try {
            for (int pageNo : collectPageNos()) {
                PageId pageId = PageId.of(SYSTEM_SPACE_ID, pageNo);
                try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                    BufferFrame frame = mtr.getPageFrame(pageId, BufferPool.FetchMode.READ_EXISTING);
                    frame.readLock();
                    try {
                        records.addAll(DdlLogPage.readAllRecords(frame));
                    } finally {
                        frame.readUnlock();
                    }
                }
            }
            return records;
        } catch (MiniDbException e) {
            throw CatalogException.persistenceFailed("scan DDL log", e);
        } catch (RuntimeException e) {
            throw new CatalogException("Failed to scan DDL log", e);
        }
    }

    public int replayPendingIntents(Collection<TableDescriptor> liveTables) throws CatalogException {
        Set<Long> liveTableIds = new LinkedHashSet<>();
        for (TableDescriptor table : liveTables) {
            liveTableIds.add(table.getTableId());
        }

        List<DdlLogRecord> records = scanAllRecords();
        Set<Long> compactedOps = new LinkedHashSet<>();
        int replayedCount = 0;
        for (DdlLogRecord record : records) {
            boolean tableAbsent = !liveTableIds.contains(record.tableId());
            boolean compactable = replay.replay(record, liveTableIds);
            if (compactable) {
                compactedOps.add(record.ddlOpId());
                if (tableAbsent) {
                    replayedCount++;
                }
            }
        }

        if (!compactedOps.isEmpty()) {
            compactByOpIds(compactedOps);
        }
        refreshNextOpIdSeed();
        return replayedCount;
    }

    public void compactByOpId(long ddlOpId) throws CatalogException {
        compactByOpIds(Set.of(ddlOpId));
    }

    public void compactByOpIds(Collection<Long> ddlOpIds) throws CatalogException {
        if (ddlOpIds == null || ddlOpIds.isEmpty()) {
            return;
        }

        Set<Long> victims = new LinkedHashSet<>(ddlOpIds);
        List<DdlLogRecord> remaining = new ArrayList<>();
        for (DdlLogRecord record : scanAllRecords()) {
            if (!victims.contains(record.ddlOpId())) {
                remaining.add(record);
            }
        }
        rewriteAllRecordsAndForce(remaining);
    }

    private List<PageId> appendRecord(DdlLogRecord record) throws CatalogException {
        LinkedHashSet<PageId> touchedPages = new LinkedHashSet<>();
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            int currentPageNo = DdlLogPage.DDL_LOG_PAGE_NO;
            while (true) {
                BufferFrame frame = mtr.getPageFrame(
                        PageId.of(SYSTEM_SPACE_ID, currentPageNo),
                        BufferPool.FetchMode.READ_EXISTING
                );
                frame.writeLock();
                try {
                    if (DdlLogPage.writeRecord(frame, record)) {
                        mtr.markDirty(frame.getPage());
                        touchedPages.add(frame.getPageId());
                        break;
                    }

                    int nextPageNo = DdlLogPage.readNextPage(frame);
                    if (nextPageNo == 0) {
                        BufferFrame newFrame = mtr.newPageFrame(SYSTEM_SPACE_ID);
                        newFrame.writeLock();
                        try {
                            DdlLogPage.initPage(newFrame);
                            mtr.markDirty(newFrame.getPage());
                            DdlLogPage.writeNextPage(frame, newFrame.getPageId().getPageNo());
                            mtr.markDirty(frame.getPage());
                            if (!DdlLogPage.writeRecord(newFrame, record)) {
                                throw new CatalogException("Fresh DDL log page has no space for new record");
                            }
                            mtr.markDirty(newFrame.getPage());
                            touchedPages.add(frame.getPageId());
                            touchedPages.add(newFrame.getPageId());
                        } finally {
                            newFrame.writeUnlock();
                        }
                        break;
                    }

                    currentPageNo = nextPageNo;
                } finally {
                    frame.writeUnlock();
                }
            }
            mtr.commit();
        } catch (MiniDbException e) {
            throw CatalogException.persistenceFailed("append DDL log intent", e);
        } catch (RuntimeException e) {
            throw new CatalogException("Failed to append DDL log intent", e);
        }
        return new ArrayList<>(touchedPages);
    }

    private void rewriteAllRecordsAndForce(List<DdlLogRecord> records) throws CatalogException {
        List<Integer> pageNos = collectPageNos();
        int requiredPages = Math.max(1, divideCeil(records.size(), DdlLogPage.MAX_ENTRIES));
        LinkedHashSet<PageId> touchedPages = new LinkedHashSet<>();

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            List<BufferFrame> frames = new ArrayList<>();

            for (int pageNo : pageNos) {
                BufferFrame frame = mtr.getPageFrame(PageId.of(SYSTEM_SPACE_ID, pageNo), BufferPool.FetchMode.READ_EXISTING);
                frame.writeLock();
                frames.add(frame);
            }

            try {
                while (frames.size() < requiredPages) {
                    BufferFrame newFrame = mtr.newPageFrame(SYSTEM_SPACE_ID);
                    newFrame.writeLock();
                    DdlLogPage.initPage(newFrame);
                    mtr.markDirty(newFrame.getPage());
                    frames.add(newFrame);
                    pageNos.add(newFrame.getPageId().getPageNo());
                }

                for (int i = 0; i < frames.size(); i++) {
                    BufferFrame frame = frames.get(i);
                    int fromIndex = i * DdlLogPage.MAX_ENTRIES;
                    int toIndex = Math.min(fromIndex + DdlLogPage.MAX_ENTRIES, records.size());
                    List<DdlLogRecord> pageRecords = fromIndex < records.size()
                            ? new ArrayList<>(records.subList(fromIndex, toIndex))
                            : List.of();
                    int nextPageNo = i + 1 < requiredPages ? frames.get(i + 1).getPageId().getPageNo() : 0;
                    DdlLogPage.rewritePage(frame, pageRecords, nextPageNo);
                    mtr.markDirty(frame.getPage());
                    touchedPages.add(frame.getPageId());
                }
            } finally {
                for (int i = frames.size() - 1; i >= 0; i--) {
                    frames.get(i).writeUnlock();
                }
            }

            mtr.commit();
        } catch (MiniDbException e) {
            throw CatalogException.persistenceFailed("compact DDL log", e);
        } catch (RuntimeException e) {
            throw new CatalogException("Failed to compact DDL log", e);
        }

        forceTouchedPages(new ArrayList<>(touchedPages));
    }

    private List<Integer> collectPageNos() throws CatalogException {
        List<Integer> pageNos = new ArrayList<>();
        int currentPageNo = DdlLogPage.DDL_LOG_PAGE_NO;
        Set<Integer> visited = new LinkedHashSet<>();
        try {
            while (currentPageNo != 0) {
                if (!visited.add(currentPageNo)) {
                    throw new CatalogException("DDL log page chain contains a cycle at page " + currentPageNo);
                }
                pageNos.add(currentPageNo);
                try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                    BufferFrame frame = mtr.getPageFrame(
                            PageId.of(SYSTEM_SPACE_ID, currentPageNo),
                            BufferPool.FetchMode.READ_EXISTING
                    );
                    frame.readLock();
                    try {
                        currentPageNo = DdlLogPage.readNextPage(frame);
                    } finally {
                        frame.readUnlock();
                    }
                }
            }
            return pageNos;
        } catch (MiniDbException e) {
            throw CatalogException.persistenceFailed("traverse DDL log pages", e);
        } catch (RuntimeException e) {
            throw new CatalogException("Failed to traverse DDL log pages", e);
        }
    }

    private void forceTouchedPages(List<PageId> touchedPages) throws CatalogException {
        try {
            for (PageId pageId : touchedPages) {
                bufferPool.flushPage(pageId);
            }
            diskManager.sync(SYSTEM_SPACE_ID);
        } catch (MiniDbException e) {
            throw CatalogException.persistenceFailed("force DDL log", e);
        }
    }

    private static int divideCeil(int dividend, int divisor) {
        return (dividend + divisor - 1) / divisor;
    }
}
