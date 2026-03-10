package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.catalog.TableMeta;

public class RelIndexedScan extends RelScan {
    private final SqlNode indexCondition;

    public RelIndexedScan(String tableName, TableMeta tableMeta, SqlNode indexCondition) {
        super(tableName, tableMeta);
        this.indexCondition = indexCondition;
    }

    public SqlNode indexCondition() { return indexCondition; }

    @Override
    public String explain() {
        return "RelIndexedScan(table=" + tableName() + ", condition=" + indexCondition + ")";
    }
}