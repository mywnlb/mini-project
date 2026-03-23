package cn.zhangyis.minidb.jdbc;

import cn.zhangyis.minidb.server.auth.MysqlNativePasswordAuth;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * JDBC Connection 实现。
 *
 * <p>构造时建立 TCP 连接并完成 MySQL 握手认证。
 * 每个 Connection 持有一个 Socket 和 {@link MysqlClientCodec}。</p>
 *
 * <p>设计模式：门面模式——将底层 Socket I/O 和 MySQL 协议交互
 * 封装为标准 JDBC Connection 接口。</p>
 */
public class MiniDbConnection implements Connection {

    private final Socket socket;
    private final MysqlClientCodec codec;
    private final String database;
    private boolean closed = false;
    private boolean autoCommit = true;
    private int serverCapabilities;

    public MiniDbConnection(String host, int port, String database,
                            String username, String password) throws SQLException {
        this.database = database;
        try {
            // 建立 TCP 连接
            this.socket = new Socket(host, port);
            this.socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            this.codec = new MysqlClientCodec(in, out);

            // 完成 MySQL 握手
            performHandshake(username, password, database);
        } catch (IOException e) {
            throw new SQLException("连接服务器失败: " + host + ":" + port, e);
        }
    }

    /**
     * 执行 MySQL 握手认证流程。
     */
    private void performHandshake(String username, String password, String database) throws IOException, SQLException {
        // 1. 读取服务端握手包
        MysqlClientCodec.RawPacket handshakePkt = codec.readPacket();
        byte[] payload = handshakePkt.payload();
        int offset = 0;

        // 解析协议版本
        int protocolVersion = payload[offset++] & 0xFF;
        if (protocolVersion != MysqlConstants.PROTOCOL_VERSION) {
            throw new SQLException("不支持的协议版本: " + protocolVersion);
        }

        // 跳过 server version（NUL 结尾）
        while (offset < payload.length && payload[offset] != 0x00) offset++;
        offset++; // 跳过 NUL

        // connection id (4 bytes)
        offset += 4;

        // auth-plugin-data-part-1 (8 bytes)
        byte[] authPart1 = new byte[8];
        System.arraycopy(payload, offset, authPart1, 0, 8);
        offset += 8;

        // filler (1 byte)
        offset += 1;

        // capability flags lower (2 bytes)
        int capLower = (int) MysqlClientCodec.readFixedLengthInt(payload, offset, 2);
        offset += 2;

        // charset (1 byte)
        offset += 1;

        // status flags (2 bytes)
        offset += 2;

        // capability flags upper (2 bytes)
        int capUpper = (int) MysqlClientCodec.readFixedLengthInt(payload, offset, 2);
        offset += 2;
        this.serverCapabilities = capLower | (capUpper << 16);

        // auth plugin data length (1 byte)
        offset += 1;

        // reserved (10 bytes)
        offset += 10;

        // auth-plugin-data-part-2 (12 bytes)
        byte[] authPart2 = new byte[12];
        if (offset + 12 <= payload.length) {
            System.arraycopy(payload, offset, authPart2, 0, 12);
        }

        // 拼接 20 字节完整挑战
        byte[] challenge = new byte[20];
        System.arraycopy(authPart1, 0, challenge, 0, 8);
        System.arraycopy(authPart2, 0, challenge, 8, 12);

        // 2. 发送认证响应
        byte[] authToken = MysqlNativePasswordAuth.computeToken(password, challenge);
        byte[] authResponsePkt = buildHandshakeResponse(username, authToken, database);
        codec.writePacket(1, authResponsePkt);

        // 3. 读取认证结果
        MysqlClientCodec.RawPacket responsePkt = codec.readPacket();
        int header = responsePkt.payload()[0] & 0xFF;
        if (header == MysqlConstants.ERR_HEADER) {
            // 解析错误消息
            int errCode = (int) MysqlClientCodec.readFixedLengthInt(responsePkt.payload(), 1, 2);
            String errMsg = new String(responsePkt.payload(), 10, responsePkt.payload().length - 10, StandardCharsets.UTF_8);
            throw new SQLException("认证失败: [" + errCode + "] " + errMsg);
        }
        // OK 包：认证成功
    }

