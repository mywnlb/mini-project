package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlAlterTable;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;

import java.util.List;

public class RelAlterTable extends RelNode {
    private final SqlAlterTable alterTable;
    private final CatalogSpi catalog;

    public RelAlterTable(SqlAlterTable alterTable, CatalogSpi catalog) {
        this.alterTable = alterTable;
        this.catalog = catalog;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) { return this; }

    @Override
    public String explain() {
        return "RelAlterTable(table=" + alterTable.table().name()
            + ", addColumn=" + alterTable.columnName() + " " + alterTable.columnType() + ")";
    }

    public SqlAlterTable alterTable() { return alterTable; }
    public CatalogSpi catalog() { return catalog; }
}
