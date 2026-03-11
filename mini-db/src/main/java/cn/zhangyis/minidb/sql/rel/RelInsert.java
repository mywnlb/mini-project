package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlNodeList;
import cn.zhangyis.minidb.sql.catalog.TableMeta;
import java.util.List;

public class RelInsert extends RelNode {
    private final String tableName;
    private final TableMeta tableMeta;
    private final SqlNodeList columns;
    private final SqlNodeList valueRows;

    public RelInsert(String tableName, TableMeta tableMeta, SqlNodeList columns, SqlNodeList valueRows) {
        this.tableName = tableName;
        this.tableMeta = tableMeta;
        this.columns = columns;
        this.valueRows = valueRows;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) { return this; }

    @Override
    public String explain() {
        return "RelInsert(table=" + tableName + ", cols=" + columns.size() + ", rows=" + valueRows.size() + ")";
    }

    public String tableName() { return tableName; }
    public TableMeta tableMeta() { return tableMeta; }
    public SqlNodeList columns() { return columns; }
    public SqlNodeList valueRows() { return valueRows; }
}