    /**
     * 构建 HandshakeResponse41 包。
     */
    private byte[] buildHandshakeResponse(String username, byte[] authToken, String database) {
        int clientCap = MysqlConstants.CLIENT_LONG_PASSWORD
                | MysqlConstants.CLIENT_PROTOCOL_41
                | MysqlConstants.CLIENT_SECURE_CONNECTION
                | MysqlConstants.CLIENT_PLUGIN_AUTH
                | MysqlConstants.CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA
                | MysqlConstants.CLIENT_TRANSACTIONS
                | MysqlConstants.CLIENT_MULTI_RESULTS;
        if (database != null && !database.isEmpty()) {
            clientCap |= MysqlConstants.CLIENT_CONNECT_WITH_DB;
        }

        byte[] usernameBytes = username.getBytes(StandardCharsets.UTF_8);
        byte[] dbBytes = (database != null && !database.isEmpty())
                ? database.getBytes(StandardCharsets.UTF_8) : null;
        byte[] pluginName = MysqlConstants.AUTH_PLUGIN_MYSQL_NATIVE_PASSWORD.getBytes(StandardCharsets.UTF_8);

        // 计算包大小
        int size = 4 + 4 + 1 + 23 // capability + max_packet + charset + reserved
                + usernameBytes.length + 1 // username + NUL
                + 1 + authToken.length // auth length + auth data
                + (dbBytes != null ? dbBytes.length + 1 : 0)
                + pluginName.length + 1;

        byte[] buf = new byte[size];
        int offset = 0;

        // capability flags (4 bytes)
        offset = MysqlClientCodec.writeFixedLengthInt(buf, offset, clientCap, 4);
        // max packet size (4 bytes)
        offset = MysqlClientCodec.writeFixedLengthInt(buf, offset, MysqlConstants.DEFAULT_MAX_PACKET_SIZE, 4);
        // charset (1 byte, utf8mb4)
        buf[offset++] = (byte) MysqlConstants.CHARSET_UTF8MB4;
        // reserved (23 bytes zeros)
        offset += 23;
        // username + NUL
        System.arraycopy(usernameBytes, 0, buf, offset, usernameBytes.length);
        offset += usernameBytes.length;
        buf[offset++] = 0x00;
        // auth response length + data
        buf[offset++] = (byte) authToken.length;
        System.arraycopy(authToken, 0, buf, offset, authToken.length);
        offset += authToken.length;
        // database + NUL
        if (dbBytes != null) {
            System.arraycopy(dbBytes, 0, buf, offset, dbBytes.length);
            offset += dbBytes.length;
            buf[offset++] = 0x00;
        }
        // auth plugin name + NUL
        System.arraycopy(pluginName, 0, buf, offset, pluginName.length);
        offset += pluginName.length;
        buf[offset++] = 0x00;

        // 返回实际使用的部分
        byte[] result = new byte[offset];
        System.arraycopy(buf, 0, result, 0, offset);
        return result;
    }

    /**
     * 内部发送 COM_QUERY 并返回原始响应包。
     * 供 Statement 和内部使用。
     */
    MysqlClientCodec.RawPacket sendQuery(String sql) throws SQLException {
        checkClosed();
        try {
            byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[1 + sqlBytes.length];
            payload[0] = MysqlConstants.COM_QUERY;
            System.arraycopy(sqlBytes, 0, payload, 1, sqlBytes.length);
            codec.writePacket(0, payload);
            return codec.readPacket();
        } catch (IOException e) {
            throw new SQLException("发送查询失败", e);
        }
    }

    /** 读取下一个包 */
    MysqlClientCodec.RawPacket readNextPacket() throws SQLException {
        try {
            return codec.readPacket();
        } catch (IOException e) {
            throw new SQLException("读取响应包失败", e);
        }
    }

    /** 发送原始包 */
    void sendRawPacket(int sequenceId, byte[] payload) throws SQLException {
        try {
            codec.writePacket(sequenceId, payload);
        } catch (IOException e) {
            throw new SQLException("发送包失败", e);
        }
    }

    MysqlClientCodec codec() { return codec; }

    // ==================== JDBC Connection 接口 ====================

