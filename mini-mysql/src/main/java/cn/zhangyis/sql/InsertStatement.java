package cn.zhangyis.sql;

import java.util.List;

/**
 * 表示INSERT语句的类
 */
public class InsertStatement extends SQLStatement {
    private final String tableName;
    private final List<String> columnNames;
    private List<List<Expression>> valuesList;
    private SelectStatement selectStatement;

    /**
     * 创建带有VALUES子句的INSERT语句
     */
    public InsertStatement(String tableName, List<String> columnNames, List<List<Expression>> valuesList) {
        super(SQLType.INSERT);

        this.tableName = tableName;
        this.columnNames = columnNames;
        this.valuesList = valuesList;
        this.selectStatement = null;
    }

    /**
     * 创建带有SELECT子句的INSERT语句
     */
    public InsertStatement(String tableName, List<String> columnNames, SelectStatement selectStatement) {
        super(SQLType.INSERT);

        this.tableName = tableName;
        this.columnNames = columnNames;
        this.valuesList = null;
        this.selectStatement = selectStatement;
    }

    public String getTableName() {
        return tableName;
    }

    public List<String> getColumnNames() {
        return columnNames;
    }

    public List<List<Expression>> getValuesList() {
        return valuesList;
    }

    public SelectStatement getSelectStatement() {
        return selectStatement;
    }

    public void setSelectStatement(SelectStatement selectStatement) {
        this.selectStatement = selectStatement;
    }

    public void setValuesList(List<List<Expression>> valuesList) {
        this.valuesList = valuesList;
    }

    public boolean isSelectBased() {
        return selectStatement != null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ").append(tableName);

        if (!columnNames.isEmpty()) {
            sb.append(" (");
            for (int i = 0; i < columnNames.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(columnNames.get(i));
            }
            sb.append(")");
        }

        if (isSelectBased()) {
            sb.append(" ").append(selectStatement.toString());
        } else {
            sb.append(" VALUES ");
            for (int i = 0; i < valuesList.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("(");
                List<Expression> row = valuesList.get(i);
                for (int j = 0; j < row.size(); j++) {
                    if (j > 0) sb.append(", ");
                    sb.append(row.get(j).toString());
                }
                sb.append(")");
            }
        }

        return sb.toString();
    }
}