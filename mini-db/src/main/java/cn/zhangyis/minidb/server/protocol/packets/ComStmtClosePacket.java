package cn.zhangyis.minidb.server.protocol.packets;

import cn.zhangyis.minidb.server.protocol.MysqlBufUtil;
import io.netty.buffer.ByteBuf;

/**
 * COM_STMT_CLOSE 命令包解码。
 *
 * <p>客户端关闭预编译语句，释放服务端资源。
 * 此命令无响应包——服务端静默关闭即可。</p>
 *
 * <p>包结构：1字节命令码(0x19) + 4字节 statement_id。</p>
 */
public class ComStmtClosePacket {

    private final int statementId;

    private ComStmtClosePacket(int statementId) {
        this.statementId = statementId;
    }

    /**
     * 从 payload 解码（命令字节已被调用方消费）。
     */
    public static ComStmtClosePacket decode(ByteBuf buf) {
        int stmtId = (int) MysqlBufUtil.readFixedLengthInt(buf, 4);
        return new ComStmtClosePacket(stmtId);
    }

    public int statementId() {
        return statementId;
    }
}
