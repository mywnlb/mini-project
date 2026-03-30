package cn.zhangyis.minidb.server;

import cn.zhangyis.minidb.sql.catalog.StorageCatalog;
import cn.zhangyis.minidb.storage.DatabaseBootstrap;
import cn.zhangyis.minidb.storage.buffer.BufferPool;
import cn.zhangyis.minidb.storage.catalog.CatalogManager;
import cn.zhangyis.minidb.storage.disk.DiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 JDBC + Server + Storage 端到端集成测试。
 *
 * <p>当前 SQL 层尚未提供 CREATE DATABASE 语法，因此测试库通过真实启动链上的
 * {@link CatalogManager#createDatabase(String)} 预先创建；后续建表、增删改查、
 * 关联查询和 PreparedStatement 全部通过 JDBC 执行。</p>
 */
class JdbcStorageIntegrationTest {

    private static final String SYSTEM_SPACE_NAME = "system";
    private static final String TEST_DATABASE = "JDBC_APP";
    private static final String CRUD_USERS_TABLE = "CRUD_USERS";
    private static final String JOIN_USERS_TABLE = "JOIN_USERS";
    private static final String JOIN_ORDERS_TABLE = "JOIN_ORDERS";
    private static final String PS_USERS_TABLE = "PS_USERS";
    private static final String PS_ORDERS_TABLE = "PS_ORDERS";
    private static final String META_USERS_TABLE = "META_USERS";

    private Path dataDir;
    private DiskManager diskManager;
    private BufferPool bufferPool;
    private CatalogManager catalogManager;
    private DatabaseBootstrap bootstrap;
    private MiniDbServer server;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        Path workingDir = Path.of(System.getProperty("user.dir"));
        Path moduleDir = workingDir.endsWith("mini-db") ? workingDir : workingDir.resolve("mini-db");
        Path testRoot = moduleDir.resolve("build").resolve("tmp").resolve("jdbc-storage-tests");
        Files.createDirectories(testRoot);
        dataDir = Files.createTempDirectory(testRoot, "minidb-jdbc-server-");
        diskManager = new DiskManager(dataDir);
        bufferPool = new BufferPool(256, diskManager);
        catalogManager = new CatalogManager(bufferPool);

        bootstrap = new DatabaseBootstrap(diskManager, bufferPool, catalogManager, SYSTEM_SPACE_NAME);
        bootstrap.start();

        if (!catalogManager.hasDatabase(TEST_DATABASE)) {
            catalogManager.createDatabase(TEST_DATABASE);
        }

        port = findFreePort();
        server = new MiniDbServerBuilder()
                .port(port)
                .catalog(new StorageCatalog(catalogManager, TEST_DATABASE, bufferPool))
                .dataSource(null)
                .transactionManager(bootstrap.getTransactionManager())
                .user("root", "")
                .defaultDatabase(TEST_DATABASE)
                .sqlThreadPoolSize(4)
                .build();
        server.startAsync();
        waitForServerReady();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
        if (bootstrap != null) {
            try {
                if (bootstrap.isStarted()) {
                    bootstrap.getTransactionManager().close();
                }
            } catch (Exception ignored) {
            }
        }
        if (bufferPool != null) {
            try {
                bufferPool.close();
            } catch (Exception ignored) {
            }
        }
        if (diskManager != null) {
            try {
                diskManager.close();
            } catch (Exception ignored) {
            }
        }
        if (dataDir != null) {
            try {
                deleteDirectory(dataDir);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void jdbcStatement_canCreateTablesAndCrudData() throws Exception {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT DATABASE()")) {
                assertTrue(rs.next());
                assertEquals(TEST_DATABASE, rs.getString(1).toUpperCase());
            }

            assertEquals(0, stmt.executeUpdate(
                    "CREATE TABLE " + CRUD_USERS_TABLE + " (ID INT PRIMARY KEY, NAME VARCHAR, AGE INT)"));

            assertEquals(1, stmt.executeUpdate(
                    "INSERT INTO " + CRUD_USERS_TABLE + " (ID, NAME, AGE) VALUES (1, 'ALICE', 20)"));
            assertEquals(1, stmt.executeUpdate(
                    "INSERT INTO " + CRUD_USERS_TABLE + " (ID, NAME, AGE) VALUES (2, 'BOB', 22)"));

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT ID, NAME, AGE FROM " + CRUD_USERS_TABLE + " ORDER BY ID")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("ALICE", rs.getString(2));
                assertEquals(20, rs.getInt(3));

                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
                assertEquals("BOB", rs.getString(2));
                assertEquals(22, rs.getInt(3));
                assertFalse(rs.next());
            }

            assertEquals(1, stmt.executeUpdate(
                    "UPDATE " + CRUD_USERS_TABLE + " SET NAME = 'ALICIA', AGE = 21 WHERE ID = 1"));

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT NAME, AGE FROM " + CRUD_USERS_TABLE + " WHERE ID = 1")) {
                assertTrue(rs.next());
                assertEquals("ALICIA", rs.getString(1));
                assertEquals(21, rs.getInt(2));
                assertFalse(rs.next());
            }

            assertEquals(1, stmt.executeUpdate(
                    "DELETE FROM " + CRUD_USERS_TABLE + " WHERE ID = 2"));

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT ID, NAME FROM " + CRUD_USERS_TABLE + " ORDER BY ID")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("ALICIA", rs.getString(2));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void jdbcStatement_canExecuteJoinQuery() throws Exception {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            createBaseTables(stmt, JOIN_USERS_TABLE, JOIN_ORDERS_TABLE);

            stmt.executeUpdate("INSERT INTO " + JOIN_USERS_TABLE + " (ID, NAME, AGE) VALUES (1, 'ALICE', 20)");
            stmt.executeUpdate("INSERT INTO " + JOIN_USERS_TABLE + " (ID, NAME, AGE) VALUES (2, 'BOB', 22)");
            stmt.executeUpdate("INSERT INTO " + JOIN_ORDERS_TABLE + " (ID, USER_ID, AMOUNT) VALUES (101, 1, 99)");
            stmt.executeUpdate("INSERT INTO " + JOIN_ORDERS_TABLE + " (ID, USER_ID, AMOUNT) VALUES (102, 2, 199)");

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT U.NAME, O.AMOUNT " +
                    "FROM " + JOIN_USERS_TABLE + " U JOIN " + JOIN_ORDERS_TABLE + " O ON U.ID = O.USER_ID " +
                    "ORDER BY O.ID")) {
                assertTrue(rs.next());
                assertEquals("ALICE", rs.getString(1));
                assertEquals(99, rs.getInt(2));

                assertTrue(rs.next());
                assertEquals("BOB", rs.getString(1));
                assertEquals(199, rs.getInt(2));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void jdbcPreparedStatement_canInsertUpdateAndQueryWithParameters() throws Exception {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            createBaseTables(stmt, PS_USERS_TABLE, PS_ORDERS_TABLE);

            try (PreparedStatement insertUser = conn.prepareStatement(
                    "INSERT INTO " + PS_USERS_TABLE + " (ID, NAME, AGE) VALUES (?, ?, ?)");
                 PreparedStatement insertOrder = conn.prepareStatement(
                         "INSERT INTO " + PS_ORDERS_TABLE + " (ID, USER_ID, AMOUNT) VALUES (?, ?, ?)");
                 PreparedStatement selectUser = conn.prepareStatement(
                         "SELECT NAME, AGE FROM " + PS_USERS_TABLE + " WHERE ID = ?");
                 PreparedStatement updateUser = conn.prepareStatement(
                         "UPDATE " + PS_USERS_TABLE + " SET AGE = ? WHERE ID = ?");
                 PreparedStatement joinQuery = conn.prepareStatement(
                         "SELECT U.NAME, O.AMOUNT " +
                         "FROM " + PS_USERS_TABLE + " U JOIN " + PS_ORDERS_TABLE + " O ON U.ID = O.USER_ID " +
                         "WHERE O.AMOUNT >= ? ORDER BY O.ID")) {

                insertUser.setInt(1, 1);
                insertUser.setString(2, "CAROL");
                insertUser.setInt(3, 25);
                assertEquals(1, insertUser.executeUpdate());

                insertUser.clearParameters();
                insertUser.setInt(1, 2);
                insertUser.setString(2, "DAVID");
                insertUser.setInt(3, 28);
                assertEquals(1, insertUser.executeUpdate());

                insertOrder.setInt(1, 201);
                insertOrder.setInt(2, 1);
                insertOrder.setInt(3, 120);
                assertEquals(1, insertOrder.executeUpdate());

                insertOrder.clearParameters();
                insertOrder.setInt(1, 202);
                insertOrder.setInt(2, 2);
                insertOrder.setInt(3, 80);
                assertEquals(1, insertOrder.executeUpdate());

                selectUser.setInt(1, 1);
                try (ResultSet rs = selectUser.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("CAROL", rs.getString(1));
                    assertEquals(25, rs.getInt(2));
                    assertFalse(rs.next());
                }

                updateUser.setInt(1, 26);
                updateUser.setInt(2, 1);
                assertEquals(1, updateUser.executeUpdate());

                selectUser.clearParameters();
                selectUser.setInt(1, 1);
                try (ResultSet rs = selectUser.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("CAROL", rs.getString(1));
                    assertEquals(26, rs.getInt(2));
                    assertFalse(rs.next());
                }

                joinQuery.setInt(1, 100);
                try (ResultSet rs = joinQuery.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("CAROL", rs.getString(1));
                    assertEquals(120, rs.getInt(2));
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void jdbcPreparedStatement_canQueryInformationSchemaTables() throws Exception {
        final String infoTable = "INFO_USERS";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE " + infoTable + " (ID INT PRIMARY KEY, NAME VARCHAR)");
            stmt.executeUpdate("INSERT INTO " + infoTable + " (ID, NAME) VALUES (1, 'ALICE')");

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT TABLE_SCHEMA, TABLE_NAME, ENGINE, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH " +
                    "FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
                ps.setString(1, TEST_DATABASE);
                ps.setString(2, infoTable);

                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(TEST_DATABASE, rs.getString(1).toUpperCase());
                    assertEquals(infoTable, rs.getString(2).toUpperCase());
                    assertEquals("mini-db", rs.getString(3));
                    assertTrue(rs.getLong(4) >= 1L);
                    assertEquals(0L, rs.getLong(5));
                    assertEquals(0L, rs.getLong(6));
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void jdbcStatement_showIndex_matchesInformationSchemaStatistics() throws Exception {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE " + META_USERS_TABLE + " (ID INT PRIMARY KEY, EMAIL VARCHAR)");
            stmt.executeUpdate("CREATE UNIQUE INDEX UQ_META_USERS_EMAIL ON " + META_USERS_TABLE + "(EMAIL)");

            List<String> showIndexRows = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery("SHOW INDEX FROM " + META_USERS_TABLE)) {
                ResultSetMetaData meta = rs.getMetaData();
                assertEquals(13, meta.getColumnCount(), "SHOW INDEX 应返回 13 列兼容元数据");
                assertEquals("Table", meta.getColumnLabel(1));
                assertEquals("Key_name", meta.getColumnLabel(3));
                assertEquals("Column_name", meta.getColumnLabel(5));

                while (rs.next()) {
                    showIndexRows.add(rs.getString("Key_name") + "|" + rs.getString("Column_name") + "|"
                            + rs.getInt("Non_unique"));
                }
            }

            List<String> statisticsRows = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE " +
                    "FROM information_schema.STATISTICS " +
                    "WHERE TABLE_SCHEMA = '" + TEST_DATABASE + "' AND TABLE_NAME = '" + META_USERS_TABLE + "' " +
                    "ORDER BY INDEX_NAME, SEQ_IN_INDEX")) {
                while (rs.next()) {
                    statisticsRows.add(rs.getString("INDEX_NAME") + "|" + rs.getString("COLUMN_NAME") + "|"
                            + rs.getInt("NON_UNIQUE"));
                }
            }

            assertEquals(statisticsRows, showIndexRows, "SHOW INDEX 与 information_schema.STATISTICS 必须对齐");
        }
    }

    @Test
    void jdbcPreparedStatement_canQueryConstraintMetadataTables() throws Exception {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE " + META_USERS_TABLE + " (ID INT PRIMARY KEY, EMAIL VARCHAR)");
            stmt.executeUpdate("CREATE UNIQUE INDEX UQ_META_USERS_EMAIL ON " + META_USERS_TABLE + "(EMAIL)");

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT CONSTRAINT_NAME, COLUMN_NAME " +
                    "FROM information_schema.KEY_COLUMN_USAGE " +
                    "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? " +
                    "ORDER BY CONSTRAINT_NAME, ORDINAL_POSITION")) {
                ps.setString(1, TEST_DATABASE);
                ps.setString(2, META_USERS_TABLE);

                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    assertEquals(2, meta.getColumnCount());
                    assertEquals("CONSTRAINT_NAME", meta.getColumnLabel(1));
                    assertEquals("COLUMN_NAME", meta.getColumnLabel(2));

                    assertTrue(rs.next());
                    assertEquals("PRIMARY", rs.getString(1));
                    assertEquals("ID", rs.getString(2));

                    assertTrue(rs.next());
                    assertEquals("UQ_META_USERS_EMAIL", rs.getString(1));
                    assertEquals("EMAIL", rs.getString(2));
                    assertFalse(rs.next());
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE " +
                    "FROM information_schema.TABLE_CONSTRAINTS " +
                    "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? " +
                    "ORDER BY CONSTRAINT_NAME")) {
                ps.setString(1, TEST_DATABASE);
                ps.setString(2, META_USERS_TABLE);

                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    assertEquals(2, meta.getColumnCount());
                    assertEquals("CONSTRAINT_NAME", meta.getColumnLabel(1));
                    assertEquals("CONSTRAINT_TYPE", meta.getColumnLabel(2));

                    assertTrue(rs.next());
                    assertEquals("PRIMARY", rs.getString(1));
                    assertEquals("PRIMARY KEY", rs.getString(2));

                    assertTrue(rs.next());
                    assertEquals("UQ_META_USERS_EMAIL", rs.getString(1));
                    assertEquals("UNIQUE", rs.getString(2));
                    assertFalse(rs.next());
                }
            }
        }
    }

    private void createBaseTables(Statement stmt, String usersTable, String ordersTable) throws SQLException {
        assertEquals(0, stmt.executeUpdate(
                "CREATE TABLE " + usersTable + " (ID INT PRIMARY KEY, NAME VARCHAR, AGE INT)"));
        assertEquals(0, stmt.executeUpdate(
                "CREATE TABLE " + ordersTable + " (ID INT PRIMARY KEY, USER_ID INT, AMOUNT INT)"));
    }

    private Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", "root");
        props.setProperty("password", "");
        return DriverManager.getConnection(
                "jdbc:minidb://127.0.0.1:" + port + "/" + TEST_DATABASE, props);
    }

    private void waitForServerReady() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        SQLException lastError = null;

        while (System.nanoTime() < deadline) {
            try (Connection ignored = getConnection()) {
                return;
            } catch (SQLException e) {
                lastError = e;
                Thread.sleep(100);
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        throw new IllegalStateException("mini-db server did not become ready in time");
    }

    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void deleteDirectory(Path root) throws IOException {
        Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
    }
}
