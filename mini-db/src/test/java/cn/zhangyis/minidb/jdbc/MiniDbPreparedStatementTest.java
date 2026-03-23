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
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 测试：JDBC PreparedStatement（二进制协议）。
 * 启动嵌入式服务器 → JDBC PreparedStatement → 验证 PREPARE/EXECUTE/CLOSE 流程。
 */
class MiniDbPreparedStatementTest {

    private static final int TEST_PORT = 13314;
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

    // ==================== 基本 PREPARE/EXECUTE ====================

    @Test
    void prepareStatement_selectVersionComment_executeQuery() throws Exception {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT @@version_comment")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "应有一行结果");
                String value = rs.getString(1);
                assertNotNull(value);
            }
        }
    }

    @Test
    void prepareStatement_selectVersion_containsMinidb() throws Exception {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT @@version")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getString(1).contains("minidb"));
            }
        }
    }

    // ==================== SET 命令（executeUpdate） ====================

    @Test
    void prepareStatement_setNames_executeUpdate() throws Exception {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SET NAMES utf8mb4")) {
            int affected = ps.executeUpdate();
            // SET 命令返回 0 affected rows
            assertTrue(affected >= 0);
        }
    }

    // ==================== execute() 方法 ====================

    @Test
    void prepareStatement_execute_selectReturnsTrue() throws Exception {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT @@version")) {
            boolean hasResultSet = ps.execute();
            assertTrue(hasResultSet, "SELECT 应返回 true（有结果集）");
            try (ResultSet rs = ps.getResultSet()) {
                assertNotNull(rs);
                assertTrue(rs.next());
            }
        }
    }

    @Test
    void prepareStatement_execute_setReturnsFalse() throws Exception {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SET NAMES utf8mb4")) {
            boolean hasResultSet = ps.execute();
            assertFalse(hasResultSet, "SET 应返回 false（无结果集）");
        }
    }

    // ==================== close 行为 ====================

    @Test
    void prepareStatement_close_sendsComStmtClose() throws Exception {
        try (Connection conn = getConnection()) {
            PreparedStatement ps = conn.prepareStatement("SELECT @@version_comment");
            assertFalse(ps.isClosed());
            ps.close();
            assertTrue(ps.isClosed());

            // 连接仍可用
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT @@version")) {
                assertTrue(rs.next());
            }
        }
    }

    @Test
    void prepareStatement_closedPs_throwsSQLException() throws Exception {
        try (Connection conn = getConnection()) {
            PreparedStatement ps = conn.prepareStatement("SELECT @@version");
            ps.close();
            assertThrows(SQLException.class, ps::executeQuery);
        }
    }

    // ==================== clearParameters ====================

    @Test
    void prepareStatement_clearParameters_noException() throws Exception {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT @@version")) {
            ps.clearParameters(); // 无参数时也不应抛异常
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
            }
        }
    }

    // ==================== 错误处理 ====================

    @Test
    void prepareStatement_invalidSql_throwsSQLException() throws Exception {
        try (Connection conn = getConnection()) {
            assertThrows(SQLException.class,
                    () -> conn.prepareStatement("SELEC INVALID"));
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
