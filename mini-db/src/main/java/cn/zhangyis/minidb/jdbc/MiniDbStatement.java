package cn.zhangyis.minidb.jdbc;

import cn.zhangyis.minidb.server.protocol.MysqlConstants;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC Statement 实现（文本协议）。
 *
 * <p>通过 COM_QUERY 发送 SQL 文本，解析服务端返回的
 * OK 包（DML）或结果集（SELECT）。</p>
 */
public class MiniDbStatement implements Statement {

    protected final MiniDbConnection connection;
    protected MiniDbResultSet currentResultSet;
    protected int updateCount = -1;
    private boolean closed = false;

    public MiniDbStatement(MiniDbConnection connection) {
        this.connection = connection;
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        checkClosed();
        MysqlClientCodec.RawPacket firstPkt = connection.sendQuery(sql);
        byte[] payload = firstPkt.payload();
        int header = payload[0] & 0xFF;

        if (header == MysqlConstants.ERR_HEADER) {
            throwSqlException(payload);
        }

        if (header == MysqlConstants.OK_HEADER) {
            // DML 结果，不应出现在 executeQuery 中，但兼容处理
            return new MiniDbResultSet(List.of(), List.of(), List.of());
        }

        // 结果集：header 是列数量（length-encoded int）
        return readResultSet(payload);
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        checkClosed();
        MysqlClientCodec.RawPacket firstPkt = connection.sendQuery(sql);
        byte[] payload = firstPkt.payload();
        int header = payload[0] & 0xFF;

        if (header == MysqlConstants.ERR_HEADER) {
            throwSqlException(payload);
        }

        if (header == MysqlConstants.OK_HEADER) {
            long[] affectedResult = MysqlClientCodec.readLengthEncodedInt(payload, 1);
            updateCount = (int) affectedResult[0];
            return updateCount;
        }

        // SELECT 意外出现在 executeUpdate 中：消费掉结果集
        readResultSet(payload);
        return 0;
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        checkClosed();
        MysqlClientCodec.RawPacket firstPkt = connection.sendQuery(sql);
        byte[] payload = firstPkt.payload();
        int header = payload[0] & 0xFF;

        if (header == MysqlConstants.ERR_HEADER) {
            throwSqlException(payload);
        }

        if (header == MysqlConstants.OK_HEADER) {
            long[] affectedResult = MysqlClientCodec.readLengthEncodedInt(payload, 1);
            updateCount = (int) affectedResult[0];
            currentResultSet = null;
            return false; // 无结果集
        }

        // 结果集
        currentResultSet = readResultSet(payload);
        updateCount = -1;
        return true;
    }

    /**
     * 读取完整的文本协议结果集。
     */
    protected MiniDbResultSet readResultSet(byte[] firstPayload) throws SQLException {
        // 第一个包是列数量
        long[] colCountResult = MysqlClientCodec.readLengthEncodedInt(firstPayload, 0);
        int columnCount = (int) colCountResult[0];

        // 读取列定义
        List<String> columnNames = new ArrayList<>(columnCount);
        List<Integer> columnTypes = new ArrayList<>(columnCount);
        for (int i = 0; i < columnCount; i++) {
            MysqlClientCodec.RawPacket colPkt = connection.readNextPacket();
            byte[] colPayload = colPkt.payload();
            // 解析 ColumnDefinition 包：跳过 catalog/schema/table/orgTable，读 name
            int offset = 0;
            // 跳过 catalog
            offset += MysqlClientCodec.lengthEncodedStringSize(colPayload, offset);
            // 跳过 schema
            offset += MysqlClientCodec.lengthEncodedStringSize(colPayload, offset);
            // 跳过 table
            offset += MysqlClientCodec.lengthEncodedStringSize(colPayload, offset);
            // 跳过 orgTable
            offset += MysqlClientCodec.lengthEncodedStringSize(colPayload, offset);
            // 读 name
            String colName = MysqlClientCodec.readLengthEncodedString(colPayload, offset, StandardCharsets.UTF_8);
            columnNames.add(colName);
            offset += MysqlClientCodec.lengthEncodedStringSize(colPayload, offset);
            // 跳过 orgName
            offset += MysqlClientCodec.lengthEncodedStringSize(colPayload, offset);
            // 跳过 filler(1) + charset(2) + columnLength(4)
            offset += 1 + 2 + 4;
            // columnType (1 byte)
            int colType = colPayload[offset] & 0xFF;
            columnTypes.add(colType);
        }

        // 读 EOF 包（列定义结束）
        connection.readNextPacket();

        // 读取行数据，直到遇到 EOF 包
        List<List<String>> rows = new ArrayList<>();
        while (true) {
            MysqlClientCodec.RawPacket rowPkt = connection.readNextPacket();
            byte[] rowPayload = rowPkt.payload();
            int rowHeader = rowPayload[0] & 0xFF;

            // EOF 包判断：header == 0xFE 且 payload <= 5 字节
            if (rowHeader == MysqlConstants.EOF_HEADER && rowPayload.length <= 5) {
                break;
            }

            // ERR 包
            if (rowHeader == MysqlConstants.ERR_HEADER) {
                throwSqlException(rowPayload);
            }

            // 解析文本行（每列为 length-encoded string，NULL = 0xFB）
            List<String> rowValues = new ArrayList<>(columnCount);
            int offset = 0;
            for (int i = 0; i < columnCount; i++) {
                int fb = rowPayload[offset] & 0xFF;
                if (fb == MysqlConstants.NULL_COLUMN_TEXT) {
                    rowValues.add(null);
                    offset += 1;
                } else {
                    String val = MysqlClientCodec.readLengthEncodedString(rowPayload, offset, StandardCharsets.UTF_8);
                    offset += MysqlClientCodec.lengthEncodedStringSize(rowPayload, offset);
                    rowValues.add(val);
                }
            }
            rows.add(rowValues);
        }

        return new MiniDbResultSet(columnNames, columnTypes, rows);
    }

