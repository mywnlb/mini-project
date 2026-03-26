package cn.zhangyis.minidb.sql.types;

public enum SqlType {
    TINYINT,
    SMALLINT,
    INT32,
    BIGINT,
    CHAR,
    VARCHAR,
    TEXT,
    BLOB,
    JSON,
    DECIMAL,
    DATE,
    TIME,
    DATETIME;

    public int sizeBytes() {
        return switch (this) {
            case TINYINT -> 1;
            case SMALLINT -> 2;
            case INT32 -> 4;
            case BIGINT -> 8;
            case CHAR -> 255;
            case VARCHAR -> 64;
            case TEXT, JSON -> 1024;
            case BLOB -> 1024;
            case DECIMAL -> 16;
            case DATE -> 10;
            case TIME -> 16;
            case DATETIME -> 32;
        };
    }
}
