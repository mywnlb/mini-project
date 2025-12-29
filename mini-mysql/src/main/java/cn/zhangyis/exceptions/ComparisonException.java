package cn.zhangyis.exceptions;

/**
 * 比较操作异常
 * 用于处理列比较操作中的异常
 */
public class ComparisonException extends RuntimeException {

    public ComparisonException(String message) {
        super(message);
    }

    public ComparisonException(String message, Throwable cause) {
        super(message, cause);
    }
}