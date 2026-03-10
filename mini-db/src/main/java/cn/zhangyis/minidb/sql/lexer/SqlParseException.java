package cn.zhangyis.minidb.sql.lexer;

public class SqlParseException extends RuntimeException {
    public SqlParseException(String message) {
        super(message);
    }
}