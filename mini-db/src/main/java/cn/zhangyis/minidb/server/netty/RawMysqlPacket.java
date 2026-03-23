package cn.zhangyis.minidb.server.netty;

import io.netty.buffer.ByteBuf;

/**
 * 从 TCP 字节流中提取的原始 MySQL 包。
 *
 * <p>MySQL 协议的传输层单位：每个包由 4 字节头（3字节payload长度 + 1字节序号）
 * 和 payload 组成。本 record 持有解码后的序号和 payload 引用。</p>
 *
 * <p>注意：payload 是引用类型，使用完毕后调用方需负责释放。</p>
 *
 * @param sequenceId MySQL 包序号（同一次交互内递增）
 * @param payload    包 payload 数据
 */
public record RawMysqlPacket(int sequenceId, ByteBuf payload) {
}
