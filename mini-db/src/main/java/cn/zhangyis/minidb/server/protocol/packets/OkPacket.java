package cn.zhangyis.minidb.server.protocol.packets;

import cn.zhangyis.minidb.server.protocol.MysqlBufUtil;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * MySQL OK 响应包。
 *
 * <p>DML 操作成功、DDL 执行成功、事务控制成功等场景返回此包。
 * 包含影响行数、最后插入 ID、服务状态标志等信息。</p>
 *
 * <p>包结构：
 * <pre>
 * 1  header (0x00)
 * n  affected_rows (length-encoded int)
 * n  last_insert_id (length-encoded int)
 * 2  status_flags (if CLIENT_PROTOCOL_41)
 * 2  warnings (if CLIENT_PROTOCOL_41)
 * n  info (rest of packet string, 可选)
 * </pre></p>
 */
public class OkPacket {

    private final long affectedRows;
    private final long lastInsertId;
    private final int statusFlags;
    private final int warnings;
    private final String info;

    public OkPacket(long affectedRows, long lastInsertId, int statusFlags, int warnings, String info) {
        this.affectedRows = affectedRows;
        this.lastInsertId = lastInsertId;
        this.statusFlags = statusFlags;
        this.warnings = warnings;
        this.info = info;
    }

    /** 创建一个无额外信息的简单 OK 包 */
    public static OkPacket ok(int statusFlags) {
        return new OkPacket(0, 0, statusFlags, 0, "");
    }

    /** 创建一个 DML 结果 OK 包 */
    public static OkPacket dml(long affectedRows, long lastInsertId, int statusFlags) {
        return new OkPacket(affectedRows, lastInsertId, statusFlags, 0, "");
    }

    public void writeTo(ByteBuf buf) {
        buf.writeByte(MysqlConstants.OK_HEADER);
        MysqlBufUtil.writeLengthEncodedInt(buf, affectedRows);
        MysqlBufUtil.writeLengthEncodedInt(buf, lastInsertId);
        // Protocol 41: status_flags + warnings
        MysqlBufUtil.writeFixedLengthInt(buf, statusFlags, 2);
        MysqlBufUtil.writeFixedLengthInt(buf, warnings, 2);
        if (info != null && !info.isEmpty()) {
            buf.writeBytes(info.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 从 ByteBuf 解码 OK 包（JDBC 驱动客户端使用）。
     * buf 的 readerIndex 应指向 header 字节之后（header 已被调用方读取）。
     */
    public static OkPacket decode(ByteBuf buf) {
        long affected = MysqlBufUtil.readLengthEncodedInt(buf);
        long lastId = MysqlBufUtil.readLengthEncodedInt(buf);
        int status = (int) MysqlBufUtil.readFixedLengthInt(buf, 2);
        int warn = (int) MysqlBufUtil.readFixedLengthInt(buf, 2);
        String infoStr = "";
        if (buf.readableBytes() > 0) {
            infoStr = MysqlBufUtil.readRestOfPacketString(buf, StandardCharsets.UTF_8);
        }
        return new OkPacket(affected, lastId, status, warn, infoStr);
    }

    public long affectedRows() { return affectedRows; }
    public long lastInsertId() { return lastInsertId; }
    public int statusFlags() { return statusFlags; }
    public int warnings() { return warnings; }
    public String info() { return info; }
}
