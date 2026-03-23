package cn.zhangyis.minidb.server.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * MySQL 包帧编码器。
 *
 * <p>将 payload ByteBuf 包装为完整的 MySQL 包帧（加 4 字节头）。
 * 与 {@link MysqlPacketDecoder} 配对使用。</p>
 *
 * <p>序号管理由上层 Handler 负责——本编码器不维护 sequence id，
 * 而是从写入的 payload 的第一个字节之前的位置读取外部设置的序号。
 * 实际做法：上层直接写入完整帧（含头），本编码器只做透传。</p>
 *
 * <p>但为了保持 pipeline 清晰，本编码器接收 payload ByteBuf，
 * 需要外部通过 channel attribute 或其他机制传递 sequenceId。
 * 当前实现采用简单方案：上层自行构造完整帧后 writeAndFlush。</p>
 */
public class MysqlPacketEncoder extends MessageToByteEncoder<ByteBuf> {

    /**
     * 将 payload 编码为 MySQL 包帧。
     *
     * <p>本编码器假设输入 ByteBuf 已经是完整的 MySQL 帧（含 4 字节头 + payload），
     * 直接透传到输出。上层通过 {@link PacketWriter} 工具类构造完整帧。</p>
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        out.writeBytes(msg);
    }
}
