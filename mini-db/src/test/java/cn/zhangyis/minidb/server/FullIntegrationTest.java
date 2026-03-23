package cn.zhangyis.minidb.server;

import cn.zhangyis.minidb.server.auth.UserManager;
import cn.zhangyis.minidb.sql.catalog.*;
import cn.zhangyis.minidb.sql.exec.DataSourceSpi;
import cn.zhangyis.minidb.sql.exec.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6 测试：端到端集成。
 * 启动服务器 → JDBC 连接 → 系统变量查询 + 多连接。
 */
class FullIntegrationTest {

    private static final int TEST_PORT = 13310;
    private static MiniDbServer server;

    @BeforeAll
    static void startServer() throws Exception {
        UserManager userManager = new UserManager();
        userManager.addUser("root", "");
        userManager.addUser("admin", "admin123");

        server = new MiniDbServerBuilder()
                .port(TEST_PORT)
                .catalog(new StubCatalog())
                .dataSource(new StubDataSource())
                .userManager(userManager)
                .sqlThreadPoolSize(4)
                .build();
        server.startAsync();
        Thread.sleep(500);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop();
    }

    private Connection getConnection(String user, String password) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        return DriverManager.getConnection("jdbc:minidb://127.0.0.1:" + TEST_PORT + "/test", props);
    }

    // ==================== 端到端查询 ====================

    @Test
    void endToEnd_selectVersionComment() throws Exception {
        try (Connection conn = getConnection("root", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT @@version_comment")) {
            assertTrue(rs.next());
            assertNotNull(rs.getString(1));
        }
    }

    @Test
    void endToEnd_selectVersion() throws Exception {
        try (Connection conn = getConnection("root", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT @@version")) {
            assertTrue(rs.next());
            assertTrue(rs.getString(1).contains("minidb"));
        }
    }

    @Test
    void endToEnd_setNamesAndSelectDatabase() throws Exception {
        try (Connection conn = getConnection("root", "");
             Statement stmt = conn.createStatement()) {
            // SET NAMES
            assertFalse(stmt.execute("SET NAMES utf8mb4"));

            // SELECT DATABASE()
            try (ResultSet rs = stmt.executeQuery("SELECT DATABASE()")) {
                assertTrue(rs.next());
                assertNotNull(rs.getString(1));
            }
        }
    }

    // ==================== 多用户认证 ====================

    @Test
    void endToEnd_authenticateWithPassword() throws Exception {
        try (Connection conn = getConnection("admin", "admin123");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT @@version")) {
            assertTrue(rs.next());
        }
    }

    @Test
    void endToEnd_wrongPassword_throwsSQLException() {
        assertThrows(SQLException.class, () -> getConnection("admin", "wrongpass"));
    }

    // ==================== 多连接 ====================

    @Test
    void endToEnd_multipleSequentialConnections() throws Exception {
        for (int i = 0; i < 5; i++) {
            try (Connection conn = getConnection("root", "");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT @@version")) {
                assertTrue(rs.next());
            }
        }
    }

    // ==================== 连接关闭 ====================

    @Test
    void endToEnd_connectionClose_isClosed() throws Exception {
        Connection conn = getConnection("root", "");
        assertFalse(conn.isClosed());
        conn.close();
        assertTrue(conn.isClosed());
    }

    // ==================== Stub 实现 ====================

    static class StubCatalog implements CatalogSpi {
        @Override public TableMeta getTable(String name) { return null; }
        @Override public List<String> listTables(String database) { return List.of(); }
        @Override public List<ColumnMeta> getColumns(String name) { return List.of(); }
        @Override public boolean tableExists(String name) { return false; }
        @Override public void createTable(TableMeta table) {}
        @Override public void dropTable(String name) {}
        @Override public void addColumn(String name, ColumnMeta col) {}
        @Override public void createIndex(IndexMeta index) {}
        @Override public void dropIndex(String table, String index) {}
        @Override public List<IndexMeta> getIndexes(String name) { return List.of(); }
    }

    static class StubDataSource implements DataSourceSpi {
        @Override public Iterator<Row> scan(String tableName) { return Collections.emptyIterator(); }
        @Override public void insertRow(String tableName, Row row) {}
        @Override public int updateRows(String tableName, Predicate<Row> f, Consumer<Row> u) { return 0; }
        @Override public int deleteRows(String tableName, Predicate<Row> f) { return 0; }
    }
}
