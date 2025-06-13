package cn.zhangyis.sql.parser;

import cn.zhangyis.sql.parser.expression.Expression;

/**
 * SQL 删除语句表示
 */
public class DeleteStatement extends SQLStatement {
    private String tableName;
    private Expression whereCondition;

    private SelectStatement selectStatement;


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


    public SelectStatement getSelectStatement() {
        return selectStatement;
    }

    public void setWhereCondition(Expression whereCondition) {
        this.whereCondition = whereCondition;
    }

    public void setSelectStatement(SelectStatement selectStatement) {
        this.selectStatement = selectStatement;
    }

    @Override
    public String toString() {
        return "DeleteStatement{" +
                "tableName='" + tableName + '\'' +
                ", whereCondition=" + whereCondition +
                "} " + super.toString();
    }
}