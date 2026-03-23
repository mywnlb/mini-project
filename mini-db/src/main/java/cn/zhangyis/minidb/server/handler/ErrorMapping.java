package cn.zhangyis.minidb.server.handler;

import cn.zhangyis.minidb.server.protocol.packets.ErrPacket;

/**
 * 异常到 MySQL 错误码的映射。
 *
 * <p>设计模式：工厂方法（Factory Method）——根据异常类型和消息内容
 * 自动选择合适的 MySQL 错误码和 SQL 状态码，构造 ErrPacket。
 * 上层 Handler 无需关心错误分类细节。</p>
 */
public final class ErrorMapping {

    private ErrorMapping() {}

    // ==================== MySQL 错误码常量 ====================

    public static final int ER_SYNTAX_ERROR = 1064;
    public static final int ER_NO_SUCH_TABLE = 1146;
    public static final int ER_BAD_FIELD_ERROR = 1054;
    public static final int ER_DUP_ENTRY = 1062;
    public static final int ER_ACCESS_DENIED = 1045;
    public static final int ER_UNKNOWN_COM_ERROR = 1047;
    public static final int ER_GENERAL_ERROR = 1105;
    public static final int ER_UNKNOWN_STMT_HANDLER = 1243;

    /**
     * 将异常转换为 ErrPacket。
     * 通过异常消息关键词匹配确定具体错误类型。
     */
    public static ErrPacket fromException(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) {
            msg = e.getClass().getSimpleName();
        }

        String lowerMsg = msg.toLowerCase();

        // 语法错误
        if (e instanceof IllegalArgumentException
                || lowerMsg.contains("syntax")
                || lowerMsg.contains("parse")
                || lowerMsg.contains("unexpected token")) {
            return new ErrPacket(ER_SYNTAX_ERROR, "42000", truncate(msg));
        }

        // 表不存在
        if (lowerMsg.contains("table") && (lowerMsg.contains("not found") || lowerMsg.contains("not exist")
                || lowerMsg.contains("doesn't exist"))) {
            return new ErrPacket(ER_NO_SUCH_TABLE, "42S02", truncate(msg));
        }

        // 列不存在
        if (lowerMsg.contains("column") && (lowerMsg.contains("not found") || lowerMsg.contains("unknown"))) {
            return new ErrPacket(ER_BAD_FIELD_ERROR, "42S22", truncate(msg));
        }

        // 重复键
        if (lowerMsg.contains("duplicate") || lowerMsg.contains("unique")) {
            return new ErrPacket(ER_DUP_ENTRY, "23000", truncate(msg));
        }

        // 预编译语句不存在
        if (lowerMsg.contains("statement") && lowerMsg.contains("not found")) {
            return new ErrPacket(ER_UNKNOWN_STMT_HANDLER, "HY000", truncate(msg));
        }

        // 通用错误
        return new ErrPacket(ER_GENERAL_ERROR, "HY000", truncate(msg));
    }

    /** 截断过长的错误消息（MySQL 客户端对消息长度有限制） */
    private static String truncate(String msg) {
        return msg.length() > 512 ? msg.substring(0, 512) : msg;
    }
}
