package cn.zhangyis.minidb.sql.optimize.cost;

import cn.zhangyis.minidb.sql.rel.*;

public class CostOptimizer {
    private final CostModel costModel = new CostModel();

    public String decidePhysicalPlan(RelNode optimizedPlan) {
        if (optimizedPlan instanceof RelIndexedScan indexed) {
            double cost = costModel.indexScanCost(indexed.tableName(), indexed.indexCondition());
            if (cost < 50.0) {  // 阈值决策
                return "PHYSICAL_INDEX_SCAN[" + indexed.tableName() + "] (cost=" + cost + ")";
            }
        }
        return "PHYSICAL_TABLE_SCAN (high cost)";
    }
}