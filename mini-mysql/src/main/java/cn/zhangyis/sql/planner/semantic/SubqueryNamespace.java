package cn.zhangyis.sql.planner.semantic;

import cn.zhangyis.sql.SelectStatement;
import cn.zhangyis.sql.ColumnExpression;
import cn.zhangyis.storage.catalog.Column;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 子查询命名空间实现
 * 代表一个子查询的命名空间
 */
public class SubqueryNamespace implements SqlValidatorNamespace {
    private final SelectStatement subquery;
    private final String alias;
    private final Map<String, Column> columnMap;
    private List<Column> columns;
    private boolean validated = false;
    
    public SubqueryNamespace(SelectStatement subquery, String alias) {
        this.subquery = subquery;
        this.alias = alias;
        this.columnMap = new HashMap<>();
        this.columns = new ArrayList<>();
    }
    
    @Override
    public String getName() {
        return alias != null ? alias : "subquery";
    }
    
    @Override
    public NamespaceType getType() {
        return NamespaceType.SUBQUERY;
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
        
        // 从子查询的 SELECT 项推导列信息
        deriveColumnsFromSelectItems();
        validated = true;
    }
    
    @Override
    public boolean isValidated() {
        return validated;
    }
    
    /**
     * 从 SELECT 项推导列信息
     */
    private void deriveColumnsFromSelectItems() throws SemanticException {
        columns.clear();
        columnMap.clear();
        
        for (SelectStatement.SelectItem item : subquery.getSelectItems()) {
            String columnName;
            String columnType = "VARCHAR"; // 默认类型
            
            // 确定列名
            if (item.getAlias() != null) {
                columnName = item.getAlias();
            } else if (item.getExpression() instanceof ColumnExpression) {
                ColumnExpression colExpr = (ColumnExpression) item.getExpression();
                columnName = colExpr.getColumnName();
                if (colExpr.getType() != null) {
                    columnType = colExpr.getType();
                }
            } else {
                // 对于复杂表达式，生成默认列名
                columnName = "column_" + (columns.size() + 1);
            }
            
            // 创建列对象
            Column column = new Column(columnName, columnType);
            columns.add(column);
            columnMap.put(columnName.toLowerCase(), column);
        }
    }
    
    /**
     * 获取底层的子查询对象
     */
    public SelectStatement getSubquery() {
        return subquery;
    }
    
    /**
     * 获取别名
     */
    public String getAlias() {
        return alias;
    }
    
    @Override
    public String toString() {
        return "SubqueryNamespace{" +
                "alias='" + alias + '\'' +
                ", columnCount=" + getColumnCount() +
                '}';
    }
} 