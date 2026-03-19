package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.CatalogManager;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.mtr.MiniTransaction;
import cn.zhangyis.minidb.storage.transaction.core.Transaction;
import cn.zhangyis.minidb.storage.transaction.core.TransactionManager;
import cn.zhangyis.minidb.storage.transaction.undo.UndoLogManager;
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
 * MVCC Scan 集成测试。
 *
 * <p>验证 StorageDataSource 的 MVCC 可见性过滤在 SQL 层的正确性。
 * 使用多会话（多 ExecutionContext）模拟并发事务场景。</p>
 *
 * <p>搭建真实 UndoLogManager 以支持版本链遍历（UPDATE/DELETE 后的旧版本可见性）。</p>
 */
class MvccScanIntegrationTest {

    private static final int SYSTEM_SPACE_ID = 0;
    private static final int UNDO_SPACE_ID = 9999;

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool bufferPool;
    private CatalogManager catalogManager;
    private UndoLogManager undoLogManager;
    private TransactionManager txnManager;

    // Session A
    private ExecutionContext ctxA;
    private StorageDataSource dsA;
    private SqlSession sessionA;

    // Session B
    private ExecutionContext ctxB;
    private StorageDataSource dsB;
    private SqlSession sessionB;

    @BeforeEach
    void setUp() throws Exception {
        diskManager = new DiskManager(tempDir);
        diskManager.createTablespace(SYSTEM_SPACE_ID, "system");
        diskManager.createTablespace(UNDO_SPACE_ID, "undo");
        bufferPool = new BufferPool(256, diskManager);
        catalogManager = new CatalogManager(bufferPool);
        catalogManager.bootstrap();
        catalogManager.createDatabase("APP");

        // 初始化 Undo 表空间并创建 UndoLogManager
        try (MiniTransaction mtr = new MiniTransaction(bufferPool)) {
            UndoLogManager.initializeUndoTablespace(mtr, bufferPool, UNDO_SPACE_ID);
            mtr.commit();
        }
        undoLogManager = new UndoLogManager(bufferPool, UNDO_SPACE_ID, 4);

        txnManager = new TransactionManager(bufferPool, undoLogManager);
        txnManager.initializeInMemory();

        // 创建两个独立会话，共享同一 TransactionManager 和存储
        StorageCatalog catalogA = new StorageCatalog(catalogManager, "APP", bufferPool);
        ctxA = new ExecutionContext(txnManager);
        dsA = new StorageDataSource(catalogManager, "APP", ctxA, bufferPool);
        sessionA = new SqlSession(catalogA, ctxA, dsA);

        StorageCatalog catalogB = new StorageCatalog(catalogManager, "APP", bufferPool);
        ctxB = new ExecutionContext(txnManager);
        dsB = new StorageDataSource(catalogManager, "APP", ctxB, bufferPool);
        sessionB = new SqlSession(catalogB, ctxB, dsB);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (txnManager != null) txnManager.close();
        if (catalogManager != null) catalogManager.close();
        if (bufferPool != null) bufferPool.close();
        if (diskManager != null) diskManager.close();
    }

    // ==================== RR 隔离级别测试 ====================

    /**
     * RR 隔离：Session A 创建 ReadView 后，Session B 提交的新行对 Session A 不可见。
     *
     * 时间线：
     * 1. DDL: CREATE TABLE (auto-commit)
     * 2. Session A: BEGIN (T_A)
     * 3. Session A: SELECT → 触发 ReadView 创建（空表）
     * 4. Session B: INSERT + auto-commit (T_B，TRX_ID > T_A 的 ReadView.lowLimitId)
     * 5. Session A: SELECT → 仍看不到 Session B 的数据（RR 复用 ReadView）
     * 6. Session A: COMMIT
     * 7. Session A: SELECT → 新事务，新 ReadView，能看到 Session B 的数据
     */
    @Test
    void rrIsolation_sessionACannotSeeRowsCommittedBySessionBAfterReadViewCreation() {
        execA("CREATE TABLE T1 (ID INT PRIMARY KEY, NAME VARCHAR)");

        // Session A: 开启显式事务，触发 ReadView
        execA("BEGIN");
        List<Row> rows = execA("SELECT * FROM T1");
        assertEquals(0, rows.size(), "表应为空");

        // Session B: 插入一行并自动提交
        execB("INSERT INTO T1 (ID, NAME) VALUES (1, 'FROM_B')");

        // Session A: 在 RR 隔离下，复用 ReadView，看不到 Session B 的数据
        rows = execA("SELECT * FROM T1");
        assertEquals(0, rows.size(), "RR 隔离下，Session A 不应看到 Session B 提交的数据");

        execA("COMMIT");

        // Session A: 新事务（新 ReadView），现在能看到 Session B 的数据
        rows = execA("SELECT * FROM T1");
        assertEquals(1, rows.size(), "新事务应能看到 Session B 已提交的数据");
        assertEquals("FROM_B", rows.get(0).get("NAME"));
    }

