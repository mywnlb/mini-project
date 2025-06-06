package cn.zhangyis.sql;

import java.util.List;

/**
 * SQL UPDATE语句表示
 * 用于更新表中的数据
 * 例如：UPDATE table_name SET column1 = value1, column2 = value2 WHERE condition;
 */
public class UpdateStatement extends SQLStatement {
    private String tableName;
    private List<Assignment> assignments;
    private Expression whereCondition;

    private SelectStatement selectStatement;

    public UpdateStatement(SQLType type) {
        super(type);
    }

    public UpdateStatement(String tableName, List<Assignment> assignments, Expression whereCondition) {
        super(SQLType.UPDATE);
        this.tableName = tableName;
        this.assignments = assignments;
        this.whereCondition = whereCondition;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public List<Assignment> getAssignments() {
        return assignments;
    }

    public void setAssignments(List<Assignment> assignments) {
        this.assignments = assignments;
    }

    public Expression getWhereCondition() {
        return whereCondition;
    }

    public void setWhereCondition(Expression whereCondition) {
        this.whereCondition = whereCondition;
    }

    public SelectStatement getSelectStatement() {
        return selectStatement;
    }

    public void setSelectStatement(SelectStatement selectStatement) {
        this.selectStatement = selectStatement;
    }

    @Override
    public String toString() {
        return "UpdateStatement{" +
                "tableName='" + tableName + '\'' +
                ", assignments=" + assignments +
                ", whereCondition=" + whereCondition +
                "} " + super.toString();
    }

    // Inner class to represent column assignments
    public static class Assignment {
        private final String columnName;
        private final Expression valueExpression;

        public Assignment(String columnName, Expression valueExpression) {
            this.columnName = columnName;
            this.valueExpression = valueExpression;
        }

        public String getColumnName() {
            return columnName;
        }

        public Expression getValueExpression() {
            return valueExpression;
        }

        @Override
        public String toString() {
            return columnName + " = " + valueExpression;
        }
    }
}