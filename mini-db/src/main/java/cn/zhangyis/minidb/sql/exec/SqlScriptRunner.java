package cn.zhangyis.minidb.sql.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SQL 脚本导入器。
 *
 * <p>读取 Navicat/MySQL 8 导出的完整 SQL 脚本，逐语句分类并路由执行。
 * 设计目标：
 * <ul>
 *   <li>支持 {@code DROP TABLE IF EXISTS}、{@code CREATE TABLE}、{@code INSERT INTO} 三类核心语句</li>
 *   <li>{@code SET} 语句（如 {@code SET NAMES utf8mb4}）直接跳过，不进入 SQL 引擎</li>
 *   <li>每条语句独立 try/catch，单条失败不中断后续导入</li>
 *   <li>输出导入摘要，便于排查兼容性问题</li>
 * </ul>
 *
 * <h3>语句切分策略</h3>
 * <p>逐行读取并累积文本，以行末 {@code ;} 作为语句结束标志。
 * Navicat dump 格式中每条语句末尾的分号都在行末，此策略简单可靠。</p>
 *
 * <h3>语句分类策略</h3>
 * <p>在调用 parser 之前，先按前缀关键字分类。
 * 这样可以安全跳过 {@code SET} 等不支持的语句，避免触发 parse 异常。</p>
 *
 * @see SqlSession
 */
public class SqlScriptRunner {

    private static final Logger log = LoggerFactory.getLogger(SqlScriptRunner.class);

    /** 进度日志间隔（每处理 N 条语句输出一次） */
    private static final int PROGRESS_INTERVAL = 1000;

    private final SqlSession session;

    /**
     * 导入摘要，不可变记录。
     *
     * @param tablesCreated   成功创建的表数
     * @param tablesDropped   成功删除的表数
     * @param rowsInserted    成功插入的行数
     * @param statementsSkipped 跳过的语句数（SET 等）
     * @param errors          执行失败的语句数
     * @param skippedObjects  被跳过的对象列表（含名称）
     * @param errorMessages   每条失败语句的错误信息
     * @param elapsedMillis   导入耗时（毫秒）
     */
    public record ImportSummary(
        int tablesCreated,
        int tablesDropped,
        long rowsInserted,
        int statementsSkipped,
        int errors,
        List<String> skippedObjects,
        List<String> errorMessages,
        long elapsedMillis
    ) {}

    /**
     * 语句分类枚举。
     * 在 parse 之前按前缀关键字分类，
     * 使不支持的语句（如 SET）可以被安全跳过。
     */
    public enum StatementKind {
        SET,
        DROP_TABLE,
        CREATE_TABLE,
        INSERT,
        COMMENT_ONLY,
        UNKNOWN
    }

    public SqlScriptRunner(SqlSession session) {
        this.session = session;
    }

    /**
     * 从文件导入整份 SQL 脚本。
     *
     * @param sqlFilePath SQL 文件路径
     * @return 导入摘要
     * @throws IOException 文件读取失败
     */
    public ImportSummary importFile(Path sqlFilePath) throws IOException {
        log.info("开始导入 SQL 脚本: {} ({} bytes)", sqlFilePath, Files.size(sqlFilePath));
        try (BufferedReader reader = Files.newBufferedReader(sqlFilePath, StandardCharsets.UTF_8)) {
            return doImport(reader);
        }
    }

    /**
     * 从字符串导入 SQL 脚本（测试用）。
     *
     * @param script SQL 脚本文本
     * @return 导入摘要
     */
    public ImportSummary importScript(String script) {
        try (BufferedReader reader = new BufferedReader(new StringReader(script))) {
            return doImport(reader);
        } catch (IOException e) {
            // StringReader 不会抛 IOException，这里只是满足编译器
            throw new RuntimeException("Unexpected IO error reading from string", e);
        }
    }

    // ==================== 核心导入逻辑 ====================

