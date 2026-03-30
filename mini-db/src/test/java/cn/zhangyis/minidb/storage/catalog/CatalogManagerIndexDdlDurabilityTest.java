package cn.zhangyis.minidb.storage.catalog;

import cn.zhangyis.minidb.storage.DatabaseBootstrap;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.record.schema.FieldType;
import cn.zhangyis.minidb.sql.catalog.IndexMeta;
import cn.zhangyis.minidb.sql.catalog.StorageCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CatalogManagerIndexDdlDurabilityTest {

    private static final String SYSTEM_SPACE_NAME = "system";
    private static final String DATABASE_NAME = "APP";
    private static final String TABLE_NAME = "USERS";

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool bufferPool;
    private CatalogManager catalogManager;
    private DatabaseBootstrap bootstrap;
    private StorageCatalog storageCatalog;

    @BeforeEach
    void setUp() throws Exception {
        openBootstrap();
        catalogManager.createDatabase(DATABASE_NAME);
    }

    @AfterEach
    void tearDown() throws Exception {
        closeBootstrap();
    }

    @Test
    void createIndex_persistsSecondaryIndexIdsAcrossRestart() throws Exception {
        createSimpleTable();

        storageCatalog.createIndex(new IndexMeta("IDX_USERS_NAME", TABLE_NAME, List.of("NAME")));
        long createdIndexId = secondaryIndexIds().getFirst();

        restartBootstrap();

        assertEquals(List.of(createdIndexId), secondaryIndexIds(),
                "createIndex 必须把 secondaryIndexIds 持久化到 TableMetaPage");
    }

    @Test
    void dropIndex_removesSecondaryIndexIdsAcrossRestart() throws Exception {
        createTableWithDurableSecondaryIndexes("IDX_USERS_NAME");
        long droppedIndexId = secondaryIndexIds().getFirst();

        storageCatalog.dropIndex(TABLE_NAME, "IDX_USERS_NAME");
        restartBootstrap();

        assertFalse(secondaryIndexIds().contains(droppedIndexId),
                "dropIndex 后重启不能继续保留已删除索引的 secondaryIndexId");
        assertEquals(0, secondaryIndexIds().size(),
                "dropIndex 必须把 TableMetaPage 中的 secondaryIndexIds 同步清理干净");
    }

    @Test
    void concurrentCreateIndex_publishesCompleteDurableSnapshot() throws Exception {
        createWideTable();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = executor.submit(() -> {
                await(start);
                storageCatalog.createIndex(new IndexMeta("IDX_USERS_NAME", TABLE_NAME, List.of("NAME")));
            });
            Future<?> second = executor.submit(() -> {
                await(start);
                storageCatalog.createIndex(new IndexMeta("IDX_USERS_EMAIL", TABLE_NAME, List.of("EMAIL")));
            });

            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        restartBootstrap();

        assertEquals(2, secondaryIndexIds().size(),
                "并发 createIndex 后重启只能看到完整 durable 新快照，不能丢 secondaryIndexIds");
    }

    private void createSimpleTable() throws Exception {
        catalogManager.createTable(
                DATABASE_NAME,
                TABLE_NAME,
                List.of(
                        new ColumnMeta(1, "ID", FieldType.bigint(false), 0, null),
                        new ColumnMeta(2, "NAME", FieldType.varchar(255, true), 1, null)
                )
        );
    }

    private void createWideTable() throws Exception {
        catalogManager.createTable(
                DATABASE_NAME,
                TABLE_NAME,
                List.of(
                        new ColumnMeta(1, "ID", FieldType.bigint(false), 0, null),
                        new ColumnMeta(2, "NAME", FieldType.varchar(255, true), 1, null),
                        new ColumnMeta(3, "EMAIL", FieldType.varchar(255, true), 2, null)
                )
        );
    }

    private void createTableWithDurableSecondaryIndexes(String... secondaryIndexNames) throws Exception {
        List<IndexDefinition> indexes = new java.util.ArrayList<>();
        indexes.add(IndexDefinition.primary("ID"));
        for (String secondaryIndexName : secondaryIndexNames) {
            indexes.add(IndexDefinition.secondary(secondaryIndexName, "NAME"));
        }

        catalogManager.createTable(
                DATABASE_NAME,
                TABLE_NAME,
                List.of(
                        new ColumnMeta(1, "ID", FieldType.bigint(false), 0, null),
                        new ColumnMeta(2, "NAME", FieldType.varchar(255, true), 1, null)
                ),
                indexes
        );
    }

    private List<Long> secondaryIndexIds() throws Exception {
        return catalogManager.getTable(DATABASE_NAME, TABLE_NAME).getSecondaryIndexIds();
    }

    private void restartBootstrap() throws Exception {
        closeBootstrap();
        openBootstrap();
    }

    private void openBootstrap() throws Exception {
        diskManager = new DiskManager(tempDir);
        bufferPool = new BufferPool(512, diskManager);
        catalogManager = new CatalogManager(bufferPool);
        bootstrap = new DatabaseBootstrap(diskManager, bufferPool, catalogManager, SYSTEM_SPACE_NAME);
        bootstrap.start();
        storageCatalog = new StorageCatalog(catalogManager, DATABASE_NAME, bufferPool);
    }

    private void closeBootstrap() throws Exception {
        if (bootstrap != null) {
            bootstrap.shutdown();
            bootstrap = null;
        }
        if (bufferPool != null) {
            bufferPool.close();
            bufferPool = null;
        }
        if (diskManager != null) {
            diskManager.close();
            diskManager = null;
        }
        catalogManager = null;
        storageCatalog = null;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("unexpected interrupt", e);
        }
    }
}
