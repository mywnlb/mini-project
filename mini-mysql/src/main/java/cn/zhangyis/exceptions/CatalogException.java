package cn.zhangyis.exceptions;

/**
 * @Description TODO
 * @Date 2025/6/5 17:31
 * @Created by libo
 */
public class CatalogException extends RuntimeException {
    public CatalogException(String message) {
        super(message);
    }

    public CatalogException(String message, Throwable cause) {
        super(message, cause);
    }
}
