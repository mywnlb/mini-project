package cn.zhangyis.minidb.jdbc;

import cn.zhangyis.minidb.server.protocol.MysqlConstants;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * JDBC ResultSetMetaData 实现。
 *
 * <p>提供列数量、列名、列类型等元数据。
 * 将 MySQL 协议列类型码映射到 {@link java.sql.Types} 常量。</p>
 */
public class MiniDbResultSetMetaData implements ResultSetMetaData {

    private final List<String> columnNames;
    private final List<Integer> columnTypes; // MySQL type codes

    public MiniDbResultSetMetaData(List<String> columnNames, List<Integer> columnTypes) {
        this.columnNames = columnNames;
        this.columnTypes = columnTypes;
    }

    @Override
    public int getColumnCount() {
        return columnNames.size();
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        checkIndex(column);
        return columnNames.get(column - 1);
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        return getColumnName(column);
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        checkIndex(column);
        return mysqlTypeToJdbcType(columnTypes.get(column - 1));
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        checkIndex(column);
        int mysqlType = columnTypes.get(column - 1);
        return switch (mysqlType) {
            case MysqlConstants.MYSQL_TYPE_LONG -> "INT";
            case MysqlConstants.MYSQL_TYPE_LONGLONG -> "BIGINT";
            case MysqlConstants.MYSQL_TYPE_VAR_STRING, MysqlConstants.MYSQL_TYPE_STRING -> "VARCHAR";
            case MysqlConstants.MYSQL_TYPE_NEWDECIMAL -> "DECIMAL";
            case MysqlConstants.MYSQL_TYPE_DATETIME -> "DATETIME";
            case MysqlConstants.MYSQL_TYPE_DOUBLE -> "DOUBLE";
            case MysqlConstants.MYSQL_TYPE_FLOAT -> "FLOAT";
            default -> "VARCHAR";
        };
    }

    /**
     * MySQL 列类型码 → java.sql.Types 常量。
     */
    private int mysqlTypeToJdbcType(int mysqlType) {
        return switch (mysqlType) {
            case MysqlConstants.MYSQL_TYPE_TINY -> Types.TINYINT;
            case MysqlConstants.MYSQL_TYPE_SHORT -> Types.SMALLINT;
            case MysqlConstants.MYSQL_TYPE_LONG, MysqlConstants.MYSQL_TYPE_INT24 -> Types.INTEGER;
            case MysqlConstants.MYSQL_TYPE_LONGLONG -> Types.BIGINT;
            case MysqlConstants.MYSQL_TYPE_FLOAT -> Types.FLOAT;
            case MysqlConstants.MYSQL_TYPE_DOUBLE -> Types.DOUBLE;
            case MysqlConstants.MYSQL_TYPE_NEWDECIMAL, MysqlConstants.MYSQL_TYPE_DECIMAL -> Types.DECIMAL;
            case MysqlConstants.MYSQL_TYPE_DATETIME, MysqlConstants.MYSQL_TYPE_TIMESTAMP -> Types.TIMESTAMP;
            case MysqlConstants.MYSQL_TYPE_DATE -> Types.DATE;
            case MysqlConstants.MYSQL_TYPE_TIME -> Types.TIME;
            default -> Types.VARCHAR;
        };
    }

    private void checkIndex(int column) throws SQLException {
        if (column < 1 || column > columnNames.size()) {
            throw new SQLException("列索引超出范围: " + column);
        }
    }

    // ==================== 其他方法返回默认值 ====================

    @Override public boolean isAutoIncrement(int c) { return false; }
    @Override public boolean isCaseSensitive(int c) { return false; }
    @Override public boolean isSearchable(int c) { return true; }
    @Override public boolean isCurrency(int c) { return false; }
    @Override public int isNullable(int c) { return columnNullable; }
    @Override public boolean isSigned(int c) { return true; }
    @Override public int getColumnDisplaySize(int c) { return 255; }
    @Override public String getSchemaName(int c) { return ""; }
    @Override public int getPrecision(int c) { return 0; }
    @Override public int getScale(int c) { return 0; }
    @Override public String getTableName(int c) { return ""; }
    @Override public String getCatalogName(int c) { return ""; }
    @Override public String getColumnClassName(int c) { return "java.lang.String"; }
    @Override public boolean isReadOnly(int c) { return true; }
    @Override public boolean isWritable(int c) { return false; }
    @Override public boolean isDefinitelyWritable(int c) { return false; }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("Not supported"); }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
}
