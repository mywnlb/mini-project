package cn.zhangyis.exceptions;

/**
 * 列操作异常
 * 用于处理列相关的异常，如约束检查、类型转换等
 */
public class ColumnException extends RuntimeException {

    public ColumnException(String message) {
        super(message);
    }

    public ColumnException(String message, Throwable cause) {
        super(message, cause);
    }
}