    /**
     * RR 隔离：Session A 能看到在 ReadView 之前已提交的数据。
     */
    @Test
    void rrIsolation_sessionACanSeeRowsCommittedBeforeReadViewCreation() {
        execA("CREATE TABLE T2 (ID INT PRIMARY KEY, VAL INT)");

        // Session B: 先插入并提交
        execB("INSERT INTO T2 (ID, VAL) VALUES (1, 100)");
        execB("INSERT INTO T2 (ID, VAL) VALUES (2, 200)");

        // Session A: 开启显式事务，此时 B 已提交
        execA("BEGIN");
        List<Row> rows = execA("SELECT * FROM T2");
        assertEquals(2, rows.size(), "Session A 应看到 ReadView 之前已提交的数据");

        // Session B: 再插入一行
        execB("INSERT INTO T2 (ID, VAL) VALUES (3, 300)");

        // Session A: RR 隔离下看不到新插入
        rows = execA("SELECT * FROM T2");
        assertEquals(2, rows.size(), "RR 隔离下，不应看到 ReadView 之后的新插入");

        execA("COMMIT");

        // 新事务：全部可见
        rows = execA("SELECT * FROM T2");
        assertEquals(3, rows.size());
    }

    /**
     * RR 隔离：Session A 看不到 Session B 在 ReadView 之后提交的 UPDATE。
     * 需要版本链遍历找到旧版本。
     */
    @Test
    void rrIsolation_sessionACannotSeeUpdatesCommittedAfterReadView() {
        execA("CREATE TABLE T3 (ID INT PRIMARY KEY, NAME VARCHAR)");
        execA("INSERT INTO T3 (ID, NAME) VALUES (1, 'ORIGINAL')");

        // Session A: 开启显式事务，创建 ReadView
        execA("BEGIN");
        List<Row> rows = execA("SELECT * FROM T3");
        assertEquals(1, rows.size());
        assertEquals("ORIGINAL", rows.get(0).get("NAME"));

        // Session B: 更新并提交
        execB("UPDATE T3 SET NAME = 'UPDATED' WHERE ID = 1");

        // Session A: RR 隔离下仍看到旧值（通过版本链回溯）
        rows = execA("SELECT * FROM T3");
        assertEquals(1, rows.size());
        assertEquals("ORIGINAL", rows.get(0).get("NAME"),
            "RR 隔离下，Session A 应看到 ReadView 时刻的快照值");

        execA("COMMIT");

        // 新事务：看到更新后的值
        rows = execA("SELECT * FROM T3");
        assertEquals(1, rows.size());
        assertEquals("UPDATED", rows.get(0).get("NAME"));
    }

    /**
     * RR 隔离：Session A 看不到 Session B 在 ReadView 之后提交的 DELETE。
     * 需要版本链遍历找到删除前的旧版本。
     */
    @Test
    void rrIsolation_sessionACannotSeeDeletesCommittedAfterReadView() {
        execA("CREATE TABLE T4 (ID INT PRIMARY KEY, NAME VARCHAR)");
        execA("INSERT INTO T4 (ID, NAME) VALUES (1, 'ALICE')");
        execA("INSERT INTO T4 (ID, NAME) VALUES (2, 'BOB')");

        // Session A: 开启显式事务
        execA("BEGIN");
        assertEquals(2, execA("SELECT * FROM T4").size());

        // Session B: 删除并提交
        execB("DELETE FROM T4 WHERE ID = 1");

        // Session A: RR 隔离下仍看到 2 行（通过版本链回溯）
        assertEquals(2, execA("SELECT * FROM T4").size(),
            "RR 隔离下，Session A 应看不到 ReadView 之后的删除");

        execA("COMMIT");

        // 新事务：只看到 1 行
        assertEquals(1, execA("SELECT * FROM T4").size());
    }

    // ==================== Overlay + MVCC 混合测试 ====================

