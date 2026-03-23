package cn.zhangyis.minidb.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.sql.Date;
import java.util.*;

/**
 * JDBC ResultSet 实现。
 *
 * <p>将服务端返回的文本协议行数据存储在内存中，
 * 提供标准 JDBC 游标遍历和类型化读取接口。</p>
 *
 * <p>所有数据以字符串形式存储（MySQL 文本协议特性），
 * getInt/getLong 等方法在读取时进行类型转换。</p>
 */
public class MiniDbResultSet implements ResultSet {

    private final List<String> columnNames;
    private final List<Integer> columnTypes;
    private final List<List<String>> rows;
    private int cursor = -1; // -1 = before first
    private boolean closed = false;
    private boolean wasNull = false;

    public MiniDbResultSet(List<String> columnNames, List<Integer> columnTypes, List<List<String>> rows) {
        this.columnNames = columnNames;
        this.columnTypes = columnTypes;
        this.rows = rows;
    }

    @Override
    public boolean next() throws SQLException {
        checkClosed();
        if (cursor + 1 < rows.size()) {
            cursor++;
            return true;
        }
        return false;
    }

    @Override
    public void close() { closed = true; }

    @Override
    public boolean isClosed() { return closed; }

    // ==================== 按索引读取（1-based） ====================

    @Override
    public String getString(int columnIndex) throws SQLException {
        String val = getRawValue(columnIndex);
        wasNull = (val == null);
        return val;
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        String val = getRawValue(columnIndex);
        wasNull = (val == null);
        if (val == null) return 0;
        return Integer.parseInt(val);
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        String val = getRawValue(columnIndex);
        wasNull = (val == null);
        if (val == null) return 0;
        return Long.parseLong(val);
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        String val = getRawValue(columnIndex);
        wasNull = (val == null);
        if (val == null) return 0;
        return Double.parseDouble(val);
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        String val = getRawValue(columnIndex);
        wasNull = (val == null);
        if (val == null) return null;
        return new BigDecimal(val);
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        return getString(columnIndex);
    }

    // ==================== 按名称读取 ====================

