package cn.zhangyis.minidb.server.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 测试：MysqlBufUtil 线格式读写工具。
 * 验证整数/字符串编解码 round-trip 和边界值。
 */
class MysqlBufUtilTest {

    // ==================== 定长整数 round-trip ====================

    @Test
    void fixedLengthInt_1byte_roundTrip() {
        ByteBuf buf = Unpooled.buffer();
        MysqlBufUtil.writeFixedLengthInt(buf, 0xAB, 1);
        assertEquals(0xAB, MysqlBufUtil.readFixedLengthInt(buf, 1));
        buf.release();
    }

    @Test
    void fixedLengthInt_2bytes_roundTrip() {
        ByteBuf buf = Unpooled.buffer();
        MysqlBufUtil.writeFixedLengthInt(buf, 0xBEEF, 2);
        assertEquals(0xBEEF, MysqlBufUtil.readFixedLengthInt(buf, 2));
        buf.release();
    }

    @Test
    void fixedLengthInt_3bytes_roundTrip() {
        ByteBuf buf = Unpooled.buffer();
        MysqlBufUtil.writeFixedLengthInt(buf, 0xABCDEF, 3);
        assertEquals(0xABCDEF, MysqlBufUtil.readFixedLengthInt(buf, 3));
        buf.release();
    }

    @Test
    void fixedLengthInt_4bytes_roundTrip() {
        ByteBuf buf = Unpooled.buffer();
        long val = 0xDEADBEEFL;
        MysqlBufUtil.writeFixedLengthInt(buf, val, 4);
        assertEquals(val, MysqlBufUtil.readFixedLengthInt(buf, 4));
        buf.release();
    }

    @Test
    void fixedLengthInt_8bytes_roundTrip() {
        ByteBuf buf = Unpooled.buffer();
        long val = 0x123456789ABCDEF0L;
        MysqlBufUtil.writeFixedLengthInt(buf, val, 8);
        assertEquals(val, MysqlBufUtil.readFixedLengthInt(buf, 8));
        buf.release();
    }

    @Test
    void fixedLengthInt_zero() {
        ByteBuf buf = Unpooled.buffer();
        MysqlBufUtil.writeFixedLengthInt(buf, 0, 4);
        assertEquals(0, MysqlBufUtil.readFixedLengthInt(buf, 4));
        buf.release();
    }

    @Test
    void fixedLengthInt_maxValues() {
        ByteBuf buf = Unpooled.buffer();
        // 1 byte max
        MysqlBufUtil.writeFixedLengthInt(buf, 0xFF, 1);
        assertEquals(0xFF, MysqlBufUtil.readFixedLengthInt(buf, 1));
        // 2 byte max
        MysqlBufUtil.writeFixedLengthInt(buf, 0xFFFF, 2);
        assertEquals(0xFFFF, MysqlBufUtil.readFixedLengthInt(buf, 2));
        buf.release();
    }

    // ==================== 变长整数 round-trip ====================

    @Test
    void lengthEncodedInt_1byte_range() {
        ByteBuf buf = Unpooled.buffer();
        // 值 < 0xFB 用 1 字节编码
        MysqlBufUtil.writeLengthEncodedInt(buf, 0);
        MysqlBufUtil.writeLengthEncodedInt(buf, 100);
        MysqlBufUtil.writeLengthEncodedInt(buf, 250); // 0xFA

        assertEquals(0, MysqlBufUtil.readLengthEncodedInt(buf));
        assertEquals(100, MysqlBufUtil.readLengthEncodedInt(buf));
        assertEquals(250, MysqlBufUtil.readLengthEncodedInt(buf));
        buf.release();
    }

    @Test
    void lengthEncodedInt_2byte_range() {
        ByteBuf buf = Unpooled.buffer();
        // 0xFB <= 值 < 2^16 用 3 字节编码 (0xFC + 2字节)
        MysqlBufUtil.writeLengthEncodedInt(buf, 251);
        MysqlBufUtil.writeLengthEncodedInt(buf, 65535);

        assertEquals(251, MysqlBufUtil.readLengthEncodedInt(buf));
        assertEquals(65535, MysqlBufUtil.readLengthEncodedInt(buf));
        buf.release();
    }

    @Test
    void lengthEncodedInt_3byte_range() {
        ByteBuf buf = Unpooled.buffer();
        // 2^16 <= 值 < 2^24 用 4 字节编码 (0xFD + 3字节)
        MysqlBufUtil.writeLengthEncodedInt(buf, 65536);
        MysqlBufUtil.writeLengthEncodedInt(buf, (1 << 24) - 1);

        assertEquals(65536, MysqlBufUtil.readLengthEncodedInt(buf));
        assertEquals((1 << 24) - 1, MysqlBufUtil.readLengthEncodedInt(buf));
        buf.release();
    }

    @Test
    void lengthEncodedInt_8byte_range() {
        ByteBuf buf = Unpooled.buffer();
        // 值 >= 2^24 用 9 字节编码 (0xFE + 8字节)
        long bigVal = (1L << 24);
        MysqlBufUtil.writeLengthEncodedInt(buf, bigVal);
        assertEquals(bigVal, MysqlBufUtil.readLengthEncodedInt(buf));
        buf.release();
    }

