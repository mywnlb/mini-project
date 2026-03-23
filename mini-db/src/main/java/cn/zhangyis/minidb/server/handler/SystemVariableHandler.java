package cn.zhangyis.minidb.server.handler;

import cn.zhangyis.minidb.server.ConnectionSession;
import cn.zhangyis.minidb.server.netty.PacketWriter;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import cn.zhangyis.minidb.server.protocol.packets.*;
import cn.zhangyis.minidb.sql.exec.Row;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL 客户端兼容性查询拦截器。
 *
 * <p>mysql CLI 和 JDBC 驱动在连接建立后会发送一系列探测/配置查询
 * （如 SELECT @@version_comment、SET NAMES utf8mb4 等），
 * 这些查询不需要走完整的 SQL 引擎，在协议层直接拦截响应即可。</p>
 *
 * <p>设计模式：拦截器/责任链变体——{@link #tryHandle} 方法尝试匹配已知模式，
 * 匹配成功返回 true（已处理），否则返回 false（交给 SQL 引擎）。</p>
 */
public class SystemVariableHandler {

    // 匹配 SELECT @@variable 模式
    private static final Pattern SELECT_SYSVAR = Pattern.compile(
            "(?i)^\\s*SELECT\\s+@@(\\w+)\\s*$");

    // 匹配 SET NAMES xxx
    private static final Pattern SET_NAMES = Pattern.compile(
            "(?i)^\\s*SET\\s+NAMES\\s+\\S+.*$");

    // 匹配 SET character_set_xxx / SET character_set_results
    private static final Pattern SET_CHARSET = Pattern.compile(
            "(?i)^\\s*SET\\s+(character_set_\\w+|character_set_results)\\s*=.*$");

    // 匹配 SET autocommit = 0/1
    private static final Pattern SET_AUTOCOMMIT = Pattern.compile(
            "(?i)^\\s*SET\\s+autocommit\\s*=\\s*(\\d+)\\s*$");

    // 匹配 SHOW WARNINGS
    private static final Pattern SHOW_WARNINGS = Pattern.compile(
            "(?i)^\\s*SHOW\\s+WARNINGS\\s*$");

    // 匹配 SELECT DATABASE()
    private static final Pattern SELECT_DATABASE = Pattern.compile(
            "(?i)^\\s*SELECT\\s+DATABASE\\s*\\(\\s*\\)\\s*$");

    // 匹配 SHOW TABLES
    private static final Pattern SHOW_TABLES = Pattern.compile(
            "(?i)^\\s*SHOW\\s+TABLES\\s*$");

    // 匹配 BEGIN / COMMIT / ROLLBACK
    private static final Pattern TXN_CONTROL = Pattern.compile(
            "(?i)^\\s*(BEGIN|COMMIT|ROLLBACK)\\s*$");

    /**
     * 执行拦截类查询并返回结果行（用于二进制协议 COM_STMT_EXECUTE）。
     * 返回 null 表示该 SQL 不匹配任何拦截模式。
     */
    public static List<Row> executeIntercepted(String sql, ConnectionSession session) {
        // SELECT @@variable
        Matcher m = SELECT_SYSVAR.matcher(sql);
        if (m.matches()) {
            String varName = m.group(1).toLowerCase();
            String value = resolveSystemVariable(varName, session);
            return List.of(new Row(Map.of("@@" + varName, (Object) value)));
        }

        // SET NAMES / SET character_set / SET autocommit → DML 类，返回空
        if (SET_NAMES.matcher(sql).matches() || SET_CHARSET.matcher(sql).matches()) {
            return List.of();
        }

        Matcher am = SET_AUTOCOMMIT.matcher(sql);
        if (am.matches()) {
            int val = Integer.parseInt(am.group(1));
            if (session.executionContext().txnManager() != null) {
                if (val == 0 && !session.executionContext().inTransaction()) {
                    session.executionContext().begin();
                } else if (val == 1 && session.executionContext().inTransaction()) {
                    session.executionContext().commit();
                }
            }
            return List.of();
        }

        if (SHOW_WARNINGS.matcher(sql).matches()) {
            return List.of();
        }

        if (SELECT_DATABASE.matcher(sql).matches()) {
            String db = session.currentDatabase();
            return List.of(new Row(Map.of("DATABASE()", (Object) (db != null ? db : ""))));
        }

        if (SHOW_TABLES.matcher(sql).matches()) {
            String db = session.currentDatabase();
            String columnName = "Tables_in_" + (db != null ? db : "");
            List<String> tables = session.catalog().listTables(db != null ? db : "");
            List<Row> rows = new java.util.ArrayList<>();
            for (String table : tables) {
                rows.add(new Row(Map.of(columnName, (Object) table)));
            }
            return rows;
        }

        return null;
    }

    /**
     * 检查 SQL 是否为可拦截的兼容性查询（不执行，仅判断）。
     * 用于 COM_STMT_PREPARE 阶段判断是否需要走拦截路径。
     */
    public static boolean canHandle(String sql) {
        return SELECT_SYSVAR.matcher(sql).matches()
                || SET_NAMES.matcher(sql).matches()
                || SET_CHARSET.matcher(sql).matches()
                || SET_AUTOCOMMIT.matcher(sql).matches()
                || SHOW_WARNINGS.matcher(sql).matches()
                || SELECT_DATABASE.matcher(sql).matches()
                || SHOW_TABLES.matcher(sql).matches();
    }

    /**
     * 尝试拦截并处理兼容性查询。
     *
     * @return true 如果已处理（调用方不应再转发到 SQL 引擎），false 需要正常执行
     */
    public static boolean tryHandle(String sql, ConnectionSession session, PacketWriter writer) {
        // SELECT @@variable
        Matcher sysvarMatcher = SELECT_SYSVAR.matcher(sql);
        if (sysvarMatcher.matches()) {
            String varName = sysvarMatcher.group(1).toLowerCase();
            String value = resolveSystemVariable(varName, session);
            writeSingleValueResult(writer, "@@" + varName, value, session);
            return true;
        }

        // SET NAMES
        if (SET_NAMES.matcher(sql).matches()) {
            writeOk(writer, session);
            return true;
        }

        // SET character_set_xxx
        if (SET_CHARSET.matcher(sql).matches()) {
            writeOk(writer, session);
            return true;
        }

        // SET autocommit
        Matcher autocommitMatcher = SET_AUTOCOMMIT.matcher(sql);
        if (autocommitMatcher.matches()) {
            int val = Integer.parseInt(autocommitMatcher.group(1));
            if (session.executionContext().txnManager() != null) {
                if (val == 0 && !session.executionContext().inTransaction()) {
                    session.executionContext().begin();
                } else if (val == 1 && session.executionContext().inTransaction()) {
                    session.executionContext().commit();
                }
            }
            writeOk(writer, session);
            return true;
        }

        // SHOW WARNINGS
        if (SHOW_WARNINGS.matcher(sql).matches()) {
            writeEmptyResultSet(writer, session,
                    List.of("Level", "Code", "Message"),
                    List.of(MysqlConstants.MYSQL_TYPE_VAR_STRING,
                            MysqlConstants.MYSQL_TYPE_LONG,
                            MysqlConstants.MYSQL_TYPE_VAR_STRING));
            return true;
        }

        // SELECT DATABASE()
        if (SELECT_DATABASE.matcher(sql).matches()) {
            String db = session.currentDatabase();
            writeSingleValueResult(writer, "DATABASE()", db != null ? db : "", session);
            return true;
        }

        // SHOW TABLES
        if (SHOW_TABLES.matcher(sql).matches()) {
            handleShowTables(session, writer);
            return true;
        }

        // BEGIN / COMMIT / ROLLBACK（txnManager 为 null 时直接返回 OK）
        Matcher txnMatcher = TXN_CONTROL.matcher(sql);
        if (txnMatcher.matches()) {
            String cmd = txnMatcher.group(1).toUpperCase();
            if (session.executionContext().txnManager() != null) {
                return false; // 有 txnManager，交给 SQL 引擎正常处理
            }
            // 无 txnManager（测试环境），直接返回 OK
            writeOk(writer, session);
            return true;
        }

        return false; // 未拦截，需要正常执行
    }

    private static String resolveSystemVariable(String varName, ConnectionSession session) {
        return switch (varName) {
            case "version_comment" -> "mini-db";
            case "version" -> MysqlConstants.SERVER_VERSION;
            case "max_allowed_packet" -> String.valueOf(MysqlConstants.DEFAULT_MAX_PACKET_SIZE);
            case "character_set_client", "character_set_connection",
                 "character_set_results", "character_set_server" -> "utf8mb4";
            case "collation_connection", "collation_server" -> "utf8mb4_general_ci";
            case "tx_isolation", "transaction_isolation" -> "REPEATABLE-READ";
            case "autocommit" -> session.executionContext().inTransaction() ? "0" : "1";
            case "wait_timeout" -> "28800";
            case "interactive_timeout" -> "28800";
            case "net_write_timeout" -> "60";
            case "sql_mode" -> "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES";
            case "lower_case_table_names" -> "0";
            default -> "";
        };
    }

    /** 写入单值结果集（1列1行） */
    private static void writeSingleValueResult(PacketWriter writer, String columnName,
                                                String value, ConnectionSession session) {
        int statusFlags = StatusFlagBuilder.build(session.executionContext());

        // 列数量
        writer.writeColumnCount(1);
        // 列定义
        writer.writeColumnDefinition(new ColumnDefinitionPacket.Builder()
                .name(columnName).orgName(columnName)
                .columnType(MysqlConstants.MYSQL_TYPE_VAR_STRING)
                .columnLength(255)
                .build());
        // EOF
        writer.writeEof(new EofPacket(0, statusFlags));
        // 行数据
        writer.writeResultSetRow(new ResultSetRowPacket(List.of(value)));
        // 结束 EOF
        writer.writeEof(new EofPacket(0, statusFlags));
        writer.flush();
    }

    /** 写入空结果集（有列定义但无数据行） */
    private static void writeEmptyResultSet(PacketWriter writer, ConnectionSession session,
                                             List<String> columnNames, List<Integer> columnTypes) {
        int statusFlags = StatusFlagBuilder.build(session.executionContext());
        int count = columnNames.size();

        writer.writeColumnCount(count);
        for (int i = 0; i < count; i++) {
            writer.writeColumnDefinition(new ColumnDefinitionPacket.Builder()
                    .name(columnNames.get(i)).orgName(columnNames.get(i))
                    .columnType(columnTypes.get(i))
                    .build());
        }
        writer.writeEof(new EofPacket(0, statusFlags));
        // 无数据行
        writer.writeEof(new EofPacket(0, statusFlags));
        writer.flush();
    }

    private static void writeOk(PacketWriter writer, ConnectionSession session) {
        int statusFlags = StatusFlagBuilder.build(session.executionContext());
        writer.writeOk(OkPacket.ok(statusFlags));
        writer.flush();
    }

    /** 处理 SHOW TABLES：列出当前数据库的所有表 */
    private static void handleShowTables(ConnectionSession session, PacketWriter writer) {
        String db = session.currentDatabase();
        String columnName = "Tables_in_" + (db != null ? db : "");
        List<String> tables = session.catalog().listTables(db != null ? db : "");

        int statusFlags = StatusFlagBuilder.build(session.executionContext());

        writer.writeColumnCount(1);
        writer.writeColumnDefinition(new ColumnDefinitionPacket.Builder()
                .name(columnName).orgName(columnName)
                .columnType(MysqlConstants.MYSQL_TYPE_VAR_STRING)
                .columnLength(255)
                .build());
        writer.writeEof(new EofPacket(0, statusFlags));

        for (String table : tables) {
            writer.writeResultSetRow(new ResultSetRowPacket(List.of(table)));
        }

        writer.writeEof(new EofPacket(0, statusFlags));
        writer.flush();
    }
}
