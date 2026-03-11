package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlDropIndex;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;

import java.util.List;

public class RelDropIndex extends RelNode {
    private final SqlDropIndex dropIndex;
    private final CatalogSpi catalog;

    public RelDropIndex(SqlDropIndex dropIndex, CatalogSpi catalog) {
        this.dropIndex = dropIndex;
        this.catalog = catalog;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) { return this; }

    @Override
    public String explain() {
        return "RelDropIndex(index=" + dropIndex.indexName()
            + ", table=" + dropIndex.table().name() + ")";
    }

    public SqlDropIndex dropIndex() { return dropIndex; }
    public CatalogSpi catalog() { return catalog; }
}
