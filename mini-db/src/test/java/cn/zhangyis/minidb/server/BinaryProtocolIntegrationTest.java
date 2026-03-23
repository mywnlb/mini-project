package cn.zhangyis.minidb.server;

import cn.zhangyis.minidb.server.auth.UserManager;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import cn.zhangyis.minidb.sql.catalog.*;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 测试：二进制协议（COM_STMT_PREPARE / EXECUTE / CLOSE）。
 * 原始 Socket 发送二进制协议字节流，验证预编译语句的完整生命周期。
 */
class BinaryProtocolIntegrationTest {

    private static final int TEST_PORT = 13313;
    private MiniDbServer server;

    @BeforeEach
    void setUp() throws Exception {
        UserManager userManager = new UserManager();
        userManager.addUser("root", "");

        server = new MiniDbServerBuilder()
                .port(TEST_PORT)
                .catalog(new InMemoryCatalog())
                .dataSource(new InMemoryDataSource())
                .userManager(userManager)
                .sqlThreadPoolSize(2)
                .build();
        server.startAsync();
        Thread.sleep(500);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    // ==================== COM_STMT_PREPARE ====================

    @Test
    void comStmtPrepare_selectSystemVariable_returnsStmtPrepareOk() throws Exception {
        try (Socket socket = connect()) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // 发送 COM_STMT_PREPARE: SELECT @@version_comment
            String sql = "SELECT @@version_comment";
            byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[1 + sqlBytes.length];
            payload[0] = MysqlConstants.COM_STMT_PREPARE;
            System.arraycopy(sqlBytes, 0, payload, 1, sqlBytes.length);
            writePacket(out, 0, payload);

            // 读取 StmtPrepareOk 响应
            byte[] resp = readPacket(in);
            assertEquals(0x00, resp[0] & 0xFF, "应返回 status=0x00 (OK)");

            // 解析 statement_id (4 bytes LE)
            int stmtId = readInt4LE(resp, 1);
            assertTrue(stmtId > 0, "statement_id 应大于 0");

            // num_columns (2 bytes LE)
            int numColumns = readInt2LE(resp, 5);
            // num_params (2 bytes LE)
            int numParams = readInt2LE(resp, 7);
            assertEquals(0, numParams, "SELECT @@version_comment 无参数");

            // 消费参数列定义 + EOF（如果有）
            if (numParams > 0) {
                for (int i = 0; i < numParams; i++) readPacket(in);
                readPacket(in); // EOF
            }
            // 消费结果列定义 + EOF（如果有）
            if (numColumns > 0) {
                for (int i = 0; i < numColumns; i++) readPacket(in);
                readPacket(in); // EOF
            }
        }
    }

    // ==================== COM_STMT_EXECUTE ====================

