package cn.zhangyis.minidb.server.protocol;

import cn.zhangyis.minidb.server.protocol.packets.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 测试：各包类型编码后解码验证（round-trip）。
 */
class PacketCodecTest {

    // ==================== HandshakePacket ====================

    @Test
    void handshakePacket_writeTo_containsProtocolVersion10() {
        byte[] challenge = new byte[20];
        Arrays.fill(challenge, (byte) 0xAA);
        HandshakePacket pkt = new HandshakePacket(1, challenge, MysqlConstants.SERVER_DEFAULT_CAPABILITIES);

        ByteBuf buf = Unpooled.buffer();
        pkt.writeTo(buf);

        // 第一个字节是协议版本 10
        assertEquals(MysqlConstants.PROTOCOL_VERSION, buf.readByte() & 0xFF);
        // 服务器版本字符串（NUL 结尾）
        String version = MysqlBufUtil.readNullTerminatedString(buf, StandardCharsets.UTF_8);
        assertEquals(MysqlConstants.SERVER_VERSION, version);
        // connection id (4 字节小端)
        assertEquals(1, MysqlBufUtil.readFixedLengthInt(buf, 4));

        buf.release();
    }

    @Test
    void handshakePacket_authPluginData_20bytes() {
        byte[] challenge = new byte[20];
        for (int i = 0; i < 20; i++) challenge[i] = (byte) i;
        HandshakePacket pkt = new HandshakePacket(42, challenge, MysqlConstants.SERVER_DEFAULT_CAPABILITIES);

        ByteBuf buf = Unpooled.buffer();
        pkt.writeTo(buf);

        // 跳过 protocol_version(1) + server_version(NUL) + connection_id(4)
        buf.skipBytes(1);
        MysqlBufUtil.readNullTerminatedString(buf, StandardCharsets.UTF_8);
        buf.skipBytes(4);

        // auth-plugin-data-part-1: 前 8 字节
        byte[] part1 = MysqlBufUtil.readBytes(buf, 8);
        for (int i = 0; i < 8; i++) assertEquals((byte) i, part1[i]);

        // filler(1) + cap_lower(2) + charset(1) + status(2) + cap_upper(2) + auth_data_len(1) + reserved(10)
        buf.skipBytes(1 + 2 + 1 + 2 + 2 + 1 + 10);

        // auth-plugin-data-part-2: 后 12 字节
        byte[] part2 = MysqlBufUtil.readBytes(buf, 12);
        for (int i = 0; i < 12; i++) assertEquals((byte) (i + 8), part2[i]);

        buf.release();
    }

    @Test
    void handshakePacket_rejectsNon20ByteChallenge() {
        assertThrows(IllegalArgumentException.class,
                () -> new HandshakePacket(1, new byte[10], MysqlConstants.SERVER_DEFAULT_CAPABILITIES));
    }

    // ==================== HandshakeResponsePacket ====================

    @Test
    void handshakeResponsePacket_decode_basicFields() {
        ByteBuf buf = Unpooled.buffer();
        int clientCap = MysqlConstants.CLIENT_PROTOCOL_41
                | MysqlConstants.CLIENT_SECURE_CONNECTION
                | MysqlConstants.CLIENT_PLUGIN_AUTH
                | MysqlConstants.CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA
                | MysqlConstants.CLIENT_CONNECT_WITH_DB;

        // capability_flags (4)
        MysqlBufUtil.writeFixedLengthInt(buf, clientCap, 4);
        // max_packet_size (4)
        MysqlBufUtil.writeFixedLengthInt(buf, 16 * 1024 * 1024, 4);
        // character_set (1)
        buf.writeByte(MysqlConstants.CHARSET_UTF8MB4);
        // reserved (23)
        buf.writeZero(23);
        // username (NUL)
        MysqlBufUtil.writeNullTerminatedString(buf, "root", StandardCharsets.UTF_8);
        // auth_response (length-encoded)
        byte[] authResp = new byte[]{1, 2, 3, 4, 5};
        MysqlBufUtil.writeLengthEncodedInt(buf, authResp.length);
        buf.writeBytes(authResp);
        // database (NUL)
        MysqlBufUtil.writeNullTerminatedString(buf, "testdb", StandardCharsets.UTF_8);
        // auth_plugin_name (NUL)
        MysqlBufUtil.writeNullTerminatedString(buf, "mysql_native_password", StandardCharsets.UTF_8);

        HandshakeResponsePacket pkt = HandshakeResponsePacket.decode(buf, MysqlConstants.SERVER_DEFAULT_CAPABILITIES);

        assertEquals("root", pkt.username());
        assertArrayEquals(authResp, pkt.authResponse());
        assertEquals("testdb", pkt.database());
        assertEquals("mysql_native_password", pkt.authPluginName());
        assertEquals(MysqlConstants.CHARSET_UTF8MB4, pkt.charset());

        buf.release();
    }

    // ==================== OkPacket ====================

