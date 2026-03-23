package cn.zhangyis.minidb.server.protocol.packets;

import cn.zhangyis.minidb.server.protocol.MysqlBufUtil;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * COM_STMT_PREPARE 命令包解码。
 *
 * <p>客户端请求预编译 SQL 语句。
 * 包结构：1字节命令码(0x16) + SQL字符串。</p>
 */
public class ComStmtPreparePacket {

    private final String sql;

    private ComStmtPreparePacket(String sql) {
        this.sql = sql;
    }

    /**
     * 从 payload 解码（命令字节已被调用方消费）。
     */
    public static ComStmtPreparePacket decode(ByteBuf buf) {
        String sql = MysqlBufUtil.readRestOfPacketString(buf, StandardCharsets.UTF_8);
        return new ComStmtPreparePacket(sql);
    }

    public String sql() {
        return sql;
    }
}
