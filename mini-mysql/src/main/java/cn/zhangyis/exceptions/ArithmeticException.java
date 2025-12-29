package cn.zhangyis.exceptions;

/**
 * 算术操作异常
 * 用于处理算术操作相关的异常
 */
public class ArithmeticException extends RuntimeException {

    public ArithmeticException(String message) {
        super(message);
    }

    public ArithmeticException(String message, Throwable cause) {
        super(message, cause);
    }
}