package cn.zhangyis.minidb.storage.catalog;

import cn.zhangyis.minidb.storage.btree.IndexManager;
import cn.zhangyis.minidb.storage.btree.IndexType;
import cn.zhangyis.minidb.storage.buffer.BufferFrame;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.persist.CatalogMetaPage;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.page.PageId;
import cn.zhangyis.minidb.storage.record.schema.FieldType;
import cn.zhangyis.minidb.storage.record.schema.RecordSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogManagerPhase4Test {

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
        bufferPool = new BufferPool(64, diskManager);
        catalogManager = new CatalogManager(bufferPool);
        catalogManager.bootstrap();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (catalogManager != null) {
            catalogManager.close();
        }
        if (bufferPool != null) {
            bufferPool.close();
        }
        if (diskManager != null) {
            diskManager.close();
        }
    }

    @Test
    void createTableBuildsTablespaceAndIndexesAndReloads() throws Exception {
        catalogManager.createDatabase("app_db");

        TableDescriptor table = catalogManager.createTable(
                "app_db",
                "users",
                List.of(
                        new ColumnMeta(99, "name", FieldType.varchar(64, false), 1, null),
                        new ColumnMeta(88, "id", FieldType.bigint(false), 0, null),
                        new ColumnMeta(77, "age", FieldType.intType(true), 2, null)
                ),
                List.of(
                        IndexDefinition.primary("id"),
                        IndexDefinition.secondary("idx_users_name", "name")
                )
        );

        assertTrue(table.getPrimaryIndexId() > 0);
        assertEquals(1, table.getSecondaryIndexIds().size());
        assertTrue(diskManager.tablespaceExists(table.getSpaceId()));
        assertTrue(diskManager.getPageCount(table.getSpaceId()) >= 6);

        List<ColumnMeta> persistedColumns = table.getColumns();
        assertEquals(1L, persistedColumns.get(0).getColumnId());
        assertEquals(2L, persistedColumns.get(1).getColumnId());
        assertEquals(3L, persistedColumns.get(2).getColumnId());

        IdGenerator persistedIds = readIdGenerator();
        assertEquals(2L, persistedIds.getNextTableId());
        assertEquals(4L, persistedIds.getNextColumnId());
        assertEquals(3L, persistedIds.getNextIndexId());

        restartCatalog();

        TableDescriptor loaded = catalogManager.getTable("app_db", "users");
        assertEquals(table.getTableId(), loaded.getTableId());
        assertEquals(table.getPrimaryIndexId(), loaded.getPrimaryIndexId());
        assertEquals(table.getSecondaryIndexIds(), loaded.getSecondaryIndexIds());
        assertTrue(diskManager.tablespaceExists(loaded.getSpaceId()));

        RecordSchema schema = catalogManager.getCurrentSchema(loaded.getTableId());
        assertEquals(3, schema.getColumnCount());
        assertEquals(1L, schema.getColumn(0).getColumnId());
        assertEquals(2L, schema.getColumn(1).getColumnId());
        assertEquals(3L, schema.getColumn(2).getColumnId());

        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            IndexManager indexManager = new IndexManager(bufferPool, loaded.getSpaceId(), 3);
            indexManager.initialize(mtr);

            assertEquals(2, indexManager.getIndexCount());
            assertEquals(IndexType.PRIMARY,
                    indexManager.getDescriptor(loaded.getPrimaryIndexId()).getIndexType());
            assertEquals(IndexType.SECONDARY,
                    indexManager.getDescriptor(loaded.getSecondaryIndexIds().get(0)).getIndexType());
        }
    }

    @Test
    void createTableFailureDropsTablespaceAndDoesNotPublishCatalog() throws Exception {
        catalogManager.createDatabase("fail_db");
        long expectedSpaceId = catalogManager.getIdGenerator().getNextTableId();

        CatalogException failure = assertThrows(CatalogException.class, () ->
                catalogManager.createTable(
                        "fail_db",
                        "bad_table",
                        List.of(
                                new ColumnMeta(1, "payload", FieldType.text(false), 0, null),
                                new ColumnMeta(2, "note", FieldType.varchar(32, true), 1, null)
                        )));

        assertTrue(failure.getMessage().contains("createTable") || failure.getMessage().contains("cannot be used"));
        assertTrue(catalogManager.listTables("fail_db").isEmpty());
        assertEquals(0, catalogManager.getDatabase("fail_db").getTableCount());
        assertFalse(diskManager.tablespaceExists((int) expectedSpaceId));
        assertFalse(Files.exists(tablespaceFile((int) expectedSpaceId)));

        IdGenerator persistedIds = readIdGenerator();
        assertEquals(1L, persistedIds.getNextTableId());
        assertEquals(1L, persistedIds.getNextColumnId());
        assertEquals(1L, persistedIds.getNextIndexId());
    }

    @Test
    void concurrentCreateSameTableOnlyOneSucceeds() throws Exception {
        catalogManager.createDatabase("race_db");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> createTableRace(start));
            Future<Boolean> second = executor.submit(() -> createTableRace(start));

            start.countDown();

            int successCount = 0;
            if (first.get()) {
                successCount++;
            }
            if (second.get()) {
                successCount++;
            }

            assertEquals(1, successCount);
            assertEquals(1, catalogManager.listTables("race_db").size());
            TableDescriptor table = catalogManager.getTable("race_db", "orders");
            assertTrue(diskManager.tablespaceExists(table.getSpaceId()));

            IdGenerator persistedIds = readIdGenerator();
            assertEquals(2L, persistedIds.getNextTableId());
            assertEquals(3L, persistedIds.getNextColumnId());
            assertEquals(2L, persistedIds.getNextIndexId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void loadCatalogRecoversOrphanTablespaceFromPendingCreateIntent() throws Exception {
        int orphanSpaceId = 41;
        long orphanTableId = 41L;

        catalogManager.getDdlLogManager().appendDeleteSpaceIntentAndForce(orphanSpaceId, orphanTableId);
        diskManager.createTablespace(orphanSpaceId, "table_" + orphanSpaceId);
        assertTrue(Files.exists(tablespaceFile(orphanSpaceId)));

        restartCatalog();

        assertFalse(Files.exists(tablespaceFile(orphanSpaceId)));
        assertFalse(diskManager.tablespaceExists(orphanSpaceId));
        assertTrue(catalogManager.getDdlLogManager().scanAllRecords().isEmpty());
    }

    @Test
    void loadCatalogSkipsStaleCreateIntentWhenTableExists() throws Exception {
        catalogManager.createDatabase("stale_db");
        TableDescriptor table = catalogManager.createTable(
                "stale_db",
                "users",
                List.of(
                        new ColumnMeta(1, "id", FieldType.bigint(false), 0, null),
                        new ColumnMeta(2, "name", FieldType.varchar(32, true), 1, null)
                )
        );

        catalogManager.getDdlLogManager().appendDeleteSpaceIntentAndForce(table.getSpaceId(), table.getTableId());
        assertTrue(Files.exists(tablespaceFile(table.getSpaceId())));

        restartCatalog();

        TableDescriptor loaded = catalogManager.getTable("stale_db", "users");
        assertEquals(table.getTableId(), loaded.getTableId());
        assertTrue(Files.exists(tablespaceFile(table.getSpaceId())));
        assertTrue(catalogManager.getDdlLogManager().scanAllRecords().isEmpty());
    }

    @Test
    void loadCatalogReplaysPendingDropIntentAfterMetadataDelete() throws Exception {
        catalogManager.createDatabase("drop_recover_db");
        TableDescriptor table = catalogManager.createTable(
                "drop_recover_db",
                "orders",
                List.of(
                        new ColumnMeta(1, "id", FieldType.bigint(false), 0, null),
                        new ColumnMeta(2, "status", FieldType.varchar(16, true), 1, null)
                )
        );

        catalogManager.getDdlLogManager().appendDeleteSpaceIntentAndForce(table.getSpaceId(), table.getTableId());
        invokePersistTableDrop(table.getTableId(), table.getDatabaseId(), 0);
        assertTrue(Files.exists(tablespaceFile(table.getSpaceId())));

        restartCatalog();

        assertThrows(CatalogException.class, () -> catalogManager.getTable("drop_recover_db", "orders"));
        assertFalse(Files.exists(tablespaceFile(table.getSpaceId())));
        assertTrue(catalogManager.getDdlLogManager().scanAllRecords().isEmpty());
    }

    private boolean createTableRace(CountDownLatch start) throws Exception {
        start.await();
        try {
            catalogManager.createTable(
                    "race_db",
                    "orders",
                    List.of(
                            new ColumnMeta(10, "id", FieldType.bigint(false), 0, null),
                            new ColumnMeta(11, "status", FieldType.varchar(16, true), 1, null)
                    ));
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

    private void restartCatalog() throws Exception {
        catalogManager.close();
        bufferPool.close();
        diskManager.close();

        diskManager = new DiskManager(tempDir);
        diskManager.openTablespace(SYSTEM_SPACE_ID, "system");
        bufferPool = new BufferPool(64, diskManager);
        catalogManager = new CatalogManager(bufferPool);
        catalogManager.loadCatalog();
    }

    private void invokePersistTableDrop(long tableId, int databaseId, int newTableCount) throws Exception {
        Method method = CatalogManager.class.getDeclaredMethod(
                "persistTableDrop",
                long.class,
                int.class,
                int.class
        );
        method.setAccessible(true);
        method.invoke(catalogManager, tableId, databaseId, newTableCount);
    }

    private Path tablespaceFile(int spaceId) {
        return tempDir.resolve("table_" + spaceId + ".ibd");
    }
}
