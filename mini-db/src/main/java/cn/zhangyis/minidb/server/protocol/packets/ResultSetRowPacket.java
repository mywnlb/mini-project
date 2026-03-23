package cn.zhangyis.minidb.server.protocol.packets;

import cn.zhangyis.minidb.server.protocol.MysqlBufUtil;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 文本协议结果集行包。
 *
 * <p>每个列值编码为 length-encoded string（字符串表示），
 * NULL 值用单字节 0xFB 表示。所有类型都先转为字符串再发送，
 * 客户端根据 ColumnDefinition 的类型信息自行转换。</p>
 */
public class ResultSetRowPacket {

    private final List<String> values; // null 元素表示 SQL NULL

    public ResultSetRowPacket(List<String> values) {
        this.values = values;
    }

    /**
     * 将行写入 ByteBuf。
     */
    public void writeTo(ByteBuf buf) {
        for (String value : values) {
            if (value == null) {
                buf.writeByte(MysqlConstants.NULL_COLUMN_TEXT);
            } else {
                MysqlBufUtil.writeLengthEncodedString(buf, value, StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * 从 ByteBuf 解码文本行（JDBC 驱动客户端使用）。
     *
     * @param buf         行 payload
     * @param columnCount 列数
     */
    public static ResultSetRowPacket decode(ByteBuf buf, int columnCount) {
        List<String> vals = new ArrayList<>(columnCount);
        for (int i = 0; i < columnCount; i++) {
            int firstByte = buf.getByte(buf.readerIndex()) & 0xFF;
            if (firstByte == MysqlConstants.NULL_COLUMN_TEXT) {
                buf.skipBytes(1);
                vals.add(null);
            } else {
                vals.add(MysqlBufUtil.readLengthEncodedString(buf, StandardCharsets.UTF_8));
            }
        }
        return new ResultSetRowPacket(vals);
    }

    public List<String> values() {
        return values;
    }
}
