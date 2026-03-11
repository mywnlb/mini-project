package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlDropTable;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;

import java.util.List;

public class RelDropTable extends RelNode {
    private final SqlDropTable dropTable;
    private final CatalogSpi catalog;

    public RelDropTable(SqlDropTable dropTable, CatalogSpi catalog) {
        this.dropTable = dropTable;
        this.catalog = catalog;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) { return this; }

    @Override
    public String explain() {
        return "RelDropTable(table=" + dropTable.table().name()
            + ", ifExists=" + dropTable.ifExists() + ")";
    }

    public SqlDropTable dropTable() { return dropTable; }
    public CatalogSpi catalog() { return catalog; }
}
