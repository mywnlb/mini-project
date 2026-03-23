package cn.zhangyis.minidb.server.protocol;

import io.netty.buffer.ByteBuf;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * MySQL 线格式（wire format）读写工具类。
 *
 * <p>MySQL 协议中整数一律小端编码（little-endian），字符串有三种编码方式：
 * NULL 结尾、长度编码前缀、定长。本类封装这些底层细节，上层包类只需
 * 调用语义化方法即可。</p>
 *
 * <p>设计模式：工具类（Utility Class）——纯静态方法，无状态，线程安全。</p>
 */
public final class MysqlBufUtil {

    private MysqlBufUtil() {}

    public static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    // ==================== 定长整数（Fixed-Length Integer） ====================

    /**
     * 读取小端定长整数。
     *
     * @param buf    源缓冲区
     * @param length 字节数（1/2/3/4/6/8）
     * @return 读取的整数值
     */
    public static long readFixedLengthInt(ByteBuf buf, int length) {
        long value = 0;
        for (int i = 0; i < length; i++) {
            value |= ((long) (buf.readByte() & 0xFF)) << (i * 8);
        }
        return value;
    }

    /**
     * 写入小端定长整数。
     *
     * @param buf    目标缓冲区
     * @param value  整数值
     * @param length 字节数（1/2/3/4/6/8）
     */
    public static void writeFixedLengthInt(ByteBuf buf, long value, int length) {
        for (int i = 0; i < length; i++) {
            buf.writeByte((int) ((value >> (i * 8)) & 0xFF));
        }
    }

    // ==================== 变长整数（Length-Encoded Integer） ====================

    /**
     * 读取 MySQL 变长编码整数。
     *
     * <p>编码规则（首字节决定后续长度）：
     * <ul>
     *   <li>{@code < 0xFB}：1字节，值即为该字节</li>
     *   <li>{@code 0xFC}：后跟2字节小端</li>
     *   <li>{@code 0xFD}：后跟3字节小端</li>
     *   <li>{@code 0xFE}：后跟8字节小端</li>
     * </ul></p>
     */
    public static long readLengthEncodedInt(ByteBuf buf) {
        int firstByte = buf.readByte() & 0xFF;
        if (firstByte < 0xFB) {
            return firstByte;
        } else if (firstByte == 0xFC) {
            return readFixedLengthInt(buf, 2);
        } else if (firstByte == 0xFD) {
            return readFixedLengthInt(buf, 3);
        } else {
            // 0xFE
            return readFixedLengthInt(buf, 8);
        }
    }

    /**
     * 写入 MySQL 变长编码整数。
     *
     * <p>根据值大小自动选择最短编码：1/3/4/9字节。</p>
     */
    public static void writeLengthEncodedInt(ByteBuf buf, long value) {
        if (value < 0xFB) {
            buf.writeByte((int) value);
        } else if (value < (1 << 16)) {
            buf.writeByte(0xFC);
            writeFixedLengthInt(buf, value, 2);
        } else if (value < (1 << 24)) {
            buf.writeByte(0xFD);
            writeFixedLengthInt(buf, value, 3);
        } else {
            buf.writeByte(0xFE);
            writeFixedLengthInt(buf, value, 8);
        }
    }

    // ==================== 字符串编码 ====================

    /**
     * 读取 NULL 结尾字符串（NUL-Terminated String）。
     * 扫描至 0x00 字节为止，不含终止符。
     */
    public static String readNullTerminatedString(ByteBuf buf, Charset charset) {
        int start = buf.readerIndex();
        while (buf.readableBytes() > 0 && buf.getByte(buf.readerIndex()) != 0x00) {
            buf.skipBytes(1);
        }
        int length = buf.readerIndex() - start;
        byte[] bytes = new byte[length];
        buf.readerIndex(start);
        buf.readBytes(bytes);
        if (buf.readableBytes() > 0) {
            buf.skipBytes(1); // 跳过 0x00 终止符
        }
        return new String(bytes, charset);
    }

    /**
     * 写入 NULL 结尾字符串。
     */
    public static void writeNullTerminatedString(ByteBuf buf, String value, Charset charset) {
        buf.writeBytes(value.getBytes(charset));
        buf.writeByte(0x00);
    }

    /**
     * 读取长度编码字符串（Length-Encoded String）。
     * 前缀为变长整数表示的长度，后跟该长度的字节。
     */
    public static String readLengthEncodedString(ByteBuf buf, Charset charset) {
        int length = (int) readLengthEncodedInt(buf);
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, charset);
    }

    /**
     * 写入长度编码字符串。
     */
    public static void writeLengthEncodedString(ByteBuf buf, String value, Charset charset) {
        byte[] bytes = value.getBytes(charset);
        writeLengthEncodedInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    /**
     * 读取定长字符串。
     */
    public static String readFixedLengthString(ByteBuf buf, int length, Charset charset) {
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, charset);
    }

    /**
     * 读取包剩余部分作为字符串（EOF String / RestOfPacket String）。
     */
    public static String readRestOfPacketString(ByteBuf buf, Charset charset) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return new String(bytes, charset);
    }

    /**
     * 写入定长字节数组。若 value 不足 length 则补 0x00。
     */
    public static void writeFixedLengthBytes(ByteBuf buf, byte[] value, int length) {
        if (value.length >= length) {
            buf.writeBytes(value, 0, length);
        } else {
            buf.writeBytes(value);
            buf.writeZero(length - value.length);
        }
    }

    /**
     * 读取指定长度的字节数组。
     */
    public static byte[] readBytes(ByteBuf buf, int length) {
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return bytes;
    }

    /**
     * 写入长度编码的字节数组。
     */
    public static void writeLengthEncodedBytes(ByteBuf buf, byte[] value) {
        writeLengthEncodedInt(buf, value.length);
        buf.writeBytes(value);
    }
}
