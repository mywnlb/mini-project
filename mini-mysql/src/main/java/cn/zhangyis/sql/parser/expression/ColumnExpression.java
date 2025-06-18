package cn.zhangyis.sql.parser.expression;

import java.util.Collections;
import java.util.List;

/**
 * 列表达式，表示SQL中的列引用
 */
public class ColumnExpression extends Expression {
    private String tableName;  // 表名，可以为null（表示无限定名）
    private String columnName; // 列名
    private String columnType = null; // 用于存储列类型，默认为null
    private boolean isAll = false;

    /**
     * 创建无表名限定的列表达式
     * 
     * @param columnName 列名
     */
    public ColumnExpression(String columnName) {
        this(null, columnName);
    }

    /**
     * 创建带表名限定的列表达式
     * 
     * @param tableName 表名，可以为null
     * @param columnName 列名
     */
    public ColumnExpression(String tableName, String columnName) {
        this.tableName = tableName;
        this.columnName = columnName;
    }

    public ColumnExpression(String tableName, boolean isAll) {
        this.tableName = tableName;
        this.columnName = null;
        this.isAll = isAll;
    }

    @Override
    public ExpressionType getType() {
        return ExpressionType.COLUMN;
    }

    /**
     * 获取表名
     * 
     * @return 表名，可能为null
     */
    public String getTableName() {
        return tableName;
    }
    
    /**
     * 获取表别名（向后兼容）
     * 
     * @return 表别名，可能为null
     * @deprecated 使用 getTableName() 替代
     */
    @Deprecated
    public String getTableAlias() {
        return tableName;
    }

    public String getColumnName() {
        return columnName;
    }

    /**
     * 检查列是否有表名限定
     * 
     * @return 如果有表名限定则返回true，否则返回false
     */
    public boolean isQualified() {
        return tableName != null && !tableName.isEmpty();
    }
    
    public boolean isAll() {
        return isAll;
    }

    public String getColumnType() {
        return columnType;
    }

    public void setColumnType(String columnType) {
        this.columnType = columnType;
    }

    @Override
    public String toString() {
        if (isAll) {
            return tableName != null ? tableName + ".*" : "*";
        }
        return isQualified() ? tableName + "." + columnName : columnName;
    }
    
    /**
     * 获取所有子表达式
     * 列表达式是叶子节点，没有子表达式
     * 
     * @return 空列表
     */
    @Override
    public List<Expression> getChildExpressions() {
        return Collections.emptyList();
    }
}