    @Override
    public Statement createStatement() throws SQLException {
        checkClosed();
        return new MiniDbStatement(this);
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(String sql) throws SQLException {
        checkClosed();
        return new MiniDbPreparedStatement(this, sql);
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkClosed();
        if (this.autoCommit != autoCommit) {
            this.autoCommit = autoCommit;
            // 通过 SQL 通知服务端
            MiniDbStatement stmt = new MiniDbStatement(this);
            stmt.executeUpdate("SET autocommit = " + (autoCommit ? 1 : 0));
        }
    }

    @Override
    public boolean getAutoCommit() { return autoCommit; }

    @Override
    public void commit() throws SQLException {
        checkClosed();
        MiniDbStatement stmt = new MiniDbStatement(this);
        stmt.executeUpdate("COMMIT");
    }

    @Override
    public void rollback() throws SQLException {
        checkClosed();
        MiniDbStatement stmt = new MiniDbStatement(this);
        stmt.executeUpdate("ROLLBACK");
    }

    @Override
    public void close() throws SQLException {
        if (!closed) {
            try {
                // 发送 COM_QUIT
                byte[] quitPacket = new byte[]{MysqlConstants.COM_QUIT};
                codec.writePacket(0, quitPacket);
            } catch (IOException ignored) {
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                closed = true;
            }
        }
    }

    @Override
    public boolean isClosed() { return closed; }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        return new MiniDbDatabaseMetaData(this, database);
    }

    private void checkClosed() throws SQLException {
        if (closed) throw new SQLException("连接已关闭");
    }

    // ==================== 未实现的方法（抛出 SQLFeatureNotSupportedException） ====================

    @Override public String nativeSQL(String sql) { return sql; }
    @Override public void setReadOnly(boolean readOnly) {}
    @Override public boolean isReadOnly() { return false; }
    @Override public void setCatalog(String catalog) {}
    @Override public String getCatalog() { return database; }
    @Override public void setTransactionIsolation(int level) {}
    @Override public int getTransactionIsolation() { return Connection.TRANSACTION_REPEATABLE_READ; }
    @Override public SQLWarning getWarnings() { return null; }
    @Override public void clearWarnings() {}
    @Override public Statement createStatement(int rsc, int rcc) throws SQLException { return createStatement(); }
    @Override public java.sql.PreparedStatement prepareStatement(String sql, int rsc, int rcc) throws SQLException { return prepareStatement(sql); }
    @Override public CallableStatement prepareCall(String sql) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public CallableStatement prepareCall(String sql, int a, int b) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Map<String, Class<?>> getTypeMap() { return Map.of(); }
    @Override public void setTypeMap(Map<String, Class<?>> map) {}
    @Override public void setHoldability(int holdability) {}
    @Override public int getHoldability() { return ResultSet.HOLD_CURSORS_OVER_COMMIT; }
    @Override public Savepoint setSavepoint() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Savepoint setSavepoint(String name) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void rollback(Savepoint savepoint) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void releaseSavepoint(Savepoint savepoint) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Statement createStatement(int a, int b, int c) throws SQLException { return createStatement(); }
    @Override public java.sql.PreparedStatement prepareStatement(String sql, int a, int b, int c) throws SQLException { return prepareStatement(sql); }
    @Override public CallableStatement prepareCall(String sql, int a, int b, int c) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public java.sql.PreparedStatement prepareStatement(String sql, int auto) throws SQLException { return prepareStatement(sql); }
    @Override public java.sql.PreparedStatement prepareStatement(String sql, int[] cols) throws SQLException { return prepareStatement(sql); }
    @Override public java.sql.PreparedStatement prepareStatement(String sql, String[] cols) throws SQLException { return prepareStatement(sql); }
    @Override public Clob createClob() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Blob createBlob() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public NClob createNClob() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public SQLXML createSQLXML() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean isValid(int timeout) { return !closed; }
    @Override public void setClientInfo(String name, String value) {}
    @Override public void setClientInfo(Properties props) {}
    @Override public String getClientInfo(String name) { return null; }
    @Override public Properties getClientInfo() { return new Properties(); }
    @Override public Array createArrayOf(String typeName, Object[] elements) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Struct createStruct(String typeName, Object[] attrs) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setSchema(String schema) {}
    @Override public String getSchema() { return database; }
    @Override public void abort(Executor executor) throws SQLException { close(); }
    @Override public void setNetworkTimeout(Executor executor, int milliseconds) {}
    @Override public int getNetworkTimeout() { return 0; }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
}
