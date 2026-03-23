package cn.zhangyis.minidb.server.protocol.packets;

import cn.zhangyis.minidb.server.protocol.MysqlBufUtil;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * COM_QUERY 命令包解码。
 *
 * <p>客户端发送文本 SQL 查询的最常用命令。
 * 包结构极简：1字节命令码(0x03) + SQL字符串（rest of packet）。</p>
 */
public class ComQueryPacket {

    private final String sql;

    private ComQueryPacket(String sql) {
        this.sql = sql;
    }

    /**
     * 从 payload 解码（命令字节已被调用方消费）。
     */
    public static ComQueryPacket decode(ByteBuf buf) {
        String sql = MysqlBufUtil.readRestOfPacketString(buf, StandardCharsets.UTF_8);
        return new ComQueryPacket(sql);
    }

    public String sql() {
        return sql;
    }
}
