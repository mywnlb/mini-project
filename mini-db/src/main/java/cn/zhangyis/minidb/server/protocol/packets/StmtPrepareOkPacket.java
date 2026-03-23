package cn.zhangyis.minidb.server.protocol.packets;

import cn.zhangyis.minidb.server.protocol.MysqlBufUtil;
import io.netty.buffer.ByteBuf;

/**
 * COM_STMT_PREPARE 的成功响应包。
 *
 * <p>告知客户端预编译成功，返回服务端分配的 statement_id、
 * 参数数量和结果列数量。后续会跟随参数和结果列的 ColumnDefinition 包。</p>
 *
 * <p>包结构：
 * <pre>
 * 1  status (0x00)
 * 4  statement_id
 * 2  num_columns
 * 2  num_params
 * 1  filler (0x00)
 * 2  warning_count
 * </pre></p>
 */
public class StmtPrepareOkPacket {

    private final int statementId;
    private final int numColumns;
    private final int numParams;
    private final int warningCount;

    public StmtPrepareOkPacket(int statementId, int numColumns, int numParams, int warningCount) {
        this.statementId = statementId;
        this.numColumns = numColumns;
        this.numParams = numParams;
        this.warningCount = warningCount;
    }

    public void writeTo(ByteBuf buf) {
        buf.writeByte(0x00); // status
        MysqlBufUtil.writeFixedLengthInt(buf, statementId, 4);
        MysqlBufUtil.writeFixedLengthInt(buf, numColumns, 2);
        MysqlBufUtil.writeFixedLengthInt(buf, numParams, 2);
        buf.writeByte(0x00); // filler
        MysqlBufUtil.writeFixedLengthInt(buf, warningCount, 2);
    }

    /**
     * 从 ByteBuf 解码（JDBC 驱动客户端使用）。
     * status 字节已被调用方读取。
     */
    public static StmtPrepareOkPacket decode(ByteBuf buf) {
        int stmtId = (int) MysqlBufUtil.readFixedLengthInt(buf, 4);
        int numCols = (int) MysqlBufUtil.readFixedLengthInt(buf, 2);
        int numPar = (int) MysqlBufUtil.readFixedLengthInt(buf, 2);
        buf.skipBytes(1); // filler
        int warns = (int) MysqlBufUtil.readFixedLengthInt(buf, 2);
        return new StmtPrepareOkPacket(stmtId, numCols, numPar, warns);
    }

    public int statementId() { return statementId; }
    public int numColumns() { return numColumns; }
    public int numParams() { return numParams; }
    public int warningCount() { return warningCount; }
}
