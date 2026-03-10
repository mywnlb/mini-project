package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.rel.*;

public class PushFilterIntoScanRule extends RelOptRule {
    public static final PushFilterIntoScanRule INSTANCE = new PushFilterIntoScanRule();

    private PushFilterIntoScanRule() {}

    @Override
    public boolean matches(RelNode node) {
        return node instanceof RelFilter filter && filter.input() instanceof RelScan;
    }

    @Override
    public RelNode apply(RelNode node) {
        RelFilter filter = (RelFilter) node;
        RelScan scan = (RelScan) filter.input();

        // 创建带索引条件的扫描 (MVP简化)
        return new RelIndexedScan(scan.tableName(), filter.condition());
    }
}