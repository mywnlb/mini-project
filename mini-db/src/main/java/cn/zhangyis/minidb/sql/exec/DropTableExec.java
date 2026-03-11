package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.SqlDropTable;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.rel.RelDropTable;

import java.util.Map;

/**
 * DROP TABLE 执行器
 */
public class DropTableExec implements ExecNode {
    private final RelDropTable relDrop;
    private String message;
    private boolean returned;

    public DropTableExec(RelDropTable relDrop) {
        this.relDrop = relDrop;
    }

    @Override
    public void open() {
        SqlDropTable drop = relDrop.dropTable();
        CatalogSpi catalog = relDrop.catalog();
        String tableName = drop.table().name();

        if (drop.ifExists() && !catalog.tableExists(tableName)) {
            message = "Table '" + tableName + "' does not exist, skipped";
        } else {
            catalog.dropTable(tableName);
            MockDataSource.deleteRows(tableName, row -> true);
            message = "Table '" + tableName + "' dropped";
        }
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
