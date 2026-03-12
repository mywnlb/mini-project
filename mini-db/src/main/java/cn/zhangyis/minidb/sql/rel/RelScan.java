package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.catalog.TableMeta;
import java.util.List;

public class RelScan extends RelNode {
    private final String tableName;
    private final TableMeta tableMeta;
    private final String outputName;

    public RelScan(String tableName, TableMeta tableMeta) {
        this(tableName, tableMeta, tableName);
    }

    public RelScan(String tableName, TableMeta tableMeta, String outputName) {
        this.tableName = tableName;
        this.tableMeta = tableMeta;
        this.outputName = outputName;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) {
        return new RelScan(tableName, tableMeta, outputName);
    }

    @Override
    public String explain() {
        String aliasPart = outputName.equalsIgnoreCase(tableName) ? "" : ", alias=" + outputName;
        return "RelScan(table=[" + tableName + "]" + aliasPart + ", rows=" + tableMeta.rowCount() + ")";
    }

    public String tableName() { return tableName; }
    public TableMeta tableMeta() { return tableMeta; }
    public String outputName() { return outputName; }
}
