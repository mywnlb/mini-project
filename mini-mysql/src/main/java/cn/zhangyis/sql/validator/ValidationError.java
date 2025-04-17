package cn.zhangyis.sql.validator;

import lombok.Data;

@Data
public class ValidationError {
    private String message;
    private ErrorLevel level;
    private String errorCode;
    private String objectName;

    // Optional: source position information
    private int line;
    private int column;

    public enum ErrorLevel {
        WARNING, ERROR, FATAL
    }

    public ValidationError(String message, ErrorLevel level) {
        this.message = message;
        this.level = level;
    }

    public ValidationError(String message, ErrorLevel level, String objectName) {
        this(message, level);
        this.objectName = objectName;
    }
}