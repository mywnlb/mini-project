package cn.zhangyis.minidb.server.protocol;

import cn.zhangyis.minidb.sql.types.SqlType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * SqlType 与 MySQL 协议列类型码之间的双向映射。
 *
 * <p>职责：
 * <ul>
 *   <li>服务端发送 ColumnDefinitionPacket 时将 SqlType 转为 MySQL type code</li>
 *   <li>二进制协议解码参数时将 MySQL type code 转回 SqlType</li>
 *   <li>推断 Java Object 的 MySQL 类型（用于计算列/无 schema 场景）</li>
 * </ul></p>
 *
 * <p>设计模式：工具类 + 查表法——用不可变 Map 实现 O(1) 查找，避免 switch 链。</p>
 */
public final class TypeMapping {

    private TypeMapping() {}

    // SqlType → MySQL type code
    private static final Map<SqlType, Integer> SQL_TO_MYSQL = Map.of(
            SqlType.INT32, MysqlConstants.MYSQL_TYPE_LONG,
            SqlType.BIGINT, MysqlConstants.MYSQL_TYPE_LONGLONG,
            SqlType.VARCHAR, MysqlConstants.MYSQL_TYPE_VAR_STRING,
            SqlType.DECIMAL, MysqlConstants.MYSQL_TYPE_NEWDECIMAL,
            SqlType.DATETIME, MysqlConstants.MYSQL_TYPE_DATETIME
    );

    // MySQL type code → SqlType
    private static final Map<Integer, SqlType> MYSQL_TO_SQL = Map.of(
            MysqlConstants.MYSQL_TYPE_LONG, SqlType.INT32,
            MysqlConstants.MYSQL_TYPE_LONGLONG, SqlType.BIGINT,
            MysqlConstants.MYSQL_TYPE_VAR_STRING, SqlType.VARCHAR,
            MysqlConstants.MYSQL_TYPE_NEWDECIMAL, SqlType.DECIMAL,
            MysqlConstants.MYSQL_TYPE_DATETIME, SqlType.DATETIME,
            MysqlConstants.MYSQL_TYPE_VARCHAR, SqlType.VARCHAR,
            MysqlConstants.MYSQL_TYPE_STRING, SqlType.VARCHAR,
            MysqlConstants.MYSQL_TYPE_BLOB, SqlType.VARCHAR,
            MysqlConstants.MYSQL_TYPE_SHORT, SqlType.INT32,
            MysqlConstants.MYSQL_TYPE_TINY, SqlType.INT32
    );

    /**
     * 将 mini-db SqlType 转为 MySQL 协议列类型码。
     */
    public static int toMysqlType(SqlType sqlType) {
        Integer code = SQL_TO_MYSQL.get(sqlType);
        if (code == null) {
            return MysqlConstants.MYSQL_TYPE_VAR_STRING; // 兜底为字符串
        }
        return code;
    }

    /**
     * 将 MySQL 协议列类型码转为 mini-db SqlType。
     */
    public static SqlType fromMysqlType(int mysqlType) {
        SqlType type = MYSQL_TO_SQL.get(mysqlType);
        if (type == null) {
            return SqlType.VARCHAR; // 未知类型兜底为字符串
        }
        return type;
    }

    /**
     * 从 Java 对象推断 MySQL 列类型码。
     *
     * <p>用于 SELECT 结果集中计算列（无 ColumnMeta）的类型推断。</p>
     */
    public static int inferMysqlType(Object value) {
        if (value == null) {
            return MysqlConstants.MYSQL_TYPE_NULL;
        } else if (value instanceof Integer) {
            return MysqlConstants.MYSQL_TYPE_LONG;
        } else if (value instanceof Long) {
            return MysqlConstants.MYSQL_TYPE_LONGLONG;
        } else if (value instanceof BigDecimal || value instanceof Double || value instanceof Float) {
            return MysqlConstants.MYSQL_TYPE_NEWDECIMAL;
        } else if (value instanceof LocalDateTime) {
            return MysqlConstants.MYSQL_TYPE_DATETIME;
        } else {
            return MysqlConstants.MYSQL_TYPE_VAR_STRING;
        }
    }

    /**
     * 返回 MySQL 列类型对应的默认显示长度。
     * 用于 ColumnDefinitionPacket 的 column_length 字段。
     */
    public static int defaultColumnLength(int mysqlType) {
        return switch (mysqlType) {
            case MysqlConstants.MYSQL_TYPE_TINY -> 4;
            case MysqlConstants.MYSQL_TYPE_SHORT -> 6;
            case MysqlConstants.MYSQL_TYPE_LONG -> 11;
            case MysqlConstants.MYSQL_TYPE_LONGLONG -> 20;
            case MysqlConstants.MYSQL_TYPE_FLOAT -> 12;
            case MysqlConstants.MYSQL_TYPE_DOUBLE -> 22;
            case MysqlConstants.MYSQL_TYPE_NEWDECIMAL -> 65;
            case MysqlConstants.MYSQL_TYPE_DATETIME, MysqlConstants.MYSQL_TYPE_TIMESTAMP -> 19;
            case MysqlConstants.MYSQL_TYPE_DATE -> 10;
            case MysqlConstants.MYSQL_TYPE_TIME -> 10;
            default -> 255; // VARCHAR 等字符串类型
        };
    }
}
