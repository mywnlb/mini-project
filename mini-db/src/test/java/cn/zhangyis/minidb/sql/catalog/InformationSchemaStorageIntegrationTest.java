package cn.zhangyis.minidb.sql.catalog;

import cn.zhangyis.minidb.sql.exec.ExecutionContext;
import cn.zhangyis.minidb.sql.exec.MetadataAwareDataSource;
import cn.zhangyis.minidb.sql.exec.Row;
import cn.zhangyis.minidb.sql.exec.SqlSession;
import cn.zhangyis.minidb.storage.DatabaseBootstrap;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.CatalogManager;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.record.schema.FieldType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InformationSchemaStorageIntegrationTest {

    private static final String SYSTEM_SPACE_NAME = "system";
    private static final String DATABASE_NAME = "APP";

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool bufferPool;
    private CatalogManager catalogManager;
    private DatabaseBootstrap bootstrap;
    private StorageCatalog storageCatalog;
    private SqlSession sqlSession;

    @BeforeEach
    void setUp() throws Exception {
        openEnvironment();
        if (!catalogManager.hasDatabase(DATABASE_NAME)) {
            catalogManager.createDatabase(DATABASE_NAME);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        closeEnvironment();
    }

    @Test
    void informationSchemaConstraintViewsTrackCreateDropAndRestart() throws Exception {
        createUsersTable();
        storageCatalog.createIndex(new IndexMeta("UQ_USERS_EMAIL", "USERS", List.of("EMAIL"), false, true));

        List<Row> beforeDropKeyUsage = query("""
                SELECT CONSTRAINT_NAME, COLUMN_NAME
                FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = 'APP' AND TABLE_NAME = 'USERS'
                ORDER BY CONSTRAINT_NAME, ORDINAL_POSITION
                """);
        List<Row> beforeDropConstraints = query("""
                SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE
                FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = 'APP' AND TABLE_NAME = 'USERS'
                ORDER BY CONSTRAINT_NAME
                """);

        assertEquals(2, beforeDropKeyUsage.size(), "PRIMARY + UNIQUE 应出现在 KEY_COLUMN_USAGE");
        assertEquals(2, beforeDropConstraints.size(), "PRIMARY KEY + UNIQUE 应出现在 TABLE_CONSTRAINTS");
        assertTrue(beforeDropKeyUsage.stream().anyMatch(row ->
                "PRIMARY".equals(row.get("CONSTRAINT_NAME")) && "ID".equals(row.get("COLUMN_NAME"))));
        assertTrue(beforeDropKeyUsage.stream().anyMatch(row ->
                "UQ_USERS_EMAIL".equals(row.get("CONSTRAINT_NAME")) && "EMAIL".equals(row.get("COLUMN_NAME"))));
        assertTrue(beforeDropConstraints.stream().anyMatch(row ->
                "PRIMARY KEY".equals(row.get("CONSTRAINT_TYPE"))));
        assertTrue(beforeDropConstraints.stream().anyMatch(row ->
                "UNIQUE".equals(row.get("CONSTRAINT_TYPE"))));

        storageCatalog.dropIndex("USERS", "UQ_USERS_EMAIL");

        List<Row> afterDropKeyUsage = query("""
                SELECT CONSTRAINT_NAME, COLUMN_NAME
                FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = 'APP' AND TABLE_NAME = 'USERS'
                ORDER BY CONSTRAINT_NAME, ORDINAL_POSITION
                """);
        List<Row> afterDropConstraints = query("""
                SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE
                FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = 'APP' AND TABLE_NAME = 'USERS'
                ORDER BY CONSTRAINT_NAME
                """);

        assertEquals(1, afterDropKeyUsage.size(), "dropIndex 后只应剩 PRIMARY");
        assertEquals(1, afterDropConstraints.size(), "dropIndex 后只应剩 PRIMARY KEY");
        assertFalse(afterDropKeyUsage.stream().anyMatch(row ->
                "UQ_USERS_EMAIL".equals(row.get("CONSTRAINT_NAME"))));
        assertFalse(afterDropConstraints.stream().anyMatch(row ->
                "UQ_USERS_EMAIL".equals(row.get("CONSTRAINT_NAME"))));

        restartEnvironment();

        List<Row> afterRestartKeyUsage = query("""
                SELECT CONSTRAINT_NAME, COLUMN_NAME
                FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = 'APP' AND TABLE_NAME = 'USERS'
                ORDER BY CONSTRAINT_NAME, ORDINAL_POSITION
                """);
        List<Row> afterRestartConstraints = query("""
                SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE
                FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = 'APP' AND TABLE_NAME = 'USERS'
                ORDER BY CONSTRAINT_NAME
                """);

        assertEquals(afterDropKeyUsage.size(), afterRestartKeyUsage.size(),
                "重启后 KEY_COLUMN_USAGE 不能回弹已删除的 UNIQUE");
        assertEquals(afterDropConstraints.size(), afterRestartConstraints.size(),
                "重启后 TABLE_CONSTRAINTS 不能回弹已删除的 UNIQUE");
        assertTrue(afterRestartKeyUsage.stream().allMatch(row ->
                "PRIMARY".equals(row.get("CONSTRAINT_NAME"))));
        assertTrue(afterRestartConstraints.stream().allMatch(row ->
                "PRIMARY".equals(row.get("CONSTRAINT_NAME"))));
    }

    @Test
    void informationSchemaStatisticsAndShowSourceStayAlignedForUniqueIndexes() throws Exception {
        createUsersTable();
        storageCatalog.createIndex(new IndexMeta("UQ_USERS_EMAIL", "USERS", List.of("EMAIL"), false, true));

        List<Row> statisticsRows = query("""
                SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = 'APP' AND TABLE_NAME = 'USERS'
                ORDER BY INDEX_NAME, SEQ_IN_INDEX
                """);

        assertEquals(2, statisticsRows.size(), "STATISTICS 应包含 PRIMARY 和 UNIQUE 两条索引定义");
        assertTrue(statisticsRows.stream().anyMatch(row ->
                "PRIMARY".equals(row.get("INDEX_NAME")) && "ID".equals(row.get("COLUMN_NAME"))));
        assertTrue(statisticsRows.stream().anyMatch(row ->
                "UQ_USERS_EMAIL".equals(row.get("INDEX_NAME"))
                        && "EMAIL".equals(row.get("COLUMN_NAME"))
                        && Integer.valueOf(0).equals(row.get("NON_UNIQUE"))));
    }

    /**
     * E10: DDL 与 metadata 查询并发 — 查询只能看到完整旧快照或完整新快照，
     * 不能看到半发布状态（如新索引出现但约束视图缺失）。覆盖不变量 I4。
     */
    @Test
    void concurrentDdlAndMetadataQuerySeeAtomicSnapshot() throws Exception {
        createUsersTable();

        int iterations = 20;
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();

        Thread ddlThread = new Thread(() -> {
            try {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                for (int i = 0; i < iterations; i++) {
                    String indexName = "UQ_ITER_" + i;
                    storageCatalog.createIndex(new IndexMeta(indexName, "USERS", List.of("EMAIL"), false, true));
                    storageCatalog.dropIndex("USERS", indexName);
                }
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        });

        Thread queryThread = new Thread(() -> {
            try {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                for (int i = 0; i < iterations * 2; i++) {
                    // 每次创建新的 provider 获取一致快照
                    InformationSchemaProvider snapshot = new InformationSchemaProvider(storageCatalog);
                    List<Row> keyUsage = snapshot.rows(InformationSchemaNames.INTERNAL_KEY_COLUMN_USAGE).stream()
                            .filter(row -> "APP".equals(row.get("CONSTRAINT_SCHEMA"))
                                    && "USERS".equals(row.get("TABLE_NAME")))
                            .toList();
                    List<Row> constraints = snapshot.rows(InformationSchemaNames.INTERNAL_TABLE_CONSTRAINTS).stream()
                            .filter(row -> "APP".equals(row.get("CONSTRAINT_SCHEMA"))
                                    && "USERS".equals(row.get("TABLE_NAME")))
                            .toList();

                    // 核心断言：KEY_COLUMN_USAGE 与 TABLE_CONSTRAINTS 的约束数量必须一致
                    assertEquals(constraints.size(), keyUsage.size(),
                            "KEY_COLUMN_USAGE 与 TABLE_CONSTRAINTS 必须呈现相同数量的约束 "
                                    + "(iteration=" + i + ", constraints=" + constraints.size()
                                    + ", keyUsage=" + keyUsage.size() + ")");
                }
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        });

        ddlThread.start();
        queryThread.start();
        ddlThread.join(30_000);
        queryThread.join(30_000);

        if (error.get() != null) {
            throw new AssertionError("并发测试失败", error.get());
        }
    }

    private void createUsersTable() throws Exception {
        catalogManager.createTable(
                DATABASE_NAME,
                "USERS",
                List.of(
                        new cn.zhangyis.minidb.storage.catalog.ColumnMeta(1, "ID", FieldType.bigint(false), 0, null),
                        new cn.zhangyis.minidb.storage.catalog.ColumnMeta(2, "EMAIL", FieldType.varchar(255, true), 1, null)
                )
        );
    }

    private List<Row> query(String sql) {
        return sqlSession.execute(sql);
    }

    private void restartEnvironment() throws Exception {
        closeEnvironment();
        openEnvironment();
    }

    private void openEnvironment() throws Exception {
        diskManager = new DiskManager(tempDir);
        bufferPool = new BufferPool(512, diskManager);
        catalogManager = new CatalogManager(bufferPool);
        bootstrap = new DatabaseBootstrap(diskManager, bufferPool, catalogManager, SYSTEM_SPACE_NAME);
        bootstrap.start();

        storageCatalog = new StorageCatalog(catalogManager, DATABASE_NAME, bufferPool);
        ExecutionContext executionContext = new ExecutionContext(bootstrap.getTransactionManager());
        InformationSchemaProvider provider = new InformationSchemaProvider(storageCatalog);
        MetadataAwareCatalog effectiveCatalog = new MetadataAwareCatalog(storageCatalog, provider);
        MetadataAwareDataSource effectiveDataSource = new MetadataAwareDataSource(
                storageCatalog.createStorageDataSource(executionContext),
                storageCatalog
        );
        sqlSession = new SqlSession(effectiveCatalog, executionContext, effectiveDataSource);
    }

    private void closeEnvironment() throws Exception {
        sqlSession = null;
        storageCatalog = null;
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
    }
}
