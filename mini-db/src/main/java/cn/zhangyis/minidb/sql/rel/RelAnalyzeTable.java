package cn.zhangyis.minidb.sql.rel;

import java.util.List;

public class RelAnalyzeTable extends RelNode {
    private final String tableName;

    public RelAnalyzeTable(String tableName) {
        this.tableName = tableName;
    }

    public String tableName() { return tableName; }

    @Override
    public RelNode copy(List<RelNode> inputs) {
        return new RelAnalyzeTable(tableName);
    }

    @Override
    public String explain() {
        return "RelAnalyzeTable(" + tableName + ")";
    }
}
