package cn.zhangyis.minidb.server.handler;

import cn.zhangyis.minidb.server.ConnectionSession;
import cn.zhangyis.minidb.server.netty.PacketWriter;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import cn.zhangyis.minidb.server.protocol.packets.*;
import cn.zhangyis.minidb.sql.exec.Row;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    // 匹配 SELECT @@var1 [AS alias1], @@var2 [AS alias2], ...（多变量逗号形式）
    private static final Pattern SELECT_MULTI_SYSVAR = Pattern.compile(
            "(?i)^\\s*SELECT\\s+@@\\w+(?:\\s+AS\\s+\\w+)?(?:\\s*,\\s*@@\\w+(?:\\s+AS\\s+\\w+)?)+\\s*$");

    // 匹配 SELECT @@var1 UNION [ALL] SELECT @@var2 UNION ...（UNION 拼接形式）
    private static final Pattern SELECT_SYSVAR_UNION = Pattern.compile(
            "(?i)^\\s*SELECT\\s+@@\\w+(?:\\s+UNION\\s+(?:ALL\\s+)?SELECT\\s+@@\\w+)+\\s*$");

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

    // 匹配 SELECT DATABASE()
    private static final Pattern SELECT_DATABASE = Pattern.compile(
            "(?i)^\\s*SELECT\\s+DATABASE\\s*\\(\\s*\\)\\s*$");

    // 匹配 SHOW TABLES / SHOW FULL TABLES [FROM db_name] [WHERE ...]
    private static final Pattern SHOW_TABLES = Pattern.compile(
            "(?i)^\\s*SHOW\\s+(?:FULL\\s+)?TABLES(?:\\s+FROM\\s+\\S+)?(?:\\s+WHERE\\s+.+)?\\s*$");

    // 匹配 SHOW TABLE STATUS [FROM db] [LIKE 'pattern'] [WHERE ...]
    private static final Pattern SHOW_TABLE_STATUS = Pattern.compile(
            "(?i)^\\s*SHOW\\s+TABLE\\s+STATUS(?:\\s+.+)?\\s*$");

    // 匹配 SHOW DATABASES
    private static final Pattern SHOW_DATABASES = Pattern.compile(
            "(?i)^\\s*SHOW\\s+DATABASES\\s*$");

    // 匹配 SHOW CHARACTER SET / SHOW COLLATION / SHOW ENGINES 等 Navicat 探测命令
    private static final Pattern SHOW_MISC = Pattern.compile(
            "(?i)^\\s*SHOW\\s+(?:CHARACTER\\s+SET|COLLATION|ENGINES|GRANTS|PROCESSLIST|STATUS)(?:\\s+.+)?\\s*$");

    // 匹配 SELECT ... FROM information_schema.<table> 元数据探测查询
    private static final Pattern SELECT_STATEMENT = Pattern.compile("(?i)^\\s*SELECT\\b");
    private static final Pattern INFORMATION_SCHEMA_TABLE = Pattern.compile(
            "(?i)\\binformation_schema\\.(\\w+)\\b");

    // 这些对象在 mini-db 中没有实现；客户端探测到它们时返回空结果集即可，避免落到 SQL 引擎报错。
    private static final Set<String> EMPTY_INFORMATION_SCHEMA_TABLES = Set.of(
            "CHARACTER_SETS",
            "CHECK_CONSTRAINTS",
            "COLLATIONS",
            "COLLATION_CHARACTER_SET_APPLICABILITY",
            "COLUMN_PRIVILEGES",
            "EVENTS",
            "FILES",
            "KEY_COLUMN_USAGE",
            "PARAMETERS",
            "PARTITIONS",
            "PLUGINS",
            "REFERENTIAL_CONSTRAINTS",
            "ROUTINES",
            "SCHEMA_PRIVILEGES",
            "TABLE_CONSTRAINTS",
            "TABLE_PRIVILEGES",
            "TRIGGERS",
            "USER_PRIVILEGES",
            "VIEWS"
    );

    // 匹配 BEGIN / COMMIT / ROLLBACK
    private static final Pattern TXN_CONTROL = Pattern.compile(
            "(?i)^\\s*(BEGIN|COMMIT|ROLLBACK)\\s*$");

    /**
     * 执行拦截类查询并返回结果行（用于二进制协议 COM_STMT_EXECUTE）。
     * 返回 null 表示该 SQL 不匹配任何拦截模式。
     */
    public static List<Row> executeIntercepted(String sql, ConnectionSession session) {
        // SELECT @@var1, @@var2, ... （多变量逗号形式，必须在单变量之前检查）
        if (SELECT_MULTI_SYSVAR.matcher(sql).matches()) {
            return executeMultiSysvar(sql, session);
        }

        // SELECT @@var1 UNION SELECT @@var2 UNION ...（UNION 拼接形式）
        if (SELECT_SYSVAR_UNION.matcher(sql).matches()) {
            return executeUnionSysvar(sql, session);
        }

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

        if (!unsupportedInformationSchemaSelectMetadata(sql).isEmpty()) {
            return List.of();
        }

        Matcher showVarMatcher = SHOW_VARIABLES.matcher(sql);
        if (showVarMatcher.matches()) {
            String regex = "(?i)^" + showVarMatcher.group(1).replace("%", ".*").replace("_", ".") + "$";
            Pattern p = Pattern.compile(regex);
            List<Row> rows = new java.util.ArrayList<>();
            allSystemVariables(session).entrySet().stream()
                    .filter(e -> p.matcher(e.getKey()).matches())
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> rows.add(new Row(Map.of(
                            "Variable_name", (Object) e.getKey(),
                            "Value", (Object) e.getValue()))));
            return rows;
        }

        if (SHOW_TABLES.matcher(sql).matches()) {
            String dbName = parseShowTablesFromDbName(sql);
            String db = resolveMetadataDatabase(session, dbName);
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

        // SHOW TABLE STATUS [FROM db] [LIKE 'pattern'] → 返回表元数据行
        if (SHOW_TABLE_STATUS.matcher(sql).matches()) {
            String fromDb = parseShowTableStatusFromDb(sql);
            String db = resolveMetadataDatabase(session, fromDb);
            String likePattern = parseShowTableStatusLikePattern(sql);
            List<String> tables = session.catalog().listTables(db != null ? db : "");
            List<Row> rows = new java.util.ArrayList<>();
            for (String table : tables) {
                if (likePattern != null && !matchesLikePattern(table, likePattern)) {
                    continue;
                }
                rows.add(buildTableStatusRow(table));
            }
            return rows;
        }

        // SHOW DATABASES（返回数据库列表）
        if (SHOW_DATABASES.matcher(sql).matches()) {
            List<String> databases = session.catalog().listDatabases();
            String currentDb = session.currentDatabase();
            if (currentDb != null && !databases.contains(currentDb)) {
                databases = new ArrayList<>(databases);
                databases.add(currentDb);
            }
            if (databases.isEmpty()) {
                databases = List.of("minidb");
            }
            List<Row> rows = new ArrayList<>();
            for (String db : databases) {
                rows.add(new Row(Map.of("Database", (Object) db)));
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
        return SELECT_MULTI_SYSVAR.matcher(sql).matches()
                || SELECT_SYSVAR_UNION.matcher(sql).matches()
                || SELECT_SYSVAR.matcher(sql).matches()
                || SET_NAMES.matcher(sql).matches()
                || SET_CHARSET.matcher(sql).matches()
                || SET_AUTOCOMMIT.matcher(sql).matches()
                || SHOW_WARNINGS.matcher(sql).matches()
                || SELECT_DATABASE.matcher(sql).matches()
                || SHOW_TABLES.matcher(sql).matches()
                || SHOW_TABLE_STATUS.matcher(sql).matches()
                || SHOW_VARIABLES.matcher(sql).matches()
                || SHOW_DATABASES.matcher(sql).matches()
                || SHOW_MISC.matcher(sql).matches()
                || !unsupportedInformationSchemaSelectMetadata(sql).isEmpty()
                || SET_GENERAL.matcher(sql).matches()
                || TXN_CONTROL.matcher(sql).matches();
    }

    /**
     * 返回拦截类语句的结果列元数据。
     * 空列表表示该语句返回 OK/无结果集。
     */
    public static List<ResultColumnMetadata> resultMetadata(String sql, ConnectionSession session) {
        // 多变量逗号形式：返回多列元数据
        if (SELECT_MULTI_SYSVAR.matcher(sql).matches()) {
            List<String[]> items = parseMultiSysvarItems(sql);
            List<ResultColumnMetadata> metadata = new ArrayList<>(items.size());
            for (String[] item : items) {
                String colName = item[1] != null ? item[1] : "@@" + item[0];
                metadata.add(ResultColumnMetadata.of(
                        colName, "", "", "", colName, colName,
                        MysqlConstants.MYSQL_TYPE_VAR_STRING, 0));
            }
            return metadata;
        }

        // UNION 形式：返回单列元数据（列名取第一个变量）
        if (SELECT_SYSVAR_UNION.matcher(sql).matches()) {
            List<String> varNames = parseUnionSysvarNames(sql);
            String colName = varNames.isEmpty() ? "@@" : "@@" + varNames.get(0);
            return List.of(ResultColumnMetadata.of(
                    colName, "", "", "", colName, colName,
                    MysqlConstants.MYSQL_TYPE_VAR_STRING, 0));
        }

        Matcher sysvarMatcher = SELECT_SYSVAR.matcher(sql);
        if (sysvarMatcher.matches()) {
            String varName = sysvarMatcher.group(1).toLowerCase();
            String columnName = "@@" + varName;
            return List.of(ResultColumnMetadata.of(
                    columnName, "", "", "", columnName, columnName,
                    MysqlConstants.MYSQL_TYPE_VAR_STRING, 0));
        }

        if (SHOW_WARNINGS.matcher(sql).matches()) {
            return List.of(
                    ResultColumnMetadata.of("Level", "", "", "", "Level", "Level",
                            MysqlConstants.MYSQL_TYPE_VAR_STRING, 0),
                    ResultColumnMetadata.of("Code", "", "", "", "Code", "Code",
                            MysqlConstants.MYSQL_TYPE_LONG, 0),
                    ResultColumnMetadata.of("Message", "", "", "", "Message", "Message",
                            MysqlConstants.MYSQL_TYPE_VAR_STRING, 0)
            );
        }

        if (SELECT_DATABASE.matcher(sql).matches()) {
            return List.of(ResultColumnMetadata.of(
                    "DATABASE()", "", "", "", "DATABASE()", "DATABASE()",
                    MysqlConstants.MYSQL_TYPE_VAR_STRING, 0));
        }

        List<ResultColumnMetadata> unsupportedInfoSchemaMetadata = unsupportedInformationSchemaSelectMetadata(sql);
        if (!unsupportedInfoSchemaMetadata.isEmpty()) {
            return unsupportedInfoSchemaMetadata;
        }

        if (SHOW_TABLES.matcher(sql).matches()) {
            String dbName = parseShowTablesFromDbName(sql);
            return showTablesMetadata(session, sql.toUpperCase().contains("FULL"), dbName);
        }

        Matcher showVarMatcher = SHOW_VARIABLES.matcher(sql);
        if (showVarMatcher.matches()) {
            return List.of(
                    ResultColumnMetadata.of("Variable_name", "", "", "", "Variable_name", "Variable_name",
                            MysqlConstants.MYSQL_TYPE_VAR_STRING, 0),
                    ResultColumnMetadata.of("Value", "", "", "", "Value", "Value",
                            MysqlConstants.MYSQL_TYPE_VAR_STRING, 0)
            );
        }

        if (SHOW_TABLE_STATUS.matcher(sql).matches()) {
            return showTableStatusMetadata();
        }

        if (SHOW_DATABASES.matcher(sql).matches()) {
            return List.of(ResultColumnMetadata.of(
                    "Database", "", "", "", "Database", "Database",
                    MysqlConstants.MYSQL_TYPE_VAR_STRING, 0));
        }

        if (SHOW_MISC.matcher(sql).matches()) {
            return List.of(ResultColumnMetadata.of(
                    "result", "", "", "", "result", "result",
                    MysqlConstants.MYSQL_TYPE_VAR_STRING, 0));
        }

        return List.of();
    }

    /**
     * 尝试拦截并处理兼容性查询。
     *
     * @return true 如果已处理（调用方不应再转发到 SQL 引擎），false 需要正常执行
     */
    public static boolean tryHandle(String sql, ConnectionSession session, PacketWriter writer) {
        // SELECT @@var1, @@var2, ... （多变量逗号形式，必须在单变量之前检查）
        if (SELECT_MULTI_SYSVAR.matcher(sql).matches()) {
            handleMultiSysvar(sql, session, writer);
            return true;
        }

        // SELECT @@var1 UNION SELECT @@var2 UNION ...（UNION 拼接形式）
        if (SELECT_SYSVAR_UNION.matcher(sql).matches()) {
            handleUnionSysvar(sql, session, writer);
            return true;
        }

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

        List<ResultColumnMetadata> unsupportedInfoSchemaMetadata = unsupportedInformationSchemaSelectMetadata(sql);
        if (!unsupportedInfoSchemaMetadata.isEmpty()) {
            ResultSetWriter.write(List.of(), unsupportedInfoSchemaMetadata, session, writer);
            return true;
        }

        // SHOW TABLES / SHOW FULL TABLES [FROM db_name] [WHERE ...]
        if (SHOW_TABLES.matcher(sql).matches()) {
            boolean full = sql.toUpperCase().contains("FULL");
            String dbName = parseShowTablesFromDbName(sql);
            handleShowTables(session, writer, full, dbName);
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
            handleShowTableStatus(sql, session, writer);
            return true;
        }

        // SHOW DATABASES（返回数据库列表）
        if (SHOW_DATABASES.matcher(sql).matches()) {
            handleShowDatabases(session, writer);
            return true;
        }

        // SHOW CHARACTER SET / SHOW COLLATION / SHOW ENGINES 等（返回空结果集）
        if (SHOW_MISC.matcher(sql).matches()) {
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

    private static List<ResultColumnMetadata> unsupportedInformationSchemaSelectMetadata(String sql) {
        if (!SELECT_STATEMENT.matcher(sql).find()) {
            return List.of();
        }

        Set<String> referencedTables = referencedUnsupportedInformationSchemaTables(sql);
        if (referencedTables.isEmpty()) {
            return List.of();
        }

        List<String> columnNames = inferProjectionColumnNames(sql);
        if (columnNames.isEmpty()) {
            return List.of(ResultColumnMetadata.of(
                    "result", "", "", "", "result", "result",
                    MysqlConstants.MYSQL_TYPE_VAR_STRING, 0));
        }

        List<ResultColumnMetadata> metadata = new ArrayList<>(columnNames.size());
        for (int i = 0; i < columnNames.size(); i++) {
            String columnName = columnNames.get(i);
            if (columnName == null || columnName.isBlank()) {
                columnName = "COLUMN_" + (i + 1);
            }
            metadata.add(ResultColumnMetadata.of(
                    columnName, "", "", "", columnName, columnName,
                    MysqlConstants.MYSQL_TYPE_VAR_STRING, 0));
        }
        return metadata;
    }

    private static Set<String> referencedUnsupportedInformationSchemaTables(String sql) {
        Matcher matcher = INFORMATION_SCHEMA_TABLE.matcher(sql);
        LinkedHashSet<String> referencedTables = new LinkedHashSet<>();
        while (matcher.find()) {
            referencedTables.add(matcher.group(1).toUpperCase(Locale.ROOT));
        }
        if (referencedTables.isEmpty() || !EMPTY_INFORMATION_SCHEMA_TABLES.containsAll(referencedTables)) {
            return Set.of();
        }
        return referencedTables;
    }

    private static List<String> inferProjectionColumnNames(String sql) {
        int selectIndex = indexOfTopLevelKeyword(sql, "SELECT", 0);
        if (selectIndex < 0) {
            return List.of();
        }

        int projectionStart = selectIndex + "SELECT".length();
        projectionStart = skipWhitespace(sql, projectionStart);
        if (startsWithKeyword(sql, projectionStart, "DISTINCT")) {
            projectionStart = skipWhitespace(sql, projectionStart + "DISTINCT".length());
        } else if (startsWithKeyword(sql, projectionStart, "ALL")) {
            projectionStart = skipWhitespace(sql, projectionStart + "ALL".length());
        }

        int fromIndex = indexOfTopLevelKeyword(sql, "FROM", projectionStart);
        if (fromIndex < 0 || fromIndex <= projectionStart) {
            return List.of();
        }

        List<String> columnNames = new ArrayList<>();
        for (String item : splitTopLevelComma(sql.substring(projectionStart, fromIndex))) {
            String columnName = inferProjectionColumnName(item);
            if (!columnName.isBlank()) {
                columnNames.add(columnName);
            }
        }
        return columnNames;
    }

    private static String inferProjectionColumnName(String item) {
        String trimmed = item.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        List<String> tokens = splitTopLevelWhitespace(trimmed);
        if (tokens.size() >= 3 && "AS".equalsIgnoreCase(tokens.get(tokens.size() - 2))) {
            return normalizeIdentifier(tokens.get(tokens.size() - 1));
        }
        if (tokens.size() >= 2) {
            return normalizeIdentifier(tokens.get(tokens.size() - 1));
        }
        if ("*".equals(trimmed) || trimmed.endsWith(".*")) {
            return normalizeIdentifier(trimmed);
        }

        int dotIndex = trimmed.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < trimmed.length() - 1) {
            return normalizeIdentifier(trimmed.substring(dotIndex + 1));
        }
        return normalizeIdentifier(trimmed);
    }

    private static List<String> splitTopLevelComma(String segment) {
        List<String> items = new ArrayList<>();
        int start = 0;
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inBacktick = false;

        for (int i = 0; i < segment.length(); i++) {
            char ch = segment.charAt(i);
            if (inSingleQuote) {
                if (ch == '\'' && !isEscaped(segment, i)) {
                    inSingleQuote = false;
                }
                continue;
            }
            if (inBacktick) {
                if (ch == '`') {
                    inBacktick = false;
                }
                continue;
            }
            if (ch == '\'') {
                inSingleQuote = true;
                continue;
            }
            if (ch == '`') {
                inBacktick = true;
                continue;
            }
            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (ch == ',' && depth == 0) {
                items.add(segment.substring(start, i));
                start = i + 1;
            }
        }
        items.add(segment.substring(start));
        return items;
    }

    private static List<String> splitTopLevelWhitespace(String segment) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inBacktick = false;

        for (int i = 0; i < segment.length(); i++) {
            char ch = segment.charAt(i);
            if (inSingleQuote) {
                current.append(ch);
                if (ch == '\'' && !isEscaped(segment, i)) {
                    inSingleQuote = false;
                }
                continue;
            }
            if (inBacktick) {
                current.append(ch);
                if (ch == '`') {
                    inBacktick = false;
                }
                continue;
            }
            if (ch == '\'') {
                inSingleQuote = true;
                current.append(ch);
                continue;
            }
            if (ch == '`') {
                inBacktick = true;
                current.append(ch);
                continue;
            }
            if (ch == '(') {
                depth++;
                current.append(ch);
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                current.append(ch);
                continue;
            }
            if (Character.isWhitespace(ch) && depth == 0) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static int indexOfTopLevelKeyword(String sql, String keyword, int startIndex) {
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inBacktick = false;

        for (int i = startIndex; i <= sql.length() - keyword.length(); i++) {
            char ch = sql.charAt(i);
            if (inSingleQuote) {
                if (ch == '\'' && !isEscaped(sql, i)) {
                    inSingleQuote = false;
                }
                continue;
            }
            if (inBacktick) {
                if (ch == '`') {
                    inBacktick = false;
                }
                continue;
            }
            if (ch == '\'') {
                inSingleQuote = true;
                continue;
            }
            if (ch == '`') {
                inBacktick = true;
                continue;
            }
            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0 && startsWithKeyword(sql, i, keyword)) {
                return i;
            }
        }
        return -1;
    }

    private static int skipWhitespace(String sql, int index) {
        int pos = index;
        while (pos < sql.length() && Character.isWhitespace(sql.charAt(pos))) {
            pos++;
        }
        return pos;
    }

    private static boolean startsWithKeyword(String sql, int index, String keyword) {
        if (index < 0 || index + keyword.length() > sql.length()) {
            return false;
        }
        if (!sql.regionMatches(true, index, keyword, 0, keyword.length())) {
            return false;
        }
        return isKeywordBoundary(sql, index - 1) && isKeywordBoundary(sql, index + keyword.length());
    }

    private static boolean isKeywordBoundary(String sql, int index) {
        if (index < 0 || index >= sql.length()) {
            return true;
        }
        char ch = sql.charAt(index);
        return !Character.isLetterOrDigit(ch) && ch != '_' && ch != '$';
    }

    private static boolean isEscaped(String sql, int index) {
        int slashCount = 0;
        for (int i = index - 1; i >= 0 && sql.charAt(i) == '\\'; i--) {
            slashCount++;
        }
        return (slashCount & 1) == 1;
    }

    private static String normalizeIdentifier(String raw) {
        String value = raw.trim();
        if (value.startsWith("`") && value.endsWith("`") && value.length() > 1) {
            value = value.substring(1, value.length() - 1);
        }
        int dotIndex = value.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < value.length() - 1) {
            value = value.substring(dotIndex + 1);
        }
        if (value.startsWith("`") && value.endsWith("`") && value.length() > 1) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    // ==================== 多变量 SELECT 解析辅助方法 ====================

    /** 用于解析单个 @@varName 的模式 */
    private static final Pattern SYSVAR_ITEM = Pattern.compile(
            "(?i)@@(\\w+)(?:\\s+AS\\s+(\\w+))?");

    /**
     * 解析多变量逗号形式 SQL 中的各项。
     * 返回 [varName, alias] 数组列表，alias 可能为 null。
     */
    private static List<String[]> parseMultiSysvarItems(String sql) {
        // 去掉 SELECT 前缀
        String body = sql.replaceFirst("(?i)^\\s*SELECT\\s+", "").trim();
        String[] parts = body.split("\\s*,\\s*");
        List<String[]> items = new ArrayList<>();
        for (String part : parts) {
            Matcher m = SYSVAR_ITEM.matcher(part.trim());
            if (m.find()) {
                items.add(new String[]{m.group(1).toLowerCase(), m.group(2)});
            }
        }
        return items;
    }

    /**
     * 解析 UNION 形式 SQL 中的变量名列表。
     */
    private static List<String> parseUnionSysvarNames(String sql) {
        // 按 UNION [ALL] SELECT 拆分
        String[] segments = sql.split("(?i)\\s+UNION\\s+(?:ALL\\s+)?SELECT\\s+");
        List<String> varNames = new ArrayList<>();
        for (String seg : segments) {
            Matcher m = SYSVAR_ITEM.matcher(seg.trim());
            if (m.find()) {
                varNames.add(m.group(1).toLowerCase());
            }
        }
        return varNames;
    }

    /**
     * executeIntercepted: 多变量逗号形式 → 单行多列
     */
    private static List<Row> executeMultiSysvar(String sql, ConnectionSession session) {
        List<String[]> items = parseMultiSysvarItems(sql);
        java.util.LinkedHashMap<String, Object> cols = new java.util.LinkedHashMap<>();
        for (String[] item : items) {
            String varName = item[0];
            String alias = item[1];
            String colName = alias != null ? alias : "@@" + varName;
            String value = resolveSystemVariable(varName, session);
            cols.put(colName, value);
        }
        return List.of(new Row(cols));
    }

    /**
     * executeIntercepted: UNION 形式 → 多行单列
     */
    private static List<Row> executeUnionSysvar(String sql, ConnectionSession session) {
        List<String> varNames = parseUnionSysvarNames(sql);
        String colName = varNames.isEmpty() ? "@@" : "@@" + varNames.get(0);
        List<Row> rows = new ArrayList<>();
        for (String varName : varNames) {
            String value = resolveSystemVariable(varName, session);
            rows.add(new Row(Map.of(colName, (Object) value)));
        }
        return rows;
    }

    /**
     * tryHandle: 多变量逗号形式 → 写多列单行结果集
     */
    private static void handleMultiSysvar(String sql, ConnectionSession session, PacketWriter writer) {
        List<String[]> items = parseMultiSysvarItems(sql);
        int statusFlags = StatusFlagBuilder.build(session.executionContext());

        writer.writeColumnCount(items.size());
        for (String[] item : items) {
            String colName = item[1] != null ? item[1] : "@@" + item[0];
            writer.writeColumnDefinition(new ColumnDefinitionPacket.Builder()
                    .name(colName).orgName(colName)
                    .columnType(MysqlConstants.MYSQL_TYPE_VAR_STRING)
                    .columnLength(255)
                    .build());
        }
        writer.writeEof(new EofPacket(0, statusFlags));

        // 单行数据
        List<String> values = new ArrayList<>(items.size());
        for (String[] item : items) {
            values.add(resolveSystemVariable(item[0], session));
        }
        writer.writeResultSetRow(new ResultSetRowPacket(values));

        writer.writeEof(new EofPacket(0, statusFlags));
        writer.flush();
    }

    /**
     * tryHandle: UNION 形式 → 写单列多行结果集
     */
    private static void handleUnionSysvar(String sql, ConnectionSession session, PacketWriter writer) {
        List<String> varNames = parseUnionSysvarNames(sql);
        String colName = varNames.isEmpty() ? "@@" : "@@" + varNames.get(0);
        int statusFlags = StatusFlagBuilder.build(session.executionContext());

        writer.writeColumnCount(1);
        writer.writeColumnDefinition(new ColumnDefinitionPacket.Builder()
                .name(colName).orgName(colName)
                .columnType(MysqlConstants.MYSQL_TYPE_VAR_STRING)
                .columnLength(255)
                .build());
        writer.writeEof(new EofPacket(0, statusFlags));

        for (String varName : varNames) {
            String value = resolveSystemVariable(varName, session);
            writer.writeResultSetRow(new ResultSetRowPacket(List.of(value)));
        }

        writer.writeEof(new EofPacket(0, statusFlags));
        writer.flush();
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
        Map<String, String> allVars = allSystemVariables(session);

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

    private static Map<String, String> allSystemVariables(ConnectionSession session) {
        return Map.ofEntries(
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
    }

    /**
     * 处理 SHOW TABLE STATUS [FROM db] [LIKE 'pattern']：返回当前数据库中每张表的元数据。
     * 兼容 MySQL 协议的 18 列格式，mini-db 未实现的字段用默认值填充。
     */
    private static void handleShowTableStatus(String sql, ConnectionSession session, PacketWriter writer) {
        String fromDb = parseShowTableStatusFromDb(sql);
        String db = resolveMetadataDatabase(session, fromDb);
        String likePattern = parseShowTableStatusLikePattern(sql);
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
            if (likePattern != null && !matchesLikePattern(table, likePattern)) {
                continue;
            }
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

    /**
     * 处理 SHOW DATABASES：返回数据库列表。
     */
    private static void handleShowDatabases(ConnectionSession session, PacketWriter writer) {
        List<String> databases = session.catalog().listDatabases();
        // 确保包含当前数据库
        String currentDb = session.currentDatabase();
        if (currentDb != null && !databases.contains(currentDb)) {
            databases = new ArrayList<>(databases);
            databases.add(currentDb);
        }
        // 如果列表为空，返回默认 minidb
        if (databases.isEmpty()) {
            databases = List.of("minidb");
        }

        int statusFlags = StatusFlagBuilder.build(session.executionContext());
        writer.writeColumnCount(1);
        writer.writeColumnDefinition(new ColumnDefinitionPacket.Builder()
                .name("Database").orgName("Database")
                .columnType(MysqlConstants.MYSQL_TYPE_VAR_STRING)
                .columnLength(255)
                .build());
        writer.writeEof(new EofPacket(0, statusFlags));

        for (String db : databases) {
            writer.writeResultSetRow(new ResultSetRowPacket(List.of(db)));
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
     * 从 SHOW TABLES SQL 中解析 FROM 后面的数据库名。
     * 注意：不能使用通用的 FROM 匹配，因为 SHOW FULL TABLES 中 "FULL" 不含 FROM，
     * 但 WHERE 子句中可能包含 FROM 关键字。这里只匹配 TABLES 后紧跟的 FROM。
     *
     * @return 数据库名（去除反引号），如果没有 FROM 子句则返回 null
     */
    private static String parseShowTablesFromDbName(String sql) {
        Pattern fromPattern = Pattern.compile("(?i)\\bTABLES\\s+FROM\\s+(\\S+)");
        Matcher m = fromPattern.matcher(sql);
        if (m.find()) {
            return m.group(1).replaceAll("`", "");
        }
        return null;
    }

    /**
     * 从 SHOW TABLE STATUS SQL 中解析 FROM 后面的数据库名。
     * 匹配 STATUS FROM db_name（注意不要匹配 LIKE 后面的内容）。
     *
     * @return 数据库名（去除反引号），如果没有 FROM 子句则返回 null
     */
    private static String parseShowTableStatusFromDb(String sql) {
        Pattern p = Pattern.compile("(?i)\\bSTATUS\\s+FROM\\s+(\\S+)");
        Matcher m = p.matcher(sql);
        return m.find() ? m.group(1).replaceAll("`", "") : null;
    }

    /**
     * 从 SHOW TABLE STATUS SQL 中解析 LIKE 'pattern' 子句。
     *
     * @return LIKE 模式字符串（不含引号），如果没有 LIKE 子句则返回 null
     */
    private static String parseShowTableStatusLikePattern(String sql) {
        Pattern p = Pattern.compile("(?i)\\bLIKE\\s+'([^']*)'");
        Matcher m = p.matcher(sql);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 判断表名是否匹配 MySQL LIKE 模式。
     * LIKE 通配符：{@code %} 匹配任意字符序列，{@code _} 匹配单个字符。
     * 用户输入中的正则特殊字符会被逐字符转义，确保安全。
     */
    static boolean matchesLikePattern(String name, String likePattern) {
        StringBuilder regex = new StringBuilder("(?i)^");
        for (char c : likePattern.toCharArray()) {
            switch (c) {
                case '%' -> regex.append(".*");
                case '_' -> regex.append(".");
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append("$");
        return Pattern.matches(regex.toString(), name);
    }

    /**
     * 处理 SHOW TABLES / SHOW FULL TABLES [FROM db_name]：列出指定数据库的所有表。
     *
     * @param full true 时返回两列（表名 + Table_type），兼容 Navicat 的 SHOW FULL TABLES WHERE ...
     * @param dbName 从 SQL 中解析的 FROM db_name，为 null 时使用 session.currentDatabase()
     */
    private static void handleShowTables(ConnectionSession session, PacketWriter writer, boolean full, String dbName) {
        String db = resolveMetadataDatabase(session, dbName);
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

    private static List<ResultColumnMetadata> showTablesMetadata(ConnectionSession session, boolean full, String dbName) {
        String db = resolveMetadataDatabase(session, dbName);
        String columnName = "Tables_in_" + (db != null ? db : "");
        if (full) {
            return List.of(
                    ResultColumnMetadata.of(columnName, "", "", "", columnName, columnName,
                            MysqlConstants.MYSQL_TYPE_VAR_STRING, 0),
                    ResultColumnMetadata.of("Table_type", "", "", "", "Table_type", "Table_type",
                            MysqlConstants.MYSQL_TYPE_VAR_STRING, 0)
            );
        }
        return List.of(ResultColumnMetadata.of(
                columnName, "", "", "", columnName, columnName,
                MysqlConstants.MYSQL_TYPE_VAR_STRING, 0));
    }

    private static String resolveMetadataDatabase(ConnectionSession session, String requestedDatabase) {
        if (requestedDatabase != null && !requestedDatabase.isBlank()) {
            return requestedDatabase;
        }

        String currentDatabase = session.currentDatabase();
        if (currentDatabase != null && !currentDatabase.isBlank()) {
            return currentDatabase;
        }

        String onlyUserDatabase = null;
        for (String database : session.catalog().listDatabases()) {
            if (database == null || database.isBlank()
                    || "information_schema".equalsIgnoreCase(database)) {
                continue;
            }
            if (onlyUserDatabase != null && !onlyUserDatabase.equalsIgnoreCase(database)) {
                return null;
            }
            onlyUserDatabase = database;
        }
        return onlyUserDatabase;
    }

    private static List<ResultColumnMetadata> showTableStatusMetadata() {
        List<ResultColumnMetadata> metadata = new java.util.ArrayList<>();
        List<String> columns = List.of(
                "Name", "Engine", "Version", "Row_format", "Rows",
                "Avg_row_length", "Data_length", "Max_data_length",
                "Index_length", "Data_free", "Auto_increment",
                "Create_time", "Update_time", "Check_time",
                "Collation", "Checksum", "Create_options", "Comment");
        for (String col : columns) {
            int colType = switch (col) {
                case "Version", "Rows", "Avg_row_length", "Data_length",
                     "Max_data_length", "Index_length", "Data_free",
                     "Auto_increment", "Checksum" -> MysqlConstants.MYSQL_TYPE_LONGLONG;
                default -> MysqlConstants.MYSQL_TYPE_VAR_STRING;
            };
            metadata.add(ResultColumnMetadata.of(col, "", "", "", col, col, colType, 0));
        }
        return metadata;
    }
}
