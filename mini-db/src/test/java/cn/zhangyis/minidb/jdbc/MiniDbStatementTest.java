package cn.zhangyis.minidb.jdbc;

import cn.zhangyis.minidb.server.MiniDbServer;
import cn.zhangyis.minidb.server.MiniDbServerBuilder;
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
 * Phase 5 测试：JDBC Statement。
 * 启动嵌入式服务器 → JDBC 连接 → 执行查询验证。
 */
class MiniDbStatementTest {

    private static final int TEST_PORT = 13309;
    private static MiniDbServer server;

    @BeforeAll
    static void startServer() throws Exception {
        UserManager userManager = new UserManager();
        userManager.addUser("root", "");

        server = new MiniDbServerBuilder()
                .port(TEST_PORT)
                .catalog(new StubCatalog())
                .dataSource(new StubDataSource())
                .userManager(userManager)
                .sqlThreadPoolSize(2)
                .build();
        server.startAsync();
        Thread.sleep(500);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop();
    }

    private Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", "root");
        props.setProperty("password", "");
        return DriverManager.getConnection("jdbc:minidb://127.0.0.1:" + TEST_PORT + "/test", props);
    }

    // ==================== 连接测试 ====================

    @Test
    void connect_andClose() throws Exception {
        try (Connection conn = getConnection()) {
            assertFalse(conn.isClosed());
        }
    }

    @Test
    void connect_isValid() throws Exception {
        try (Connection conn = getConnection()) {
            assertTrue(conn.isValid(1));
        }
    }

    // ==================== 系统变量查询 ====================

    @Test
    void executeQuery_selectVersionComment() throws Exception {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT @@version_comment")) {
            assertTrue(rs.next(), "应有一行结果");
            String value = rs.getString(1);
            assertNotNull(value);
        }
    }

    @Test
    void executeQuery_selectVersion() throws Exception {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT @@version")) {
            assertTrue(rs.next());
            String version = rs.getString(1);
            assertTrue(version.contains("minidb"), "版本应包含 minidb");
        }
    }

    @Test
    void executeQuery_selectDatabase() throws Exception {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT DATABASE()")) {
            assertTrue(rs.next());
            // 应返回连接时指定的数据库名
            assertNotNull(rs.getString(1));
        }
    }

    // ==================== SET 命令 ====================

    @Test
    void execute_setNames() throws Exception {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            boolean hasResultSet = stmt.execute("SET NAMES utf8mb4");
            assertFalse(hasResultSet, "SET NAMES 不应返回结果集");
        }
    }

    // ==================== 错误处理 ====================

    @Test
    void executeQuery_invalidSql_throwsSQLException() throws Exception {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            assertThrows(SQLException.class, () -> stmt.executeQuery("SELEC INVALID"));
        }
    }

    // ==================== Statement 状态 ====================

    @Test
    void statement_closeAndIsClosed() throws Exception {
        try (Connection conn = getConnection()) {
            Statement stmt = conn.createStatement();
            assertFalse(stmt.isClosed());
            stmt.close();
            assertTrue(stmt.isClosed());
        }
    }

    @Test
    void statement_closedStatement_throwsSQLException() throws Exception {
        try (Connection conn = getConnection()) {
            Statement stmt = conn.createStatement();
            stmt.close();
            assertThrows(SQLException.class, () -> stmt.executeQuery("SELECT 1"));
        }
    }

    @Test
    void statement_getConnection() throws Exception {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            assertSame(conn, stmt.getConnection());
        }
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