    @Test
    void okPacket_writeAndDecode_roundTrip() {
        OkPacket original = new OkPacket(10, 5, MysqlConstants.SERVER_STATUS_AUTOCOMMIT, 2, "done");

        ByteBuf buf = Unpooled.buffer();
        original.writeTo(buf);

        // 读取 header 字节
        int header = buf.readByte() & 0xFF;
        assertEquals(MysqlConstants.OK_HEADER, header);

        OkPacket decoded = OkPacket.decode(buf);
        assertEquals(10, decoded.affectedRows());
        assertEquals(5, decoded.lastInsertId());
        assertEquals(MysqlConstants.SERVER_STATUS_AUTOCOMMIT, decoded.statusFlags());
        assertEquals(2, decoded.warnings());
        assertEquals("done", decoded.info());

        buf.release();
    }

    @Test
    void okPacket_dml_factory() {
        OkPacket pkt = OkPacket.dml(100, 42, MysqlConstants.SERVER_STATUS_IN_TRANS);

        ByteBuf buf = Unpooled.buffer();
        pkt.writeTo(buf);
        buf.skipBytes(1); // header

        OkPacket decoded = OkPacket.decode(buf);
        assertEquals(100, decoded.affectedRows());
        assertEquals(42, decoded.lastInsertId());
        assertEquals(MysqlConstants.SERVER_STATUS_IN_TRANS, decoded.statusFlags());

        buf.release();
    }

    @Test
    void okPacket_simple_noInfo() {
        OkPacket pkt = OkPacket.ok(MysqlConstants.SERVER_STATUS_AUTOCOMMIT);

        ByteBuf buf = Unpooled.buffer();
        pkt.writeTo(buf);
        buf.skipBytes(1);

        OkPacket decoded = OkPacket.decode(buf);
        assertEquals(0, decoded.affectedRows());
        assertEquals(0, decoded.lastInsertId());

        buf.release();
    }

    // ==================== ErrPacket ====================

    @Test
    void errPacket_writeAndDecode_roundTrip() {
        ErrPacket original = new ErrPacket(1064, "42000", "Syntax error near 'SELEC'");

        ByteBuf buf = Unpooled.buffer();
        original.writeTo(buf);

        int header = buf.readByte() & 0xFF;
        assertEquals(MysqlConstants.ERR_HEADER, header);

        ErrPacket decoded = ErrPacket.decode(buf);
        assertEquals(1064, decoded.errorCode());
        assertEquals("42000", decoded.sqlState());
        assertEquals("Syntax error near 'SELEC'", decoded.message());

        buf.release();
    }

    @Test
    void errPacket_errorFactory() {
        ErrPacket pkt = ErrPacket.error(1105, "General error");

        assertEquals(1105, pkt.errorCode());
        assertEquals("HY000", pkt.sqlState());
        assertEquals("General error", pkt.message());
    }

    // ==================== EofPacket ====================

    @Test
    void eofPacket_writeTo_correctStructure() {
        EofPacket pkt = new EofPacket(3, MysqlConstants.SERVER_STATUS_AUTOCOMMIT);

        ByteBuf buf = Unpooled.buffer();
        pkt.writeTo(buf);

        assertEquals(MysqlConstants.EOF_HEADER, buf.readByte() & 0xFF);
        assertEquals(3, MysqlBufUtil.readFixedLengthInt(buf, 2)); // warnings
        assertEquals(MysqlConstants.SERVER_STATUS_AUTOCOMMIT, MysqlBufUtil.readFixedLengthInt(buf, 2));

        buf.release();
    }

    @Test
    void eofPacket_isEof_detection() {
        assertTrue(EofPacket.isEof(0xFE, 5));
        assertTrue(EofPacket.isEof(0xFE, 1));
        assertFalse(EofPacket.isEof(0xFE, 6)); // payload > 5 不是 EOF
        assertFalse(EofPacket.isEof(0x00, 5));  // header 不是 0xFE
    }

    // ==================== ColumnDefinitionPacket ====================

    @Test
    void columnDefinitionPacket_writeAndDecode_roundTrip() {
        ColumnDefinitionPacket original = new ColumnDefinitionPacket.Builder()
                .catalog("def")
                .schema("testdb")
                .table("users")
                .orgTable("users")
                .name("id")
                .orgName("id")
                .charset(MysqlConstants.CHARSET_UTF8MB4)
                .columnLength(11)
                .columnType(MysqlConstants.MYSQL_TYPE_LONG)
                .flags(MysqlConstants.COLUMN_FLAG_PRI_KEY | MysqlConstants.COLUMN_FLAG_NOT_NULL)
                .decimals(0)
                .build();

        ByteBuf buf = Unpooled.buffer();
        original.writeTo(buf);

        ColumnDefinitionPacket decoded = ColumnDefinitionPacket.decode(buf);
        assertEquals("id", decoded.name());
        assertEquals("users", decoded.table());
        assertEquals(MysqlConstants.MYSQL_TYPE_LONG, decoded.columnType());
        assertEquals(MysqlConstants.COLUMN_FLAG_PRI_KEY | MysqlConstants.COLUMN_FLAG_NOT_NULL, decoded.flags());
        assertEquals(11, decoded.columnLength());

        buf.release();
    }

