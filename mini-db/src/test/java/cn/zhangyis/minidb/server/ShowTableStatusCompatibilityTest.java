package cn.zhangyis.minidb.server;

import cn.zhangyis.minidb.server.auth.UserManager;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.catalog.ColumnMeta;
import cn.zhangyis.minidb.sql.catalog.IndexMeta;
import cn.zhangyis.minidb.sql.catalog.TableMeta;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ShowTableStatusCompatibilityTest {

    private static final int TEST_PORT = 13318;

    private MiniDbServer server;
    private DatabaseScopedCatalog catalog;

    @BeforeEach
    void setUp() throws Exception {
        catalog = new DatabaseScopedCatalog();
        catalog.createTable("a_default_db", table("fallback_only"));
        catalog.createTable("z_navicat_db", table("users"));

        UserManager userManager = new UserManager();
        userManager.addUser("root", "");

        server = new MiniDbServerBuilder()
                .port(TEST_PORT)
                .catalog(catalog)
                .dataSource(new NoOpDataSource())
                .userManager(userManager)
                .sqlThreadPoolSize(2)
                .defaultDatabase("a_default_db")
                .build();
        server.startAsync();
        Thread.sleep(500);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void comQuery_showTableStatus_usesHandshakeDatabaseInsteadOfDefaultDatabase() throws Exception {
        try (Socket socket = connect("z_navicat_db")) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            sendComQuery(out, "SHOW TABLE STATUS");

            byte[] colCountPkt = readPacket(in);
            assertEquals(18, colCountPkt[0] & 0xFF, "SHOW TABLE STATUS 应返回 18 列");

            for (int i = 0; i < 18; i++) {
                readPacket(in);
            }
            byte[] eof1 = readPacket(in);
            assertEquals(MysqlConstants.EOF_HEADER, eof1[0] & 0xFF);

            byte[] rowPkt = readPacket(in);
            assertNotEquals(MysqlConstants.EOF_HEADER, rowPkt[0] & 0xFF,
                    "握手已选择数据库时不应返回空结果集");
            assertEquals("users", firstColumnValue(rowPkt));

            byte[] eof2 = readPacket(in);
            assertEquals(MysqlConstants.EOF_HEADER, eof2[0] & 0xFF);
        }
    }

    @Test
    void comQuery_showTableStatus_fallsBackToSingleCatalogDatabaseWhenCurrentDatabaseMissing() throws Exception {
        server.stop();

        DatabaseScopedCatalog singleDbCatalog = new DatabaseScopedCatalog();
        singleDbCatalog.createTable("only_db", table("orders"));

        UserManager userManager = new UserManager();
        userManager.addUser("root", "");

        server = new MiniDbServerBuilder()
                .port(TEST_PORT)
                .catalog(singleDbCatalog)
                .dataSource(new NoOpDataSource())
                .userManager(userManager)
                .sqlThreadPoolSize(2)
                .build();
        server.startAsync();
        Thread.sleep(500);

        try (Socket socket = connect(null)) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            sendComQuery(out, "SHOW TABLE STATUS");

            byte[] colCountPkt = readPacket(in);
            assertEquals(18, colCountPkt[0] & 0xFF);

            for (int i = 0; i < 18; i++) {
                readPacket(in);
            }
            readPacket(in);

            byte[] rowPkt = readPacket(in);
            assertNotEquals(MysqlConstants.EOF_HEADER, rowPkt[0] & 0xFF,
                    "无当前库但 catalog 只有一个库时，应回退到该库");
            assertEquals("orders", firstColumnValue(rowPkt));
        }
    }

    private static TableMeta table(String name) {
        return TableMeta.of(name, List.of(
                new ColumnMeta("id", SqlType.INT32, false)
        ), 0);
    }

    private Socket connect(String database) throws Exception {
        Socket socket = new Socket("127.0.0.1", TEST_PORT);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        readPacket(in);

        int clientCap = MysqlConstants.CLIENT_PROTOCOL_41
                | MysqlConstants.CLIENT_SECURE_CONNECTION
                | MysqlConstants.CLIENT_PLUGIN_AUTH
                | MysqlConstants.CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA;
        byte[] databaseBytes = null;
        if (database != null && !database.isBlank()) {
            clientCap |= MysqlConstants.CLIENT_CONNECT_WITH_DB;
            databaseBytes = database.getBytes(StandardCharsets.UTF_8);
        }

        byte[] userBytes = "root".getBytes(StandardCharsets.UTF_8);
        byte[] pluginBytes = "mysql_native_password".getBytes(StandardCharsets.UTF_8);

        int payloadSize = 4 + 4 + 1 + 23 + userBytes.length + 1 + 1
                + (databaseBytes != null ? databaseBytes.length + 1 : 0)
                + pluginBytes.length + 1;
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

        if (databaseBytes != null) {
            System.arraycopy(databaseBytes, 0, payload, pos, databaseBytes.length);
            pos += databaseBytes.length;
            payload[pos++] = 0x00;
        }

        System.arraycopy(pluginBytes, 0, payload, pos, pluginBytes.length);
        pos += pluginBytes.length;
        payload[pos++] = 0x00;

        writePacket(out, 1, payload);

        byte[] okResp = readPacket(in);
        assertEquals(MysqlConstants.OK_HEADER, okResp[0] & 0xFF, "认证应成功");

        return socket;
    }

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
            if (read == -1) {
                throw new java.io.IOException("EOF");
            }
            offset += read;
        }
        return buf;
    }

    private static String firstColumnValue(byte[] rowPacket) {
        int len = rowPacket[0] & 0xFF;
        return new String(rowPacket, 1, len, StandardCharsets.UTF_8);
    }

    static class DatabaseScopedCatalog implements CatalogSpi {
        private final Map<String, Map<String, TableMeta>> tablesByDatabase = new ConcurrentHashMap<>();

        void createTable(String database, TableMeta table) {
            tablesByDatabase
                    .computeIfAbsent(database.toUpperCase(), ignored -> new ConcurrentHashMap<>())
                    .put(table.name().toUpperCase(), table);
        }

        @Override
        public TableMeta getTable(String qualifiedName) {
            for (Map<String, TableMeta> tables : tablesByDatabase.values()) {
                TableMeta table = tables.get(qualifiedName.toUpperCase());
                if (table != null) {
                    return table;
                }
            }
            return null;
        }

        @Override
        public List<String> listTables(String database) {
            if (database == null || database.isBlank()) {
                return List.of();
            }
            Map<String, TableMeta> tables = tablesByDatabase.get(database.toUpperCase());
            if (tables == null) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (TableMeta table : tables.values()) {
                names.add(table.name());
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names;
        }

        @Override
        public List<String> listDatabases() {
            List<String> names = new ArrayList<>(tablesByDatabase.keySet());
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names;
        }

        @Override
        public TableMeta getTable(String database, String tableName) {
            if (database == null || tableName == null) {
                return null;
            }
            Map<String, TableMeta> tables = tablesByDatabase.get(database.toUpperCase());
            return tables != null ? tables.get(tableName.toUpperCase()) : null;
        }

        @Override
        public List<ColumnMeta> getColumns(String tableName) {
            TableMeta table = getTable(tableName);
            return table != null ? table.columns() : List.of();
        }

        @Override
        public List<ColumnMeta> getColumns(String database, String tableName) {
            TableMeta table = getTable(database, tableName);
            return table != null ? table.columns() : List.of();
        }

        @Override
        public boolean tableExists(String tableName) {
            return getTable(tableName) != null;
        }

        @Override
        public boolean tableExists(String database, String tableName) {
            return getTable(database, tableName) != null;
        }

        @Override
        public void createTable(TableMeta table) {
            createTable("DEFAULT", table);
        }

        @Override
        public void dropTable(String tableName) {
            for (Map<String, TableMeta> tables : tablesByDatabase.values()) {
                tables.remove(tableName.toUpperCase());
            }
        }

        @Override
        public void addColumn(String tableName, ColumnMeta column) {
        }

        @Override
        public void createIndex(IndexMeta index) {
        }

        @Override
        public void dropIndex(String tableName, String indexName) {
        }

        @Override
        public List<IndexMeta> getIndexes(String tableName) {
            return List.of();
        }
    }

    static class NoOpDataSource implements DataSourceSpi {
        @Override
        public Iterator<Row> scan(String tableName) {
            return List.<Row>of().iterator();
        }

        @Override
        public void insertRow(String tableName, Row row) {
        }

        @Override
        public int updateRows(String tableName, Predicate<Row> filter, Consumer<Row> updater) {
            return 0;
        }

        @Override
        public int deleteRows(String tableName, Predicate<Row> filter) {
            return 0;
        }
    }
}
