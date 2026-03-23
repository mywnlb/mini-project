package cn.zhangyis.minidb.jdbc;

import cn.zhangyis.minidb.server.protocol.MysqlConstants;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.sql.Date;
import java.util.*;

/**
 * JDBC PreparedStatement 实现（二进制协议）。
 *
 * <p>通过 COM_STMT_PREPARE 预编译 SQL，COM_STMT_EXECUTE 发送二进制参数，
 * COM_STMT_CLOSE 释放服务端资源。</p>
 *
 * <p>继承 {@link MiniDbStatement} 以复用 {@code readResultSet()} 和
 * {@code throwSqlException()} 等文本协议解析方法。二进制结果集解析
 * 由 {@link #readBinaryResultSet(byte[])} 单独处理。</p>
 */
public class MiniDbPreparedStatement extends MiniDbStatement implements PreparedStatement {

    private final String sql;
    private final int statementId;
    private final int numParams;
    private final Map<Integer, Object> parameters = new HashMap<>();
    private final Map<Integer, Integer> paramTypes = new HashMap<>();
    private boolean psClosed = false;

    /**
     * 构造时发送 COM_STMT_PREPARE，解析 StmtPrepareOk 响应。
     */
    public MiniDbPreparedStatement(MiniDbConnection connection, String sql) throws SQLException {
        super(connection);
        this.sql = sql;

        // 发送 COM_STMT_PREPARE
        byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[1 + sqlBytes.length];
        payload[0] = MysqlConstants.COM_STMT_PREPARE;
        System.arraycopy(sqlBytes, 0, payload, 1, sqlBytes.length);
        connection.sendRawPacket(0, payload);

        // 读取 StmtPrepareOk 响应
        MysqlClientCodec.RawPacket resp = connection.readNextPacket();
        byte[] respPayload = resp.payload();
        int header = respPayload[0] & 0xFF;

        if (header == MysqlConstants.ERR_HEADER) {
            throwSqlException(respPayload);
        }

        // 解析 StmtPrepareOk: status(1) + stmt_id(4) + num_columns(2) + num_params(2) + filler(1) + warnings(2)
        int offset = 1; // 跳过 status byte (0x00)
        this.statementId = (int) MysqlClientCodec.readFixedLengthInt(respPayload, offset, 4);
        offset += 4;
        int numColumns = (int) MysqlClientCodec.readFixedLengthInt(respPayload, offset, 2);
        offset += 2;
        this.numParams = (int) MysqlClientCodec.readFixedLengthInt(respPayload, offset, 2);

        // 消费参数列定义包 + EOF
        if (this.numParams > 0) {
            for (int i = 0; i < this.numParams; i++) {
                connection.readNextPacket(); // ColumnDefinition
            }
            connection.readNextPacket(); // EOF
        }

        // 消费结果列定义包 + EOF
        if (numColumns > 0) {
            for (int i = 0; i < numColumns; i++) {
                connection.readNextPacket(); // ColumnDefinition
            }
            connection.readNextPacket(); // EOF
        }
    }

