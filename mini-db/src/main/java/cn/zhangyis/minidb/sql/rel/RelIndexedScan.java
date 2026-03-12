package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.TableMeta;

public class RelIndexedScan extends RelScan {
    private final SqlNode indexCondition;

    public RelIndexedScan(String tableName, TableMeta tableMeta, SqlNode indexCondition) {
        this(tableName, tableMeta, tableName, indexCondition);
    }

    public RelIndexedScan(String tableName, TableMeta tableMeta, String outputName, SqlNode indexCondition) {
        super(tableName, tableMeta, outputName);
        this.indexCondition = indexCondition;
    }

    public SqlNode indexCondition() { return indexCondition; }

    @Override
    public String explain() {
        String aliasPart = outputName().equalsIgnoreCase(tableName()) ? "" : ", alias=" + outputName();
        return "RelIndexedScan(table=" + tableName() + aliasPart + ", condition=" + indexCondition + ")";
    }
}
