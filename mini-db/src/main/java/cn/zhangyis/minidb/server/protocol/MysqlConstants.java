package cn.zhangyis.minidb.server.protocol;

/**
 * MySQL 协议常量集中定义。
 *
 * <p>设计模式：常量类（Constant Class）——将所有协议魔数集中管理，
 * 避免散落在各处导致不一致。私有构造器防止实例化。</p>
 *
 * <p>参考 MySQL 官方文档：
 * <a href="https://dev.mysql.com/doc/dev/mysql-server/latest/page_protocol_basic_packets.html">MySQL Protocol</a></p>
 */
public final class MysqlConstants {

    private MysqlConstants() {}

    // ==================== 协议版本 ====================

    /** MySQL 协议版本号，握手包第一个字节 */
    public static final int PROTOCOL_VERSION = 10;

    /** 伪装的 MySQL 服务器版本，影响客户端行为分支 */
    public static final String SERVER_VERSION = "8.0.0-minidb";

    /** 默认字符集：utf8mb4 general ci */
    public static final int CHARSET_UTF8MB4 = 45;

    // ==================== 命令字节 ====================
    // 每个命令对应 COM_XXX packet 的第一个字节

    public static final byte COM_QUIT = 0x01;
    public static final byte COM_INIT_DB = 0x02;
    public static final byte COM_QUERY = 0x03;
    public static final byte COM_FIELD_LIST = 0x04;
    public static final byte COM_PING = 0x0E;
    public static final byte COM_STMT_PREPARE = 0x16;
    public static final byte COM_STMT_EXECUTE = 0x17;
    public static final byte COM_STMT_SEND_LONG_DATA = 0x18;
    public static final byte COM_STMT_CLOSE = 0x19;
    public static final byte COM_STMT_RESET = 0x1A;

    // ==================== 包头标记字节 ====================

    /** OK 包的标记字节 */
    public static final int OK_HEADER = 0x00;

    /** ERR 包的标记字节 */
    public static final int ERR_HEADER = 0xFF;

    /** EOF 包的标记字节 */
    public static final int EOF_HEADER = 0xFE;

    /** 二进制结果集行的 NULL 标记 */
    public static final int NULL_COLUMN_TEXT = 0xFB;

    // ==================== 能力标志（Capability Flags） ====================
    // 握手期间客户端/服务端协商使用哪些协议特性

    public static final int CLIENT_LONG_PASSWORD = 1;
    public static final int CLIENT_FOUND_ROWS = 1 << 1;
    public static final int CLIENT_LONG_FLAG = 1 << 2;
    public static final int CLIENT_CONNECT_WITH_DB = 1 << 3;
    public static final int CLIENT_NO_SCHEMA = 1 << 4;
    public static final int CLIENT_PROTOCOL_41 = 1 << 9;
    public static final int CLIENT_INTERACTIVE = 1 << 10;
    public static final int CLIENT_SSL = 1 << 11;
    public static final int CLIENT_TRANSACTIONS = 1 << 13;
    public static final int CLIENT_SECURE_CONNECTION = 1 << 15;
    public static final int CLIENT_MULTI_STATEMENTS = 1 << 16;
    public static final int CLIENT_MULTI_RESULTS = 1 << 17;
    public static final int CLIENT_PS_MULTI_RESULTS = 1 << 18;
    public static final int CLIENT_PLUGIN_AUTH = 1 << 19;
    public static final int CLIENT_CONNECT_ATTRS = 1 << 20;
    public static final int CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA = 1 << 21;
    public static final int CLIENT_DEPRECATE_EOF = 1 << 24;

    /** 服务端默认声明的能力集合 */
    public static final int SERVER_DEFAULT_CAPABILITIES =
            CLIENT_LONG_PASSWORD
            | CLIENT_FOUND_ROWS
            | CLIENT_LONG_FLAG
            | CLIENT_CONNECT_WITH_DB
            | CLIENT_PROTOCOL_41
            | CLIENT_TRANSACTIONS
            | CLIENT_SECURE_CONNECTION
            | CLIENT_MULTI_STATEMENTS
            | CLIENT_MULTI_RESULTS
            | CLIENT_PS_MULTI_RESULTS
            | CLIENT_PLUGIN_AUTH
            | CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA;

    // ==================== 服务状态标志（Status Flags） ====================
    // OK/EOF 包中携带，告知客户端当前服务端状态

    public static final int SERVER_STATUS_IN_TRANS = 0x0001;
    public static final int SERVER_STATUS_AUTOCOMMIT = 0x0002;
    public static final int SERVER_MORE_RESULTS_EXISTS = 0x0008;
    public static final int SERVER_STATUS_NO_GOOD_INDEX_USED = 0x0010;
    public static final int SERVER_STATUS_NO_INDEX_USED = 0x0020;
    public static final int SERVER_STATUS_CURSOR_EXISTS = 0x0040;
    public static final int SERVER_STATUS_LAST_ROW_SENT = 0x0080;

    // ==================== MySQL 列类型码 ====================
    // ColumnDefinitionPacket 和 COM_STMT_EXECUTE 中使用

    public static final int MYSQL_TYPE_DECIMAL = 0x00;
    public static final int MYSQL_TYPE_TINY = 0x01;
    public static final int MYSQL_TYPE_SHORT = 0x02;
    public static final int MYSQL_TYPE_LONG = 0x03;
    public static final int MYSQL_TYPE_FLOAT = 0x04;
    public static final int MYSQL_TYPE_DOUBLE = 0x05;
    public static final int MYSQL_TYPE_NULL = 0x06;
    public static final int MYSQL_TYPE_TIMESTAMP = 0x07;
    public static final int MYSQL_TYPE_LONGLONG = 0x08;
    public static final int MYSQL_TYPE_INT24 = 0x09;
    public static final int MYSQL_TYPE_DATE = 0x0A;
    public static final int MYSQL_TYPE_TIME = 0x0B;
    public static final int MYSQL_TYPE_DATETIME = 0x0C;
    public static final int MYSQL_TYPE_YEAR = 0x0D;
    public static final int MYSQL_TYPE_VARCHAR = 0x0F;
    public static final int MYSQL_TYPE_BIT = 0x10;
    public static final int MYSQL_TYPE_NEWDECIMAL = 0xF6;
    public static final int MYSQL_TYPE_BLOB = 0xFC;
    public static final int MYSQL_TYPE_VAR_STRING = 0xFD;
    public static final int MYSQL_TYPE_STRING = 0xFE;

    // ==================== 认证插件 ====================

    public static final String AUTH_PLUGIN_MYSQL_NATIVE_PASSWORD = "mysql_native_password";

    // ==================== 包大小限制 ====================

    /** 单个 MySQL 包最大 payload 长度（3字节能表示的最大值） */
    public static final int MAX_PACKET_PAYLOAD = 0xFFFFFF;

    /** 默认最大包大小（16MB） */
    public static final int DEFAULT_MAX_PACKET_SIZE = 1 << 24;

    // ==================== 列标志（Column Flags） ====================

    public static final int COLUMN_FLAG_NOT_NULL = 0x0001;
    public static final int COLUMN_FLAG_PRI_KEY = 0x0002;
    public static final int COLUMN_FLAG_UNIQUE_KEY = 0x0004;
    public static final int COLUMN_FLAG_BLOB = 0x0010;
    public static final int COLUMN_FLAG_UNSIGNED = 0x0020;
    public static final int COLUMN_FLAG_AUTO_INCREMENT = 0x0200;
}
