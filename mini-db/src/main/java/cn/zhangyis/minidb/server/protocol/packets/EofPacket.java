package cn.zhangyis.minidb.server.protocol.packets;

import cn.zhangyis.minidb.server.protocol.MysqlBufUtil;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import io.netty.buffer.ByteBuf;

/**
 * MySQL EOF 标记包。
 *
 * <p>在文本/二进制结果集中用作分隔符：
 * 列定义之后、行数据之后各发一个 EOF 包。</p>
 *
 * <p>注意：当客户端声明 CLIENT_DEPRECATE_EOF 能力时，
 * EOF 包被 OK 包替代。当前实现不支持 DEPRECATE_EOF。</p>
 *
 * <p>包结构：
 * <pre>
 * 1  header (0xFE)
 * 2  warnings
 * 2  status_flags
 * </pre></p>
 */
public class EofPacket {

    private final int warnings;
    private final int statusFlags;

    public EofPacket(int warnings, int statusFlags) {
        this.warnings = warnings;
        this.statusFlags = statusFlags;
    }

    public void writeTo(ByteBuf buf) {
        buf.writeByte(MysqlConstants.EOF_HEADER);
        MysqlBufUtil.writeFixedLengthInt(buf, warnings, 2);
        MysqlBufUtil.writeFixedLengthInt(buf, statusFlags, 2);
    }

    /** 判断一个包是否为 EOF 包（header=0xFE 且 payload≤5字节） */
    public static boolean isEof(int header, int payloadLength) {
        return header == MysqlConstants.EOF_HEADER && payloadLength <= 5;
    }

    public int warnings() { return warnings; }
    public int statusFlags() { return statusFlags; }
}
