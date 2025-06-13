package cn.zhangyis.sql.parser;

public abstract class SQLStatement {
    public enum SQLType {
        SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, UNKNOWN
    }

    private SQLType type;

    public SQLStatement(SQLType type) {
        this.type = type;
    }

    public SQLType getType() {
        return type;
    }

    @Override
    public String toString() {
        return "SQLStatement{" +
                "type=" + type +
                '}';
    }
}