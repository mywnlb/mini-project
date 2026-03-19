package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.CatalogManager;
import cn.zhangyis.minidb.storage.catalog.TableDescriptor;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.transaction.core.TransactionManager;
import cn.zhangyis.minidb.sql.catalog.StorageCatalog;
import cn.zhangyis.minidb.sql.exec.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Instant DDL ALTER TABLE ADD COLUMN 端到端测试。
 *
 * <p>验证 invariants:</p>
 * <ul>
 *   <li>I6: Instant 默认值按 columnId + introducedVersion 填充</li>
 *   <li>INV-3: 版本隔离 — 旧行使用默认值，新行使用实际值</li>
 *   <li>INV-5: DDL 后缓存失效</li>
 *   <li>INV-7: NOT NULL 无默认值被拒绝</li>
 * </ul>
 */
class InstantDdlAddColumnTest {

    private static final int SYSTEM_SPACE_ID = 0;

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool bufferPool;
    private CatalogManager catalogManager;
    private TransactionManager txnManager;
    private ExecutionContext executionContext;
    private StorageCatalog catalog;
    private StorageDataSource dataSource;
    private SqlSession session;

    @BeforeEach
    void setUp() throws Exception {
        diskManager = new DiskManager(tempDir);
        diskManager.createTablespace(SYSTEM_SPACE_ID, "system");
        bufferPool = new BufferPool(128, diskManager);
        catalogManager = new CatalogManager(bufferPool);
        catalogManager.bootstrap();
        catalogManager.createDatabase("APP");

        txnManager = new TransactionManager(bufferPool, null);
        txnManager.initializeInMemory();
        executionContext = new ExecutionContext(txnManager);
        catalog = new StorageCatalog(catalogManager, "APP", bufferPool);
        dataSource = new StorageDataSource(catalogManager, "APP", executionContext, bufferPool);
        session = new SqlSession(catalog, executionContext, dataSource);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (txnManager != null) txnManager.close();
        if (catalogManager != null) catalogManager.close();
        if (bufferPool != null) bufferPool.close();
        if (diskManager != null) diskManager.close();
    }

    private List<Row> execute(String sql) {
        return session.execute(sql);
    }

    // ==================== Test 1: 端到端 ADD COLUMN + 旧行默认值填充 (I6, INV-3) ====================

    /**
     * 验证 I6 + INV-3:
     * - 创建表 → 插入数据 → ALTER TABLE ADD COLUMN age INT DEFAULT 0
     * - 旧行 SELECT 应看到 age=0（Instant 默认值填充）
     * - 新行插入 age=25 → SELECT 应看到 age=25
     */
    @Test
    void addColumnWithDefaultFillsOldRowsAndNewRowsUseActualValue() throws Exception {
        execute("CREATE TABLE USERS (ID INT PRIMARY KEY, NAME VARCHAR)");
        execute("INSERT INTO USERS (ID, NAME) VALUES (1, 'ALICE')");
        execute("INSERT INTO USERS (ID, NAME) VALUES (2, 'BOB')");

        // ALTER TABLE ADD COLUMN with DEFAULT
        execute("ALTER TABLE USERS ADD COLUMN AGE INT DEFAULT 0");

        // 验证旧行看到默认值
        List<Row> rows = execute("SELECT * FROM USERS");
        assertEquals(2, rows.size());
        for (Row row : rows) {
            assertEquals(0, row.get("USERS.AGE"), "Old row should have default value 0 for AGE");
        }

        // 插入新行含 AGE
        execute("INSERT INTO USERS (ID, NAME, AGE) VALUES (3, 'CHARLIE', 25)");

        // 验证新行有实际值
        List<Row> allRows = execute("SELECT * FROM USERS");
        assertEquals(3, allRows.size());
        Row charlie = allRows.stream()
                .filter(r -> Integer.valueOf(3).equals(r.get("USERS.ID")))
                .findFirst().orElseThrow();
        assertEquals(25, charlie.get("USERS.AGE"), "New row should have actual value 25 for AGE");

        // 验证 schema version 递增
        TableDescriptor table = catalogManager.getTable("APP", "USERS");
        assertEquals(2, table.getSchemaRegistry().getCurrentSchema().getVersion(),
                "Schema version should be 2 after one ADD COLUMN");
    }

    // ==================== Test 2: 连续两次 ADD COLUMN + 多版本读取 (INV-3) ====================

