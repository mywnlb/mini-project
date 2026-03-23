package cn.zhangyis.minidb.server.protocol.packets;

import cn.zhangyis.minidb.server.protocol.MysqlBufUtil;
import cn.zhangyis.minidb.server.protocol.MysqlConstants;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * COM_STMT_EXECUTE 命令包解码。
 *
 * <p>二进制协议中最复杂的包：客户端发送预编译语句的参数值。</p>
 *
 * <p>包结构：
 * <pre>
 * 1  command (0x17)（已被调用方消费）
 * 4  statement_id
 * 1  flags (CURSOR_TYPE)
 * 4  iteration_count (固定为 1)
 * -- 以下仅当 num_params > 0 时存在 --
 * n  null_bitmap ((num_params+7)/8 字节，offset=0)
 * 1  new_params_bound_flag
 * -- 以下仅当 new_params_bound_flag == 1 时存在 --
 * n  param_type (每个参数 2 字节：type + unsigned_flag)
 * n  param_values (按类型编码的参数值)
 * </pre></p>
 */
public class ComStmtExecutePacket {

    private final int statementId;
    private final int flags;
    private final List<Object> paramValues;
    private final List<Integer> paramTypes;

    private ComStmtExecutePacket(int statementId, int flags,
                                  List<Object> paramValues, List<Integer> paramTypes) {
        this.statementId = statementId;
        this.flags = flags;
        this.paramValues = paramValues;
        this.paramTypes = paramTypes;
    }

    /**
     * 从 payload 解码（命令字节已被调用方消费）。
     *
     * @param buf       包 payload
     * @param numParams 参数数量（来自 PREPARE 阶段的记录）
     */
    public static ComStmtExecutePacket decode(ByteBuf buf, int numParams) {
        int stmtId = (int) MysqlBufUtil.readFixedLengthInt(buf, 4);
        int flg = buf.readByte() & 0xFF;
        buf.skipBytes(4); // iteration_count, 固定为 1

        List<Object> values = new ArrayList<>(numParams);
        List<Integer> types = new ArrayList<>(numParams);

        if (numParams > 0) {
            // null bitmap（offset=0）
            int bitmapLength = (numParams + 7) / 8;
            byte[] nullBitmap = MysqlBufUtil.readBytes(buf, bitmapLength);

            // new-params-bound flag
            int newParamsBound = buf.readByte() & 0xFF;

            // 读取参数类型
            if (newParamsBound == 1) {
                for (int i = 0; i < numParams; i++) {
                    int type = (int) MysqlBufUtil.readFixedLengthInt(buf, 2);
                    types.add(type & 0xFF); // 低字节为类型，高字节为 unsigned flag
                }
            }

            // 读取参数值
            for (int i = 0; i < numParams; i++) {
                // 检查 null bitmap
                int bytePos = i / 8;
                int bitPos = i % 8;
                boolean isNull = (nullBitmap[bytePos] & (1 << bitPos)) != 0;

                if (isNull) {
                    values.add(null);
                } else {
                    int paramType = types.isEmpty() ? MysqlConstants.MYSQL_TYPE_VAR_STRING : types.get(i);
                    values.add(readBinaryValue(buf, paramType));
                }
            }
        }

        return new ComStmtExecutePacket(stmtId, flg, values, types);
    }

    /**
     * 按 MySQL 类型从 ByteBuf 读取二进制值。
     */
    private static Object readBinaryValue(ByteBuf buf, int mysqlType) {
        return switch (mysqlType) {
            case MysqlConstants.MYSQL_TYPE_TINY -> (int) (buf.readByte());
            case MysqlConstants.MYSQL_TYPE_SHORT -> (int) MysqlBufUtil.readFixedLengthInt(buf, 2);
            case MysqlConstants.MYSQL_TYPE_LONG, MysqlConstants.MYSQL_TYPE_INT24 ->
                    (int) MysqlBufUtil.readFixedLengthInt(buf, 4);
            case MysqlConstants.MYSQL_TYPE_LONGLONG -> MysqlBufUtil.readFixedLengthInt(buf, 8);
            case MysqlConstants.MYSQL_TYPE_FLOAT -> {
                int bits = (int) MysqlBufUtil.readFixedLengthInt(buf, 4);
                yield (double) Float.intBitsToFloat(bits);
            }
            case MysqlConstants.MYSQL_TYPE_DOUBLE -> {
                long bits = MysqlBufUtil.readFixedLengthInt(buf, 8);
                yield Double.longBitsToDouble(bits);
            }
            case MysqlConstants.MYSQL_TYPE_DATETIME, MysqlConstants.MYSQL_TYPE_TIMESTAMP -> {
                int length = buf.readByte() & 0xFF;
                if (length == 0) {
                    yield LocalDateTime.of(0, 1, 1, 0, 0, 0);
                }
                int year = (int) MysqlBufUtil.readFixedLengthInt(buf, 2);
                int month = buf.readByte() & 0xFF;
                int day = buf.readByte() & 0xFF;
                int hour = 0, minute = 0, second = 0;
                if (length >= 7) {
                    hour = buf.readByte() & 0xFF;
                    minute = buf.readByte() & 0xFF;
                    second = buf.readByte() & 0xFF;
                }
                if (length > 7) {
                    buf.skipBytes(length - 7); // 跳过微秒部分
                }
                yield LocalDateTime.of(year, month, day, hour, minute, second);
            }
            default -> {
                // VARCHAR / DECIMAL / 其他：length-encoded string
                yield MysqlBufUtil.readLengthEncodedString(buf, StandardCharsets.UTF_8);
            }
        };
    }

    public int statementId() { return statementId; }
    public int flags() { return flags; }
    public List<Object> paramValues() { return paramValues; }
    public List<Integer> paramTypes() { return paramTypes; }
}