    /**
     * Overlay 的 read-your-writes：事务内未提交的写入对自己可见。
     */
    @Test
    void overlayReadYourWrites_uncommittedInsertVisibleToSelf() {
        execA("CREATE TABLE T5 (ID INT PRIMARY KEY, NAME VARCHAR)");

        execA("BEGIN");
        execA("INSERT INTO T5 (ID, NAME) VALUES (1, 'ALICE')");

        // 未提交，但 overlay 保证自己能看到
        List<Row> rows = execA("SELECT * FROM T5");
        assertEquals(1, rows.size());
        assertEquals("ALICE", rows.get(0).get("NAME"));

        // Session B: 看不到 Session A 未提交的数据
        rows = execB("SELECT * FROM T5");
        assertEquals(0, rows.size(), "Session B 不应看到 Session A 未提交的数据");

        execA("COMMIT");

        // Session B: 现在能看到
        rows = execB("SELECT * FROM T5");
        assertEquals(1, rows.size());
    }

    /**
     * Overlay + MVCC 合并：事务内能同时看到自己的未提交写入和已提交的历史数据。
     */
    @Test
    void overlayAndMvccMerge_bothVisibleInSameTransaction() {
        execA("CREATE TABLE T6 (ID INT PRIMARY KEY, NAME VARCHAR)");
        execA("INSERT INTO T6 (ID, NAME) VALUES (1, 'COMMITTED_ROW')");

        execA("BEGIN");
        // 已提交行通过 MVCC 可见
        assertEquals(1, execA("SELECT * FROM T6").size());

        // overlay 中新增行
        execA("INSERT INTO T6 (ID, NAME) VALUES (2, 'PENDING_ROW')");

        // 两行都可见：1 通过 MVCC，2 通过 overlay
        List<Row> rows = execA("SELECT * FROM T6");
        assertEquals(2, rows.size());

        execA("COMMIT");
    }

    // ==================== 向后兼容测试 ====================

    /**
     * 基本 CRUD 正常工作。
     */
    @Test
    void basicCrudWorks() {
        execA("CREATE TABLE T7 (ID INT PRIMARY KEY, NAME VARCHAR)");

        // INSERT + SELECT
        execA("INSERT INTO T7 (ID, NAME) VALUES (1, 'ALICE')");
        execA("INSERT INTO T7 (ID, NAME) VALUES (2, 'BOB')");
        assertEquals(2, execA("SELECT * FROM T7").size());

        // UPDATE + SELECT
        execA("UPDATE T7 SET NAME = 'ALLY' WHERE ID = 1");
        List<Row> rows = execA("SELECT * FROM T7 WHERE ID = 1");
        assertEquals(1, rows.size());
        assertEquals("ALLY", rows.get(0).get("NAME"));

        // DELETE + SELECT
        execA("DELETE FROM T7 WHERE ID = 2");
        assertEquals(1, execA("SELECT * FROM T7").size());
    }

    /**
     * 显式事务 ROLLBACK 时，overlay 被丢弃，BTree 未被修改。
     */
    @Test
    void rollbackDiscardsOverlayChanges() {
        execA("CREATE TABLE T8 (ID INT PRIMARY KEY, NAME VARCHAR)");
        execA("INSERT INTO T8 (ID, NAME) VALUES (1, 'ORIGINAL')");

        execA("BEGIN");
        execA("INSERT INTO T8 (ID, NAME) VALUES (2, 'WILL_ROLLBACK')");
        assertEquals(2, execA("SELECT * FROM T8").size());
        execA("ROLLBACK");

        assertEquals(1, execA("SELECT * FROM T8").size());
    }

    // ==================== Lookup (点查) MVCC 测试 ====================

    /**
     * 索引点查也应遵循 MVCC 可见性。
     */
    @Test
    void lookupRespectsReadViewVisibility() {
        execA("CREATE TABLE T9 (ID INT PRIMARY KEY, NAME VARCHAR)");
        execA("INSERT INTO T9 (ID, NAME) VALUES (1, 'ALICE')");

        // Session A: 开启显式事务，创建 ReadView
        execA("BEGIN");
        List<Row> rows = execA("SELECT * FROM T9 WHERE ID = 1");
        assertEquals(1, rows.size());
        assertEquals("ALICE", rows.get(0).get("NAME"));

        // Session B: 插入新行并提交
        execB("INSERT INTO T9 (ID, NAME) VALUES (2, 'BOB')");

        // Session A: 点查 ID=2，在 RR 下应看不到
        rows = execA("SELECT * FROM T9 WHERE ID = 2");
        assertEquals(0, rows.size(), "RR 隔离下，点查也应遵循 ReadView 可见性");

        execA("COMMIT");

        // 新事务：能看到
        rows = execA("SELECT * FROM T9 WHERE ID = 2");
        assertEquals(1, rows.size());
    }

    // ==================== 辅助方法 ====================

    private List<Row> execA(String sql) {
        return sessionA.execute(sql);
    }

    private List<Row> execB(String sql) {
        return sessionB.execute(sql);
    }
}
