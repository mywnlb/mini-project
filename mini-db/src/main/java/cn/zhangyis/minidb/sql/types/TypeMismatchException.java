package cn.zhangyis.minidb.sql.types;

/**
 * 类型不匹配异常：值无法转换为目标列类型时抛出。
 */
public class TypeMismatchException extends RuntimeException {

    public TypeMismatchException(String columnName, SqlType expectedType, Object actualValue) {
        super("Type mismatch for column '" + columnName + "': expected " + expectedType
            + ", got " + (actualValue == null ? "NULL" : actualValue.getClass().getSimpleName()
            + "('" + actualValue + "')"));
    }
}
