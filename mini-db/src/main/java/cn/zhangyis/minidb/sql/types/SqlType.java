package cn.zhangyis.minidb.sql.types;

public enum SqlType {
    INT32, VARCHAR, DECIMAL, DATETIME;

    public int sizeBytes() {
        return switch (this) {
            case INT32 -> 4;
            case VARCHAR -> 64;
            case DECIMAL -> 16;
            case DATETIME -> 8;
        };
    }
}