    /**
     * 核心导入循环：逐行读取 → 累积语句 → 分类执行。
     *
     * <p>算法：
     * <ol>
     *   <li>逐行读取，跳过纯空行</li>
     *   <li>将每行追加到 currentStatement</li>
     *   <li>当累积文本去尾空白后以 {@code ;} 结尾时，视为完整语句</li>
     *   <li>提取语句文本（去掉尾部 {@code ;}），分类并执行</li>
     * </ol>
     */
    private ImportSummary doImport(BufferedReader reader) throws IOException {
        SummaryBuilder summary = new SummaryBuilder();
        summary.start();

        StringBuilder currentStatement = new StringBuilder(4096);
        int totalStatements = 0;
        String line;

        while ((line = reader.readLine()) != null) {
            String trimmed = line.strip();

            // 跳过纯空行
            if (trimmed.isEmpty()) {
                continue;
            }

            // 累积到当前语句
            currentStatement.append(line).append('\n');

            // 检查语句是否完整（以 ; 结尾）
            if (trimmed.endsWith(";")) {
                String stmtText = currentStatement.toString().strip();
                // 去掉尾部分号
                stmtText = stmtText.substring(0, stmtText.length() - 1).strip();

                if (!stmtText.isEmpty()) {
                    totalStatements++;
                    executeClassified(stmtText, summary);

                    // 进度日志
                    if (totalStatements % PROGRESS_INTERVAL == 0) {
                        log.info("进度: {} 条语句已处理 (tables={}, rows={}, errors={})",
                            totalStatements, summary.tablesCreated, summary.rowsInserted, summary.errors);
                    }
                }

                currentStatement.setLength(0);
            }
        }

        // 处理末尾可能没有分号的残余文本
        String remaining = currentStatement.toString().strip();
        if (!remaining.isEmpty()) {
            totalStatements++;
            executeClassified(remaining, summary);
        }

        ImportSummary result = summary.build();
        log.info("导入完成: {} 表创建, {} 表删除, {} 行插入, {} 跳过, {} 错误, 耗时 {} ms",
            result.tablesCreated(), result.tablesDropped(), result.rowsInserted(),
            result.statementsSkipped(), result.errors(), result.elapsedMillis());

        return result;
    }

    // ==================== 语句分类 ====================

    /**
     * 对语句进行分类。
     *
     * <p>先用 {@link #stripLeadingComments(String)} 去掉前导注释，
     * 再按首个关键字前缀匹配。这样可以正确处理 Navicat dump 中
     * 注释紧跟 SQL 语句的情况（如 {@code -- Table structure\nDROP TABLE ...}）。
     */
    public static StatementKind classify(String sql) {
        String stripped = stripLeadingComments(sql);
        if (stripped.isEmpty()) {
            return StatementKind.COMMENT_ONLY;
        }

        String upper = stripped.toUpperCase();

        if (upper.startsWith("SET ")) {
            return StatementKind.SET;
        }
        if (upper.startsWith("DROP TABLE")) {
            return StatementKind.DROP_TABLE;
        }
        if (upper.startsWith("CREATE TABLE")) {
            return StatementKind.CREATE_TABLE;
        }
        if (upper.startsWith("INSERT ")) {
            return StatementKind.INSERT;
        }

        return StatementKind.UNKNOWN;
    }

    /**
     * 去掉 SQL 文本开头的注释。
     *
     * <p>支持两种注释风格：
     * <ul>
     *   <li>{@code /* ... * /} 块注释</li>
     *   <li>{@code -- ...} 行注释（到行尾）</li>
     * </ul>
     */
    public static String stripLeadingComments(String sql) {
        String s = sql.stripLeading();
        while (true) {
            if (s.startsWith("/*")) {
                int end = s.indexOf("*/");
                if (end < 0) {
                    return ""; // 未闭合的块注释，视为纯注释
                }
                s = s.substring(end + 2).stripLeading();
            } else if (s.startsWith("--")) {
                int newline = s.indexOf('\n');
                if (newline < 0) {
                    return ""; // 整行都是注释
                }
                s = s.substring(newline + 1).stripLeading();
            } else {
                return s;
            }
        }
    }

    // ==================== 执行路由 ====================

