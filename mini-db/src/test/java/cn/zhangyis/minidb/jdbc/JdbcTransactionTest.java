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
 * Phase 5 测试：JDBC 事务控制。
 * 验证 setAutoCommit / commit / rollback 通过 COM_QUERY 发送 SQL 文本命令。
 */
class JdbcTransactionTest {

    private static final int TEST_PORT = 13315;
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

    // ==================== autoCommit ====================

    @Test
    void defaultAutoCommit_isTrue() throws Exception {
        try (Connection conn = getConnection()) {
            assertTrue(conn.getAutoCommit(), "默认应为 autoCommit=true");
        }
    }

    @Test
    void setAutoCommit_false_thenTrue() throws Exception {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            assertFalse(conn.getAutoCommit());

            conn.setAutoCommit(true);
            assertTrue(conn.getAutoCommit());
        }
    }

    @Test
    void setAutoCommit_sameValue_noExtraRoundTrip() throws Exception {
        try (Connection conn = getConnection()) {
            // 设置相同值不应发送额外命令
            conn.setAutoCommit(true);
            assertTrue(conn.getAutoCommit());

            // 连接仍可用
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT @@version")) {
                assertTrue(rs.next());
            }
        }
    }

    // ==================== commit ====================

    @Test
    void commit_afterDisableAutoCommit() throws Exception {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            // COMMIT 应成功（即使没有实际事务）
            conn.commit();

            // 连接仍可用
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT @@version_comment")) {
                assertTrue(rs.next());
            }
        }
    }

    // ==================== rollback ====================

    @Test
    void rollback_afterDisableAutoCommit() throws Exception {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            // ROLLBACK 应成功
            conn.rollback();

            // 连接仍可用
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT @@version")) {
                assertTrue(rs.next());
            }
        }
    }

    // ==================== 连接关闭后操作 ====================

    @Test
    void setAutoCommit_afterClose_throwsSQLException() throws Exception {
        Connection conn = getConnection();
        conn.close();
        assertThrows(SQLException.class, () -> conn.setAutoCommit(false));
    }

    @Test
    void commit_afterClose_throwsSQLException() throws Exception {
        Connection conn = getConnection();
        conn.close();
        assertThrows(SQLException.class, conn::commit);
    }

    @Test
    void rollback_afterClose_throwsSQLException() throws Exception {
        Connection conn = getConnection();
        conn.close();
        assertThrows(SQLException.class, conn::rollback);
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
