package cn.zhangyis.minidb.server.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * MySQL 包帧解码器。
 *
 * <p>从 TCP 字节流中按 MySQL 包格式切分出独立的 {@link RawMysqlPacket}。</p>
 *
 * <p>MySQL 包帧格式：
 * <pre>
 * [3字节] payload_length（小端）
 * [1字节] sequence_id
 * [N字节] payload（N = payload_length）
 * </pre></p>
 *
 * <p>大包拆分：当 payload_length == 0xFFFFFF（16MB-1）时，表示后续还有
 * 续传包，需要累积直到收到 payload_length < 0xFFFFFF 的包。
 * 当前实现支持此场景。</p>
 */
public class MysqlPacketDecoder extends ByteToMessageDecoder {

    /** MySQL 包头长度：3字节payload长度 + 1字节序号 */
    private static final int HEADER_SIZE = 4;

    /** 单个 MySQL 包最大 payload */
    private static final int MAX_PAYLOAD = 0xFFFFFF;

    /** 累积大包的缓冲区（仅在大包拆分场景下使用） */
    private ByteBuf accumulated;

    /** 大包的起始序号 */
    private int accumulatedSequenceId;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.readableBytes() >= HEADER_SIZE) {
            in.markReaderIndex();

            // 读取 3 字节小端 payload 长度
            int payloadLength = (in.readByte() & 0xFF)
                    | ((in.readByte() & 0xFF) << 8)
                    | ((in.readByte() & 0xFF) << 16);
            int sequenceId = in.readByte() & 0xFF;

            // 检查 payload 是否完整到达
            if (in.readableBytes() < payloadLength) {
                in.resetReaderIndex();
                return; // 等待更多数据
            }

            ByteBuf payload = in.readRetainedSlice(payloadLength);

            if (payloadLength == MAX_PAYLOAD) {
                // 大包分片：累积后续分片
                if (accumulated == null) {
                    accumulated = ctx.alloc().compositeBuffer();
                    accumulatedSequenceId = sequenceId;
                }
                accumulated.writeBytes(payload);
                payload.release();
                continue; // 继续读下一个分片
            }

            if (accumulated != null) {
                // 最后一个分片：合并并输出
                accumulated.writeBytes(payload);
                payload.release();
                out.add(new RawMysqlPacket(accumulatedSequenceId, accumulated));
                accumulated = null;
            } else {
                // 正常大小的包：直接输出
                out.add(new RawMysqlPacket(sequenceId, payload));
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 清理未完成的累积缓冲区
        if (accumulated != null) {
            accumulated.release();
            accumulated = null;
        }
        super.channelInactive(ctx);
    }
}
