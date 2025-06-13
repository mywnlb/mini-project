package cn.zhangyis.sql.parser.expression;

public class ColumnExpression implements Expression {
    private final String tableAlias;
    private final String columnName;
    private String columnType = null; // 用于存储列类型，默认为null
    private boolean isAll = false;

    public ColumnExpression(String columnName) {
        this.tableAlias = null;
        this.columnName = columnName;
    }

    public ColumnExpression(String tableAlias, String columnName) {
        this.tableAlias = tableAlias;
        this.columnName = columnName;
    }

    public ColumnExpression(String tableAlias, boolean isAll) {
        this.tableAlias = tableAlias;
        this.columnName = null;
        this.isAll = isAll;
    }

    public Boolean getAll() {
        return isAll;
    }

    public String getTableAlias() {
        return tableAlias;
    }

    public String getColumnName() {
        return columnName;
    }

    public String getColumnType() {
        return columnType;
    }

    public void setColumnType(String columnType) {
        this.columnType = columnType;
    }

    @Override
    public ExpressionType getType() {
        return ExpressionType.COLUMN;
    }

    @Override
    public String toString() {
        if (tableAlias == null) {
            return columnName;
        }
        return tableAlias + "." + (isAll? "*" : columnName);
    }
}