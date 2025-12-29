package cn.zhangyis.sql.planner.logical;

import cn.zhangyis.sql.parser.expression.ColumnExpression;
import cn.zhangyis.storage.catalog.Column;
import cn.zhangyis.storage.catalog.Table;

import java.util.ArrayList;

/**
 * 逻辑表扫描节点
 * 表示对基本表的扫描操作
 */
public class LogicalTableScan extends AbstractRelNode {
    private final Table table;

    public LogicalTableScan(Table table) {
        super();
        this.table = table;
        deriveOutput();
    }

    @Override
    public RelNodeType getType() {
        return RelNodeType.TABLE_SCAN;
    }

    @Override
    protected void deriveOutput() {
        outputExpressions = new ArrayList<>();
        outputNames = new ArrayList<>();

        for (Column column : table.getColumns()) {
            // 为每一列创建列表达式
            ColumnExpression colExpr = new ColumnExpression(table.getName(), column.getName());
            outputExpressions.add(colExpr);
            outputNames.add(column.getName());
        }
    }

    /**
     * 获取表对象
     */
    public Table getTable() {
        return table;
    }

    @Override
    public String toString() {
        return "LogicalTableScan(table=" + table.getName() +
                (alias != null ? ", alias=" + alias : "") + ")";
    }
}