    @Test
    void columnDefinitionPacket_varcharColumn() {
        ColumnDefinitionPacket pkt = new ColumnDefinitionPacket.Builder()
                .name("name")
                .orgName("name")
                .table("users")
                .columnType(MysqlConstants.MYSQL_TYPE_VAR_STRING)
                .columnLength(255)
                .build();

        ByteBuf buf = Unpooled.buffer();
        pkt.writeTo(buf);

        ColumnDefinitionPacket decoded = ColumnDefinitionPacket.decode(buf);
        assertEquals("name", decoded.name());
        assertEquals(MysqlConstants.MYSQL_TYPE_VAR_STRING, decoded.columnType());
        assertEquals(255, decoded.columnLength());

        buf.release();
    }

    // ==================== ResultSetRowPacket ====================

    @Test
    void resultSetRowPacket_writeAndDecode_roundTrip() {
        List<String> values = List.of("1", "Alice", "30");
        ResultSetRowPacket original = new ResultSetRowPacket(values);

        ByteBuf buf = Unpooled.buffer();
        original.writeTo(buf);

        ResultSetRowPacket decoded = ResultSetRowPacket.decode(buf, 3);
        assertEquals(List.of("1", "Alice", "30"), decoded.values());

        buf.release();
    }

    @Test
    void resultSetRowPacket_withNullValues() {
        List<String> values = Arrays.asList("1", null, "active");
        ResultSetRowPacket original = new ResultSetRowPacket(values);

        ByteBuf buf = Unpooled.buffer();
        original.writeTo(buf);

        ResultSetRowPacket decoded = ResultSetRowPacket.decode(buf, 3);
        assertEquals("1", decoded.values().get(0));
        assertNull(decoded.values().get(1));
        assertEquals("active", decoded.values().get(2));

        buf.release();
    }

    @Test
    void resultSetRowPacket_allNulls() {
        List<String> values = Arrays.asList(null, null, null);
        ResultSetRowPacket original = new ResultSetRowPacket(values);

        ByteBuf buf = Unpooled.buffer();
        original.writeTo(buf);

        ResultSetRowPacket decoded = ResultSetRowPacket.decode(buf, 3);
        for (String v : decoded.values()) {
            assertNull(v);
        }

        buf.release();
    }

    // ==================== StmtPrepareOkPacket ====================

    @Test
    void stmtPrepareOkPacket_writeAndDecode_roundTrip() {
        StmtPrepareOkPacket original = new StmtPrepareOkPacket(1, 3, 2, 0);

        ByteBuf buf = Unpooled.buffer();
        original.writeTo(buf);

        // 跳过 status 字节 (0x00)
        buf.skipBytes(1);

        StmtPrepareOkPacket decoded = StmtPrepareOkPacket.decode(buf);
        assertEquals(1, decoded.statementId());
        assertEquals(3, decoded.numColumns());
        assertEquals(2, decoded.numParams());
        assertEquals(0, decoded.warningCount());

        buf.release();
    }

    // ==================== ComQueryPacket ====================

    @Test
    void comQueryPacket_decode() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeBytes("SELECT * FROM users".getBytes(StandardCharsets.UTF_8));

        ComQueryPacket pkt = ComQueryPacket.decode(buf);
        assertEquals("SELECT * FROM users", pkt.sql());

        buf.release();
    }

    @Test
    void comQueryPacket_decode_chineseSQL() {
        ByteBuf buf = Unpooled.buffer();
        String sql = "SELECT * FROM 用户表 WHERE 名字 = '张三'";
        buf.writeBytes(sql.getBytes(StandardCharsets.UTF_8));

        ComQueryPacket pkt = ComQueryPacket.decode(buf);
        assertEquals(sql, pkt.sql());

        buf.release();
    }

    // ==================== TypeMapping ====================

    @Test
    void typeMapping_inferMysqlType() {
        assertEquals(MysqlConstants.MYSQL_TYPE_LONG, TypeMapping.inferMysqlType(42));
        assertEquals(MysqlConstants.MYSQL_TYPE_LONGLONG, TypeMapping.inferMysqlType(100L));
        assertEquals(MysqlConstants.MYSQL_TYPE_VAR_STRING, TypeMapping.inferMysqlType("hello"));
        assertEquals(MysqlConstants.MYSQL_TYPE_NULL, TypeMapping.inferMysqlType(null));
        assertEquals(MysqlConstants.MYSQL_TYPE_NEWDECIMAL, TypeMapping.inferMysqlType(new java.math.BigDecimal("3.14")));
        assertEquals(MysqlConstants.MYSQL_TYPE_DATETIME, TypeMapping.inferMysqlType(java.time.LocalDateTime.now()));
    }
}
