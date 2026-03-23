package cn.zhangyis.minidb.server;

import cn.zhangyis.minidb.server.auth.MysqlNativePasswordAuth;
import cn.zhangyis.minidb.server.auth.UserManager;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.exec.DataSourceSpi;
import cn.zhangyis.minidb.sql.exec.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 测试：Netty 服务器 + 连接生命周期。
 * 使用原始 Socket 连接，验证握手包解析、认证流程、COM_QUIT。
 */
class MiniDbServerTest {

    private static final int TEST_PORT = 13307;
    private MiniDbServer server;

    @BeforeEach
    void setUp() throws Exception {
        UserManager userManager = new UserManager();
        userManager.addUser("root", "");
        userManager.addUser("testuser", "testpass");

        server = new MiniDbServerBuilder()
                .port(TEST_PORT)
                .catalog(new StubCatalog())
                .dataSource(new StubDataSource())
                .userManager(userManager)
                .sqlThreadPoolSize(2)
                .build();
        server.startAsync();
        Thread.sleep(500); // 等待服务器启动
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    // ==================== 握手包验证 ====================

    @Test
    void connect_receivesHandshakePacket() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", TEST_PORT)) {
            InputStream in = socket.getInputStream();
            byte[] header = readFully(in, 4);
            int payloadLen = (header[0] & 0xFF) | ((header[1] & 0xFF) << 8) | ((header[2] & 0xFF) << 16);
            int seqId = header[3] & 0xFF;

            assertEquals(0, seqId, "握手包 sequence id 应为 0");
            assertTrue(payloadLen > 0, "握手包 payload 不应为空");

            byte[] payload = readFully(in, payloadLen);
            // 第一个字节是协议版本 10
            assertEquals(MysqlConstants.PROTOCOL_VERSION, payload[0] & 0xFF);

            // 服务器版本字符串（NUL 结尾）
            int nullPos = 1;
            while (nullPos < payload.length && payload[nullPos] != 0x00) nullPos++;
            String serverVersion = new String(payload, 1, nullPos - 1, StandardCharsets.UTF_8);
            assertEquals(MysqlConstants.SERVER_VERSION, serverVersion);
        }
    }

    // ==================== 认证流程 ====================

    @Test
    void authenticate_rootEmptyPassword_receivesOk() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", TEST_PORT)) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // 读握手包
            byte[] challenge = readHandshakeChallenge(in);

            // 发送认证响应（root 空密码）
            sendAuthResponse(out, "root", new byte[0], "");

            // 读响应
            byte[] respHeader = readFully(in, 4);
            int respLen = (respHeader[0] & 0xFF) | ((respHeader[1] & 0xFF) << 8) | ((respHeader[2] & 0xFF) << 16);
            byte[] respPayload = readFully(in, respLen);

            // 应收到 OK 包 (header = 0x00)
            assertEquals(MysqlConstants.OK_HEADER, respPayload[0] & 0xFF, "认证成功应返回 OK 包");
        }
    }

    @Test
    void authenticate_withPassword_receivesOk() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", TEST_PORT)) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            byte[] challenge = readHandshakeChallenge(in);
            byte[] token = MysqlNativePasswordAuth.computeToken("testpass", challenge);

            sendAuthResponse(out, "testuser", token, "");

            byte[] respHeader = readFully(in, 4);
            int respLen = (respHeader[0] & 0xFF) | ((respHeader[1] & 0xFF) << 8) | ((respHeader[2] & 0xFF) << 16);
            byte[] respPayload = readFully(in, respLen);

            assertEquals(MysqlConstants.OK_HEADER, respPayload[0] & 0xFF, "密码认证成功应返回 OK 包");
        }
    }

    @Test
    void authenticate_wrongPassword_receivesErr() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", TEST_PORT)) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            byte[] challenge = readHandshakeChallenge(in);
            byte[] token = MysqlNativePasswordAuth.computeToken("wrongpass", challenge);

            sendAuthResponse(out, "testuser", token, "");

            byte[] respHeader = readFully(in, 4);
            int respLen = (respHeader[0] & 0xFF) | ((respHeader[1] & 0xFF) << 8) | ((respHeader[2] & 0xFF) << 16);
            byte[] respPayload = readFully(in, respLen);

            assertEquals(MysqlConstants.ERR_HEADER, respPayload[0] & 0xFF, "错误密码应返回 ERR 包");
        }
    }

    // ==================== COM_QUIT ====================

    @Test
    void comQuit_closesConnection() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", TEST_PORT)) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            byte[] challenge = readHandshakeChallenge(in);
            sendAuthResponse(out, "root", new byte[0], "");
            // 读 OK
            readPacket(in);

            // 发送 COM_QUIT
            sendCommand(out, MysqlConstants.COM_QUIT, new byte[0]);

            // 连接应被关闭，读取应返回 -1
            Thread.sleep(200);
            assertEquals(-1, in.read(), "COM_QUIT 后连接应关闭");
        }
    }

    // ==================== COM_PING ====================

    @Test
    void comPing_receivesOk() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", TEST_PORT)) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            byte[] challenge = readHandshakeChallenge(in);
            sendAuthResponse(out, "root", new byte[0], "");
            readPacket(in); // OK

            // 发送 COM_PING
            sendCommand(out, MysqlConstants.COM_PING, new byte[0]);

            byte[] resp = readPacket(in);
            assertEquals(MysqlConstants.OK_HEADER, resp[0] & 0xFF, "COM_PING 应返回 OK");
        }
    }

    // ==================== 辅助方法 ====================

    /** 读取握手包并提取 20 字节 auth challenge */
    private byte[] readHandshakeChallenge(InputStream in) throws Exception {
        byte[] header = readFully(in, 4);
        int payloadLen = (header[0] & 0xFF) | ((header[1] & 0xFF) << 8) | ((header[2] & 0xFF) << 16);
        byte[] payload = readFully(in, payloadLen);

        // 跳过 protocol_version(1)
        int offset = 1;
        // 跳过 server_version (NUL-terminated)
        while (offset < payload.length && payload[offset] != 0x00) offset++;
        offset++; // 跳过 NUL
        // 跳过 connection_id(4)
        offset += 4;
        // auth-plugin-data-part-1 (8 bytes)
        byte[] part1 = Arrays.copyOfRange(payload, offset, offset + 8);
        offset += 8;
        // filler(1) + cap_lower(2) + charset(1) + status(2) + cap_upper(2) + auth_data_len(1) + reserved(10)
        offset += 1 + 2 + 1 + 2 + 2 + 1 + 10;
        // auth-plugin-data-part-2 (12 bytes)
        byte[] part2 = Arrays.copyOfRange(payload, offset, offset + 12);

        byte[] challenge = new byte[20];
        System.arraycopy(part1, 0, challenge, 0, 8);
        System.arraycopy(part2, 0, challenge, 8, 12);
        return challenge;
    }

    /** 构造并发送 HandshakeResponse41 认证包 */
    private void sendAuthResponse(OutputStream out, String username, byte[] authToken, String database) throws Exception {
        int clientCap = MysqlConstants.CLIENT_PROTOCOL_41
                | MysqlConstants.CLIENT_SECURE_CONNECTION
                | MysqlConstants.CLIENT_PLUGIN_AUTH
                | MysqlConstants.CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA;
        if (database != null && !database.isEmpty()) {
            clientCap |= MysqlConstants.CLIENT_CONNECT_WITH_DB;
        }

        // 估算 payload 大小
        byte[] userBytes = username.getBytes(StandardCharsets.UTF_8);
        byte[] dbBytes = (database != null && !database.isEmpty()) ? database.getBytes(StandardCharsets.UTF_8) : null;
        byte[] pluginBytes = "mysql_native_password".getBytes(StandardCharsets.UTF_8);

        int payloadSize = 4 + 4 + 1 + 23 + userBytes.length + 1
                + 1 + authToken.length
                + (dbBytes != null ? dbBytes.length + 1 : 0)
                + pluginBytes.length + 1;

        byte[] payload = new byte[payloadSize];
        int pos = 0;

        // capability_flags (4 bytes LE)
        payload[pos++] = (byte) (clientCap & 0xFF);
        payload[pos++] = (byte) ((clientCap >> 8) & 0xFF);
        payload[pos++] = (byte) ((clientCap >> 16) & 0xFF);
        payload[pos++] = (byte) ((clientCap >> 24) & 0xFF);
        // max_packet_size (4 bytes LE)
        int maxPkt = 16 * 1024 * 1024;
        payload[pos++] = (byte) (maxPkt & 0xFF);
        payload[pos++] = (byte) ((maxPkt >> 8) & 0xFF);
        payload[pos++] = (byte) ((maxPkt >> 16) & 0xFF);
        payload[pos++] = (byte) ((maxPkt >> 24) & 0xFF);
        // charset (1 byte)
        payload[pos++] = (byte) MysqlConstants.CHARSET_UTF8MB4;
        // reserved (23 bytes, all zeros)
        pos += 23;
        // username (NUL-terminated)
        System.arraycopy(userBytes, 0, payload, pos, userBytes.length);
        pos += userBytes.length;
        payload[pos++] = 0x00;
        // auth_response (length-encoded: 使用 LENENC_CLIENT_DATA)
        payload[pos++] = (byte) authToken.length;
        System.arraycopy(authToken, 0, payload, pos, authToken.length);
        pos += authToken.length;
        // database (NUL-terminated, if CLIENT_CONNECT_WITH_DB)
        if (dbBytes != null) {
            System.arraycopy(dbBytes, 0, payload, pos, dbBytes.length);
            pos += dbBytes.length;
            payload[pos++] = 0x00;
        }
        // auth_plugin_name (NUL-terminated)
        System.arraycopy(pluginBytes, 0, payload, pos, pluginBytes.length);
        pos += pluginBytes.length;
        payload[pos++] = 0x00;

        // 截断到实际长度
        payload = Arrays.copyOf(payload, pos);

        // 写 MySQL 包帧: [3字节长度LE][1字节seqId][payload]
        writePacket(out, 1, payload); // seq=1 (握手响应)
    }

    /** 发送命令包 */
    private void sendCommand(OutputStream out, byte command, byte[] data) throws Exception {
        byte[] payload = new byte[1 + data.length];
        payload[0] = command;
        System.arraycopy(data, 0, payload, 1, data.length);
        writePacket(out, 0, payload);
    }

    /** 写一个 MySQL 包帧 */
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

    /** 读取一个完整 MySQL 包的 payload */
    private byte[] readPacket(InputStream in) throws Exception {
        byte[] header = readFully(in, 4);
        int payloadLen = (header[0] & 0xFF) | ((header[1] & 0xFF) << 8) | ((header[2] & 0xFF) << 16);
        return readFully(in, payloadLen);
    }

    private byte[] readFully(InputStream in, int length) throws Exception {
        byte[] buf = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buf, offset, length - offset);
            if (read == -1) throw new java.io.IOException("EOF: 期望 " + length + " 字节，已读 " + offset);
            offset += read;
        }
        return buf;
    }

    // ==================== Stub 实现 ====================

    /** 最小 CatalogSpi 实现，用于服务器启动 */
    static class StubCatalog implements CatalogSpi {
        @Override public cn.zhangyis.minidb.sql.catalog.TableMeta getTable(String name) { return null; }
        @Override public List<String> listTables(String database) { return List.of(); }
        @Override public List<cn.zhangyis.minidb.sql.catalog.ColumnMeta> getColumns(String name) { return List.of(); }
        @Override public boolean tableExists(String name) { return false; }
        @Override public void createTable(cn.zhangyis.minidb.sql.catalog.TableMeta table) {}
        @Override public void dropTable(String name) {}
        @Override public void addColumn(String name, cn.zhangyis.minidb.sql.catalog.ColumnMeta col) {}
        @Override public void createIndex(cn.zhangyis.minidb.sql.catalog.IndexMeta index) {}
        @Override public void dropIndex(String table, String index) {}
        @Override public List<cn.zhangyis.minidb.sql.catalog.IndexMeta> getIndexes(String name) { return List.of(); }
    }

    /** 最小 DataSourceSpi 实现 */
    static class StubDataSource implements DataSourceSpi {
        @Override public Iterator<Row> scan(String tableName) { return Collections.emptyIterator(); }
        @Override public void insertRow(String tableName, Row row) {}
        @Override public int updateRows(String tableName, Predicate<Row> f, Consumer<Row> u) { return 0; }
        @Override public int deleteRows(String tableName, Predicate<Row> f) { return 0; }
    }
}
