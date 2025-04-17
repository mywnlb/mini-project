package cn.zhangyis.sql;

import java.util.List;

public class UpdateStatement extends SQLStatement {
    private String tableName;
    private List<Assignment> assignments;
    private Expression whereCondition;

    public UpdateStatement(String tableName, List<Assignment> assignments, Expression whereCondition) {
        super(SQLType.UPDATE);
        this.tableName = tableName;
        this.assignments = assignments;
        this.whereCondition = whereCondition;
    }

    public String getTableName() {
        return tableName;
    }

    public List<Assignment> getAssignments() {
        return assignments;
    }

    public Expression getWhereCondition() {
        return whereCondition;
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