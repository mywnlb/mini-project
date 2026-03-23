package cn.zhangyis.minidb.server.protocol.packets;

import cn.zhangyis.minidb.server.protocol.MysqlBufUtil;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * MySQL 错误响应包。
 *
 * <p>SQL 执行失败、认证失败等场景返回此包。
 * 包含 MySQL 错误码、SQL 状态码和人可读的错误消息。</p>
 *
 * <p>包结构（Protocol 41）：
 * <pre>
 * 1  header (0xFF)
 * 2  error_code
 * 1  sql_state_marker ('#')
 * 5  sql_state
 * n  error_message (rest of packet)
 * </pre></p>
 */
public class ErrPacket {

    private final int errorCode;
    private final String sqlState;
    private final String message;

    public ErrPacket(int errorCode, String sqlState, String message) {
        this.errorCode = errorCode;
        this.sqlState = sqlState;
        this.message = message;
    }

    /** 通用错误快捷构造 */
    public static ErrPacket error(int errorCode, String message) {
        return new ErrPacket(errorCode, "HY000", message);
    }

    public void writeTo(ByteBuf buf) {
        buf.writeByte(MysqlConstants.ERR_HEADER);
        MysqlBufUtil.writeFixedLengthInt(buf, errorCode, 2);
        buf.writeByte('#');
        buf.writeBytes(sqlState.getBytes(StandardCharsets.UTF_8));
        buf.writeBytes(message.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 从 ByteBuf 解码 ERR 包（JDBC 驱动客户端使用）。
     * buf 的 readerIndex 应指向 header 字节之后。
     */
    public static ErrPacket decode(ByteBuf buf) {
        int code = (int) MysqlBufUtil.readFixedLengthInt(buf, 2);
        buf.skipBytes(1); // '#'
        String state = MysqlBufUtil.readFixedLengthString(buf, 5, StandardCharsets.UTF_8);
        String msg = MysqlBufUtil.readRestOfPacketString(buf, StandardCharsets.UTF_8);
        return new ErrPacket(code, state, msg);
    }

    public int errorCode() { return errorCode; }
    public String sqlState() { return sqlState; }
    public String message() { return message; }
}
