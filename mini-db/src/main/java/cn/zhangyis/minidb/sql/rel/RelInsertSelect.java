package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlNodeList;
import cn.zhangyis.minidb.sql.catalog.TableMeta;
import java.util.List;

/**
 * INSERT INTO table [(columns)] SELECT ...
 */
public class RelInsertSelect extends RelNode {
    private final String tableName;
    private final TableMeta tableMeta;
    private final SqlNodeList columns;
    private final RelNode input; // SELECT 子查询的逻辑计划

    public RelInsertSelect(String tableName, TableMeta tableMeta, SqlNodeList columns, RelNode input) {
        this.tableName = tableName;
        this.tableMeta = tableMeta;
        this.columns = columns;
        this.input = input;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) {
        return new RelInsertSelect(tableName, tableMeta, columns, inputs.get(0));
    }

    @Override
    public String explain() {
        return "RelInsertSelect(table=" + tableName + ", cols=" + columns.size() + ")\n  " + input.explain();
    }

    public String tableName() { return tableName; }
    public TableMeta tableMeta() { return tableMeta; }
    public SqlNodeList columns() { return columns; }
    public RelNode input() { return input; }
}
