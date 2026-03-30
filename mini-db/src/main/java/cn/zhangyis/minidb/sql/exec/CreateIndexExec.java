package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.SqlCreateIndex;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.catalog.IndexMeta;
import cn.zhangyis.minidb.sql.rel.RelCreateIndex;

import java.util.Map;

/**
 * CREATE INDEX 执行器
 */
public class CreateIndexExec implements ExecNode {
    private final RelCreateIndex relCreateIndex;
    private String message;
    private boolean returned;

    public CreateIndexExec(RelCreateIndex relCreateIndex) {
        this.relCreateIndex = relCreateIndex;
    }

    @Override
    public void open() {
        SqlCreateIndex create = relCreateIndex.createIndex();
        CatalogSpi catalog = relCreateIndex.catalog();

        IndexMeta index = new IndexMeta(
                create.indexName(),
                create.table().name(),
                create.columns(),
                false,
                create.unique());
        catalog.createIndex(index);
        message = "Index '" + create.indexName() + "' created on table '" + create.table().name() + "'";
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