    // ==================== NULL 结尾字符串 ====================

    @Test
    void nullTerminatedString_roundTrip() {
        ByteBuf buf = Unpooled.buffer();
        MysqlBufUtil.writeNullTerminatedString(buf, "hello", StandardCharsets.UTF_8);
        assertEquals("hello", MysqlBufUtil.readNullTerminatedString(buf, StandardCharsets.UTF_8));
        buf.release();
    }

    @Test
    void nullTerminatedString_empty() {
        ByteBuf buf = Unpooled.buffer();
        MysqlBufUtil.writeNullTerminatedString(buf, "", StandardCharsets.UTF_8);
        assertEquals("", MysqlBufUtil.readNullTerminatedString(buf, StandardCharsets.UTF_8));
        buf.release();
    }

    @Test
    void nullTerminatedString_chinese() {
        ByteBuf buf = Unpooled.buffer();
        MysqlBufUtil.writeNullTerminatedString(buf, "你好世界", StandardCharsets.UTF_8);
        assertEquals("你好世界", MysqlBufUtil.readNullTerminatedString(buf, StandardCharsets.UTF_8));
        buf.release();
    }

    // ==================== 长度编码字符串 ====================

    @Test
    void lengthEncodedString_roundTrip() {
        ByteBuf buf = Unpooled.buffer();
        MysqlBufUtil.writeLengthEncodedString(buf, "test_string", StandardCharsets.UTF_8);
        assertEquals("test_string", MysqlBufUtil.readLengthEncodedString(buf, StandardCharsets.UTF_8));
        buf.release();
    }

    @Test
    void lengthEncodedString_empty() {
        ByteBuf buf = Unpooled.buffer();
        MysqlBufUtil.writeLengthEncodedString(buf, "", StandardCharsets.UTF_8);
        assertEquals("", MysqlBufUtil.readLengthEncodedString(buf, StandardCharsets.UTF_8));
        buf.release();
    }

    @Test
    void lengthEncodedString_longString() {
        ByteBuf buf = Unpooled.buffer();
        String longStr = "A".repeat(300); // 超过 250，触发 2 字节长度编码
        MysqlBufUtil.writeLengthEncodedString(buf, longStr, StandardCharsets.UTF_8);
        assertEquals(longStr, MysqlBufUtil.readLengthEncodedString(buf, StandardCharsets.UTF_8));
        buf.release();
    }

    // ==================== 定长字符串 ====================

    @Test
    void fixedLengthString_roundTrip() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeBytes("ABCDE".getBytes(StandardCharsets.UTF_8));
        assertEquals("ABCDE", MysqlBufUtil.readFixedLengthString(buf, 5, StandardCharsets.UTF_8));
        buf.release();
    }

    // ==================== RestOfPacket 字符串 ====================

    @Test
    void restOfPacketString_roundTrip() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeBytes("SELECT 1".getBytes(StandardCharsets.UTF_8));
        assertEquals("SELECT 1", MysqlBufUtil.readRestOfPacketString(buf, StandardCharsets.UTF_8));
        assertEquals(0, buf.readableBytes());
        buf.release();
    }

    // ==================== 定长字节数组 ====================

    @Test
    void fixedLengthBytes_exactLength() {
        ByteBuf buf = Unpooled.buffer();
        byte[] data = {1, 2, 3, 4, 5};
        MysqlBufUtil.writeFixedLengthBytes(buf, data, 5);
        byte[] read = MysqlBufUtil.readBytes(buf, 5);
        assertArrayEquals(data, read);
        buf.release();
    }

    @Test
    void fixedLengthBytes_padWithZeros() {
        ByteBuf buf = Unpooled.buffer();
        byte[] data = {1, 2, 3};
        MysqlBufUtil.writeFixedLengthBytes(buf, data, 5);
        byte[] read = MysqlBufUtil.readBytes(buf, 5);
        assertArrayEquals(new byte[]{1, 2, 3, 0, 0}, read);
        buf.release();
    }

    // ==================== 长度编码字节数组 ====================

    @Test
    void lengthEncodedBytes_roundTrip() {
        ByteBuf buf = Unpooled.buffer();
        byte[] data = {0x01, 0x02, 0x03, 0x04};
        MysqlBufUtil.writeLengthEncodedBytes(buf, data);
        // 读回：先读长度，再读字节
        int len = (int) MysqlBufUtil.readLengthEncodedInt(buf);
        assertEquals(4, len);
        byte[] read = MysqlBufUtil.readBytes(buf, len);
        assertArrayEquals(data, read);
        buf.release();
    }

    // ==================== 小端字节序验证 ====================

    @Test
    void littleEndian_byteOrder() {
        ByteBuf buf = Unpooled.buffer();
        // 写入 0x01020304，小端应为 04 03 02 01
        MysqlBufUtil.writeFixedLengthInt(buf, 0x01020304L, 4);
        assertEquals(0x04, buf.getByte(0) & 0xFF);
        assertEquals(0x03, buf.getByte(1) & 0xFF);
        assertEquals(0x02, buf.getByte(2) & 0xFF);
        assertEquals(0x01, buf.getByte(3) & 0xFF);
        buf.release();
    }
}
