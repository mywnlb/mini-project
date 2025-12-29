package cn.zhangyis.sql.planner.semantic.scope;

import cn.zhangyis.storage.catalog.Column;
import cn.zhangyis.storage.catalog.Table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 表命名空间实现
 * 代表一个表或视图的命名空间
 * 参考 Apache Calcite 的 TableNamespace
 */
public class TableNamespace implements SqlValidatorNamespace {
    private final Table table;
    private final String alias;
    private final Map<String, Column> columnMap;
    private boolean validated = false;

    public TableNamespace(Table table, String alias) {
        this.table = table;
        this.alias = alias;
        this.columnMap = new HashMap<>();
        initializeColumnMap();
    }

    @Override
    public String getName() {
        return alias != null ? alias : table.getName();
    }

    @Override
    public NamespaceType getType() {
        return NamespaceType.TABLE;
    }

    @Override
    public Column findColumn(String columnName) {
        return columnMap.get(columnName.toLowerCase());
    }

    @Override
    public List<Column> getColumns() {
        return new ArrayList<>(table.getColumns());
    }

    @Override
    public boolean hasColumn(String columnName) {
        return columnMap.containsKey(columnName.toLowerCase());
    }

    @Override
    public int getColumnCount() {
        return table.getColumns().size();
    }


    /**
     * 初始化列映射
     */
    private void initializeColumnMap() {
        for (Column column : table.getColumns()) {
            columnMap.put(column.getName().toLowerCase(), column);
        }
    }

    /**
     * 获取底层的表对象
     */
    public Table getTable() {
        return table;
    }

    /**
     * 获取别名
     */
    public String getAlias() {
        return alias;
    }

    /**
     * 检查是否有别名
     */
    public boolean hasAlias() {
        return alias != null && !alias.trim().isEmpty();
    }

    @Override
    public String toString() {
        return "TableNamespace{" +
                "table='" + table.getName() + '\'' +
                ", alias='" + alias + '\'' +
                ", columnCount=" + getColumnCount() +
                '}';
    }
} 