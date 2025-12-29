package cn.zhangyis.sql.planner.semantic.scope;

import cn.zhangyis.enums.FiledType;
import cn.zhangyis.sql.parser.SelectStatement;
import cn.zhangyis.sql.parser.expression.ColumnExpression;
import cn.zhangyis.sql.planner.semantic.SemanticException;
import cn.zhangyis.storage.catalog.Column;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SELECT查询命名空间实现
 * 代表一个SELECT查询的结果集命名空间
 * 参考 Apache Calcite 的 SelectNamespace
 */
public class SelectNamespace implements SqlValidatorNamespace {
    private final SelectStatement selectStatement;
    private final Map<String, Column> columnMap;
    private List<Column> columns;

    public SelectNamespace(SelectStatement selectStatement) {
        this.selectStatement = selectStatement;
        this.columnMap = new HashMap<>();
        this.columns = new ArrayList<>();
    }

    @Override
    public String getName() {
        return "SELECT";
    }

    @Override
    public NamespaceType getType() {
        return NamespaceType.SELECT;
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
     * 获取底层的SELECT语句
     */
    public SelectStatement getSelectStatement() {
        return selectStatement;
    }

    /**
     * 检查是否包含星号选择
     */
    public boolean hasStar() {
        for (SelectStatement.SelectItem item : selectStatement.getSelectItems()) {
            if (item.getExpression() instanceof ColumnExpression) {
                ColumnExpression colExpr = (ColumnExpression) item.getExpression();
                return colExpr.isAll();
            }
        }
        return false;
    }

    /**
     * 获取指定位置的SELECT项
     */
    public SelectStatement.SelectItem getSelectItem(int index) {
        List<SelectStatement.SelectItem> items = selectStatement.getSelectItems();
        if (index >= 0 && index < items.size()) {
            return items.get(index);
        }
        return null;
    }

    @Override
    public String toString() {
        return "SelectNamespace{" +
                "columnCount=" + getColumnCount() +
                '}';
    }
}