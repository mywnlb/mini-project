package cn.zhangyis.minidb.server.protocol.packets;

import cn.zhangyis.minidb.server.protocol.MysqlBufUtil;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * MySQL 客户端认证响应包（Protocol::HandshakeResponse41）。
 *
 * <p>客户端在收到服务端握手包后发送此包，包含用户名、认证数据、
 * 目标数据库等信息。仅解码使用，服务端不需要编码此包。</p>
 */
public class HandshakeResponsePacket {

    private final int clientCapabilities;
    private final int maxPacketSize;
    private final int charset;
    private final String username;
    private final byte[] authResponse;
    private final String database;
    private final String authPluginName;

    private HandshakeResponsePacket(int clientCapabilities, int maxPacketSize, int charset,
                                    String username, byte[] authResponse, String database,
                                    String authPluginName) {
        this.clientCapabilities = clientCapabilities;
        this.maxPacketSize = maxPacketSize;
        this.charset = charset;
        this.username = username;
        this.authResponse = authResponse;
        this.database = database;
        this.authPluginName = authPluginName;
    }

    /**
     * 从 ByteBuf 解码客户端认证响应。
     *
     * <p>包结构（Protocol 41）：
     * <pre>
     * 4  capability_flags
     * 4  max_packet_size
     * 1  character_set
     * 23 reserved (all zeros)
     * n  username (NUL-terminated)
     * n  auth_response (length-encoded or fixed)
     * n  database (NUL-terminated, if CLIENT_CONNECT_WITH_DB)
     * n  auth_plugin_name (NUL-terminated, if CLIENT_PLUGIN_AUTH)
     * </pre></p>
     *
     * @param buf                包 payload（不含包头）
     * @param serverCapabilities 服务端声明的能力集，用于判断哪些字段存在
     */
    public static HandshakeResponsePacket decode(ByteBuf buf, int serverCapabilities) {
        int clientCap = (int) MysqlBufUtil.readFixedLengthInt(buf, 4);
        int maxPktSize = (int) MysqlBufUtil.readFixedLengthInt(buf, 4);
        int charsetId = buf.readByte() & 0xFF;

        // 跳过 23 字节 reserved
        buf.skipBytes(23);

        String user = MysqlBufUtil.readNullTerminatedString(buf, StandardCharsets.UTF_8);

        // 读取 auth response
        byte[] authResp;
        if ((clientCap & MysqlConstants.CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA) != 0) {
            int len = (int) MysqlBufUtil.readLengthEncodedInt(buf);
            authResp = MysqlBufUtil.readBytes(buf, len);
        } else if ((clientCap & MysqlConstants.CLIENT_SECURE_CONNECTION) != 0) {
            int len = buf.readByte() & 0xFF;
            authResp = MysqlBufUtil.readBytes(buf, len);
        } else {
            // 旧协议：读到 NUL
            authResp = readBytesUntilNul(buf);
        }

        // 可选：database
        String db = null;
        if ((clientCap & MysqlConstants.CLIENT_CONNECT_WITH_DB) != 0 && buf.readableBytes() > 0) {
            db = MysqlBufUtil.readNullTerminatedString(buf, StandardCharsets.UTF_8);
        }

        // 可选：auth plugin name
        String pluginName = null;
        if ((clientCap & MysqlConstants.CLIENT_PLUGIN_AUTH) != 0 && buf.readableBytes() > 0) {
            pluginName = MysqlBufUtil.readNullTerminatedString(buf, StandardCharsets.UTF_8);
        }

        return new HandshakeResponsePacket(clientCap, maxPktSize, charsetId,
                user, authResp, db, pluginName);
    }

    private static byte[] readBytesUntilNul(ByteBuf buf) {
        int start = buf.readerIndex();
        while (buf.readableBytes() > 0 && buf.getByte(buf.readerIndex()) != 0x00) {
            buf.skipBytes(1);
        }
        int len = buf.readerIndex() - start;
        buf.readerIndex(start);
        byte[] bytes = MysqlBufUtil.readBytes(buf, len);
        if (buf.readableBytes() > 0) {
            buf.skipBytes(1); // 跳过 NUL
        }
        return bytes;
    }

    public int clientCapabilities() { return clientCapabilities; }
    public int maxPacketSize() { return maxPacketSize; }
    public int charset() { return charset; }
    public String username() { return username; }
    public byte[] authResponse() { return authResponse; }
    public String database() { return database; }
    public String authPluginName() { return authPluginName; }
}
