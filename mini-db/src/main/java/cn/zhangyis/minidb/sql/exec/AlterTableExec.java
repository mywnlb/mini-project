package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.SqlAlterTable;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.catalog.ColumnMeta;
import cn.zhangyis.minidb.sql.rel.RelAlterTable;

import java.util.Map;

/**
 * ALTER TABLE ADD COLUMN 执行器
 */
public class AlterTableExec implements ExecNode {
    private final RelAlterTable relAlter;
    private String message;
    private boolean returned;

    public AlterTableExec(RelAlterTable relAlter) {
        this.relAlter = relAlter;
    }

    @Override
    public void open() {
        SqlAlterTable alter = relAlter.alterTable();
        CatalogSpi catalog = relAlter.catalog();
        String tableName = alter.table().name();

        ColumnMeta newCol = new ColumnMeta(alter.columnName(), alter.columnType(), false);
        catalog.addColumn(tableName, newCol);
        message = "Column '" + alter.columnName() + "' added to table '" + tableName + "'";
        returned = false;
    }

    @Override
    public Row next() {
        if (!returned) {
            returned = true;
            return new Row(Map.of("result", message));
        }
        return null;
    }

    @Override
    public void close() {}
}
