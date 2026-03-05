package cn.zhangyis.minidb.storage.catalog;

import cn.zhangyis.minidb.common.exception.MiniDbException;

/**
 * Catalog 专用异常
 *
 * <p>错误码范围: 70xxxx (Module 70 = Catalog)</p>
 */
public class CatalogException extends MiniDbException {

    private static final int MODULE = 70;

    public CatalogException(String message) {
        super(message);
    }

    public CatalogException(int errorCode, String message) {
        super(errorCode, message);
    }

    public CatalogException(String message, Throwable cause) {
        super(message, cause);
    }

    public CatalogException(int errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    public static CatalogException tableAlreadyExists(String name) {
        return new CatalogException(700101,
                "Table already exists: " + name);
    }

    public static CatalogException tableNotFound(String name) {
        return new CatalogException(700201,
                "Table not found: " + name);
    }

    public static CatalogException tableNotFound(long tableId) {
        return new CatalogException(700202,
                "Table not found: tableId=" + tableId);
    }

    public static CatalogException columnNotFound(String tableName, String columnName) {
        return new CatalogException(700301,
                "Column not found: " + tableName + "." + columnName);
    }

    public static CatalogException invalidPrimaryKey(String reason) {
        return new CatalogException(700401,
                "Invalid primary key: " + reason);
    }
}
