package cn.zhangyis.minidb.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * JDBC 驱动端 MySQL 包 I/O 编解码器。
 *
 * <p>基于标准 {@link java.net.Socket} 的阻塞 I/O，不依赖 Netty。
 * 编码逻辑与服务端 {@code MysqlBufUtil} 对等，但基于 {@code byte[]}
 * 和 {@code ByteBuffer} 而非 Netty ByteBuf。</p>
 *
 * <p>设计决策：JDBC 驱动应尽量轻量，使用标准 JDK API，
 * 避免引入 Netty 依赖。每个连接持有一个 MysqlClientCodec 实例。</p>
 */
public class MysqlClientCodec {

    public static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    private final InputStream in;
    private final OutputStream out;

    public MysqlClientCodec(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    // ==================== 包级 I/O ====================

    /**
     * 读取一个完整的 MySQL 包。
     *
     * @return [sequenceId, payload] 封装
     */
    public RawPacket readPacket() throws IOException {
        // 读 4 字节头
        byte[] header = readFully(4);
        int payloadLength = (header[0] & 0xFF)
                | ((header[1] & 0xFF) << 8)
                | ((header[2] & 0xFF) << 16);
        int sequenceId = header[3] & 0xFF;

        // 读 payload
        byte[] payload = readFully(payloadLength);
        return new RawPacket(sequenceId, payload);
    }

    /**
     * 写入一个完整的 MySQL 包。
     *
     * @param sequenceId 包序号
     * @param payload    包 payload
     */
    public void writePacket(int sequenceId, byte[] payload) throws IOException {
        int length = payload.length;
        byte[] header = new byte[4];
        header[0] = (byte) (length & 0xFF);
        header[1] = (byte) ((length >> 8) & 0xFF);
        header[2] = (byte) ((length >> 16) & 0xFF);
        header[3] = (byte) (sequenceId & 0xFF);
        out.write(header);
        out.write(payload);
        out.flush();
    }

    // ==================== 底层读取 ====================

    private byte[] readFully(int length) throws IOException {
        byte[] buf = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buf, offset, length - offset);
            if (read == -1) {
                throw new IOException("连接已关闭（EOF），期望读取 " + length + " 字节");
            }
            offset += read;
        }
        return buf;
    }

    // ==================== 整数编解码（小端） ====================

    /** 从 byte[] 的指定偏移读取小端定长整数 */
    public static long readFixedLengthInt(byte[] data, int offset, int length) {
        long value = 0;
        for (int i = 0; i < length; i++) {
            value |= ((long) (data[offset + i] & 0xFF)) << (i * 8);
        }
        return value;
    }

    /** 将小端定长整数写入 byte[]，返回写入后的偏移 */
    public static int writeFixedLengthInt(byte[] buf, int offset, long value, int length) {
        for (int i = 0; i < length; i++) {
            buf[offset + i] = (byte) ((value >> (i * 8)) & 0xFF);
        }
        return offset + length;
    }

    // ==================== 变长整数编解码 ====================

    /** 读取变长编码整数，返回 [value, bytesConsumed] */
    public static long[] readLengthEncodedInt(byte[] data, int offset) {
        int firstByte = data[offset] & 0xFF;
        if (firstByte < 0xFB) {
            return new long[]{firstByte, 1};
        } else if (firstByte == 0xFC) {
            return new long[]{readFixedLengthInt(data, offset + 1, 2), 3};
        } else if (firstByte == 0xFD) {
            return new long[]{readFixedLengthInt(data, offset + 1, 3), 4};
        } else {
            return new long[]{readFixedLengthInt(data, offset + 1, 8), 9};
        }
    }

    /** 读取变长编码字符串 */
    public static String readLengthEncodedString(byte[] data, int offset, Charset charset) {
        long[] lenAndConsumed = readLengthEncodedInt(data, offset);
        int strLen = (int) lenAndConsumed[0];
        int headerLen = (int) lenAndConsumed[1];
        return new String(data, offset + headerLen, strLen, charset);
    }

    /** 计算变长编码字符串的总字节数（长度前缀 + 字符串内容） */
    public static int lengthEncodedStringSize(byte[] data, int offset) {
        long[] lenAndConsumed = readLengthEncodedInt(data, offset);
        return (int) lenAndConsumed[1] + (int) lenAndConsumed[0];
    }

    // ==================== NULL 结尾字符串 ====================

    /** 读取 NULL 结尾字符串，返回 [string, bytesConsumed] */
    public static Object[] readNullTerminatedString(byte[] data, int offset, Charset charset) {
        int end = offset;
        while (end < data.length && data[end] != 0x00) {
            end++;
        }
        String str = new String(data, offset, end - offset, charset);
        return new Object[]{str, end - offset + 1}; // +1 for NUL byte
    }

    // ==================== 包类型 ====================

    /** 原始 MySQL 包 */
    public record RawPacket(int sequenceId, byte[] payload) {}
}
