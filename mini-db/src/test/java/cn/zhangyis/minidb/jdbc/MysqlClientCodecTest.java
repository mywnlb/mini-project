package cn.zhangyis.minidb.jdbc;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 测试：JDBC 客户端编解码器。
 * 验证包读写 round-trip、整数/字符串编解码。
 */
class MysqlClientCodecTest {

    // ==================== 包 I/O round-trip ====================

    @Test
    void writeAndReadPacket_roundTrip() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        MysqlClientCodec writer = new MysqlClientCodec(null, baos);

        byte[] payload = "Hello MySQL".getBytes(StandardCharsets.UTF_8);
        writer.writePacket(0, payload);

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        MysqlClientCodec reader = new MysqlClientCodec(bais, null);

        MysqlClientCodec.RawPacket pkt = reader.readPacket();
        assertEquals(0, pkt.sequenceId());
        assertArrayEquals(payload, pkt.payload());
    }

    @Test
    void writeAndReadPacket_sequenceId() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        MysqlClientCodec writer = new MysqlClientCodec(null, baos);

        writer.writePacket(42, new byte[]{1, 2, 3});

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        MysqlClientCodec reader = new MysqlClientCodec(bais, null);

        MysqlClientCodec.RawPacket pkt = reader.readPacket();
        assertEquals(42, pkt.sequenceId());
        assertArrayEquals(new byte[]{1, 2, 3}, pkt.payload());
    }

    @Test
    void writeAndReadPacket_emptyPayload() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        MysqlClientCodec writer = new MysqlClientCodec(null, baos);

        writer.writePacket(0, new byte[0]);

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        MysqlClientCodec reader = new MysqlClientCodec(bais, null);

        MysqlClientCodec.RawPacket pkt = reader.readPacket();
        assertEquals(0, pkt.payload().length);
    }

    @Test
    void writeAndReadPacket_multiplePackets() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        MysqlClientCodec writer = new MysqlClientCodec(null, baos);

        writer.writePacket(0, new byte[]{0x01});
        writer.writePacket(1, new byte[]{0x02, 0x03});
        writer.writePacket(2, new byte[]{0x04, 0x05, 0x06});

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        MysqlClientCodec reader = new MysqlClientCodec(bais, null);

        MysqlClientCodec.RawPacket p1 = reader.readPacket();
        assertEquals(0, p1.sequenceId());
        assertArrayEquals(new byte[]{0x01}, p1.payload());

        MysqlClientCodec.RawPacket p2 = reader.readPacket();
        assertEquals(1, p2.sequenceId());
        assertArrayEquals(new byte[]{0x02, 0x03}, p2.payload());

        MysqlClientCodec.RawPacket p3 = reader.readPacket();
        assertEquals(2, p3.sequenceId());
        assertArrayEquals(new byte[]{0x04, 0x05, 0x06}, p3.payload());
    }

    @Test
    void readPacket_eof_throwsIOException() {
        ByteArrayInputStream bais = new ByteArrayInputStream(new byte[0]);
        MysqlClientCodec reader = new MysqlClientCodec(bais, null);
        assertThrows(IOException.class, reader::readPacket);
    }

    // ==================== 定长整数 ====================

    @Test
    void readFixedLengthInt_1byte() {
        byte[] data = {(byte) 0xAB};
        assertEquals(0xAB, MysqlClientCodec.readFixedLengthInt(data, 0, 1));
    }

    @Test
    void readFixedLengthInt_2bytes_littleEndian() {
        byte[] data = {(byte) 0xEF, (byte) 0xBE}; // 0xBEEF LE
        assertEquals(0xBEEF, MysqlClientCodec.readFixedLengthInt(data, 0, 2));
    }

    @Test
    void readFixedLengthInt_4bytes() {
        byte[] data = {0x04, 0x03, 0x02, 0x01}; // 0x01020304 LE
        assertEquals(0x01020304L, MysqlClientCodec.readFixedLengthInt(data, 0, 4));
    }

    @Test
    void writeFixedLengthInt_roundTrip() {
        byte[] buf = new byte[4];
        MysqlClientCodec.writeFixedLengthInt(buf, 0, 0xDEADBEEFL, 4);
        assertEquals(0xDEADBEEFL, MysqlClientCodec.readFixedLengthInt(buf, 0, 4));
    }

    // ==================== 变长整数 ====================

    @Test
    void readLengthEncodedInt_1byte() {
        byte[] data = {100};
        long[] result = MysqlClientCodec.readLengthEncodedInt(data, 0);
        assertEquals(100, result[0]);
        assertEquals(1, result[1]); // consumed 1 byte
    }

    @Test
    void readLengthEncodedInt_2byte() {
        // 0xFC + 2 bytes LE
        byte[] data = {(byte) 0xFC, (byte) 0x00, (byte) 0x01}; // 256
        long[] result = MysqlClientCodec.readLengthEncodedInt(data, 0);
        assertEquals(256, result[0]);
        assertEquals(3, result[1]); // consumed 3 bytes
    }

    @Test
    void readLengthEncodedInt_3byte() {
        // 0xFD + 3 bytes LE
        byte[] data = {(byte) 0xFD, 0x00, 0x00, 0x01}; // 65536
        long[] result = MysqlClientCodec.readLengthEncodedInt(data, 0);
        assertEquals(65536, result[0]);
        assertEquals(4, result[1]);
    }

    // ==================== 变长字符串 ====================

    @Test
    void readLengthEncodedString_basic() {
        byte[] data = {5, 'h', 'e', 'l', 'l', 'o'};
        String str = MysqlClientCodec.readLengthEncodedString(data, 0, StandardCharsets.UTF_8);
        assertEquals("hello", str);
    }

    @Test
    void lengthEncodedStringSize_basic() {
        byte[] data = {5, 'h', 'e', 'l', 'l', 'o'};
        assertEquals(6, MysqlClientCodec.lengthEncodedStringSize(data, 0)); // 1 (len) + 5 (data)
    }

    // ==================== NULL 结尾字符串 ====================

    @Test
    void readNullTerminatedString_basic() {
        byte[] data = {'r', 'o', 'o', 't', 0x00, 'x'};
        Object[] result = MysqlClientCodec.readNullTerminatedString(data, 0, StandardCharsets.UTF_8);
        assertEquals("root", result[0]);
        assertEquals(5, result[1]); // 4 chars + 1 NUL
    }

    @Test
    void readNullTerminatedString_empty() {
        byte[] data = {0x00, 'x'};
        Object[] result = MysqlClientCodec.readNullTerminatedString(data, 0, StandardCharsets.UTF_8);
        assertEquals("", result[0]);
        assertEquals(1, result[1]);
    }

    // ==================== 包帧格式验证 ====================

    @Test
    void packetFrame_headerFormat() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        MysqlClientCodec writer = new MysqlClientCodec(null, baos);

        byte[] payload = new byte[]{0x01, 0x02, 0x03}; // 3 bytes
        writer.writePacket(5, payload);

        byte[] frame = baos.toByteArray();
        // header: [3字节长度LE][1字节seqId]
        assertEquals(3, frame[0] & 0xFF);  // length byte 0
        assertEquals(0, frame[1] & 0xFF);  // length byte 1
        assertEquals(0, frame[2] & 0xFF);  // length byte 2
        assertEquals(5, frame[3] & 0xFF);  // sequence id
        // payload
        assertEquals(0x01, frame[4] & 0xFF);
        assertEquals(0x02, frame[5] & 0xFF);
        assertEquals(0x03, frame[6] & 0xFF);
    }
}
