package cn.zhangyis.sql.parser;

public class ColumnDefinition {
    private String name;
    private String type;
    private int length;
    private int precision;
    private boolean notNull;
    private String defaultValue;
    private String comment;

    public ColumnDefinition(String name, String type, int length, int precision, boolean notNull, String defaultValue, String comment) {
        this.name = name;
        this.type = type;
        this.length = length;
        this.precision = precision;
        this.notNull = notNull;
        this.defaultValue = defaultValue;
        this.comment = comment;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public int getLength() {
        return length;
    }

    public int getPrecision() {
        return precision;
    }

    public boolean isNotNull() {
        return notNull;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public String getComment() {
        return comment;
    }

    @Override
    public String toString() {
        return "ColumnDefinition{" +
                "name='" + name + '\'' +
                ", type='" + type + '\'' +
                ", length=" + length +
                ", precision=" + precision +
                ", notNull=" + notNull +
                ", defaultValue='" + defaultValue + '\'' +
                ", comment='" + comment + '\'' +
                '}';
    }
}