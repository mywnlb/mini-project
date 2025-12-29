package cn.zhangyis.sql.planner.semantic.scope;

import cn.zhangyis.enums.FiledType;
import cn.zhangyis.sql.parser.expression.Expression;
import cn.zhangyis.sql.parser.expression.LiteralExpression;
import cn.zhangyis.sql.planner.semantic.SemanticException;
import cn.zhangyis.storage.catalog.Column;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * VALUES命名空间实现
 * 代表一个VALUES子句的命名空间
 * 参考 Apache Calcite 的 ValuesNamespace
 *
 * 用于处理如下SQL:
 * VALUES (1, 'a'), (2, 'b'), (3, 'c')
 * INSERT INTO table VALUES (1, 'a'), (2, 'b')
 */
public class ValuesNamespace implements SqlValidatorNamespace {
    private final List<List<Expression>> valuesList;
    private final String alias;
    private final Map<String, Column> columnMap;
    private List<Column> columns;
    private boolean validated = false;

    public ValuesNamespace(List<List<Expression>> valuesList, String alias) {
        this.valuesList = valuesList;
        this.alias = alias;
        this.columnMap = new HashMap<>();
        this.columns = new ArrayList<>();
    }

    @Override
    public String getName() {
        return alias != null ? alias : "VALUES";
    }

    @Override
    public NamespaceType getType() {
        return NamespaceType.VALUES;
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
     * 获取别名
     */
    public String getAlias() {
        return alias;
    }

    @Override
    public String toString() {
        return "ValuesNamespace{" +
                "alias='" + alias + '\'' +
                ", rowCount=" + valuesList.size() +
                ", columnCount=" + getColumnCount() +
                '}';
    }
}