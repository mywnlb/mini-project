package cn.zhangyis.exceptions;

/**
 * 数据类型异常
 * 用于处理数据类型转换、类型不兼容等异常
 */
public class DataTypeException extends RuntimeException {

    public DataTypeException(String message) {
        super(message);
    }

    public DataTypeException(String message, Throwable cause) {
        super(message, cause);
    }
}