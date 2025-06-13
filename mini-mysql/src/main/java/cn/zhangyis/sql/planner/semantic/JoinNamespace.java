package cn.zhangyis.sql.planner.semantic;

import cn.zhangyis.sql.parser.expression.Expression;
import cn.zhangyis.sql.parser.SelectStatement;
import cn.zhangyis.storage.catalog.Column;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JOIN命名空间实现
 * 代表一个JOIN操作的结果命名空间
 * 参考 Apache Calcite 的 JoinNamespace
 * 
 * 用于处理如下SQL:
 * SELECT * FROM table1 t1 JOIN table2 t2 ON t1.id = t2.id
 * SELECT * FROM table1 LEFT JOIN table2 ON table1.x = table2.y
 */
public class JoinNamespace implements SqlValidatorNamespace {
    private final SqlValidatorNamespace leftNamespace;
    private final SqlValidatorNamespace rightNamespace;
    private final SelectStatement.JoinClause.JoinType joinType;
    private final Expression joinCondition;
    private final String alias;
    private final Map<String, Column> columnMap;
    private List<Column> columns;
    private boolean validated = false;
    
    public JoinNamespace(SqlValidatorNamespace leftNamespace, 
                        SqlValidatorNamespace rightNamespace,
                        SelectStatement.JoinClause.JoinType joinType,
                        Expression joinCondition,
                        String alias) {
        this.leftNamespace = leftNamespace;
        this.rightNamespace = rightNamespace;
        this.joinType = joinType;
        this.joinCondition = joinCondition;
        this.alias = alias;
        this.columnMap = new HashMap<>();
        this.columns = new ArrayList<>();
    }
    
    @Override
    public String getName() {
        return alias != null ? alias : "JOIN";
    }
    
    @Override
    public NamespaceType getType() {
        return NamespaceType.JOIN;
    }
    
    @Override
    public Column findColumn(String columnName) {
        return columnMap.get(columnName.toLowerCase());
    }
    
    @Override
    public List<Column> getColumns() {
        return new ArrayList<>(columns);
    }
    
    @Override
    public boolean hasColumn(String columnName) {
        return columnMap.containsKey(columnName.toLowerCase());
    }
    
    @Override
    public int getColumnCount() {
        return columns.size();
    }
    
    @Override
    public void validate() throws SemanticException {
        if (validated) {
            return;
        }
        
        // 验证左右命名空间
        leftNamespace.validate();
        rightNamespace.validate();
        
        // 合并左右命名空间的列
        mergeNamespaceColumns();
        validated = true;
    }
    
    @Override
    public boolean isValidated() {
        return validated;
    }
    
    /**
     * 合并左右命名空间的列
     */
    private void mergeNamespaceColumns() throws SemanticException {
        columns.clear();
        columnMap.clear();
        
        // 添加左表的列
        for (Column leftColumn : leftNamespace.getColumns()) {
            Column column = new Column(leftColumn.getName(), leftColumn.getType());
            
            // 根据JOIN类型决定是否可为空
            if (joinType == SelectStatement.JoinClause.JoinType.RIGHT) {
                // RIGHT JOIN时左表列可能为NULL
                column.setNullable(true);
            }
            
            columns.add(column);
            columnMap.put(column.getName().toLowerCase(), column);
        }
        
        // 添加右表的列
        for (Column rightColumn : rightNamespace.getColumns()) {
            // 检查列名冲突
            String columnName = rightColumn.getName();
            if (columnMap.containsKey(columnName.toLowerCase())) {
                // 如果有列名冲突，可以选择重命名或报错
                // 这里采用简单的重命名策略
                columnName = rightNamespace.getName() + "_" + columnName;
            }
            
            Column column = new Column(columnName, rightColumn.getType());
            
            // 根据JOIN类型决定是否可为空
            if (joinType == SelectStatement.JoinClause.JoinType.LEFT) {
                // LEFT JOIN时右表列可能为NULL
                column.setNullable(true);
            }
            
            columns.add(column);
            columnMap.put(columnName.toLowerCase(), column);
        }
    }
    
    /**
     * 在指定的命名空间中查找列
     * 支持表名限定的列引用
     */
    public Column findQualifiedColumn(String tableName, String columnName) {
        // 先在左命名空间中查找
        if (tableName == null || tableName.equals(leftNamespace.getName())) {
            Column leftColumn = leftNamespace.findColumn(columnName);
            if (leftColumn != null) {
                return leftColumn;
            }
        }
        
        // 再在右命名空间中查找
        if (tableName == null || tableName.equals(rightNamespace.getName())) {
            Column rightColumn = rightNamespace.findColumn(columnName);
            if (rightColumn != null) {
                return rightColumn;
            }
        }
        
        // 如果都没找到，返回null
        return null;
    }
    
    /**
     * 获取左命名空间
     */
    public SqlValidatorNamespace getLeftNamespace() {
        return leftNamespace;
    }
    
    /**
     * 获取右命名空间
     */
    public SqlValidatorNamespace getRightNamespace() {
        return rightNamespace;
    }
    
    /**
     * 获取JOIN类型
     */
    public SelectStatement.JoinClause.JoinType getJoinType() {
        return joinType;
    }
    
    /**
     * 获取JOIN条件
     */
    public Expression getJoinCondition() {
        return joinCondition;
    }
    
    /**
     * 获取别名
     */
    public String getAlias() {
        return alias;
    }
    
    /**
     * 检查是否是外连接
     */
    public boolean isOuterJoin() {
        return joinType == SelectStatement.JoinClause.JoinType.LEFT ||
               joinType == SelectStatement.JoinClause.JoinType.RIGHT;
    }
    
    @Override
    public String toString() {
        return "JoinNamespace{" +
                "joinType=" + joinType +
                ", leftNamespace=" + leftNamespace.getName() +
                ", rightNamespace=" + rightNamespace.getName() +
                ", columnCount=" + getColumnCount() +
                '}';
    }
} 