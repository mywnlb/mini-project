package cn.zhangyis.minidb.sql.types;

public enum SqlType {
    INT32, BIGINT, VARCHAR, DECIMAL, DATETIME;

    public int sizeBytes() {
        return switch (this) {
            case INT32 -> 4;
            case BIGINT -> 8;
            case VARCHAR -> 64;
            case DECIMAL -> 16;
            case DATETIME -> 8;
        };
    }
}
