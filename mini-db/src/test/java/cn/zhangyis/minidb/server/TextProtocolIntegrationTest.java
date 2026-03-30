package cn.zhangyis.minidb.server;

import cn.zhangyis.minidb.server.auth.UserManager;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import cn.zhangyis.minidb.sql.catalog.*;
import cn.zhangyis.minidb.sql.exec.DataSourceSpi;
import cn.zhangyis.minidb.sql.exec.Row;
import cn.zhangyis.minidb.sql.types.SqlType;
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
 * Phase 3 测试：文本协议（COM_QUERY）。
 * 原始 Socket 发送 COM_QUERY 字节流，验证 SELECT/INSERT/UPDATE/DELETE 响应。
 */
class TextProtocolIntegrationTest {

    private static final int TEST_PORT = 13308;
    private MiniDbServer server;
    private InMemoryCatalog catalog;
    private InMemoryDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        catalog = new InMemoryCatalog();
        dataSource = new InMemoryDataSource();

        UserManager userManager = new UserManager();
        userManager.addUser("root", "");

        server = new MiniDbServerBuilder()
                .port(TEST_PORT)
                .catalog(catalog)
                .dataSource(dataSource)
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

    // ==================== SELECT 系统变量 ====================

    @Test
    void comQuery_selectVersionComment_returnsResultSet() throws Exception {
        try (Socket socket = connect()) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            sendComQuery(out, "SELECT @@version_comment");

            // 读取结果集：列数量包
            byte[] colCountPkt = readPacket(in);
            int colCount = colCountPkt[0] & 0xFF;
            assertEquals(1, colCount, "应返回 1 列");

            // 列定义包
            readPacket(in);
            // EOF 包
            byte[] eof1 = readPacket(in);
            assertEquals(MysqlConstants.EOF_HEADER, eof1[0] & 0xFF);

            // 行数据包
            byte[] rowPkt = readPacket(in);
            assertNotEquals(MysqlConstants.EOF_HEADER, rowPkt[0] & 0xFF, "应有至少一行数据");

            // 最终 EOF 包
            byte[] eof2 = readPacket(in);
            assertEquals(MysqlConstants.EOF_HEADER, eof2[0] & 0xFF);
        }
    }

    @Test
    void comQuery_selectVersion_returnsResultSet() throws Exception {
        try (Socket socket = connect()) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            sendComQuery(out, "SELECT @@version");

            byte[] colCountPkt = readPacket(in);
            assertEquals(1, colCountPkt[0] & 0xFF);

            // 列定义 + EOF
            readPacket(in);
            readPacket(in);

            // 行数据：应包含 "8.0.0-minidb"
            byte[] rowPkt = readPacket(in);
            // 解析 length-encoded string
            int strLen = rowPkt[0] & 0xFF;
            String value = new String(rowPkt, 1, strLen, StandardCharsets.UTF_8);
            assertTrue(value.contains("minidb"), "版本应包含 minidb");

            // 最终 EOF
            readPacket(in);
        }
    }

    @Test
    void comQuery_setNames_returnsOk() throws Exception {
        try (Socket socket = connect()) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            sendComQuery(out, "SET NAMES utf8mb4");

            byte[] resp = readPacket(in);
            assertEquals(MysqlConstants.OK_HEADER, resp[0] & 0xFF, "SET NAMES 应返回 OK");
        }
    }

    // ==================== 错误处理 ====================

    @Test
    void comQuery_invalidSql_returnsErr() throws Exception {
        try (Socket socket = connect()) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            sendComQuery(out, "SELEC INVALID SQL");

            byte[] resp = readPacket(in);
            assertEquals(MysqlConstants.ERR_HEADER, resp[0] & 0xFF, "无效 SQL 应返回 ERR 包");
        }
    }

    @Test
    void comQuery_informationSchemaEmptyResult_returnsColumnMetadata() throws Exception {
        try (Socket socket = connect()) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            sendComQuery(out,
                    "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'MISSING_DB'");

            byte[] colCountPkt = readPacket(in);
            assertEquals(1, colCountPkt[0] & 0xFF, "0 行 SELECT 也应返回列数量");

            byte[] colDef = readPacket(in);
            assertTrue(colDef.length > 0, "应返回列定义包");

            byte[] eof1 = readPacket(in);
            assertEquals(MysqlConstants.EOF_HEADER, eof1[0] & 0xFF, "列定义后应有 EOF");

            byte[] eof2 = readPacket(in);
            assertEquals(MysqlConstants.EOF_HEADER, eof2[0] & 0xFF, "空结果集应直接结束，而不是返回 OK");
        }
    }

    @Test
    void comQuery_informationSchemaEngines_returnsVirtualRows() throws Exception {
        try (Socket socket = connect()) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            sendComQuery(out, "SELECT ENGINE, SUPPORT FROM information_schema.ENGINES");

            byte[] colCountPkt = readPacket(in);
            assertEquals(2, colCountPkt[0] & 0xFF, "应返回 ENGINE/SUPPORT 两列");

            readPacket(in);
            readPacket(in);

            byte[] eof1 = readPacket(in);
            assertEquals(MysqlConstants.EOF_HEADER, eof1[0] & 0xFF);

            byte[] rowPkt = readPacket(in);
            assertNotEquals(MysqlConstants.EOF_HEADER, rowPkt[0] & 0xFF, "ENGINES 虚拟表至少应返回一行");

            byte[] eof2 = readPacket(in);
            assertEquals(MysqlConstants.EOF_HEADER, eof2[0] & 0xFF);
        }
    }

    @Test
    void comQuery_navicatRoutineMetadataProbe_returnsEmptyResultSetMetadata() throws Exception {
        try (Socket socket = connect()) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String sql = "SELECT DISTINCT ROUTINE_SCHEMA, ROUTINE_NAME, PARAMS.PARAMETER " +
                    "FROM information_schema.ROUTINES " +
                    "LEFT JOIN ( " +
                    "SELECT SPECIFIC_SCHEMA, SPECIFIC_NAME, " +
                    "GROUP_CONCAT(CONCAT(DATA_TYPE, ' ', PARAMETER_NAME) ORDER BY ORDINAL_POSITION SEPARATOR ', ') PARAMETER, " +
                    "ROUTINE_TYPE FROM information_schema.PARAMETERS " +
                    "GROUP BY SPECIFIC_SCHEMA, SPECIFIC_NAME, ROUTINE_TYPE " +
                    ") PARAMS " +
                    "ON ROUTINES.ROUTINE_SCHEMA = PARAMS.SPECIFIC_SCHEMA " +
                    "AND ROUTINES.ROUTINE_NAME = PARAMS.SPECIFIC_NAME " +
                    "AND ROUTINES.ROUTINE_TYPE = PARAMS.ROUTINE_TYPE " +
                    "WHERE ROUTINE_SCHEMA = 'sql_mode' ORDER BY ROUTINE_SCHEMA";
            sendComQuery(out, sql);

            byte[] colCountPkt = readPacket(in);
            assertEquals(3, colCountPkt[0] & 0xFF, "应返回 ROUTINE_SCHEMA/ROUTINE_NAME/PARAMETER 三列");

            readPacket(in);
            readPacket(in);
            readPacket(in);

            byte[] eof1 = readPacket(in);
            assertEquals(MysqlConstants.EOF_HEADER, eof1[0] & 0xFF);

            byte[] eof2 = readPacket(in);
            assertEquals(MysqlConstants.EOF_HEADER, eof2[0] & 0xFF, "无存储过程时应返回空结果集，而不是 ERR");
        }
    }

    @Test
    void comQuery_informationSchemaTriggersProbe_returnsEmptyResultSetMetadata() throws Exception {
        try (Socket socket = connect()) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            sendComQuery(out,
                    "SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE FROM information_schema.TRIGGERS " +
                    "WHERE TRIGGER_SCHEMA = 'sql_mode' ORDER BY TRIGGER_NAME");

            byte[] colCountPkt = readPacket(in);
            assertEquals(2, colCountPkt[0] & 0xFF, "应返回查询投影的两列元数据");

            readPacket(in);
            readPacket(in);

            byte[] eof1 = readPacket(in);
            assertEquals(MysqlConstants.EOF_HEADER, eof1[0] & 0xFF);

            byte[] eof2 = readPacket(in);
            assertEquals(MysqlConstants.EOF_HEADER, eof2[0] & 0xFF);
        }
    }

    @Test
    void comQuery_showIndex_returnsIndexMetadataRows() throws Exception {
        catalog.createTable(TableMeta.of("USERS", List.of(
                new ColumnMeta("ID", SqlType.INT32, true),
                new ColumnMeta("NAME", SqlType.VARCHAR, false)
        ), 0));
        catalog.createIndex(new IndexMeta("PRIMARY", "USERS", List.of("ID"), true, true));
        catalog.createIndex(new IndexMeta("IDX_USERS_NAME", "USERS", List.of("NAME"), false, false));

        try (Socket socket = connect()) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            sendComQuery(out, "SHOW INDEX FROM USERS");

            byte[] colCountPkt = readPacket(in);
            assertEquals(13, colCountPkt[0] & 0xFF, "SHOW INDEX 应返回兼容的索引元数据列");

            for (int i = 0; i < 13; i++) {
                readPacket(in);
            }
            byte[] eof1 = readPacket(in);
            assertEquals(MysqlConstants.EOF_HEADER, eof1[0] & 0xFF);

            List<List<String>> rows = new ArrayList<>();
            while (true) {
                byte[] packet = readPacket(in);
                if ((packet[0] & 0xFF) == MysqlConstants.EOF_HEADER) {
                    break;
                }
                rows.add(decodeTextRow(packet));
            }

            assertEquals(2, rows.size(), "SHOW INDEX 应返回 PRIMARY 和普通二级索引两行");
            assertTrue(rows.stream().anyMatch(row -> row.contains("PRIMARY")));
            assertTrue(rows.stream().anyMatch(row -> row.contains("IDX_USERS_NAME")));
        }
    }

    // ==================== 辅助方法 ====================

    /** 建立连接并完成认证 */
    private Socket connect() throws Exception {
        Socket socket = new Socket("127.0.0.1", TEST_PORT);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        // 读握手包
        readPacket(in);

        // 发送认证响应（root 空密码）
        int clientCap = MysqlConstants.CLIENT_PROTOCOL_41
                | MysqlConstants.CLIENT_SECURE_CONNECTION
                | MysqlConstants.CLIENT_PLUGIN_AUTH
                | MysqlConstants.CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA;

        byte[] userBytes = "root".getBytes(StandardCharsets.UTF_8);
        byte[] pluginBytes = "mysql_native_password".getBytes(StandardCharsets.UTF_8);

        int payloadSize = 4 + 4 + 1 + 23 + userBytes.length + 1 + 1 + pluginBytes.length + 1;
        byte[] payload = new byte[payloadSize];
        int pos = 0;

        // capability_flags
        payload[pos++] = (byte) (clientCap & 0xFF);
        payload[pos++] = (byte) ((clientCap >> 8) & 0xFF);
        payload[pos++] = (byte) ((clientCap >> 16) & 0xFF);
        payload[pos++] = (byte) ((clientCap >> 24) & 0xFF);
        // max_packet_size
        int maxPkt = 16 * 1024 * 1024;
        payload[pos++] = (byte) (maxPkt & 0xFF);
        payload[pos++] = (byte) ((maxPkt >> 8) & 0xFF);
        payload[pos++] = (byte) ((maxPkt >> 16) & 0xFF);
        payload[pos++] = (byte) ((maxPkt >> 24) & 0xFF);
        // charset
        payload[pos++] = (byte) MysqlConstants.CHARSET_UTF8MB4;
        // reserved 23 bytes
        pos += 23;
        // username NUL
        System.arraycopy(userBytes, 0, payload, pos, userBytes.length);
        pos += userBytes.length;
        payload[pos++] = 0x00;
        // auth_response (empty for root)
        payload[pos++] = 0x00; // length = 0
        // auth_plugin_name NUL
        System.arraycopy(pluginBytes, 0, payload, pos, pluginBytes.length);
        pos += pluginBytes.length;
        payload[pos++] = 0x00;

        payload = Arrays.copyOf(payload, pos);
        writePacket(out, 1, payload);

        // 读 OK
        byte[] okResp = readPacket(in);
        assertEquals(MysqlConstants.OK_HEADER, okResp[0] & 0xFF, "认证应成功");

        return socket;
    }

    /** 发送 COM_QUERY */
    private void sendComQuery(OutputStream out, String sql) throws Exception {
        byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[1 + sqlBytes.length];
        payload[0] = MysqlConstants.COM_QUERY;
        System.arraycopy(sqlBytes, 0, payload, 1, sqlBytes.length);
        writePacket(out, 0, payload);
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

    private List<String> decodeTextRow(byte[] packet) {
        List<String> values = new ArrayList<>();
        int offset = 0;
        while (offset < packet.length) {
            int len = packet[offset] & 0xFF;
            offset++;
            values.add(new String(packet, offset, len, StandardCharsets.UTF_8));
            offset += len;
        }
        return values;
    }

    // ==================== 内存 Catalog/DataSource 实现 ====================

    static class InMemoryCatalog implements CatalogSpi {
        private final Map<String, TableMeta> tables = new ConcurrentHashMap<>();
        private final List<IndexMeta> indexes = new CopyOnWriteArrayList<>();

        @Override public TableMeta getTable(String name) { return tables.get(name.toUpperCase()); }
        @Override public List<String> listTables(String database) { return new ArrayList<>(tables.keySet()); }
        @Override public List<ColumnMeta> getColumns(String name) {
            TableMeta t = tables.get(name.toUpperCase());
            return t != null ? t.columns() : List.of();
        }
        @Override public boolean tableExists(String name) { return tables.containsKey(name.toUpperCase()); }
        @Override public void createTable(TableMeta table) { tables.put(table.name().toUpperCase(), table); }
        @Override public void dropTable(String name) {
            tables.remove(name.toUpperCase());
            indexes.removeIf(index -> index.tableName().equalsIgnoreCase(name));
        }
        @Override public void addColumn(String name, ColumnMeta col) {}
        @Override public void createIndex(IndexMeta index) { indexes.add(index); }
        @Override public void dropIndex(String table, String index) {
            indexes.removeIf(meta -> meta.tableName().equalsIgnoreCase(table)
                    && meta.indexName().equalsIgnoreCase(index));
        }
        @Override public List<IndexMeta> getIndexes(String name) {
            return indexes.stream()
                    .filter(index -> index.tableName().equalsIgnoreCase(name))
                    .toList();
        }
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
