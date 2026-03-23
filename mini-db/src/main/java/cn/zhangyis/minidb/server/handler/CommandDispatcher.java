package cn.zhangyis.minidb.server.handler;

import cn.zhangyis.minidb.server.ConnectionSession;
import cn.zhangyis.minidb.server.netty.PacketWriter;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import cn.zhangyis.minidb.server.protocol.TypeMapping;
import cn.zhangyis.minidb.server.protocol.packets.*;
import cn.zhangyis.minidb.sql.catalog.ColumnMeta;
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

    // ==================== COM_QUERY ====================

    private void handleQuery(ByteBuf payload) {
        String sql = ComQueryPacket.decode(payload).sql().trim();
        log.debug("COM_QUERY: {}", sql);

        // 先尝试拦截兼容性查询
        if (SystemVariableHandler.tryHandle(sql, session, writer)) {
            return;
        }

        // 正常执行 SQL
        List<Row> rows = session.sqlSession().execute(sql);
        writeQueryResult(rows, sql);
    }

    /**
     * 根据查询结果判断是 DML 还是 SELECT，选择对应的响应格式。
     */
    private void writeQueryResult(List<Row> rows, String sql) {
        int statusFlags = StatusFlagBuilder.build(session.executionContext());

        if (isDmlResult(rows)) {
            // DML 结果：返回 OkPacket
            long affectedRows = 0;
            if (!rows.isEmpty()) {
                Object val = rows.get(0).get("affected_rows");
                if (val instanceof Number n) affectedRows = n.longValue();
            }
            writer.writeOk(OkPacket.dml(affectedRows, 0, statusFlags));
            writer.flush();
        } else {
            // SELECT / EXPLAIN 结果：返回完整结果集
            ResultSetWriter.write(rows, session, writer);
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
        return rows.isEmpty(); // DDL 也可能返回空结果
    }

    // ==================== COM_STMT_PREPARE ====================

    private void handleStmtPrepare(ByteBuf payload) {
        String sql = ComStmtPreparePacket.decode(payload).sql();
        log.debug("COM_STMT_PREPARE: {}", sql);

        // 检查是否为系统变量/拦截类查询（SQL 引擎不支持的语法如 SELECT @@xxx）
        if (SystemVariableHandler.canHandle(sql)) {
            int stmtId = session.nextStatementId();
            ServerPreparedStatement sps = new ServerPreparedStatement(stmtId, sql, 0, Collections.emptyList());
            session.registerPreparedStatement(stmtId, sps);

            // 返回 StmtPrepareOk：0参数、0列（execute 时再返回实际结果）
            writer.writeStmtPrepareOk(new StmtPrepareOkPacket(stmtId, 0, 0, 0));
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

        // 推断结果列元数据（当前简化：无法在 PREPARE 阶段确定结果列）
        List<ColumnMeta> resultMeta = Collections.emptyList();

        ServerPreparedStatement sps = new ServerPreparedStatement(stmtId, innerPs, numParams, resultMeta);
        session.registerPreparedStatement(stmtId, sps);

        int statusFlags = StatusFlagBuilder.build(session.executionContext());

        // 发送 StmtPrepareOk
        writer.writeStmtPrepareOk(new StmtPrepareOkPacket(stmtId, 0, numParams, 0));

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
            if (rows == null || rows.isEmpty()) {
                writer.writeOk(OkPacket.ok(statusFlags));
                writer.flush();
            } else {
                List<Integer> colTypes = inferColumnTypes(rows);
                BinaryResultSetWriter.write(rows, colTypes, session, writer);
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
        if (isDmlResult(rows)) {
            long affectedRows = 0;
            if (!rows.isEmpty()) {
                Object val = rows.get(0).get("affected_rows");
                if (val instanceof Number n) affectedRows = n.longValue();
            }
            writer.writeOk(OkPacket.dml(affectedRows, 0, statusFlags));
            writer.flush();
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
        if (sps != null) {
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
}
