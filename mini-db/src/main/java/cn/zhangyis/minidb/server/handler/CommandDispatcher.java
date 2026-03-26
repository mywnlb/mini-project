package cn.zhangyis.minidb.server.handler;

import cn.zhangyis.minidb.server.ConnectionSession;
import cn.zhangyis.minidb.server.netty.PacketWriter;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import cn.zhangyis.minidb.server.protocol.TypeMapping;
import cn.zhangyis.minidb.server.protocol.packets.*;
import cn.zhangyis.minidb.sql.exec.*;
import cn.zhangyis.minidb.sql.exec.PreparedStatement;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * MySQL 命令分发器。
 *
 * <p>设计模式：命令分发（Command Dispatcher）——根据命令字节路由到对应的
 * 处理逻辑。每个命令的处理封装为独立方法，保持单一职责。</p>
 *
 * <p>核心不变式：SQL 执行必须在独立线程池中完成（由调用方保证），
 * 本类只负责命令解析和结果编码。</p>
 */
public class CommandDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CommandDispatcher.class);

    private final ConnectionSession session;
    private final PacketWriter writer;

    public CommandDispatcher(ConnectionSession session, PacketWriter writer) {
        this.session = session;
        this.writer = writer;
    }

    /**
     * 分发命令。
     *
     * @param commandByte 命令字节
     * @param payload     命令 payload（不含命令字节）
     */
    public void dispatch(byte commandByte, ByteBuf payload) {
        try {
            switch (commandByte) {
                case MysqlConstants.COM_QUERY -> handleQuery(payload);
                case MysqlConstants.COM_STMT_PREPARE -> handleStmtPrepare(payload);
                case MysqlConstants.COM_STMT_EXECUTE -> handleStmtExecute(payload);
                case MysqlConstants.COM_STMT_CLOSE -> handleStmtClose(payload);
                case MysqlConstants.COM_STMT_RESET -> handleStmtReset(payload);
                case MysqlConstants.COM_INIT_DB -> handleInitDb(payload);
                case MysqlConstants.COM_PING -> handlePing();
                case MysqlConstants.COM_QUIT -> { /* 由 ConnectionHandler 处理 */ }
                default -> {
                    writer.writeErr(new ErrPacket(ErrorMapping.ER_UNKNOWN_COM_ERROR,
                            "08S01", "Unknown command: " + (commandByte & 0xFF)));
                    writer.flush();
                }
            }
        } catch (Exception e) {
            log.error("命令执行异常: command=0x{}", Integer.toHexString(commandByte & 0xFF), e);
            writer.writeErr(ErrorMapping.fromException(e));
            writer.flush();
        }
    }

    // ==================== 多语句拆分 ====================

    /**
     * 引号感知的分号拆分。
     * <p>状态机遍历字符，跟踪单引号/双引号/反引号状态，
     * 仅在引号外的 {@code ;} 处拆分。正确处理反斜杠转义的引号。</p>
     *
     * @param rawSql 原始 SQL 文本（可能包含多条语句）
     * @return 拆分后的非空语句列表
     */
    static List<String> splitStatements(String rawSql) {
        List<String> result = new ArrayList<>();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inBacktick = false;

        int start = 0;
        for (int i = 0; i < rawSql.length(); i++) {
            char c = rawSql.charAt(i);

            // 处理反斜杠转义：跳过下一个字符
            if (c == '\\' && (inSingleQuote || inDoubleQuote)) {
                i++; // 跳过被转义的字符
                continue;
            }

            if (c == '\'' && !inDoubleQuote && !inBacktick) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote && !inBacktick) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == '`' && !inSingleQuote && !inDoubleQuote) {
                inBacktick = !inBacktick;
            } else if (c == ';' && !inSingleQuote && !inDoubleQuote && !inBacktick) {
                String stmt = rawSql.substring(start, i).trim();
                if (!stmt.isEmpty()) {
                    result.add(stmt);
                }
                start = i + 1;
            }
        }

        // 处理最后一条语句（没有尾部分号的情况）
        if (start < rawSql.length()) {
            String stmt = rawSql.substring(start).trim();
            if (!stmt.isEmpty()) {
                result.add(stmt);
            }
        }

        return result;
    }

    // ==================== COM_QUERY ====================

    private void handleQuery(ByteBuf payload) {
        String rawSql = ComQueryPacket.decode(payload).sql().trim();
        log.debug("COM_QUERY: {}", rawSql);

        // 多语句拆分（引号感知，Navicat 等客户端会用分号拼接多条 SQL）
        List<String> statements = splitStatements(rawSql);
        for (String sql : statements) {

            // 先尝试拦截兼容性查询
            if (SystemVariableHandler.tryHandle(sql, session, writer)) {
                continue;
            }

            // 正常执行 SQL
            try {
                List<Row> rows = session.sqlSession().execute(sql);
                writeQueryResult(rows, sql);
            } catch (UnsupportedOperationException e) {
                // information_schema 不支持的表等场景：返回空结果集而非断开连接
                log.warn("查询不支持，返回空结果集: {}", e.getMessage());
                writer.writeErr(new ErrPacket(ErrorMapping.ER_GENERAL_ERROR,
                        "42000", e.getMessage()));
                writer.flush();
            } catch (Exception e) {
                // 其他执行错误：返回错误包并停止处理后续语句
                log.warn("SQL 执行失败: {}", e.getMessage());
                writer.writeErr(ErrorMapping.fromException(e));
                writer.flush();
                return;
            }
        }
    }

    /**
     * 根据查询结果判断是 DML 还是 SELECT，选择对应的响应格式。
     */
    private void writeQueryResult(List<Row> rows, String sql) {
        ResultMetadataResolver.QueryMetadata metadata = ResultMetadataResolver.resolve(sql, session);
        int statusFlags = StatusFlagBuilder.build(session.executionContext());

        if (isDmlResult(rows) && !metadata.resultSet()) {
            // DML 结果：返回 OkPacket
            long affectedRows = 0;
            if (!rows.isEmpty()) {
                Object val = rows.get(0).get("affected_rows");
                if (val instanceof Number n) affectedRows = n.longValue();
            }
            writer.writeOk(OkPacket.dml(affectedRows, 0, statusFlags));
            writer.flush();
        } else if (metadata.resultSet()) {
            // SELECT / EXPLAIN 结果：返回完整结果集
            ResultSetWriter.write(rows, metadata.columns(), session, writer);
        } else {
            writer.writeOk(OkPacket.ok(statusFlags));
            writer.flush();
        }
    }

    /**
     * 判断结果是否为 DML 操作的结果。
     * DML 操作返回单行 Row，包含 "affected_rows" 键。
     */
    private boolean isDmlResult(List<Row> rows) {
        if (rows.size() == 1) {
            Row row = rows.get(0);
            return row.columns().containsKey("affected_rows");
        }
        return false;
    }

    // ==================== COM_STMT_PREPARE ====================

    private void handleStmtPrepare(ByteBuf payload) {
        String sql = ComStmtPreparePacket.decode(payload).sql();
        log.debug("COM_STMT_PREPARE: {}", sql);

        // 检查是否为系统变量/拦截类查询（SQL 引擎不支持的语法如 SELECT @@xxx）
        if (SystemVariableHandler.canHandle(sql)) {
            int stmtId = session.nextStatementId();
            List<ResultColumnMetadata> resultMeta = SystemVariableHandler.resultMetadata(sql, session);
            ServerPreparedStatement sps = new ServerPreparedStatement(stmtId, sql, 0, resultMeta);
            session.registerPreparedStatement(stmtId, sps);

            writer.writeStmtPrepareOk(new StmtPrepareOkPacket(stmtId, resultMeta.size(), 0, 0));
            if (!resultMeta.isEmpty()) {
                writeResultMetadata(resultMeta);
            }
            writer.flush();
            return;
        }

        // 创建 mini-db PreparedStatement
        PreparedStatement innerPs = new PreparedStatement(
                sql, session.catalog(), session.planCache(),
                new cn.zhangyis.minidb.sql.exec.PhysicalPlanner(
                        new cn.zhangyis.minidb.sql.optimize.cost.CostOptimizer(),
                        session.dataSource(), session.catalog()));

        int stmtId = session.nextStatementId();
        int numParams = innerPs.paramCount();

        ResultMetadataResolver.QueryMetadata metadata = ResultMetadataResolver.resolve(sql, session);
        List<ResultColumnMetadata> resultMeta = metadata.resultSet()
                ? metadata.columns()
                : Collections.emptyList();

        ServerPreparedStatement sps = new ServerPreparedStatement(stmtId, innerPs, numParams, resultMeta);
        session.registerPreparedStatement(stmtId, sps);

        int statusFlags = StatusFlagBuilder.build(session.executionContext());

        // 发送 StmtPrepareOk
        writer.writeStmtPrepareOk(new StmtPrepareOkPacket(stmtId, resultMeta.size(), numParams, 0));

        // 参数列定义（每个参数一个 ColumnDefinition）
        if (numParams > 0) {
            for (int i = 0; i < numParams; i++) {
                writer.writeColumnDefinition(new ColumnDefinitionPacket.Builder()
                        .name("?").orgName("?")
                        .columnType(MysqlConstants.MYSQL_TYPE_VAR_STRING)
                        .build());
            }
            writer.writeEof(new EofPacket(0, statusFlags));
        }

        if (!resultMeta.isEmpty()) {
            writeResultMetadata(resultMeta);
        }

        writer.flush();
    }

    // ==================== COM_STMT_EXECUTE ====================

    private void handleStmtExecute(ByteBuf payload) {
        // 先读 statement_id 以查找参数数量
        int stmtId = (int) cn.zhangyis.minidb.server.protocol.MysqlBufUtil.readFixedLengthInt(payload, 4);
        ServerPreparedStatement sps = session.getPreparedStatement(stmtId);
        if (sps == null) {
            writer.writeErr(new ErrPacket(ErrorMapping.ER_UNKNOWN_STMT_HANDLER,
                    "HY000", "Unknown prepared statement id: " + stmtId));
            writer.flush();
            return;
        }

        // 拦截类语句：获取结果后用二进制协议写出
        if (sps.isIntercepted()) {
            payload.skipBytes(payload.readableBytes());
            List<Row> rows = SystemVariableHandler.executeIntercepted(sps.sql(), session);
            int statusFlags = StatusFlagBuilder.build(session.executionContext());
            if (sps.resultColumnMeta().isEmpty()) {
                writer.writeOk(OkPacket.ok(statusFlags));
                writer.flush();
            } else {
                BinaryResultSetWriter.writeWithMetadata(rows != null ? rows : List.of(), sps.resultColumnMeta(), session, writer);
            }
            return;
        }

        // 回退 readerIndex，让 ComStmtExecutePacket 完整解码
        payload.readerIndex(payload.readerIndex() - 4);
        ComStmtExecutePacket execPacket = ComStmtExecutePacket.decode(payload, sps.numParams());

        log.debug("COM_STMT_EXECUTE: stmtId={}, params={}", stmtId, execPacket.paramValues());

        // 绑定参数
        PreparedStatement innerPs = sps.innerPs();
        innerPs.reset();
        List<Object> paramValues = execPacket.paramValues();
        for (int i = 0; i < paramValues.size(); i++) {
            Object val = paramValues.get(i);
            if (val == null) {
                innerPs.setNull(i + 1); // JDBC 风格 1-based
            } else if (val instanceof Integer intVal) {
                innerPs.setInt(i + 1, intVal);
            } else if (val instanceof Long longVal) {
                innerPs.setLong(i + 1, longVal);
            } else if (val instanceof Double doubleVal) {
                innerPs.setDouble(i + 1, doubleVal);
            } else if (val instanceof String strVal) {
                innerPs.setString(i + 1, strVal);
            } else {
                innerPs.setObject(i + 1, val);
            }
        }

        // 执行
        List<Row> rows = innerPs.execute();

        // 判断结果类型并写入
        int statusFlags = StatusFlagBuilder.build(session.executionContext());
        if (isDmlResult(rows) && sps.resultColumnMeta().isEmpty()) {
            long affectedRows = 0;
            if (!rows.isEmpty()) {
                Object val = rows.get(0).get("affected_rows");
                if (val instanceof Number n) affectedRows = n.longValue();
            }
            writer.writeOk(OkPacket.dml(affectedRows, 0, statusFlags));
            writer.flush();
        } else if (!sps.resultColumnMeta().isEmpty()) {
            BinaryResultSetWriter.writeWithMetadata(rows, sps.resultColumnMeta(), session, writer);
        } else {
            // 推断列类型
            List<Integer> colTypes = inferColumnTypes(rows);
            BinaryResultSetWriter.write(rows, colTypes, session, writer);
        }
    }

    private List<Integer> inferColumnTypes(List<Row> rows) {
        if (rows.isEmpty()) return Collections.emptyList();
        Row firstRow = rows.get(0);
        List<Integer> types = new ArrayList<>();
        for (Object val : firstRow.columns().values()) {
            types.add(TypeMapping.inferMysqlType(val));
        }
        return types;
    }

    // ==================== COM_STMT_CLOSE ====================

    private void handleStmtClose(ByteBuf payload) {
        ComStmtClosePacket closePacket = ComStmtClosePacket.decode(payload);
        session.removePreparedStatement(closePacket.statementId());
        // COM_STMT_CLOSE 无响应包
    }

    // ==================== COM_STMT_RESET ====================

    private void handleStmtReset(ByteBuf payload) {
        int stmtId = (int) cn.zhangyis.minidb.server.protocol.MysqlBufUtil.readFixedLengthInt(payload, 4);
        ServerPreparedStatement sps = session.getPreparedStatement(stmtId);
        if (sps != null && sps.innerPs() != null) {
            sps.innerPs().reset();
        }
        writer.writeOk(OkPacket.ok(StatusFlagBuilder.build(session.executionContext())));
        writer.flush();
    }

    // ==================== COM_INIT_DB ====================

    private void handleInitDb(ByteBuf payload) {
        String database = payload.toString(StandardCharsets.UTF_8).trim();
        session.setCurrentDatabase(database);
        writer.writeOk(OkPacket.ok(StatusFlagBuilder.build(session.executionContext())));
        writer.flush();
    }

    // ==================== COM_PING ====================

    private void handlePing() {
        writer.writeOk(OkPacket.ok(StatusFlagBuilder.build(session.executionContext())));
        writer.flush();
    }

    private void writeResultMetadata(List<ResultColumnMetadata> metadata) {
        int statusFlags = StatusFlagBuilder.build(session.executionContext());
        for (ResultColumnMetadata column : metadata) {
            writer.writeColumnDefinition(column.definition());
        }
        writer.writeEof(new EofPacket(0, statusFlags));
    }
}
