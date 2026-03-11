package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlCreateTable;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;

import java.util.List;

public class RelCreateTable extends RelNode {
    private final SqlCreateTable createTable;
    private final CatalogSpi catalog;

    public RelCreateTable(SqlCreateTable createTable, CatalogSpi catalog) {
        this.createTable = createTable;
        this.catalog = catalog;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) { return this; }

    @Override
    public String explain() {
        return "RelCreateTable(table=" + createTable.table().name()
            + ", cols=" + createTable.columnDefs().size()
            + ", ifNotExists=" + createTable.ifNotExists() + ")";
    }

    public SqlCreateTable createTable() { return createTable; }
    public CatalogSpi catalog() { return catalog; }
}
