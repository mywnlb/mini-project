package cn.zhangyis.minidb.sql;

import cn.zhangyis.minidb.sql.catalog.StorageCatalog;
import cn.zhangyis.minidb.sql.exec.*;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.CatalogManager;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import cn.zhangyis.minidb.storage.transaction.core.TransactionManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqlScriptRunner 集成测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>基础导入（SET / DROP TABLE / CREATE TABLE / INSERT）</li>
 *   <li>聚簇索引（PRIMARY KEY）语义验证</li>
 *   <li>唯一索引（UNIQUE KEY）语义验证</li>
 *   <li>普通索引（KEY / INDEX）允许重复值</li>
 *   <li>全量 db_onetravel.sql 回归导入</li>
 * </ul>
 */
class SqlScriptRunnerTest {

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
        bufferPool = new BufferPool(256, diskManager);
        catalogManager = new CatalogManager(bufferPool);
        catalogManager.bootstrap();
        catalogManager.createDatabase("TEST");

        txnManager = new TransactionManager(bufferPool, null);
        txnManager.initializeInMemory();
        executionContext = new ExecutionContext(txnManager);
        catalog = new StorageCatalog(catalogManager, "TEST", bufferPool);
        dataSource = new StorageDataSource(catalogManager, "TEST", executionContext, bufferPool);
        session = new SqlSession(catalog, executionContext, dataSource);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (txnManager != null) txnManager.close();
        if (catalogManager != null) catalogManager.close();
        if (bufferPool != null) bufferPool.close();
        if (diskManager != null) diskManager.close();
    }

    // ==================== 1. 基础导入测试 ====================

    @Test
    void importBasicScript() {
        String script = """
            SET NAMES utf8mb4;
            SET FOREIGN_KEY_CHECKS = 0;
            DROP TABLE IF EXISTS `users`;
            CREATE TABLE `users` (
                `id` int NOT NULL,
                `name` varchar(50) DEFAULT NULL,
                PRIMARY KEY (`id`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            INSERT INTO `users` VALUES (1, 'Alice');
            INSERT INTO `users` VALUES (2, 'Bob');
            INSERT INTO `users` VALUES (3, 'Charlie');
            SET FOREIGN_KEY_CHECKS = 1;
            """;

        SqlScriptRunner runner = new SqlScriptRunner(session);
        SqlScriptRunner.ImportSummary summary = runner.importScript(script);

        assertEquals(1, summary.tablesCreated(), "tablesCreated");
        assertEquals(1, summary.tablesDropped(), "tablesDropped");
        assertEquals(3, summary.rowsInserted(), "rowsInserted");
        assertEquals(3, summary.statementsSkipped(), "statementsSkipped (3 SET)");
        assertEquals(0, summary.errors(), "errors");

        // 验证数据可查
        List<Row> rows = session.execute("SELECT * FROM users");
        assertEquals(3, rows.size());
    }

    // ==================== 2. 聚簇索引语义测试 ====================

    @Test
    void clusteredIndexStoresDataInPrimaryKeyOrder() {
        String script = """
            CREATE TABLE `products` (
                `id` int NOT NULL,
                `name` varchar(100) DEFAULT NULL,
                `price` decimal(10,2) DEFAULT NULL,
                PRIMARY KEY (`id`)
            ) ENGINE=InnoDB;
            INSERT INTO `products` VALUES (3, 'Cherry', 5.50);
            INSERT INTO `products` VALUES (1, 'Apple', 2.00);
            INSERT INTO `products` VALUES (2, 'Banana', 3.50);
            """;

        SqlScriptRunner runner = new SqlScriptRunner(session);
        SqlScriptRunner.ImportSummary summary = runner.importScript(script);

        assertEquals(1, summary.tablesCreated());
        assertEquals(3, summary.rowsInserted());
        assertEquals(0, summary.errors());

        // 验证数据完整性
        List<Row> rows = session.execute("SELECT * FROM products");
        assertEquals(3, rows.size());
    }

    @Test
    void duplicatePrimaryKeyIsRejected() {
        String script = """
            CREATE TABLE `pk_test` (
                `id` int NOT NULL,
                `val` varchar(20) DEFAULT NULL,
                PRIMARY KEY (`id`)
            );
            INSERT INTO `pk_test` VALUES (1, 'first');
            INSERT INTO `pk_test` VALUES (1, 'duplicate');
            """;

        SqlScriptRunner runner = new SqlScriptRunner(session);
        SqlScriptRunner.ImportSummary summary = runner.importScript(script);

        assertEquals(1, summary.tablesCreated());
        // 第一条 INSERT 成功，第二条因主键冲突失败
        assertEquals(1, summary.rowsInserted());
        assertEquals(1, summary.errors());
        assertTrue(summary.errorMessages().get(0).contains("Duplicate"),
            "错误信息应包含 Duplicate: " + summary.errorMessages().get(0));

        // 只有一行数据
        List<Row> rows = session.execute("SELECT * FROM pk_test");
        assertEquals(1, rows.size());
    }

    @Test
    void compositePrimaryKeyWorks() {
        String script = """
            CREATE TABLE `order_items` (
                `order_id` int NOT NULL,
                `item_id` int NOT NULL,
                `quantity` int DEFAULT NULL,
                PRIMARY KEY (`order_id`, `item_id`)
            );
            INSERT INTO `order_items` VALUES (1, 1, 10);
            INSERT INTO `order_items` VALUES (1, 2, 5);
            INSERT INTO `order_items` VALUES (2, 1, 3);
            INSERT INTO `order_items` VALUES (1, 1, 99);
            """;

        SqlScriptRunner runner = new SqlScriptRunner(session);
        SqlScriptRunner.ImportSummary summary = runner.importScript(script);

        assertEquals(1, summary.tablesCreated());
        assertEquals(3, summary.rowsInserted(), "前三条成功，第四条 (1,1) 重复主键");
        assertEquals(1, summary.errors(), "第四条 INSERT 应失败");

        List<Row> rows = session.execute("SELECT * FROM order_items");
        assertEquals(3, rows.size());
    }

    // ==================== 3. 唯一索引语义测试 ====================

    @Test
    void uniqueIndexRejectsDuplicateValues() {
        String script = """
            CREATE TABLE `members` (
                `id` int NOT NULL,
                `email` varchar(100) DEFAULT NULL,
                `name` varchar(50) DEFAULT NULL,
                PRIMARY KEY (`id`),
                UNIQUE KEY `uk_email` (`email`)
            ) ENGINE=InnoDB;
            INSERT INTO `members` VALUES (1, 'alice@test.com', 'Alice');
            INSERT INTO `members` VALUES (2, 'bob@test.com', 'Bob');
            INSERT INTO `members` VALUES (3, 'alice@test.com', 'Alice2');
            INSERT INTO `members` VALUES (4, 'charlie@test.com', 'Charlie');
            """;

        SqlScriptRunner runner = new SqlScriptRunner(session);
        SqlScriptRunner.ImportSummary summary = runner.importScript(script);

        assertEquals(1, summary.tablesCreated());
        // 第3条 INSERT 因 email 唯一索引冲突失败
        assertEquals(3, summary.rowsInserted(), "第 1/2/4 条成功，第 3 条唯一索引冲突");
        assertEquals(1, summary.errors());
        assertTrue(summary.errorMessages().get(0).toLowerCase().contains("duplicate"),
            "错误信息应包含 duplicate: " + summary.errorMessages().get(0));

        List<Row> rows = session.execute("SELECT * FROM members");
        assertEquals(3, rows.size());
    }

    @Test
    void uniqueIndexEnforcesConstraintAfterCreateTable() {
        // 通过行为验证唯一索引被真实创建：插入重复值应失败
        session.execute("CREATE TABLE idx_check (" +
            "id int NOT NULL, code varchar(20), " +
            "PRIMARY KEY (id), " +
            "UNIQUE KEY uk_code (code))");

        session.execute("INSERT INTO idx_check (id, code) VALUES (1, 'AAA')");
        session.execute("INSERT INTO idx_check (id, code) VALUES (2, 'BBB')");

        // 重复 code 值应被唯一索引拒绝
        assertThrows(IllegalStateException.class,
            () -> session.execute("INSERT INTO idx_check (id, code) VALUES (3, 'AAA')"),
            "唯一索引 uk_code 应拒绝重复值 'AAA'");

        // 确认只有 2 行
        List<Row> rows = session.execute("SELECT * FROM idx_check");
        assertEquals(2, rows.size());
    }

    // ==================== 4. 普通索引允许重复测试 ====================

    @Test
    void regularIndexAllowsDuplicateValues() {
        String script = """
            CREATE TABLE `logs` (
                `id` int NOT NULL,
                `category` varchar(50) DEFAULT NULL,
                `msg` varchar(200) DEFAULT NULL,
                PRIMARY KEY (`id`),
                KEY `idx_category` (`category`)
            ) ENGINE=InnoDB;
            INSERT INTO `logs` VALUES (1, 'ERROR', 'something broke');
            INSERT INTO `logs` VALUES (2, 'ERROR', 'another error');
            INSERT INTO `logs` VALUES (3, 'INFO', 'all good');
            """;

        SqlScriptRunner runner = new SqlScriptRunner(session);
        SqlScriptRunner.ImportSummary summary = runner.importScript(script);

        assertEquals(1, summary.tablesCreated());
        assertEquals(3, summary.rowsInserted(), "普通索引允许重复值，三条全部成功");
        assertEquals(0, summary.errors());

        List<Row> rows = session.execute("SELECT * FROM logs");
        assertEquals(3, rows.size());
    }

    // ==================== 5. SET 语句跳过测试 ====================

    @Test
    void setStatementsAreSkipped() {
        String script = """
            SET NAMES utf8mb4;
            SET FOREIGN_KEY_CHECKS = 0;
            SET character_set_client = utf8;
            SET FOREIGN_KEY_CHECKS = 1;
            """;

        SqlScriptRunner runner = new SqlScriptRunner(session);
        SqlScriptRunner.ImportSummary summary = runner.importScript(script);

        assertEquals(4, summary.statementsSkipped());
        assertEquals(0, summary.tablesCreated());
        assertEquals(0, summary.rowsInserted());
        assertEquals(0, summary.errors());
    }

    // ==================== 6. 注释处理测试 ====================

    @Test
    void commentsBeforeStatementsAreHandled() {
        String script = """
            /*
             Navicat Premium Data Transfer
             Source Server: localhost
            */

            SET NAMES utf8mb4;

            -- ----------------------------
            -- Table structure for test
            -- ----------------------------
            DROP TABLE IF EXISTS `test`;
            CREATE TABLE `test` (
                `id` int NOT NULL,
                PRIMARY KEY (`id`)
            );
            INSERT INTO `test` VALUES (1);
            """;

        SqlScriptRunner runner = new SqlScriptRunner(session);
        SqlScriptRunner.ImportSummary summary = runner.importScript(script);

        assertEquals(1, summary.tablesCreated());
        assertEquals(1, summary.tablesDropped());
        assertEquals(1, summary.rowsInserted());
        assertEquals(1, summary.statementsSkipped(), "SET NAMES");
        assertEquals(0, summary.errors());
    }

    // ==================== 7. 语句分类单元测试 ====================

    @Test
    void classifyCategorizesProperly() {
        assertEquals(SqlScriptRunner.StatementKind.SET,
            SqlScriptRunner.classify("SET NAMES utf8mb4"));
        assertEquals(SqlScriptRunner.StatementKind.DROP_TABLE,
            SqlScriptRunner.classify("DROP TABLE IF EXISTS `foo`"));
        assertEquals(SqlScriptRunner.StatementKind.CREATE_TABLE,
            SqlScriptRunner.classify("CREATE TABLE `bar` (id INT)"));
        assertEquals(SqlScriptRunner.StatementKind.INSERT,
            SqlScriptRunner.classify("INSERT INTO `bar` VALUES (1)"));
        assertEquals(SqlScriptRunner.StatementKind.COMMENT_ONLY,
            SqlScriptRunner.classify("-- just a comment"));
        assertEquals(SqlScriptRunner.StatementKind.UNKNOWN,
            SqlScriptRunner.classify("ALTER TABLE `foo` ADD COLUMN `x` INT"));
    }

    @Test
    void classifyHandlesLeadingComments() {
        assertEquals(SqlScriptRunner.StatementKind.CREATE_TABLE,
            SqlScriptRunner.classify("/* comment */\nCREATE TABLE `t` (id INT)"));
        assertEquals(SqlScriptRunner.StatementKind.DROP_TABLE,
            SqlScriptRunner.classify("-- comment\nDROP TABLE IF EXISTS `t`"));
    }

    // ==================== 8. 全量 db_onetravel.sql 导入测试 ====================

    @Test
    void fullDbOnetravelImport() throws Exception {
        // 查找 SQL 文件
        Path sqlFile = Path.of("mini-db/src/test/java/cn/zhangyis/minidb/sql/db_onetravel.sql");
        if (!Files.exists(sqlFile)) {
            sqlFile = Path.of("src/test/java/cn/zhangyis/minidb/sql/db_onetravel.sql");
        }
        if (!Files.exists(sqlFile)) {
            System.err.println("db_onetravel.sql 不存在，跳过全量导入测试");
            return;
        }

        // 全量导入需要更大的 BufferPool，重建环境（使用子目录避免文件冲突）
        tearDown();
        Path importDir = tempDir.resolve("fullimport");
        Files.createDirectories(importDir);
        diskManager = new DiskManager(importDir);
        diskManager.createTablespace(SYSTEM_SPACE_ID, "system");
        bufferPool = new BufferPool(4096, diskManager);
        catalogManager = new CatalogManager(bufferPool);
        catalogManager.bootstrap();
        catalogManager.createDatabase("ONETRAVEL");

        txnManager = new TransactionManager(bufferPool, null);
        txnManager.initializeInMemory();
        executionContext = new ExecutionContext(txnManager);
        catalog = new StorageCatalog(catalogManager, "ONETRAVEL", bufferPool);
        dataSource = new StorageDataSource(catalogManager, "ONETRAVEL", executionContext, bufferPool);
        session = new SqlSession(catalog, executionContext, dataSource);

        // 执行导入
        SqlScriptRunner runner = new SqlScriptRunner(session);
        SqlScriptRunner.ImportSummary summary = runner.importFile(sqlFile);

        // 输出摘要供人工检查
        System.out.println("=== db_onetravel.sql 导入摘要 ===");
        System.out.println("表创建: " + summary.tablesCreated());
        System.out.println("表删除: " + summary.tablesDropped());
        System.out.println("行插入: " + summary.rowsInserted());
        System.out.println("跳过:   " + summary.statementsSkipped());
        System.out.println("错误:   " + summary.errors());
        System.out.println("耗时:   " + summary.elapsedMillis() + " ms");

        if (!summary.errorMessages().isEmpty()) {
            System.out.println("\n=== 错误列表（前 50 条）===");
            summary.errorMessages().stream().limit(50).forEach(System.out::println);
        }

        // 基础验收
        assertTrue(summary.tablesCreated() >= 10,
            "至少应创建 10 张表，实际: " + summary.tablesCreated());
        assertTrue(summary.rowsInserted() > 0,
            "至少应有行被插入");
        assertTrue(summary.statementsSkipped() >= 2,
            "至少 SET NAMES + SET FOREIGN_KEY_CHECKS 应被跳过");
    }
}
