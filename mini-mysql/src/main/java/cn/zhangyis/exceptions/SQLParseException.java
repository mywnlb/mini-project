package cn.zhangyis.exceptions;

/**
 * @Description TODO
 * @Date 2025/3/16 21:53
 * @Created by libo
 */
public class SQLParseException extends RuntimeException {
    public SQLParseException(String message) {
        super(message);
    }

    public SQLParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
