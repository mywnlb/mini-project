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

    // 匹配 SHOW VARIABLES LIKE 'xxx'
    private static final Pattern SHOW_VARIABLES = Pattern.compile(
            "(?i)^\\s*SHOW\\s+VARIABLES\\s+LIKE\\s+'([^']*)'\\s*$");

    // 匹配通用 SET variable = value（兜底，处理 Navicat 的各种 SET 语句）
    private static final Pattern SET_GENERAL = Pattern.compile(
            "(?i)^\\s*SET\\s+(?:(?:GLOBAL|SESSION|LOCAL)\\s+)?\\w+\\s*=.*$");

    // 匹配 SELECT ... FROM information_schema（Navicat 探测查询）
    private static final Pattern INFORMATION_SCHEMA = Pattern.compile(
            "(?i)^\\s*SELECT\\s+.+\\s+FROM\\s+information_schema\\..*$");

    // 匹配 SELECT DATABASE()
    private static final Pattern SELECT_DATABASE = Pattern.compile(
            "(?i)^\\s*SELECT\\s+DATABASE\\s*\\(\\s*\\)\\s*$");

    // 匹配 SHOW TABLES / SHOW FULL TABLES [WHERE ...]
    private static final Pattern SHOW_TABLES = Pattern.compile(
            "(?i)^\\s*SHOW\\s+(?:FULL\\s+)?TABLES(?:\\s+WHERE\\s+.+)?\\s*$");

    // 匹配 SHOW TABLE STATUS [FROM db] [LIKE 'pattern'] [WHERE ...]
    private static final Pattern SHOW_TABLE_STATUS = Pattern.compile(
            "(?i)^\\s*SHOW\\s+TABLE\\s+STATUS(?:\\s+.+)?\\s*$");

    // 匹配 SHOW CHARACTER SET / SHOW COLLATION / SHOW ENGINES 等 Navicat 探测命令
    private static final Pattern SHOW_MISC = Pattern.compile(
            "(?i)^\\s*SHOW\\s+(?:CHARACTER\\s+SET|COLLATION|ENGINES|GRANTS|PROCESSLIST|STATUS|DATABASES)(?:\\s+.+)?\\s*$");

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
            boolean full = sql.toUpperCase().contains("FULL");
            List<String> tables = session.catalog().listTables(db != null ? db : "");
            List<Row> rows = new java.util.ArrayList<>();
            for (String table : tables) {
                if (full) {
                    rows.add(new Row(Map.of(columnName, (Object) table, "Table_type", (Object) "BASE TABLE")));
                } else {
                    rows.add(new Row(Map.of(columnName, (Object) table)));
                }
            }
            return rows;
        }

        // SHOW TABLE STATUS → 返回表元数据行
        if (SHOW_TABLE_STATUS.matcher(sql).matches()) {
            String db = session.currentDatabase();
            List<String> tables = session.catalog().listTables(db != null ? db : "");
            List<Row> rows = new java.util.ArrayList<>();
            for (String table : tables) {
                rows.add(buildTableStatusRow(table));
            }
            return rows;
        }

        // SHOW CHARACTER SET / SHOW COLLATION 等 → 返回空
        if (SHOW_MISC.matcher(sql).matches()) {
            return List.of();
        }

        // BEGIN / COMMIT / ROLLBACK → DML 类，返回空
        Matcher txnMatcher = TXN_CONTROL.matcher(sql);
        if (txnMatcher.matches()) {
            String cmd = txnMatcher.group(1).toUpperCase();
            var txnMgr = session.executionContext().txnManager();
            if (txnMgr != null) {
                switch (cmd) {
                    case "BEGIN" -> {
                        if (!session.executionContext().inTransaction()) {
                            session.executionContext().begin();
                        }
                    }
                    case "COMMIT" -> {
                        if (session.executionContext().inTransaction()) {
                            session.executionContext().commit();
                        }
                    }
                    case "ROLLBACK" -> {
                        if (session.executionContext().inTransaction()) {
                            session.executionContext().rollback();
                        }
                    }
                }
            }
            return List.of();
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
                || SHOW_TABLES.matcher(sql).matches()
                || SHOW_TABLE_STATUS.matcher(sql).matches()
                || SHOW_VARIABLES.matcher(sql).matches()
                || SHOW_MISC.matcher(sql).matches()
                || SET_GENERAL.matcher(sql).matches()
                || INFORMATION_SCHEMA.matcher(sql).matches()
                || TXN_CONTROL.matcher(sql).matches();
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

        // SHOW TABLES / SHOW FULL TABLES [WHERE ...]
        if (SHOW_TABLES.matcher(sql).matches()) {
            boolean full = sql.toUpperCase().contains("FULL");
            handleShowTables(session, writer, full);
            return true;
        }

        // BEGIN / COMMIT / ROLLBACK
        // MySQL 行为：COMMIT/ROLLBACK 在无活跃事务时是 no-op，不报错
        Matcher txnMatcher = TXN_CONTROL.matcher(sql);
        if (txnMatcher.matches()) {
            String cmd = txnMatcher.group(1).toUpperCase();
            var txnMgr = session.executionContext().txnManager();
            if (txnMgr != null) {
                switch (cmd) {
                    case "BEGIN" -> {
                        if (!session.executionContext().inTransaction()) {
                            session.executionContext().begin();
                        }
                    }
                    case "COMMIT" -> {
                        if (session.executionContext().inTransaction()) {
                            session.executionContext().commit();
                        }
                    }
                    case "ROLLBACK" -> {
                        if (session.executionContext().inTransaction()) {
                            session.executionContext().rollback();
                        }
                    }
                }
            }
            // 无论 txnManager 是否存在，都返回 OK
            writeOk(writer, session);
            return true;
        }

        // SHOW VARIABLES LIKE 'xxx'
        Matcher showVarMatcher = SHOW_VARIABLES.matcher(sql);
        if (showVarMatcher.matches()) {
            handleShowVariables(showVarMatcher.group(1), session, writer);
            return true;
        }

        // SHOW TABLE STATUS（返回表元数据）
        if (SHOW_TABLE_STATUS.matcher(sql).matches()) {
            handleShowTableStatus(session, writer);
            return true;
        }

        // SHOW CHARACTER SET / SHOW COLLATION / SHOW ENGINES 等（返回空结果集）
        if (SHOW_MISC.matcher(sql).matches()) {
            writeEmptyResultSet(writer, session,
                    List.of("result"), List.of(MysqlConstants.MYSQL_TYPE_VAR_STRING));
            return true;
        }

        // SELECT ... FROM information_schema（返回空结果集）
        if (INFORMATION_SCHEMA.matcher(sql).matches()) {
            writeEmptyResultSet(writer, session,
                    List.of("result"), List.of(MysqlConstants.MYSQL_TYPE_VAR_STRING));
            return true;
        }

        // 通用 SET（兜底，直接返回 OK）
        if (SET_GENERAL.matcher(sql).matches()) {
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

    /** 处理 SHOW VARIABLES LIKE 'pattern' */
    private static void handleShowVariables(String likePattern, ConnectionSession session,
                                             PacketWriter writer) {
        // 已知系统变量
        Map<String, String> allVars = Map.ofEntries(
                Map.entry("lower_case_table_names", "0"),
                Map.entry("lower_case_file_system", "OFF"),
                Map.entry("sql_mode", "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES"),
                Map.entry("version", MysqlConstants.SERVER_VERSION),
                Map.entry("version_comment", "mini-db"),
                Map.entry("character_set_client", "utf8mb4"),
                Map.entry("character_set_connection", "utf8mb4"),
                Map.entry("character_set_results", "utf8mb4"),
                Map.entry("character_set_server", "utf8mb4"),
                Map.entry("collation_connection", "utf8mb4_general_ci"),
                Map.entry("collation_server", "utf8mb4_general_ci"),
                Map.entry("max_allowed_packet", String.valueOf(MysqlConstants.DEFAULT_MAX_PACKET_SIZE)),
                Map.entry("transaction_isolation", "REPEATABLE-READ"),
                Map.entry("autocommit", session.executionContext().inTransaction() ? "0" : "1"),
                Map.entry("wait_timeout", "28800"),
                Map.entry("interactive_timeout", "28800")
        );

        // 将 LIKE 通配符转换为正则（% → .*, _ → .）
        String regex = "(?i)^" + likePattern.replace("%", ".*").replace("_", ".") + "$";
        Pattern p = Pattern.compile(regex);

        int statusFlags = StatusFlagBuilder.build(session.executionContext());

        writer.writeColumnCount(2);
        writer.writeColumnDefinition(new ColumnDefinitionPacket.Builder()
                .name("Variable_name").orgName("Variable_name")
                .columnType(MysqlConstants.MYSQL_TYPE_VAR_STRING).columnLength(255).build());
        writer.writeColumnDefinition(new ColumnDefinitionPacket.Builder()
                .name("Value").orgName("Value")
                .columnType(MysqlConstants.MYSQL_TYPE_VAR_STRING).columnLength(255).build());
        writer.writeEof(new EofPacket(0, statusFlags));

        allVars.entrySet().stream()
                .filter(e -> p.matcher(e.getKey()).matches())
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> writer.writeResultSetRow(
                        new ResultSetRowPacket(List.of(e.getKey(), e.getValue()))));

        writer.writeEof(new EofPacket(0, statusFlags));
        writer.flush();
    }

    /**
     * 处理 SHOW TABLE STATUS：返回当前数据库中每张表的元数据。
     * 兼容 MySQL 协议的 18 列格式，mini-db 未实现的字段用默认值填充。
     */
    private static void handleShowTableStatus(ConnectionSession session, PacketWriter writer) {
        String db = session.currentDatabase();
        List<String> tables = session.catalog().listTables(db != null ? db : "");

        int statusFlags = StatusFlagBuilder.build(session.executionContext());

        // SHOW TABLE STATUS 标准 18 列
        List<String> columns = List.of(
                "Name", "Engine", "Version", "Row_format", "Rows",
                "Avg_row_length", "Data_length", "Max_data_length",
                "Index_length", "Data_free", "Auto_increment",
                "Create_time", "Update_time", "Check_time",
                "Collation", "Checksum", "Create_options", "Comment");

        writer.writeColumnCount(columns.size());
        for (String col : columns) {
            int colType = switch (col) {
                case "Version", "Rows", "Avg_row_length", "Data_length",
                     "Max_data_length", "Index_length", "Data_free",
                     "Auto_increment", "Checksum" -> MysqlConstants.MYSQL_TYPE_LONGLONG;
                default -> MysqlConstants.MYSQL_TYPE_VAR_STRING;
            };
            writer.writeColumnDefinition(new ColumnDefinitionPacket.Builder()
                    .name(col).orgName(col)
                    .columnType(colType)
                    .columnLength(255)
                    .build());
        }
        writer.writeEof(new EofPacket(0, statusFlags));

        for (String table : tables) {
            writer.writeResultSetRow(new ResultSetRowPacket(List.of(
                    table,              // Name
                    "MiniDB",           // Engine
                    "10",               // Version
                    "Dynamic",          // Row_format
                    "0",                // Rows
                    "0",                // Avg_row_length
                    "0",                // Data_length
                    "0",                // Max_data_length
                    "0",                // Index_length
                    "0",                // Data_free
                    "",                 // Auto_increment (NULL → 空串)
                    "",                 // Create_time
                    "",                 // Update_time
                    "",                 // Check_time
                    "utf8mb4_general_ci", // Collation
                    "",                 // Checksum
                    "",                 // Create_options
                    ""                  // Comment
            )));
        }

        writer.writeEof(new EofPacket(0, statusFlags));
        writer.flush();
    }

    /** 构造 SHOW TABLE STATUS 的单行结果（用于二进制协议） */
    private static Row buildTableStatusRow(String tableName) {
        java.util.LinkedHashMap<String, Object> cols = new java.util.LinkedHashMap<>();
        cols.put("Name", tableName);
        cols.put("Engine", "MiniDB");
        cols.put("Version", "10");
        cols.put("Row_format", "Dynamic");
        cols.put("Rows", "0");
        cols.put("Avg_row_length", "0");
        cols.put("Data_length", "0");
        cols.put("Max_data_length", "0");
        cols.put("Index_length", "0");
        cols.put("Data_free", "0");
        cols.put("Auto_increment", "");
        cols.put("Create_time", "");
        cols.put("Update_time", "");
        cols.put("Check_time", "");
        cols.put("Collation", "utf8mb4_general_ci");
        cols.put("Checksum", "");
        cols.put("Create_options", "");
        cols.put("Comment", "");
        return new Row(cols);
    }

    /**
     * 处理 SHOW TABLES / SHOW FULL TABLES：列出当前数据库的所有表。
     *
     * @param full true 时返回两列（表名 + Table_type），兼容 Navicat 的 SHOW FULL TABLES WHERE ...
     */
    private static void handleShowTables(ConnectionSession session, PacketWriter writer, boolean full) {
        String db = session.currentDatabase();
        String columnName = "Tables_in_" + (db != null ? db : "");
        List<String> tables = session.catalog().listTables(db != null ? db : "");

        int statusFlags = StatusFlagBuilder.build(session.executionContext());

        if (full) {
            // SHOW FULL TABLES：两列（表名 + Table_type）
            writer.writeColumnCount(2);
            writer.writeColumnDefinition(new ColumnDefinitionPacket.Builder()
                    .name(columnName).orgName(columnName)
                    .columnType(MysqlConstants.MYSQL_TYPE_VAR_STRING)
                    .columnLength(255)
                    .build());
            writer.writeColumnDefinition(new ColumnDefinitionPacket.Builder()
                    .name("Table_type").orgName("Table_type")
                    .columnType(MysqlConstants.MYSQL_TYPE_VAR_STRING)
                    .columnLength(255)
                    .build());
            writer.writeEof(new EofPacket(0, statusFlags));

            for (String table : tables) {
                writer.writeResultSetRow(new ResultSetRowPacket(List.of(table, "BASE TABLE")));
            }
        } else {
            // SHOW TABLES：单列
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
        }

        writer.writeEof(new EofPacket(0, statusFlags));
        writer.flush();
    }
}
