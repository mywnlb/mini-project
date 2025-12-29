package cn.zhangyis.sql.planner.semantic.scope;

import cn.zhangyis.sql.parser.SelectStatement;
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