    // ==================== 参数绑定 ====================

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        checkPsClosed();
        parameters.put(parameterIndex, x);
        paramTypes.put(parameterIndex, MysqlConstants.MYSQL_TYPE_LONG);
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        checkPsClosed();
        parameters.put(parameterIndex, x);
        paramTypes.put(parameterIndex, MysqlConstants.MYSQL_TYPE_LONGLONG);
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        checkPsClosed();
        parameters.put(parameterIndex, x);
        paramTypes.put(parameterIndex, MysqlConstants.MYSQL_TYPE_VAR_STRING);
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        checkPsClosed();
        parameters.put(parameterIndex, x);
        paramTypes.put(parameterIndex, MysqlConstants.MYSQL_TYPE_DOUBLE);
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        checkPsClosed();
        parameters.put(parameterIndex, (double) x);
        paramTypes.put(parameterIndex, MysqlConstants.MYSQL_TYPE_DOUBLE);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        checkPsClosed();
        parameters.put(parameterIndex, null);
        paramTypes.put(parameterIndex, MysqlConstants.MYSQL_TYPE_NULL);
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        checkPsClosed();
        if (x == null) {
            setNull(parameterIndex, Types.NULL);
        } else if (x instanceof Integer i) {
            setInt(parameterIndex, i);
        } else if (x instanceof Long l) {
            setLong(parameterIndex, l);
        } else if (x instanceof Double d) {
            setDouble(parameterIndex, d);
        } else if (x instanceof Float f) {
            setFloat(parameterIndex, f);
        } else if (x instanceof String s) {
            setString(parameterIndex, s);
        } else {
            setString(parameterIndex, x.toString());
        }
    }

    // ==================== 执行 ====================

    @Override
    public ResultSet executeQuery() throws SQLException {
        checkPsClosed();
        sendExecute();
        MysqlClientCodec.RawPacket firstPkt = connection.readNextPacket();
        byte[] payload = firstPkt.payload();
        int header = payload[0] & 0xFF;

        if (header == MysqlConstants.ERR_HEADER) {
            throwSqlException(payload);
        }
        if (header == MysqlConstants.OK_HEADER) {
            return new MiniDbResultSet(List.of(), List.of(), List.of());
        }
        return readBinaryResultSet(payload);
    }

    @Override
    public int executeUpdate() throws SQLException {
        checkPsClosed();
        sendExecute();
        MysqlClientCodec.RawPacket firstPkt = connection.readNextPacket();
        byte[] payload = firstPkt.payload();
        int header = payload[0] & 0xFF;

        if (header == MysqlConstants.ERR_HEADER) {
            throwSqlException(payload);
        }
        if (header == MysqlConstants.OK_HEADER) {
            long[] affected = MysqlClientCodec.readLengthEncodedInt(payload, 1);
            updateCount = (int) affected[0];
            return updateCount;
        }
        // 意外的结果集：消费掉
        readBinaryResultSet(payload);
        return 0;
    }

    @Override
    public boolean execute() throws SQLException {
        checkPsClosed();
        sendExecute();
        MysqlClientCodec.RawPacket firstPkt = connection.readNextPacket();
        byte[] payload = firstPkt.payload();
        int header = payload[0] & 0xFF;

        if (header == MysqlConstants.ERR_HEADER) {
            throwSqlException(payload);
        }
        if (header == MysqlConstants.OK_HEADER) {
            long[] affected = MysqlClientCodec.readLengthEncodedInt(payload, 1);
            updateCount = (int) affected[0];
            currentResultSet = null;
            return false;
        }
        currentResultSet = readBinaryResultSet(payload);
        updateCount = -1;
        return true;
    }

    @Override
    public void clearParameters() throws SQLException {
        checkPsClosed();
        parameters.clear();
        paramTypes.clear();
    }

    @Override
    public void close() {
        if (!psClosed) {
            psClosed = true;
            // 发送 COM_STMT_CLOSE（无响应）
            try {
                byte[] payload = new byte[5];
                payload[0] = MysqlConstants.COM_STMT_CLOSE;
                MysqlClientCodec.writeFixedLengthInt(payload, 1, statementId, 4);
                connection.sendRawPacket(0, payload);
            } catch (SQLException ignored) {
                // 关闭时忽略异常
            }
            super.close();
        }
    }

    // ==================== 二进制协议编码 ====================

    /**
     * 构建并发送 COM_STMT_EXECUTE 包。
     */
    private void sendExecute() throws SQLException {
        // 计算 null bitmap
        int bitmapLength = (numParams + 7) / 8;
        byte[] nullBitmap = new byte[bitmapLength];
        for (int i = 0; i < numParams; i++) {
            Object val = parameters.get(i + 1);
            if (val == null && parameters.containsKey(i + 1)) {
                int bytePos = i / 8;
                int bitPos = i % 8;
                nullBitmap[bytePos] |= (1 << bitPos);
            }
        }

        // 构建类型和值的字节
        byte[] typeBytes = new byte[numParams * 2];
        List<byte[]> valueBytesList = new ArrayList<>();
        for (int i = 0; i < numParams; i++) {
            int paramIdx = i + 1;
            int mysqlType = paramTypes.getOrDefault(paramIdx, MysqlConstants.MYSQL_TYPE_VAR_STRING);
            typeBytes[i * 2] = (byte) (mysqlType & 0xFF);
            typeBytes[i * 2 + 1] = 0; // unsigned flag

            Object val = parameters.get(paramIdx);
            if (val != null) {
                valueBytesList.add(encodeBinaryValue(val, mysqlType));
            }
        }

        // 计算总大小: cmd(1) + stmt_id(4) + flags(1) + iteration_count(4) + bitmap + new_params_bound(1) + types + values
        int totalValueSize = 0;
        for (byte[] vb : valueBytesList) totalValueSize += vb.length;

        int payloadSize = 1 + 4 + 1 + 4;
        if (numParams > 0) {
            payloadSize += bitmapLength + 1 + typeBytes.length + totalValueSize;
        }

        byte[] payload = new byte[payloadSize];
        int offset = 0;

        // command
        payload[offset++] = MysqlConstants.COM_STMT_EXECUTE;
        // statement_id
        offset = MysqlClientCodec.writeFixedLengthInt(payload, offset, statementId, 4);
        // flags (CURSOR_TYPE_NO_CURSOR = 0)
        payload[offset++] = 0x00;
        // iteration_count (固定 1)
        offset = MysqlClientCodec.writeFixedLengthInt(payload, offset, 1, 4);

        if (numParams > 0) {
            // null bitmap
            System.arraycopy(nullBitmap, 0, payload, offset, bitmapLength);
            offset += bitmapLength;
            // new_params_bound_flag = 1
            payload[offset++] = 0x01;
            // param types
            System.arraycopy(typeBytes, 0, payload, offset, typeBytes.length);
            offset += typeBytes.length;
            // param values
            for (byte[] vb : valueBytesList) {
                System.arraycopy(vb, 0, payload, offset, vb.length);
                offset += vb.length;
            }
        }

        connection.sendRawPacket(0, payload);
    }

    /**
     * 将 Java 值编码为 MySQL 二进制格式。
     */
    private byte[] encodeBinaryValue(Object value, int mysqlType) {
        return switch (mysqlType) {
            case MysqlConstants.MYSQL_TYPE_LONG -> {
                byte[] buf = new byte[4];
                MysqlClientCodec.writeFixedLengthInt(buf, 0, ((Number) value).intValue(), 4);
                yield buf;
            }
            case MysqlConstants.MYSQL_TYPE_LONGLONG -> {
                byte[] buf = new byte[8];
                MysqlClientCodec.writeFixedLengthInt(buf, 0, ((Number) value).longValue(), 8);
                yield buf;
            }
            case MysqlConstants.MYSQL_TYPE_DOUBLE -> {
                byte[] buf = new byte[8];
                long bits = Double.doubleToLongBits(((Number) value).doubleValue());
                MysqlClientCodec.writeFixedLengthInt(buf, 0, bits, 8);
                yield buf;
            }
            default -> {
                // VARCHAR / 其他：length-encoded string
                byte[] strBytes = value.toString().getBytes(StandardCharsets.UTF_8);
                byte[] lenPrefix = encodeLengthEncodedInt(strBytes.length);
                byte[] buf = new byte[lenPrefix.length + strBytes.length];
                System.arraycopy(lenPrefix, 0, buf, 0, lenPrefix.length);
                System.arraycopy(strBytes, 0, buf, lenPrefix.length, strBytes.length);
                yield buf;
            }
        };
    }

    /** 编码变长整数前缀 */
    private byte[] encodeLengthEncodedInt(long value) {
        if (value < 251) {
            return new byte[]{(byte) value};
        } else if (value < 65536) {
            byte[] buf = new byte[3];
            buf[0] = (byte) 0xFC;
            MysqlClientCodec.writeFixedLengthInt(buf, 1, value, 2);
            return buf;
        } else if (value < 16777216) {
            byte[] buf = new byte[4];
            buf[0] = (byte) 0xFD;
            MysqlClientCodec.writeFixedLengthInt(buf, 1, value, 3);
            return buf;
        } else {
            byte[] buf = new byte[9];
            buf[0] = (byte) 0xFE;
            MysqlClientCodec.writeFixedLengthInt(buf, 1, value, 8);
            return buf;
        }
    }

    // ==================== 二进制结果集解析 ====================

    /**
     * 读取二进制协议结果集。
     * 服务端对 COM_STMT_EXECUTE 的 SELECT 结果使用二进制行编码。
     */
    private MiniDbResultSet readBinaryResultSet(byte[] firstPayload) throws SQLException {
        // 列数量
        long[] colCountResult = MysqlClientCodec.readLengthEncodedInt(firstPayload, 0);
        int columnCount = (int) colCountResult[0];

        // 读取列定义
        List<String> columnNames = new ArrayList<>(columnCount);
        List<Integer> columnTypes = new ArrayList<>(columnCount);
        for (int i = 0; i < columnCount; i++) {
            MysqlClientCodec.RawPacket colPkt = connection.readNextPacket();
            byte[] colPayload = colPkt.payload();
            int off = 0;
            // 跳过 catalog, schema, table, orgTable
            off += MysqlClientCodec.lengthEncodedStringSize(colPayload, off);
            off += MysqlClientCodec.lengthEncodedStringSize(colPayload, off);
            off += MysqlClientCodec.lengthEncodedStringSize(colPayload, off);
            off += MysqlClientCodec.lengthEncodedStringSize(colPayload, off);
            // 读 name
            String colName = MysqlClientCodec.readLengthEncodedString(colPayload, off, StandardCharsets.UTF_8);
            columnNames.add(colName);
            off += MysqlClientCodec.lengthEncodedStringSize(colPayload, off);
            // 跳过 orgName
            off += MysqlClientCodec.lengthEncodedStringSize(colPayload, off);
            // filler(1) + charset(2) + columnLength(4)
            off += 1 + 2 + 4;
            // columnType (1 byte)
            int colType = colPayload[off] & 0xFF;
            columnTypes.add(colType);
        }

        // EOF（列定义结束）
        connection.readNextPacket();

        // 读取二进制行
        List<List<String>> rows = new ArrayList<>();
        while (true) {
            MysqlClientCodec.RawPacket rowPkt = connection.readNextPacket();
            byte[] rowPayload = rowPkt.payload();
            int rowHeader = rowPayload[0] & 0xFF;

            // EOF 判断
            if (rowHeader == MysqlConstants.EOF_HEADER && rowPayload.length <= 5) {
                break;
            }
            if (rowHeader == MysqlConstants.ERR_HEADER) {
                throwSqlException(rowPayload);
            }

            // 二进制行：0x00 header + NULL bitmap (offset=2) + typed values
            int off = 1; // 跳过 0x00 header
            int bitmapLen = (columnCount + 7 + 2) / 8;
            byte[] nullBmp = new byte[bitmapLen];
            System.arraycopy(rowPayload, off, nullBmp, 0, bitmapLen);
            off += bitmapLen;

            List<String> rowValues = new ArrayList<>(columnCount);
            for (int i = 0; i < columnCount; i++) {
                int bytePos = (i + 2) / 8;
                int bitPos = (i + 2) % 8;
                boolean isNull = (nullBmp[bytePos] & (1 << bitPos)) != 0;

                if (isNull) {
                    rowValues.add(null);
                } else {
                    off = readBinaryColumnValue(rowPayload, off, columnTypes.get(i), rowValues);
                }
            }
            rows.add(rowValues);
        }

        return new MiniDbResultSet(columnNames, columnTypes, rows);
    }

    /**
     * 从二进制行中读取单列值，转为字符串存入 rowValues。
     * 返回更新后的 offset。
     */
    private int readBinaryColumnValue(byte[] data, int offset, int mysqlType, List<String> rowValues) {
        switch (mysqlType) {
            case MysqlConstants.MYSQL_TYPE_LONG, MysqlConstants.MYSQL_TYPE_INT24 -> {
                int val = (int) MysqlClientCodec.readFixedLengthInt(data, offset, 4);
                rowValues.add(String.valueOf(val));
                return offset + 4;
            }
            case MysqlConstants.MYSQL_TYPE_LONGLONG -> {
                long val = MysqlClientCodec.readFixedLengthInt(data, offset, 8);
                rowValues.add(String.valueOf(val));
                return offset + 8;
            }
            case MysqlConstants.MYSQL_TYPE_TINY -> {
                int val = data[offset] & 0xFF;
                rowValues.add(String.valueOf(val));
                return offset + 1;
            }
            case MysqlConstants.MYSQL_TYPE_SHORT -> {
                int val = (int) MysqlClientCodec.readFixedLengthInt(data, offset, 2);
                rowValues.add(String.valueOf(val));
                return offset + 2;
            }
            case MysqlConstants.MYSQL_TYPE_DOUBLE -> {
                long bits = MysqlClientCodec.readFixedLengthInt(data, offset, 8);
                double val = Double.longBitsToDouble(bits);
                rowValues.add(String.valueOf(val));
                return offset + 8;
            }
            case MysqlConstants.MYSQL_TYPE_FLOAT -> {
                int bits = (int) MysqlClientCodec.readFixedLengthInt(data, offset, 4);
                float val = Float.intBitsToFloat(bits);
                rowValues.add(String.valueOf((double) val));
                return offset + 4;
            }
            default -> {
                // length-encoded string
                long[] lenResult = MysqlClientCodec.readLengthEncodedInt(data, offset);
                int strLen = (int) lenResult[0];
                int headerLen = (int) lenResult[1];
                String val = new String(data, offset + headerLen, strLen, StandardCharsets.UTF_8);
                rowValues.add(val);
                return offset + headerLen + strLen;
            }
        }
    }

    private void checkPsClosed() throws SQLException {
        if (psClosed) throw new SQLException("PreparedStatement 已关闭");
    }

    // ==================== 未实现的 PreparedStatement 方法 ====================

    @Override public void setBoolean(int pi, boolean x) throws SQLException { setInt(pi, x ? 1 : 0); }
    @Override public void setByte(int pi, byte x) throws SQLException { setInt(pi, x); }
    @Override public void setShort(int pi, short x) throws SQLException { setInt(pi, x); }
    @Override public void setBigDecimal(int pi, BigDecimal x) throws SQLException { setString(pi, x != null ? x.toPlainString() : null); }
    @Override public void setBytes(int pi, byte[] x) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setDate(int pi, Date x) throws SQLException { setString(pi, x != null ? x.toString() : null); }
    @Override public void setTime(int pi, Time x) throws SQLException { setString(pi, x != null ? x.toString() : null); }
    @Override public void setTimestamp(int pi, Timestamp x) throws SQLException { setString(pi, x != null ? x.toString() : null); }
    @Override public void setAsciiStream(int pi, InputStream x, int l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override @SuppressWarnings("deprecation") public void setUnicodeStream(int pi, InputStream x, int l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setBinaryStream(int pi, InputStream x, int l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setObject(int pi, Object x, int t) throws SQLException { setObject(pi, x); }
    @Override public void addBatch() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setCharacterStream(int pi, Reader r, int l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setRef(int pi, Ref x) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setBlob(int pi, Blob x) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setClob(int pi, Clob x) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setArray(int pi, Array x) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public ResultSetMetaData getMetaData() throws SQLException { return currentResultSet != null ? currentResultSet.getMetaData() : null; }
    @Override public void setDate(int pi, Date x, Calendar c) throws SQLException { setDate(pi, x); }
    @Override public void setTime(int pi, Time x, Calendar c) throws SQLException { setTime(pi, x); }
    @Override public void setTimestamp(int pi, Timestamp x, Calendar c) throws SQLException { setTimestamp(pi, x); }
    @Override public void setNull(int pi, int t, String tn) throws SQLException { setNull(pi, t); }
    @Override public void setURL(int pi, URL x) throws SQLException { setString(pi, x != null ? x.toString() : null); }
    @Override public ParameterMetaData getParameterMetaData() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setRowId(int pi, RowId x) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setNString(int pi, String x) throws SQLException { setString(pi, x); }
    @Override public void setNCharacterStream(int pi, Reader r, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setNClob(int pi, NClob x) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setClob(int pi, Reader r, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setBlob(int pi, InputStream is, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setNClob(int pi, Reader r, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setSQLXML(int pi, SQLXML x) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setObject(int pi, Object x, int t, int s) throws SQLException { setObject(pi, x); }
    @Override public void setAsciiStream(int pi, InputStream x, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setBinaryStream(int pi, InputStream x, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setCharacterStream(int pi, Reader r, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setAsciiStream(int pi, InputStream x) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setBinaryStream(int pi, InputStream x) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setCharacterStream(int pi, Reader r) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setNCharacterStream(int pi, Reader r) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setClob(int pi, Reader r) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setBlob(int pi, InputStream is) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void setNClob(int pi, Reader r) throws SQLException { throw new SQLFeatureNotSupportedException(); }
}
