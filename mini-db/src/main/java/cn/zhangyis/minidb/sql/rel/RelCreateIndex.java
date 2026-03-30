package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlCreateIndex;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;

import java.util.List;

public class RelCreateIndex extends RelNode {
    private final SqlCreateIndex createIndex;
    private final CatalogSpi catalog;

    public RelCreateIndex(SqlCreateIndex createIndex, CatalogSpi catalog) {
        this.createIndex = createIndex;
        this.catalog = catalog;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) { return this; }

    @Override
    public String explain() {
        return "RelCreateIndex(index=" + createIndex.indexName()
            + ", table=" + createIndex.table().name()
            + ", cols=" + createIndex.columns()
            + ", unique=" + createIndex.unique() + ")";
    }

    public SqlCreateIndex createIndex() { return createIndex; }
    public CatalogSpi catalog() { return catalog; }
}
