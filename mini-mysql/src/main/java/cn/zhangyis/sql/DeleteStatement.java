package cn.zhangyis.sql;

public class DeleteStatement extends SQLStatement {
    private String tableName;
    private Expression whereCondition;

    public DeleteStatement(String tableName, Expression whereCondition) {
        super(SQLType.DELETE);
        this.tableName = tableName;
        this.whereCondition = whereCondition;
    }

    public String getTableName() {
        return tableName;
    }

    public Expression getWhereCondition() {
        return whereCondition;
    }

    @Override
    public String toString() {
        return "DeleteStatement{" +
                "tableName='" + tableName + '\'' +
                ", whereCondition=" + whereCondition +
                "} " + super.toString();
    }
}