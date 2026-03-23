package cn.zhangyis.minidb.server.protocol.packets;

import cn.zhangyis.minidb.server.protocol.MysqlBufUtil;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * MySQL 服务端初始握手包（Protocol::HandshakeV10）。
 *
 * <p>连接建立后服务端发送的第一个包，包含协议版本、服务器信息、
 * 认证挑战数据（auth-plugin-data）和能力协商标志。</p>
 *
 * <p>auth-plugin-data 总共 20 字节，分两部分传输：
 * 前 8 字节在包前部，后 12 字节在 reserved 之后。
 * 客户端需拼接完整 20 字节用于密码认证计算。</p>
 */
public class HandshakePacket {

    private final int connectionId;
    private final byte[] authPluginData; // 20字节随机挑战
    private final int serverCapabilities;

    public HandshakePacket(int connectionId, byte[] authPluginData, int serverCapabilities) {
        if (authPluginData.length != 20) {
            throw new IllegalArgumentException("auth-plugin-data 必须为 20 字节");
        }
        this.connectionId = connectionId;
        this.authPluginData = authPluginData;
        this.serverCapabilities = serverCapabilities;
    }

    /**
     * 将握手包写入 ByteBuf。
     *
     * <p>包结构：
     * <pre>
     * 1  protocol_version
     * n  server_version (NUL-terminated)
     * 4  connection_id
     * 8  auth-plugin-data-part-1
     * 1  filler (0x00)
     * 2  capability_flags_lower
     * 1  character_set
     * 2  status_flags
     * 2  capability_flags_upper
     * 1  auth_plugin_data_length (or 0x00)
     * 10 reserved (all zeros)
     * 12 auth-plugin-data-part-2 (if CLIENT_SECURE_CONNECTION)
     * 1  filler (0x00)
     * n  auth_plugin_name (NUL-terminated)
     * </pre></p>
     */
    public void writeTo(ByteBuf buf) {
        // protocol version
        buf.writeByte(MysqlConstants.PROTOCOL_VERSION);
        // server version
        MysqlBufUtil.writeNullTerminatedString(buf, MysqlConstants.SERVER_VERSION, StandardCharsets.UTF_8);
        // connection id
        MysqlBufUtil.writeFixedLengthInt(buf, connectionId, 4);
        // auth-plugin-data-part-1（前8字节）
        buf.writeBytes(authPluginData, 0, 8);
        // filler
        buf.writeByte(0x00);
        // capability flags lower 2 bytes
        MysqlBufUtil.writeFixedLengthInt(buf, serverCapabilities & 0xFFFF, 2);
        // character set
        buf.writeByte(MysqlConstants.CHARSET_UTF8MB4);
        // status flags
        MysqlBufUtil.writeFixedLengthInt(buf, MysqlConstants.SERVER_STATUS_AUTOCOMMIT, 2);
        // capability flags upper 2 bytes
        MysqlBufUtil.writeFixedLengthInt(buf, (serverCapabilities >> 16) & 0xFFFF, 2);
        // auth plugin data length（整个 auth data 的长度）
        buf.writeByte(21); // 20 字节 data + 1 字节 NUL
        // reserved 10 bytes
        buf.writeZero(10);
        // auth-plugin-data-part-2（后12字节）
        buf.writeBytes(authPluginData, 8, 12);
        // NUL 终止
        buf.writeByte(0x00);
        // auth plugin name
        MysqlBufUtil.writeNullTerminatedString(buf,
                MysqlConstants.AUTH_PLUGIN_MYSQL_NATIVE_PASSWORD, StandardCharsets.UTF_8);
    }

    public int connectionId() {
        return connectionId;
    }

    public byte[] authPluginData() {
        return authPluginData;
    }

    public int serverCapabilities() {
        return serverCapabilities;
    }
}