    /**
     * 分类语句后路由到对应的执行策略。
     */
    private void executeClassified(String sql, SummaryBuilder summary) {
        StatementKind kind = classify(sql);

        switch (kind) {
            case COMMENT_ONLY -> {
                // 静默跳过
            }
            case SET -> {
                log.debug("跳过 SET 语句: {}", truncate(sql, 80));
                summary.skip();
            }
            case DROP_TABLE -> {
                try {
                    session.execute(sql);
                    summary.droppedTable();
                } catch (Exception e) {
                    summary.addError("DROP TABLE: " + truncate(sql, 100) + " -> " + e.getMessage());
                    log.warn("DROP TABLE 失败: {}", e.getMessage());
                }
            }
            case CREATE_TABLE -> {
                try {
                    session.execute(sql);
                    summary.createdTable();
                    // 提取表名用于日志
                    String tableName = extractTableName(sql);
                    log.info("创建表: {}", tableName);
                } catch (Exception e) {
                    summary.addError("CREATE TABLE: " + truncate(sql, 100) + " -> " + e.getMessage());
                    log.warn("CREATE TABLE 失败: {}", e.getMessage());
                }
            }
            case INSERT -> {
                try {
                    List<Row> result = session.execute(sql);
                    // result 通常包含一行 {affected_rows=N} 或类似结构
                    summary.insertedRows(1);
                } catch (Exception e) {
                    summary.addError("INSERT: " + truncate(sql, 100) + " -> " + e.getMessage());
                    log.debug("INSERT 失败: {}", e.getMessage());
                }
            }
            case UNKNOWN -> {
                try {
                    session.execute(sql);
                } catch (Exception e) {
                    summary.addError("UNKNOWN: " + truncate(sql, 100) + " -> " + e.getMessage());
                    log.debug("未知语句执行失败: {}", e.getMessage());
                }
            }
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 从 CREATE TABLE 语句中提取表名。
     * 支持反引号包裹和 IF NOT EXISTS。
     */
    public static String extractTableName(String sql) {
        String stripped = stripLeadingComments(sql);
        String upper = stripped.toUpperCase();
        int idx = upper.indexOf("CREATE TABLE");
        if (idx < 0) return "?";

        String after = stripped.substring(idx + "CREATE TABLE".length()).stripLeading();
        // 跳过 IF NOT EXISTS
        if (after.toUpperCase().startsWith("IF NOT EXISTS")) {
            after = after.substring("IF NOT EXISTS".length()).stripLeading();
        }
        // 提取表名（可能带反引号）
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < after.length(); i++) {
            char c = after.charAt(i);
            if (c == '`') continue; // 跳过反引号
            if (c == ' ' || c == '(' || c == '\n' || c == '\r' || c == '\t') break;
            name.append(c);
        }
        return name.toString();
    }

    /**
     * 截断字符串，超过 maxLen 时加省略号。
     */
    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    // ==================== 摘要构建器 ====================

    /**
     * 可变的摘要构建器，导入过程中逐步累积计数。
     * 导入结束后调用 {@link #build()} 生成不可变的 {@link ImportSummary}。
     */
    private static class SummaryBuilder {
        int tablesCreated;
        int tablesDropped;
        long rowsInserted;
        int statementsSkipped;
        int errors;
        final List<String> skippedObjects = new ArrayList<>();
        final List<String> errorMessages = new ArrayList<>();
        long startTime;

        void start() { startTime = System.currentTimeMillis(); }
        void createdTable() { tablesCreated++; }
        void droppedTable() { tablesDropped++; }
        void insertedRows(long n) { rowsInserted += n; }
        void skip() { statementsSkipped++; }
        void skipObject(String name) { statementsSkipped++; skippedObjects.add(name); }
        void addError(String msg) { errors++; errorMessages.add(msg); }

        ImportSummary build() {
            return new ImportSummary(
                tablesCreated, tablesDropped, rowsInserted,
                statementsSkipped, errors,
                Collections.unmodifiableList(new ArrayList<>(skippedObjects)),
                Collections.unmodifiableList(new ArrayList<>(errorMessages)),
                System.currentTimeMillis() - startTime
            );
        }
    }
}
