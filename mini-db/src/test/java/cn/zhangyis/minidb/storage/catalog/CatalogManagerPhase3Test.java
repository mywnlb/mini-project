package cn.zhangyis.minidb.storage.catalog;

import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.persist.CatalogMetaPage;
import cn.zhangyis.minidb.storage.catalog.persist.TableMetaPage;
import cn.zhangyis.minidb.storage.constants.StorageConstants;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.record.schema.RecordSchema;
import cn.zhangyis.minidb.storage.record.schema.FieldType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogManagerPhase3Test {

    private static final int SYSTEM_SPACE_ID = 0;

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool bufferPool;
    private CatalogManager catalogManager;

    @BeforeEach
    void setUp() throws Exception {
        diskManager = new DiskManager(tempDir);
        diskManager.createTablespace(SYSTEM_SPACE_ID, "system");
        bufferPool = new BufferPool(32, diskManager);
        catalogManager = new CatalogManager(bufferPool);
        catalogManager.bootstrap();
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
    void createDatabaseAndTableRoundTrip() throws Exception {
        DatabaseDescriptor db = catalogManager.createDatabase("app_db");
        TableDescriptor table = catalogManager.createTable("app_db", "users", List.of(
                new ColumnMeta(11, "name", FieldType.varchar(32, true), 1, null),
                new ColumnMeta(10, "id", FieldType.intType(false), 0, null)
        ));

        assertEquals(db.getDatabaseId(), table.getDatabaseId());
        assertEquals(1, catalogManager.listDatabases().size());
        assertEquals(1, catalogManager.listTables("app_db").size());
        assertEquals(1, catalogManager.getDatabase("app_db").getTableCount());

        RecordSchema schema = catalogManager.getCurrentSchema(table.getTableId());
        assertEquals(2, schema.getColumnCount());
        assertEquals("id", schema.getColumn(0).getName());
        assertEquals("name", schema.getColumn(1).getName());

        IdGenerator persistedIds = readIdGenerator();
        assertEquals(catalogManager.getIdGenerator().getNextTableId(), persistedIds.getNextTableId());
        assertEquals(2L, persistedIds.getNextTableId());
        assertEquals(2L, persistedIds.getNextDatabaseId());
        assertEquals(1, readPersistedDatabaseTableCount("app_db"));

        CatalogManager reloaded = new CatalogManager(bufferPool);
        reloaded.loadCatalog();

        TableDescriptor loaded = reloaded.getTable("app_db", "users");
        assertEquals(table.getTableId(), loaded.getTableId());
        assertEquals(1, reloaded.getDatabase("app_db").getTableCount());
        assertEquals(1, reloaded.listTables("app_db").size());
    }

    @Test
    void createTableRejectsInvalidColumnMetadata() throws Exception {
        catalogManager.createDatabase("bad_db");

        CatalogException duplicateName = assertThrows(CatalogException.class, () ->
                catalogManager.createTable("bad_db", "dup_name", List.of(
                        new ColumnMeta(1, "id", FieldType.intType(false), 0, null),
                        new ColumnMeta(2, "id", FieldType.intType(false), 1, null)
                )));
        assertTrue(duplicateName.getMessage().contains("Duplicate column name"));

        CatalogException badOrdinal = assertThrows(CatalogException.class, () ->
                catalogManager.createTable("bad_db", "gap_ordinal", List.of(
                        new ColumnMeta(3, "id", FieldType.intType(false), 0, null),
                        new ColumnMeta(4, "name", FieldType.varchar(32, true), 2, null)
                )));
        assertTrue(badOrdinal.getMessage().contains("Column ordinals"));

        assertTrue(catalogManager.listTables("bad_db").isEmpty());
        assertEquals(0, catalogManager.getDatabase("bad_db").getTableCount());
        assertTrue(readAllTableEntries().isEmpty());
        assertEquals(0, readPersistedDatabaseTableCount("bad_db"));
    }

    @Test
    void concurrentCreateDatabaseSameNameOnlyOneSucceeds() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> createDatabaseRace(start));
            Future<Boolean> second = executor.submit(() -> createDatabaseRace(start));

            start.countDown();

            int successCount = 0;
            if (first.get()) {
                successCount++;
            }
            if (second.get()) {
                successCount++;
            }

            assertEquals(1, successCount);
            assertEquals(1, catalogManager.listDatabases().size());
            assertEquals("race_db", catalogManager.getDatabase("race_db").getDatabaseName());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void dropTableAndDatabaseUpdatePersistence() throws Exception {
        catalogManager.createDatabase("drop_db");
        catalogManager.createTable("drop_db", "orders", List.of(
                new ColumnMeta(21, "id", FieldType.bigint(false), 0, null),
                new ColumnMeta(22, "status", FieldType.varchar(16, true), 1, null)
        ));

        CatalogException notEmpty = assertThrows(CatalogException.class,
                () -> catalogManager.dropDatabase("drop_db"));
        assertTrue(notEmpty.getMessage().contains("Database not empty"));

        catalogManager.dropTable("drop_db", "orders");
        assertTrue(catalogManager.listTables("drop_db").isEmpty());
        assertEquals(0, catalogManager.getDatabase("drop_db").getTableCount());
        assertTrue(readAllTableEntries().isEmpty());
        assertEquals(0, readPersistedDatabaseTableCount("drop_db"));

        catalogManager.dropDatabase("drop_db");
        assertTrue(catalogManager.listDatabases().isEmpty());
        assertFalse(hasDatabase("drop_db"));
    }

    private boolean createDatabaseRace(CountDownLatch start) throws Exception {
        start.await();
        try {
            catalogManager.createDatabase("race_db");
            return true;
        } catch (CatalogException e) {
            return false;
        }
    }

    private IdGenerator readIdGenerator() throws Exception {
        PageId metaPageId = PageId.of(SYSTEM_SPACE_ID, CatalogMetaPage.CATALOG_META_PAGE_NO);
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            BufferFrame frame = mtr.getPageFrame(metaPageId, BufferPool.FetchMode.READ_EXISTING);
            frame.readLock();
            try {
                return CatalogMetaPage.readIdGenerator(frame);
            } finally {
                frame.readUnlock();
            }
        }
    }

    private int readPersistedDatabaseTableCount(String dbName) throws Exception {
        PageId metaPageId = PageId.of(SYSTEM_SPACE_ID, CatalogMetaPage.CATALOG_META_PAGE_NO);
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            BufferFrame frame = mtr.getPageFrame(metaPageId, BufferPool.FetchMode.READ_EXISTING);
            frame.readLock();
            try {
                ByteBuffer buf = frame.buffer();
                int databaseCount = buf.getInt(StorageConstants.FIL_HEADER_SIZE + 36);
                int offset = StorageConstants.FIL_HEADER_SIZE + CatalogMetaPage.CATALOG_HEADER_SIZE;
                for (int i = 0; i < databaseCount; i++) {
                    offset += 4; // dbId

                    String name = readPersistedString(buf, offset);
                    offset += 4 + name.getBytes(StandardCharsets.UTF_8).length;

                    String charset = readPersistedString(buf, offset);
                    offset += 4 + charset.getBytes(StandardCharsets.UTF_8).length;

                    offset += 8; // createTime
                    int tableCount = buf.getInt(offset);
                    offset += 4;

                    if (name.equals(dbName)) {
                        return tableCount;
                    }
                }
                return -1;
            } finally {
                frame.readUnlock();
            }
        }
    }

    private boolean hasDatabase(String dbName) throws Exception {
        return readPersistedDatabaseTableCount(dbName) >= 0;
    }

    private List<TableMetaPage.TableEntry> readAllTableEntries() throws Exception {
        List<TableMetaPage.TableEntry> entries = new ArrayList<>();
        PageId metaPageId = PageId.of(SYSTEM_SPACE_ID, CatalogMetaPage.CATALOG_META_PAGE_NO);
        int currentPageNo;

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            BufferFrame metaFrame = mtr.getPageFrame(metaPageId, BufferPool.FetchMode.READ_EXISTING);
            metaFrame.readLock();
            try {
                currentPageNo = CatalogMetaPage.readFirstTableMetaPage(metaFrame);
            } finally {
                metaFrame.readUnlock();
            }
        }

        while (currentPageNo != 0) {
            PageId pageId = PageId.of(SYSTEM_SPACE_ID, currentPageNo);
            try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
                BufferFrame frame = mtr.getPageFrame(pageId, BufferPool.FetchMode.READ_EXISTING);
                frame.readLock();
                try {
                    entries.addAll(TableMetaPage.readAllEntries(frame));
                    currentPageNo = TableMetaPage.readNextPage(frame);
                } finally {
                    frame.readUnlock();
                }
            }
        }

        return entries;
    }

    private String readPersistedString(ByteBuffer buf, int offset) {
        int len = buf.getInt(offset);
        byte[] bytes = new byte[len];
        ByteBuffer dup = buf.duplicate();
        dup.position(offset + 4);
        dup.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
