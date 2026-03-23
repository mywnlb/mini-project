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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6 测试：并发连接。
 * 10 个并发 JDBC 连接同时执行查询。
 */
class ConcurrentConnectionsTest {

    private static final int TEST_PORT = 13311;
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
                .sqlThreadPoolSize(4)
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

    @Test
    void concurrentConnections_10threads_allSucceed() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try (Connection conn = getConnection();
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT @@version")) {
                    if (rs.next() && rs.getString(1).contains("minidb")) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "所有线程应在 10 秒内完成");
        assertEquals(threadCount, successCount.get(), "所有连接应成功");
        assertEquals(0, failCount.get(), "不应有失败");

        executor.shutdown();
    }

    @Test
    void concurrentConnections_multipleQueriesPerConnection() throws Exception {
        int threadCount = 5;
        int queriesPerThread = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger totalQueries = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try (Connection conn = getConnection();
                     Statement stmt = conn.createStatement()) {
                    for (int q = 0; q < queriesPerThread; q++) {
                        try (ResultSet rs = stmt.executeQuery("SELECT @@version_comment")) {
                            if (rs.next()) {
                                totalQueries.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(threadCount * queriesPerThread, totalQueries.get(),
                "所有查询应成功完成");

        executor.shutdown();
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
