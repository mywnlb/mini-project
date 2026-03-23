package cn.zhangyis.minidb.server.protocol.packets;

import cn.zhangyis.minidb.server.protocol.MysqlBufUtil;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import io.netty.buffer.ByteBuf;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * MySQL 二进制协议结果集行包。
 *
 * <p>与文本行不同，二进制行使用类型化编码：
 * <ul>
 *   <li>包头固定为 0x00</li>
 *   <li>NULL bitmap（offset=2）标记哪些列为 NULL</li>
 *   <li>非 NULL 列按各自类型二进制编码</li>
 * </ul></p>
 *
 * <p>NULL bitmap offset=2 是 MySQL 协议的历史设计：
 * 前 2 bit 被保留（分别对应 OK header 和一个 reserved bit），
 * 第 i 列的 NULL 标记位于 bitmap[(i+2)/8] 的第 (i+2)%8 bit。</p>
 */
public class BinaryResultSetRowPacket {

    private final List<Object> values;
    private final List<Integer> columnTypes; // 每列的 MySQL type code

    public BinaryResultSetRowPacket(List<Object> values, List<Integer> columnTypes) {
        this.values = values;
        this.columnTypes = columnTypes;
    }

    /**
     * 将二进制行写入 ByteBuf。
     */
    public void writeTo(ByteBuf buf) {
        int columnCount = values.size();
        // 包头
        buf.writeByte(0x00);

        // NULL bitmap（offset=2）
        int bitmapLength = (columnCount + 7 + 2) / 8;
        byte[] nullBitmap = new byte[bitmapLength];
        for (int i = 0; i < columnCount; i++) {
            if (values.get(i) == null) {
                int bytePos = (i + 2) / 8;
                int bitPos = (i + 2) % 8;
                nullBitmap[bytePos] |= (1 << bitPos);
            }
        }
        buf.writeBytes(nullBitmap);

        // 非 NULL 列的二进制值
        for (int i = 0; i < columnCount; i++) {
            Object value = values.get(i);
            if (value == null) {
                continue;
            }
            int mysqlType = columnTypes.get(i);
            writeBinaryValue(buf, value, mysqlType);
        }
    }

    /**
     * 按 MySQL 类型将 Java 值编码为二进制格式。
     */
    private void writeBinaryValue(ByteBuf buf, Object value, int mysqlType) {
        switch (mysqlType) {
            case MysqlConstants.MYSQL_TYPE_LONG -> {
                int intVal = (value instanceof Number n) ? n.intValue() : Integer.parseInt(value.toString());
                MysqlBufUtil.writeFixedLengthInt(buf, intVal, 4);
            }
            case MysqlConstants.MYSQL_TYPE_LONGLONG -> {
                long longVal = (value instanceof Number n) ? n.longValue() : Long.parseLong(value.toString());
                MysqlBufUtil.writeFixedLengthInt(buf, longVal, 8);
            }
            case MysqlConstants.MYSQL_TYPE_DOUBLE, MysqlConstants.MYSQL_TYPE_FLOAT -> {
                double dVal = (value instanceof Number n) ? n.doubleValue() : Double.parseDouble(value.toString());
                MysqlBufUtil.writeFixedLengthInt(buf, Double.doubleToLongBits(dVal), 8);
            }
            case MysqlConstants.MYSQL_TYPE_DATETIME, MysqlConstants.MYSQL_TYPE_TIMESTAMP -> {
                writeDatetime(buf, value);
            }
            default -> {
                // VARCHAR / DECIMAL / 其他：统一用 length-encoded string
                String strVal = value.toString();
                MysqlBufUtil.writeLengthEncodedString(buf, strVal, StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * 写入 DATETIME 二进制格式。
     *
     * <p>格式：1字节长度 + year(2) + month(1) + day(1) + hour(1) + min(1) + sec(1)</p>
     */
    private void writeDatetime(ByteBuf buf, Object value) {
        if (value instanceof LocalDateTime dt) {
            buf.writeByte(7); // 长度：year(2)+month+day+hour+min+sec = 7
            MysqlBufUtil.writeFixedLengthInt(buf, dt.getYear(), 2);
            buf.writeByte(dt.getMonthValue());
            buf.writeByte(dt.getDayOfMonth());
            buf.writeByte(dt.getHour());
            buf.writeByte(dt.getMinute());
            buf.writeByte(dt.getSecond());
        } else {
            // 非 LocalDateTime：转字符串传输
            String strVal = value.toString();
            MysqlBufUtil.writeLengthEncodedString(buf, strVal, StandardCharsets.UTF_8);
        }
    }

    public List<Object> values() { return values; }
    public List<Integer> columnTypes() { return columnTypes; }
}
