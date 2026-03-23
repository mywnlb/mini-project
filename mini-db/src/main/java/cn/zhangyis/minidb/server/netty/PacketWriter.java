package cn.zhangyis.minidb.server.netty;

import cn.zhangyis.minidb.server.protocol.packets.*;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

/**
 * MySQL 包写入门面（Facade）。
 *
 * <p>设计模式：门面模式——将包帧构造（4字节头 + payload）和包内容序列化
 * 两个步骤封装为简单的一步调用。上层 Handler 只需调用
 * {@code writer.writeOk()} 而无需关心帧格式细节。</p>
 *
 * <p>每个 PacketWriter 绑定一个 ChannelHandlerContext，管理
 * 当前交互的 sequence id 自增。新命令开始时调用 {@link #resetSequenceId()}。</p>
 */
public class PacketWriter {

    private final ChannelHandlerContext ctx;
    private int sequenceId;

    public PacketWriter(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        this.sequenceId = 0;
    }

    /** 重置序号（新命令开始时调用） */
    public void resetSequenceId() {
        this.sequenceId = 0;
    }

    /** 设置序号（用于响应包需要匹配请求序号的场景） */
    public void setSequenceId(int sequenceId) {
        this.sequenceId = sequenceId;
    }

    /** 获取当前序号 */
    public int currentSequenceId() {
        return sequenceId;
    }

    // ==================== 包发送方法 ====================

    /** 发送握手包 */
    public void writeHandshake(HandshakePacket packet) {
        writePacket(buf -> packet.writeTo(buf));
    }

    /** 发送 OK 包 */
    public void writeOk(OkPacket packet) {
        writePacket(buf -> packet.writeTo(buf));
    }

    /** 发送 ERR 包 */
    public void writeErr(ErrPacket packet) {
        writePacket(buf -> packet.writeTo(buf));
    }

    /** 发送 EOF 包 */
    public void writeEof(EofPacket packet) {
        writePacket(buf -> packet.writeTo(buf));
    }

    /** 发送列定义包 */
    public void writeColumnDefinition(ColumnDefinitionPacket packet) {
        writePacket(buf -> packet.writeTo(buf));
    }

    /** 发送文本行包 */
    public void writeResultSetRow(ResultSetRowPacket packet) {
        writePacket(buf -> packet.writeTo(buf));
    }

    /** 发送二进制行包 */
    public void writeBinaryResultSetRow(BinaryResultSetRowPacket packet) {
        writePacket(buf -> packet.writeTo(buf));
    }

    /** 发送 PREPARE OK 包 */
    public void writeStmtPrepareOk(StmtPrepareOkPacket packet) {
        writePacket(buf -> packet.writeTo(buf));
    }

    /** 发送列数量包（length-encoded integer） */
    public void writeColumnCount(int count) {
        writePacket(buf -> {
            cn.zhangyis.minidb.server.protocol.MysqlBufUtil.writeLengthEncodedInt(buf, count);
        });
    }

    /** 发送并刷新（立即写入网络） */
    public void flush() {
        ctx.flush();
    }

    // ==================== 内部帧构造 ====================

    /**
     * 构造完整 MySQL 包帧并写入 channel。
     *
     * <p>帧格式：[3字节payload长度(小端)] [1字节sequenceId] [payload]</p>
     *
     * @param payloadWriter 写入 payload 内容的回调
     */
    private void writePacket(PayloadWriter payloadWriter) {
        // 先写 payload 到临时 buf 以获取长度
        ByteBuf payloadBuf = ctx.alloc().buffer();
        payloadWriter.write(payloadBuf);

        int payloadLength = payloadBuf.readableBytes();

        // 构造完整帧
        ByteBuf frame = ctx.alloc().buffer(4 + payloadLength);
        // 3字节小端 payload 长度
        frame.writeByte(payloadLength & 0xFF);
        frame.writeByte((payloadLength >> 8) & 0xFF);
        frame.writeByte((payloadLength >> 16) & 0xFF);
        // 1字节 sequence id
        frame.writeByte(sequenceId++);
        // payload
        frame.writeBytes(payloadBuf);
        payloadBuf.release();

        ctx.write(frame);
    }

    @FunctionalInterface
    private interface PayloadWriter {
        void write(ByteBuf buf);
    }
}