    @Override
    public String getString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return getBigDecimal(findColumn(columnLabel));
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public boolean wasNull() { return wasNull; }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        for (int i = 0; i < columnNames.size(); i++) {
            if (columnNames.get(i).equalsIgnoreCase(columnLabel)) {
                return i + 1;
            }
        }
        throw new SQLException("列不存在: " + columnLabel);
    }

    @Override
    public ResultSetMetaData getMetaData() {
        return new MiniDbResultSetMetaData(columnNames, columnTypes);
    }

    // ==================== 内部方法 ====================

    private String getRawValue(int columnIndex) throws SQLException {
        checkClosed();
        if (cursor < 0 || cursor >= rows.size()) {
            throw new SQLException("游标位置无效");
        }
        if (columnIndex < 1 || columnIndex > columnNames.size()) {
            throw new SQLException("列索引超出范围: " + columnIndex);
        }
        return rows.get(cursor).get(columnIndex - 1);
    }

    private void checkClosed() throws SQLException {
        if (closed) throw new SQLException("ResultSet 已关闭");
    }

    // ==================== 未实现的方法 ====================

    @Override public boolean getBoolean(int ci) throws SQLException { String v = getString(ci); return v != null && (v.equals("1") || v.equalsIgnoreCase("true")); }
    @Override public byte getByte(int ci) throws SQLException { return (byte) getInt(ci); }
    @Override public short getShort(int ci) throws SQLException { return (short) getInt(ci); }
    @Override public float getFloat(int ci) throws SQLException { return (float) getDouble(ci); }
    @Override public byte[] getBytes(int ci) throws SQLException { String v = getString(ci); return v == null ? null : v.getBytes(); }
    @Override public Date getDate(int ci) throws SQLException { String v = getString(ci); return v == null ? null : Date.valueOf(v); }
    @Override public Time getTime(int ci) throws SQLException { String v = getString(ci); return v == null ? null : Time.valueOf(v); }
    @Override public Timestamp getTimestamp(int ci) throws SQLException { String v = getString(ci); return v == null ? null : Timestamp.valueOf(v); }
    @Override public boolean getBoolean(String cl) throws SQLException { return getBoolean(findColumn(cl)); }
    @Override public byte getByte(String cl) throws SQLException { return getByte(findColumn(cl)); }
    @Override public short getShort(String cl) throws SQLException { return getShort(findColumn(cl)); }
    @Override public float getFloat(String cl) throws SQLException { return getFloat(findColumn(cl)); }
    @Override public byte[] getBytes(String cl) throws SQLException { return getBytes(findColumn(cl)); }
    @Override public Date getDate(String cl) throws SQLException { return getDate(findColumn(cl)); }
    @Override public Time getTime(String cl) throws SQLException { return getTime(findColumn(cl)); }
    @Override public Timestamp getTimestamp(String cl) throws SQLException { return getTimestamp(findColumn(cl)); }

    // 以下方法抛出不支持异常或返回默认值
    @Override public InputStream getAsciiStream(int ci) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override @SuppressWarnings("deprecation") public InputStream getUnicodeStream(int ci) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public InputStream getBinaryStream(int ci) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public InputStream getAsciiStream(String cl) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override @SuppressWarnings("deprecation") public InputStream getUnicodeStream(String cl) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public InputStream getBinaryStream(String cl) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public SQLWarning getWarnings() { return null; }
    @Override public void clearWarnings() {}
    @Override public String getCursorName() { return null; }
    @Override public Reader getCharacterStream(int ci) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Reader getCharacterStream(String cl) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override @SuppressWarnings("deprecation") public BigDecimal getBigDecimal(int ci, int scale) throws SQLException { return getBigDecimal(ci); }
    @Override @SuppressWarnings("deprecation") public BigDecimal getBigDecimal(String cl, int scale) throws SQLException { return getBigDecimal(cl); }
    @Override public boolean isBeforeFirst() { return cursor == -1; }
    @Override public boolean isAfterLast() { return cursor >= rows.size(); }
    @Override public boolean isFirst() { return cursor == 0; }
    @Override public boolean isLast() { return cursor == rows.size() - 1; }
    @Override public void beforeFirst() { cursor = -1; }
    @Override public void afterLast() { cursor = rows.size(); }
    @Override public boolean first() { if (rows.isEmpty()) return false; cursor = 0; return true; }
    @Override public boolean last() { if (rows.isEmpty()) return false; cursor = rows.size() - 1; return true; }
    @Override public int getRow() { return cursor + 1; }
    @Override public boolean absolute(int row) { cursor = row - 1; return cursor >= 0 && cursor < rows.size(); }
    @Override public boolean relative(int rows) { return absolute(cursor + 1 + rows); }
    @Override public boolean previous() { if (cursor > 0) { cursor--; return true; } return false; }
    @Override public void setFetchDirection(int d) {}
    @Override public int getFetchDirection() { return FETCH_FORWARD; }
    @Override public void setFetchSize(int r) {}
    @Override public int getFetchSize() { return 0; }
    @Override public int getType() { return TYPE_SCROLL_INSENSITIVE; }
    @Override public int getConcurrency() { return CONCUR_READ_ONLY; }
    @Override public boolean rowUpdated() { return false; }
    @Override public boolean rowInserted() { return false; }
    @Override public boolean rowDeleted() { return false; }
    @Override public void updateNull(int ci) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateBoolean(int ci, boolean v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateByte(int ci, byte v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateShort(int ci, short v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateInt(int ci, int v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateLong(int ci, long v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateFloat(int ci, float v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateDouble(int ci, double v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateBigDecimal(int ci, BigDecimal v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateString(int ci, String v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateBytes(int ci, byte[] v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateDate(int ci, Date v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateTime(int ci, Time v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateTimestamp(int ci, Timestamp v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateAsciiStream(int ci, InputStream v, int l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateBinaryStream(int ci, InputStream v, int l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateCharacterStream(int ci, Reader r, int l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateObject(int ci, Object v, int s) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateObject(int ci, Object v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateNull(String cl) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateBoolean(String cl, boolean v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateByte(String cl, byte v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateShort(String cl, short v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateInt(String cl, int v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateLong(String cl, long v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateFloat(String cl, float v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateDouble(String cl, double v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateBigDecimal(String cl, BigDecimal v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateString(String cl, String v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateBytes(String cl, byte[] v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateDate(String cl, Date v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateTime(String cl, Time v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateTimestamp(String cl, Timestamp v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateAsciiStream(String cl, InputStream v, int l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateBinaryStream(String cl, InputStream v, int l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateCharacterStream(String cl, Reader r, int l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateObject(String cl, Object v, int s) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateObject(String cl, Object v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void insertRow() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateRow() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void deleteRow() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void refreshRow() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void cancelRowUpdates() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void moveToInsertRow() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void moveToCurrentRow() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Statement getStatement() { return null; }
    @Override public Object getObject(int ci, Map<String, Class<?>> map) throws SQLException { return getObject(ci); }
    @Override public Ref getRef(int ci) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Blob getBlob(int ci) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Clob getClob(int ci) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Array getArray(int ci) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Object getObject(String cl, Map<String, Class<?>> map) throws SQLException { return getObject(cl); }
    @Override public Ref getRef(String cl) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Blob getBlob(String cl) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Clob getClob(String cl) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Array getArray(String cl) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Date getDate(int ci, Calendar cal) throws SQLException { return getDate(ci); }
    @Override public Date getDate(String cl, Calendar cal) throws SQLException { return getDate(cl); }
    @Override public Time getTime(int ci, Calendar cal) throws SQLException { return getTime(ci); }
    @Override public Time getTime(String cl, Calendar cal) throws SQLException { return getTime(cl); }
    @Override public Timestamp getTimestamp(int ci, Calendar cal) throws SQLException { return getTimestamp(ci); }
    @Override public Timestamp getTimestamp(String cl, Calendar cal) throws SQLException { return getTimestamp(cl); }
    @Override public URL getURL(int ci) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public URL getURL(String cl) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateRef(int ci, Ref v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateRef(String cl, Ref v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateBlob(int ci, Blob v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateBlob(String cl, Blob v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateClob(int ci, Clob v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateClob(String cl, Clob v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateArray(int ci, Array v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateArray(String cl, Array v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public RowId getRowId(int ci) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public RowId getRowId(String cl) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateRowId(int ci, RowId v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateRowId(String cl, RowId v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public int getHoldability() { return HOLD_CURSORS_OVER_COMMIT; }
    @Override public void updateNString(int ci, String v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateNString(String cl, String v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateNClob(int ci, NClob v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateNClob(String cl, NClob v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public NClob getNClob(int ci) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public NClob getNClob(String cl) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public SQLXML getSQLXML(int ci) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public SQLXML getSQLXML(String cl) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateSQLXML(int ci, SQLXML v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateSQLXML(String cl, SQLXML v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public String getNString(int ci) throws SQLException { return getString(ci); }
    @Override public String getNString(String cl) throws SQLException { return getString(cl); }
    @Override public Reader getNCharacterStream(int ci) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Reader getNCharacterStream(String cl) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateNCharacterStream(int ci, Reader r, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateNCharacterStream(String cl, Reader r, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateAsciiStream(int ci, InputStream v, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateBinaryStream(int ci, InputStream v, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateCharacterStream(int ci, Reader r, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateAsciiStream(String cl, InputStream v, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateBinaryStream(String cl, InputStream v, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateCharacterStream(String cl, Reader r, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateBlob(int ci, InputStream v, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateBlob(String cl, InputStream v, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateClob(int ci, Reader r, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateClob(String cl, Reader r, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateNClob(int ci, Reader r, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateNClob(String cl, Reader r, long l) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateNCharacterStream(int ci, Reader r) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateNCharacterStream(String cl, Reader r) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateAsciiStream(int ci, InputStream v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateBinaryStream(int ci, InputStream v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateCharacterStream(int ci, Reader r) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateAsciiStream(String cl, InputStream v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateBinaryStream(String cl, InputStream v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateCharacterStream(String cl, Reader r) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateBlob(int ci, InputStream v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateBlob(String cl, InputStream v) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateClob(int ci, Reader r) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateClob(String cl, Reader r) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateNClob(int ci, Reader r) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateNClob(String cl, Reader r) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T getObject(int ci, Class<T> type) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T getObject(String cl, Class<T> type) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
}