    @Test
    void comStmtExecute_selectSystemVariable_returnsBinaryResultSet() throws Exception {
        try (Socket socket = connect()) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // PREPARE
            String sql = "SELECT @@version";
            byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
            byte[] prepPayload = new byte[1 + sqlBytes.length];
            prepPayload[0] = MysqlConstants.COM_STMT_PREPARE;
            System.arraycopy(sqlBytes, 0, prepPayload, 1, sqlBytes.length);
            writePacket(out, 0, prepPayload);

            byte[] prepResp = readPacket(in);
            assertEquals(0x00, prepResp[0] & 0xFF);
            int stmtId = readInt4LE(prepResp, 1);
            int numColumns = readInt2LE(prepResp, 5);
            int numParams = readInt2LE(prepResp, 7);

            // 消费列定义
            if (numParams > 0) {
                for (int i = 0; i < numParams; i++) readPacket(in);
                readPacket(in);
            }
            if (numColumns > 0) {
                for (int i = 0; i < numColumns; i++) readPacket(in);
                readPacket(in);
            }

            // EXECUTE（无参数）
            byte[] execPayload = new byte[1 + 4 + 1 + 4]; // cmd + stmt_id + flags + iteration_count
            execPayload[0] = MysqlConstants.COM_STMT_EXECUTE;
            writeInt4LE(execPayload, 1, stmtId);
            execPayload[5] = 0x00; // flags
            writeInt4LE(execPayload, 6, 1); // iteration_count = 1
            writePacket(out, 0, execPayload);

            // 读取结果：可能是列数量包或 OK 包
            byte[] execResp = readPacket(in);
            int header = execResp[0] & 0xFF;

            if (header == MysqlConstants.OK_HEADER && execResp.length < 10) {
                // OK 包（DML 结果）
                return;
            }

            // 结果集：列数量
            assertTrue(header > 0 && header < 0xFB, "应返回列数量");

            // 消费列定义 + EOF
            for (int i = 0; i < header; i++) readPacket(in);
            byte[] eof1 = readPacket(in);
            assertEquals(MysqlConstants.EOF_HEADER, eof1[0] & 0xFF);

            // 读取二进制行（至少一行）
            byte[] rowPkt = readPacket(in);
            int rowHeader = rowPkt[0] & 0xFF;
            // 二进制行以 0x00 开头，或者可能直接是 EOF
            if (rowHeader != MysqlConstants.EOF_HEADER) {
                assertEquals(0x00, rowHeader, "二进制行应以 0x00 开头");
                // 读最终 EOF
                byte[] eof2 = readPacket(in);
                assertEquals(MysqlConstants.EOF_HEADER, eof2[0] & 0xFF);
            }
        }
    }

    // ==================== COM_STMT_CLOSE ====================

    @Test
    void comStmtClose_noResponse() throws Exception {
        try (Socket socket = connect()) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // PREPARE
            String sql = "SELECT @@version_comment";
            byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
            byte[] prepPayload = new byte[1 + sqlBytes.length];
            prepPayload[0] = MysqlConstants.COM_STMT_PREPARE;
            System.arraycopy(sqlBytes, 0, prepPayload, 1, sqlBytes.length);
            writePacket(out, 0, prepPayload);

            byte[] prepResp = readPacket(in);
            int stmtId = readInt4LE(prepResp, 1);
            int numColumns = readInt2LE(prepResp, 5);
            int numParams = readInt2LE(prepResp, 7);

            if (numParams > 0) {
                for (int i = 0; i < numParams; i++) readPacket(in);
                readPacket(in);
            }
            if (numColumns > 0) {
                for (int i = 0; i < numColumns; i++) readPacket(in);
                readPacket(in);
            }

            // CLOSE（无响应）
            byte[] closePayload = new byte[5];
            closePayload[0] = MysqlConstants.COM_STMT_CLOSE;
            writeInt4LE(closePayload, 1, stmtId);
            writePacket(out, 0, closePayload);

            // 验证连接仍然可用：发送 COM_PING
            byte[] pingPayload = new byte[]{MysqlConstants.COM_PING};
            writePacket(out, 0, pingPayload);
            byte[] pingResp = readPacket(in);
            assertEquals(MysqlConstants.OK_HEADER, pingResp[0] & 0xFF, "CLOSE 后连接应仍可用");
        }
    }

    // ==================== 错误处理 ====================

    @Test
    void comStmtPrepare_invalidSql_returnsErr() throws Exception {
        try (Socket socket = connect()) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String sql = "SELEC INVALID";
            byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[1 + sqlBytes.length];
            payload[0] = MysqlConstants.COM_STMT_PREPARE;
            System.arraycopy(sqlBytes, 0, payload, 1, sqlBytes.length);
            writePacket(out, 0, payload);

            byte[] resp = readPacket(in);
            assertEquals(MysqlConstants.ERR_HEADER, resp[0] & 0xFF, "无效 SQL 应返回 ERR");
        }
    }

    // ==================== 辅助方法 ====================

    private Socket connect() throws Exception {
        Socket socket = new Socket("127.0.0.1", TEST_PORT);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        readPacket(in); // 握手包

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
        payload[pos++] = 0x00;
        System.arraycopy(pluginBytes, 0, payload, pos, pluginBytes.length);
        pos += pluginBytes.length;
        payload[pos++] = 0x00;

        payload = Arrays.copyOf(payload, pos);
        writePacket(out, 1, payload);

        byte[] okResp = readPacket(in);
        assertEquals(MysqlConstants.OK_HEADER, okResp[0] & 0xFF, "认证应成功");
        return socket;
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
            if (read == -1) throw new java.io.IOException("EOF");
            offset += read;
        }
        return buf;
    }

    private int readInt2LE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private int readInt4LE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private void writeInt4LE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    // ==================== Stub 实现 ====================

    static class InMemoryCatalog implements CatalogSpi {
        private final Map<String, TableMeta> tables = new ConcurrentHashMap<>();
        @Override public TableMeta getTable(String name) { return tables.get(name.toUpperCase()); }
        @Override public List<String> listTables(String database) { return new ArrayList<>(tables.keySet()); }
        @Override public List<ColumnMeta> getColumns(String name) {
            TableMeta t = tables.get(name.toUpperCase());
            return t != null ? t.columns() : List.of();
        }
        @Override public boolean tableExists(String name) { return tables.containsKey(name.toUpperCase()); }
        @Override public void createTable(TableMeta table) { tables.put(table.name().toUpperCase(), table); }
        @Override public void dropTable(String name) { tables.remove(name.toUpperCase()); }
        @Override public void addColumn(String name, ColumnMeta col) {}
        @Override public void createIndex(IndexMeta index) {}
        @Override public void dropIndex(String table, String index) {}
        @Override public List<IndexMeta> getIndexes(String name) { return List.of(); }
    }

    static class InMemoryDataSource implements DataSourceSpi {
        private final Map<String, List<Row>> data = new ConcurrentHashMap<>();
        @Override public Iterator<Row> scan(String tableName) {
            return data.getOrDefault(tableName.toUpperCase(), List.of()).iterator();
        }
        @Override public void insertRow(String tableName, Row row) {
            data.computeIfAbsent(tableName.toUpperCase(), k -> new CopyOnWriteArrayList<>()).add(row);
        }
        @Override public int updateRows(String tableName, Predicate<Row> f, Consumer<Row> u) {
            List<Row> rows = data.getOrDefault(tableName.toUpperCase(), List.of());
            int count = 0;
            for (Row r : rows) { if (f.test(r)) { u.accept(r); count++; } }
            return count;
        }
        @Override public int deleteRows(String tableName, Predicate<Row> f) {
            List<Row> rows = data.getOrDefault(tableName.toUpperCase(), List.of());
            int before = rows.size();
            rows.removeIf(f);
            return before - rows.size();
        }
    }
}