    /** 将 ERR 包转为 SQLException */
    protected void throwSqlException(byte[] payload) throws SQLException {
        int errCode = (int) MysqlClientCodec.readFixedLengthInt(payload, 1, 2);
        // 跳过 sql_state_marker(1) + sql_state(5) = 6 字节
        String errMsg = new String(payload, 10, payload.length - 10, StandardCharsets.UTF_8);
        throw new SQLException("[" + errCode + "] " + errMsg, "HY000", errCode);
    }

    @Override
    public ResultSet getResultSet() { return currentResultSet; }

    @Override
    public int getUpdateCount() { return updateCount; }

    @Override
    public void close() { closed = true; }

    @Override
    public boolean isClosed() { return closed; }

    @Override
    public Connection getConnection() { return connection; }

    protected void checkClosed() throws SQLException {
        if (closed) throw new SQLException("Statement 已关闭");
    }

    // ==================== 未实现的方法 ====================

    @Override public int getMaxFieldSize() { return 0; }
    @Override public void setMaxFieldSize(int max) {}
    @Override public int getMaxRows() { return 0; }
    @Override public void setMaxRows(int max) {}
    @Override public void setEscapeProcessing(boolean enable) {}
    @Override public int getQueryTimeout() { return 0; }
    @Override public void setQueryTimeout(int seconds) {}
    @Override public void cancel() {}
    @Override public SQLWarning getWarnings() { return null; }
    @Override public void clearWarnings() {}
    @Override public void setCursorName(String name) {}
    @Override public boolean getMoreResults() { return false; }
    @Override public void setFetchDirection(int direction) {}
    @Override public int getFetchDirection() { return ResultSet.FETCH_FORWARD; }
    @Override public void setFetchSize(int rows) {}
    @Override public int getFetchSize() { return 0; }
    @Override public int getResultSetConcurrency() { return ResultSet.CONCUR_READ_ONLY; }
    @Override public int getResultSetType() { return ResultSet.TYPE_FORWARD_ONLY; }
    @Override public void addBatch(String sql) {}
    @Override public void clearBatch() {}
    @Override public int[] executeBatch() { return new int[0]; }
    @Override public boolean getMoreResults(int current) { return false; }
    @Override public ResultSet getGeneratedKeys() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public int executeUpdate(String sql, int a) throws SQLException { return executeUpdate(sql); }
    @Override public int executeUpdate(String sql, int[] a) throws SQLException { return executeUpdate(sql); }
    @Override public int executeUpdate(String sql, String[] a) throws SQLException { return executeUpdate(sql); }
    @Override public boolean execute(String sql, int a) throws SQLException { return execute(sql); }
    @Override public boolean execute(String sql, int[] a) throws SQLException { return execute(sql); }
    @Override public boolean execute(String sql, String[] a) throws SQLException { return execute(sql); }
    @Override public int getResultSetHoldability() { return ResultSet.HOLD_CURSORS_OVER_COMMIT; }
    @Override public void setPoolable(boolean poolable) {}
    @Override public boolean isPoolable() { return false; }
    @Override public void closeOnCompletion() {}
    @Override public boolean isCloseOnCompletion() { return false; }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
}
