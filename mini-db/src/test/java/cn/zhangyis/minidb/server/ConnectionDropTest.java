package cn.zhangyis.minidb.server;

import cn.zhangyis.minidb.server.auth.UserManager;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import cn.zhangyis.minidb.sql.catalog.*;
import cn.zhangyis.minidb.sql.exec.DataSourceSpi;
import cn.zhangyis.minidb.sql.exec.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6 测试：客户端异常断开。
 * 验证服务端在客户端突然断开后能正常清理资源，后续连接不受影响。
 */
class ConnectionDropTest {

    private static final int TEST_PORT = 13312;
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

    @Test
    void abruptDisconnect_serverSurvives() throws Exception {
        // 建立连接后立即关闭 socket（不发 COM_QUIT）
        Socket socket = new Socket("127.0.0.1", TEST_PORT);
        InputStream in = socket.getInputStream();
        // 读握手包
        readPacket(in);
        // 直接关闭，不发认证响应
        socket.close();

        Thread.sleep(300); // 等待服务端处理断开

        // 验证服务端仍然正常：新连接应能成功
        Properties props = new Properties();
        props.setProperty("user", "root");
        props.setProperty("password", "");
        try (Connection conn = DriverManager.getConnection(
                "jdbc:minidb://127.0.0.1:" + TEST_PORT + "/test", props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT @@version")) {
            assertTrue(rs.next());
            assertTrue(rs.getString(1).contains("minidb"));
        }
    }

    @Test
    void abruptDisconnect_afterAuth_serverSurvives() throws Exception {
        // 完成认证后直接关闭 socket
        Socket socket = new Socket("127.0.0.1", TEST_PORT);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        // 读握手包
        readPacket(in);

        // 发送认证（root 空密码）
        int clientCap = MysqlConstants.CLIENT_PROTOCOL_41
                | MysqlConstants.CLIENT_SECURE_CONNECTION
                | MysqlConstants.CLIENT_PLUGIN_AUTH
                | MysqlConstants.CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA;
        byte[] userBytes = "root".getBytes(StandardCharsets.UTF_8);
        byte[] pluginBytes = "mysql_native_password".getBytes(StandardCharsets.UTF_8);
        int payloadSize = 4 + 4 + 1 + 23 + userBytes.length + 1 + 1 + pluginBytes.length + 1;
        byte[] payload = new byte[payloadSize];
        int pos = 0;
        payload[pos++] = (byte) (clientCap & 0xFF);
        payload[pos++] = (byte) ((clientCap >> 8) & 0xFF);
        payload[pos++] = (byte) ((clientCap >> 16) & 0xFF);
        payload[pos++] = (byte) ((clientCap >> 24) & 0xFF);
        int maxPkt = 16 * 1024 * 1024;
        payload[pos++] = (byte) (maxPkt & 0xFF);
        payload[pos++] = (byte) ((maxPkt >> 8) & 0xFF);
        payload[pos++] = (byte) ((maxPkt >> 16) & 0xFF);
        payload[pos++] = (byte) ((maxPkt >> 24) & 0xFF);
        payload[pos++] = (byte) MysqlConstants.CHARSET_UTF8MB4;
        pos += 23;
        System.arraycopy(userBytes, 0, payload, pos, userBytes.length);
        pos += userBytes.length;
        payload[pos++] = 0x00;
        payload[pos++] = 0x00; // empty auth
        System.arraycopy(pluginBytes, 0, payload, pos, pluginBytes.length);
        pos += pluginBytes.length;
        payload[pos++] = 0x00;
        payload = Arrays.copyOf(payload, pos);

        writePacket(out, 1, payload);
        readPacket(in); // OK

        // 直接关闭 socket（不发 COM_QUIT）
        socket.close();

        Thread.sleep(300);

        // 新连接应正常
        Properties props = new Properties();
        props.setProperty("user", "root");
        props.setProperty("password", "");
        try (Connection conn = DriverManager.getConnection(
                "jdbc:minidb://127.0.0.1:" + TEST_PORT + "/test", props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT @@version")) {
            assertTrue(rs.next());
        }
    }

    @Test
    void multipleAbruptDisconnects_serverStaysHealthy() throws Exception {
        // 连续 5 次异常断开
        for (int i = 0; i < 5; i++) {
            Socket socket = new Socket("127.0.0.1", TEST_PORT);
            readPacket(socket.getInputStream());
            socket.close();
        }

        Thread.sleep(500);

        // 服务端仍然健康
        Properties props = new Properties();
        props.setProperty("user", "root");
        props.setProperty("password", "");
        try (Connection conn = DriverManager.getConnection(
                "jdbc:minidb://127.0.0.1:" + TEST_PORT + "/test", props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT @@version")) {
            assertTrue(rs.next());
        }
    }

    // ==================== 辅助方法 ====================

    private byte[] readPacket(InputStream in) throws Exception {
        byte[] header = readFully(in, 4);
        int payloadLen = (header[0] & 0xFF) | ((header[1] & 0xFF) << 8) | ((header[2] & 0xFF) << 16);
        return readFully(in, payloadLen);
    }

    private void writePacket(OutputStream out, int seqId, byte[] payload) throws Exception {
        int len = payload.length;
        byte[] header = new byte[4];
        header[0] = (byte) (len & 0xFF);
        header[1] = (byte) ((len >> 8) & 0xFF);
        header[2] = (byte) ((len >> 16) & 0xFF);
        header[3] = (byte) (seqId & 0xFF);
        out.write(header);
        out.write(payload);
        out.flush();
    }

    private byte[] readFully(InputStream in, int length) throws Exception {
        byte[] buf = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buf, offset, length - offset);
            if (read == -1) throw new java.io.IOException("EOF");
            offset += read;
        }
        return buf;
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