    /**
     * 验证多次 Instant DDL 的版本隔离:
     * - row1(v1) → ADD COLUMN a → row2(v2) → ADD COLUMN b → row3(v3)
     * - row1: a=默认, b=默认
     * - row2: a=实际, b=默认
     * - row3: a=实际, b=实际
     */
    @Test
    void consecutiveAddColumnsWithMultiVersionReads() throws Exception {
        execute("CREATE TABLE ITEMS (ID INT PRIMARY KEY, NAME VARCHAR)");
        execute("INSERT INTO ITEMS (ID, NAME) VALUES (1, 'ITEM1')");

        // 第一次 ADD COLUMN
        execute("ALTER TABLE ITEMS ADD COLUMN PRICE INT DEFAULT 100");
        execute("INSERT INTO ITEMS (ID, NAME, PRICE) VALUES (2, 'ITEM2', 200)");

        // 第二次 ADD COLUMN
        execute("ALTER TABLE ITEMS ADD COLUMN QTY INT DEFAULT 10");
        execute("INSERT INTO ITEMS (ID, NAME, PRICE, QTY) VALUES (3, 'ITEM3', 300, 30)");

        List<Row> rows = execute("SELECT * FROM ITEMS");
        assertEquals(3, rows.size());

        // row1 (v1): PRICE=100(默认), QTY=10(默认)
        Row row1 = rows.stream().filter(r -> Integer.valueOf(1).equals(r.get("ITEMS.ID"))).findFirst().orElseThrow();
        assertEquals(100, row1.get("ITEMS.PRICE"), "row1 PRICE should be default 100");
        assertEquals(10, row1.get("ITEMS.QTY"), "row1 QTY should be default 10");

        // row2 (v2): PRICE=200(实际), QTY=10(默认)
        Row row2 = rows.stream().filter(r -> Integer.valueOf(2).equals(r.get("ITEMS.ID"))).findFirst().orElseThrow();
        assertEquals(200, row2.get("ITEMS.PRICE"), "row2 PRICE should be actual 200");
        assertEquals(10, row2.get("ITEMS.QTY"), "row2 QTY should be default 10");

        // row3 (v3): PRICE=300(实际), QTY=30(实际)
        Row row3 = rows.stream().filter(r -> Integer.valueOf(3).equals(r.get("ITEMS.ID"))).findFirst().orElseThrow();
        assertEquals(300, row3.get("ITEMS.PRICE"), "row3 PRICE should be actual 300");
        assertEquals(30, row3.get("ITEMS.QTY"), "row3 QTY should be actual 30");

        // 验证 schema version = 3
        TableDescriptor table = catalogManager.getTable("APP", "ITEMS");
        assertEquals(3, table.getSchemaRegistry().getCurrentSchema().getVersion());
    }

    // ==================== Test 3: SqlValidator 拒绝 NOT NULL 无默认值 (INV-7) ====================

    /**
     * 验证 INV-7: NOT NULL 无 DEFAULT 被拒绝
     */
    @Test
    void notNullWithoutDefaultIsRejected() {
        execute("CREATE TABLE T1 (ID INT PRIMARY KEY, NAME VARCHAR)");

        // NOT NULL 无 DEFAULT → 应抛异常
        assertThrows(RuntimeException.class, () ->
                execute("ALTER TABLE T1 ADD COLUMN AGE INT NOT NULL"),
                "NOT NULL without DEFAULT should be rejected");
    }

    /**
     * 验证 NOT NULL + DEFAULT 被接受
     */
    @Test
    void notNullWithDefaultIsAccepted() {
        execute("CREATE TABLE T2 (ID INT PRIMARY KEY, NAME VARCHAR)");
        execute("INSERT INTO T2 (ID, NAME) VALUES (1, 'ALICE')");

        // NOT NULL + DEFAULT → 应成功
        execute("ALTER TABLE T2 ADD COLUMN AGE INT NOT NULL DEFAULT 0");

        List<Row> rows = execute("SELECT * FROM T2");
        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).get("T2.AGE"), "Old row should have default value 0");
    }

    // ==================== Test 4: DDL 后缓存失效验证 (INV-5) ====================

    /**
     * 验证 INV-5: DDL 后 tableCache 失效，后续 DML 使用新 schema
     */
    @Test
    void cacheInvalidationAfterDdl() {
        execute("CREATE TABLE CACHE_TEST (ID INT PRIMARY KEY, NAME VARCHAR)");
        execute("INSERT INTO CACHE_TEST (ID, NAME) VALUES (1, 'ALICE')");

        // 触发 tableCache 加载（通过 SELECT）
        List<Row> before = execute("SELECT * FROM CACHE_TEST");
        assertEquals(1, before.size());
        assertEquals(2, before.get(0).columns().size(), "Should have 2 columns before ADD COLUMN");

        // ADD COLUMN → 应失效缓存
        execute("ALTER TABLE CACHE_TEST ADD COLUMN SCORE INT DEFAULT 99");

        // 后续 SELECT 应看到新列（证明缓存已失效并重新加载）
        List<Row> after = execute("SELECT * FROM CACHE_TEST");
        assertEquals(1, after.size());
        assertEquals(99, after.get(0).get("CACHE_TEST.SCORE"),
                "After ADD COLUMN, SELECT should see new column with default value");

        // 插入新行含新列
        execute("INSERT INTO CACHE_TEST (ID, NAME, SCORE) VALUES (2, 'BOB', 88)");
        List<Row> all = execute("SELECT * FROM CACHE_TEST");
        assertEquals(2, all.size());
    }
}
