package cn.zhangyis.sql.planner.semantic;

/**
 * 语义异常类
 * 用于表示语义分析过程中的错误
 */
public class SemanticException extends RuntimeException {
    public SemanticException(String message) {
        super(message);
    }
    
    public SemanticException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public SemanticException(Throwable cause) {
        super(cause);
    }
    
    /**
     * 表不存在异常
     */
    public static class TableNotFoundException extends SemanticException {
        public TableNotFoundException(String tableName) {
            super("Table not found: " + tableName);
        }
    }
    
    /**
     * 列不存在异常
     */
    public static class ColumnNotFoundException extends SemanticException {
        public ColumnNotFoundException(String tableName, String columnName) {
            super("Column not found: " + tableName + "." + columnName);
        }
    }
    
    /**
     * 类型不兼容异常
     */
    public static class TypeIncompatibleException extends SemanticException {
        public TypeIncompatibleException(String sourceType, String targetType) {
            super("Type incompatible: " + sourceType + " -> " + targetType);
        }
    }
    
    /**
     * 表达式不合法异常
     */
    public static class InvalidExpressionException extends SemanticException {
        public InvalidExpressionException(String expression) {
            super("Invalid expression: " + expression);
        }
    }
    
    /**
     * 函数不合法异常
     */
    public static class InvalidFunctionException extends SemanticException {
        public InvalidFunctionException(String functionName) {
            super("Invalid function: " + functionName);
        }
    }
} 