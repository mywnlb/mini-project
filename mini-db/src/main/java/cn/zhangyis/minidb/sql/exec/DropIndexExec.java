package cn.zhangyis.minidb.sql.exec;

import cn.zhangyis.minidb.sql.ast.SqlDropIndex;
import cn.zhangyis.minidb.sql.catalog.CatalogSpi;
import cn.zhangyis.minidb.sql.rel.RelDropIndex;

import java.util.Map;

/**
 * DROP INDEX 执行器
 */
public class DropIndexExec implements ExecNode {
    private final RelDropIndex relDropIndex;
    private String message;
    private boolean returned;

    public DropIndexExec(RelDropIndex relDropIndex) {
        this.relDropIndex = relDropIndex;
    }

    @Override
    public void open() {
        SqlDropIndex drop = relDropIndex.dropIndex();
        CatalogSpi catalog = relDropIndex.catalog();

        catalog.dropIndex(drop.table().name(), drop.indexName());
        message = "Index '" + drop.indexName() + "' dropped from table '" + drop.table().name() + "'";
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
