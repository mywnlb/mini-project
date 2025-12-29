package cn.zhangyis.exceptions;

/**
 * 列工厂异常
 * 用于处理列工厂创建列时的异常
 */
public class ColumnFactoryException extends RuntimeException {

    public ColumnFactoryException(String message) {
        super(message);
    }

    public ColumnFactoryException(String message, Throwable cause) {
        super(message, cause);
    }
}