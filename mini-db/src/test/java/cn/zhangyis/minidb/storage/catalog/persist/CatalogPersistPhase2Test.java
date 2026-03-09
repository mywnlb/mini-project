package cn.zhangyis.minidb.storage.catalog.persist;

import cn.zhangyis.minidb.common.exception.PageCorruptedException;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.ColumnMeta;
import cn.zhangyis.minidb.storage.catalog.DatabaseDescriptor;
import cn.zhangyis.minidb.storage.catalog.TableDescriptor;
import cn.zhangyis.minidb.storage.catalog.ddl.DdlLogPage;
import cn.zhangyis.minidb.storage.constants.StorageConstants;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.page.PageType;
import cn.zhangyis.minidb.storage.record.schema.FieldType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogPersistPhase2Test {

    private static final int SYSTEM_SPACE_ID = 0;
    private static final int TABLE_META_COUNT_OFFSET = StorageConstants.FIL_HEADER_SIZE + 8;
    private static final int TABLE_META_DATA_OFFSET = StorageConstants.FIL_HEADER_SIZE + 16;

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool bufferPool;

    @BeforeEach
    void setUp() throws Exception {
        diskManager = new DiskManager(tempDir);
        diskManager.createTablespace(SYSTEM_SPACE_ID, "system");
        bufferPool = new BufferPool(16, diskManager);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (bufferPool != null) {
            bufferPool.close();
        }
        if (diskManager != null) {
            diskManager.close();
        }
    }

    @Test
    void bootstrapAllocatesFixedPagesAndDoesNotLeakPins() throws Exception {
        CatalogBootstrap bootstrap = new CatalogBootstrap(bufferPool);

        assertFalse(bootstrap.isCatalogInitialized());

        bootstrap.initCatalog();
        CatalogBootstrap.CatalogSnapshot snapshot = bootstrap.loadCatalog();

        assertTrue(bootstrap.isCatalogInitialized());
        assertEquals(6, diskManager.getPageCount(SYSTEM_SPACE_ID));
        assertEquals(1L, snapshot.idGenerator().getNextTableId());
        assertTrue(snapshot.databases().isEmpty());
        assertTrue(snapshot.tables().isEmpty());

        PageId catalogPageId = PageId.of(SYSTEM_SPACE_ID, CatalogMetaPage.CATALOG_META_PAGE_NO);
        BufferFrame catalogFrame = bufferPool.getPage(catalogPageId, BufferPool.FetchMode.READ_EXISTING);
        try {
            assertEquals(1, catalogFrame.getPinCount());
            assertEquals(PageType.FIL_PAGE_CATALOG_META, catalogFrame.getPage().getPageType());
        } finally {
            bufferPool.unpinPage(catalogPageId, false);
        }

        PageId tableMetaPageId = PageId.of(SYSTEM_SPACE_ID, TableMetaPage.FIRST_TABLE_META_PAGE_NO);
        BufferFrame tableMetaFrame = bufferPool.getPage(tableMetaPageId, BufferPool.FetchMode.READ_EXISTING);
        try {
            assertEquals(1, tableMetaFrame.getPinCount());
            assertEquals(PageType.FIL_PAGE_TABLE_META, tableMetaFrame.getPage().getPageType());
        } finally {
            bufferPool.unpinPage(tableMetaPageId, false);
        }

        PageId ddlLogPageId = PageId.of(SYSTEM_SPACE_ID, DdlLogPage.DDL_LOG_PAGE_NO);
        BufferFrame ddlLogFrame = bufferPool.getPage(ddlLogPageId, BufferPool.FetchMode.READ_EXISTING);
        try {
            assertEquals(1, ddlLogFrame.getPinCount());
            assertEquals(PageType.FIL_PAGE_DDL_LOG, ddlLogFrame.getPage().getPageType());
        } finally {
            bufferPool.unpinPage(ddlLogPageId, false);
        }
    }

    @Test
    void concurrentCatalogMetaReadsStayStable() throws Exception {
        PageId pageId = createCatalogMetaPage(
                new DatabaseDescriptor(1, "alpha_db", "utf8mb4", 101L),
                new DatabaseDescriptor(2, "beta_db", "utf8mb4", 202L));

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<? extends Future<?>> futures = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(i -> executor.submit(() -> {
                        for (int j = 0; j < 1_000; j++) {
                            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                                BufferFrame frame = mtr.getPageFrame(pageId, BufferPool.FetchMode.READ_EXISTING);
                                frame.readLock();
                                try {
                                    List<DatabaseDescriptor> databases = CatalogMetaPage.readAllDatabases(frame);
                                    assertEquals(2, databases.size());
                                    assertEquals("alpha_db", databases.get(0).getDatabaseName());
                                    assertEquals("beta_db", databases.get(1).getDatabaseName());
                                } finally {
                                    frame.readUnlock();
                                }
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }))
                    .toList();

            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void readAllEntriesRejectsCorruptedEntryLength() throws Exception {
        PageId pageId;
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            BufferFrame frame = mtr.newPageFrame(SYSTEM_SPACE_ID);
            frame.writeLock();
            try {
                TableMetaPage.initPage(frame);
                ByteBuffer buf = frame.buffer();
                buf.putInt(TABLE_META_COUNT_OFFSET, 1);
                buf.putInt(TABLE_META_DATA_OFFSET, StorageConstants.PAGE_SIZE);
                mtr.markDirty(frame.getPage());
            } finally {
                frame.writeUnlock();
            }
            pageId = frame.getPageId();
            mtr.commit();
        }

        assertThrows(PageCorruptedException.class, () -> {
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BufferFrame frame = mtr.getPageFrame(pageId, BufferPool.FetchMode.READ_EXISTING);
                frame.readLock();
                try {
                    TableMetaPage.readAllEntries(frame);
                } finally {
                    frame.readUnlock();
                }
            }
        });
    }

    @Test
    void failedRewriteLeavesOriginalPageUntouched() throws Exception {
        PageId pageId = createTableMetaPage(
                tableEntry(1L, "orders"),
                tableEntry(2L, "customers"));

        byte[] before;
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            BufferFrame frame = mtr.getPageFrame(pageId, BufferPool.FetchMode.READ_EXISTING);
            frame.readLock();
            try {
                before = snapshot(frame);
            } finally {
                frame.readUnlock();
            }
        }

        TableMetaPage.TableEntry oversized = tableEntry(1L, "x".repeat(StorageConstants.PAGE_SIZE));

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            BufferFrame frame = mtr.getPageFrame(pageId, BufferPool.FetchMode.READ_EXISTING);
            frame.writeLock();
            try {
                assertFalse(TableMetaPage.updateEntry(frame, oversized));
                assertArrayEquals(before, snapshot(frame));
            } finally {
                frame.writeUnlock();
            }
        }

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            BufferFrame frame = mtr.getPageFrame(pageId, BufferPool.FetchMode.READ_EXISTING);
            frame.readLock();
            try {
                assertArrayEquals(before, snapshot(frame));
            } finally {
                frame.readUnlock();
            }
        }
    }

    private PageId createCatalogMetaPage(DatabaseDescriptor... databases) throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            BufferFrame frame = mtr.newPageFrame(SYSTEM_SPACE_ID);
            frame.writeLock();
            try {
                CatalogMetaPage.initPage(frame);
                for (DatabaseDescriptor database : databases) {
                    assertTrue(CatalogMetaPage.writeDatabase(frame, database));
                }
                mtr.markDirty(frame.getPage());
            } finally {
                frame.writeUnlock();
            }
            PageId pageId = frame.getPageId();
            mtr.commit();
            return pageId;
        }
    }

    private PageId createTableMetaPage(TableMetaPage.TableEntry... entries) throws Exception {
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            BufferFrame frame = mtr.newPageFrame(SYSTEM_SPACE_ID);
            frame.writeLock();
            try {
                TableMetaPage.initPage(frame);
                for (TableMetaPage.TableEntry entry : entries) {
                    assertTrue(TableMetaPage.writeEntry(frame, entry));
                }
                mtr.markDirty(frame.getPage());
            } finally {
                frame.writeUnlock();
            }
            PageId pageId = frame.getPageId();
            mtr.commit();
            return pageId;
        }
    }

    private TableMetaPage.TableEntry tableEntry(long tableId, String tableName) {
        List<ColumnMeta> columns = List.of(
                new ColumnMeta(tableId * 10, "id", FieldType.intType(false), 0, null),
                new ColumnMeta(tableId * 10 + 1, "name", FieldType.varchar(32, true), 1, null));
        long now = 1_700_000_000_000L + tableId;
        return new TableMetaPage.TableEntry(
                tableId,
                tableName,
                1,
                (int) tableId,
                columns,
                tableId * 100,
                List.of(tableId * 100 + 1),
                now,
                now,
                TableDescriptor.TableState.ACTIVE);
    }

    private byte[] snapshot(BufferFrame frame) {
        byte[] image = new byte[StorageConstants.PAGE_SIZE];
        ByteBuffer duplicate = frame.buffer();
        duplicate.position(0);
        duplicate.get(image);
        return image;
    }
}
