package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.catalog.TableMeta;

public class RelScan extends RelNode {
    private final String tableName;
    private final TableMeta tableMeta;

    public RelScan(String tableName, TableMeta tableMeta) {
        this.tableName = tableName;
        this.tableMeta = tableMeta;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) {
        return new RelScan(tableName, tableMeta);
    }

    @Override
    public String explain() {
        return "RelScan(table=[" + tableName + "], rows=" + tableMeta.rowCount() + ")";
    }

    public String tableName() { return tableName; }
    public TableMeta tableMeta() { return tableMeta; }
}