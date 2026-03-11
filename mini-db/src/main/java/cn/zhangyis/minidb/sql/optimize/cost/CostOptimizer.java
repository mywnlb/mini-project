package cn.zhangyis.minidb.sql.optimize.cost;

import cn.zhangyis.minidb.sql.rel.*;

public class CostOptimizer {
    private final CostModel costModel = new CostModel();

    public String decidePhysicalPlan(RelNode plan) {
        if (plan instanceof RelInsert insert) {
            return "INSERT_EXEC[" + insert.tableName() + "](rows=" + insert.valueRows().size() + ")";
        }
        if (plan instanceof RelUpdate update) {
            return "UPDATE_EXEC(sets=" + update.assignments().size() + ")\n" +
                   "  " + decidePhysicalPlan(update.input());
        }
        if (plan instanceof RelDelete delete) {
            return "DELETE_EXEC\n  " + decidePhysicalPlan(delete.input());
        }
        if (plan instanceof RelSort sort) {
            double cost = costModel.sortCost(sort.input());
            String lim = sort.limit() != null ? ", limit=" + sort.limit() : "";
            return "SORT(cost=" + String.format("%.1f", cost) + lim + ")\n" +
                   "  " + decidePhysicalPlan(sort.input());
        }
        if (plan instanceof RelProject project) {
            return "PHYSICAL_PROJECT\n  " + decidePhysicalPlan(project.input());
        }
        if (plan instanceof RelAggregate agg) {
            double cost = costModel.aggregateCost(agg.input());
            return "HASH_AGGREGATE(cost=" + String.format("%.1f", cost) + ")\n" +
                   "  " + decidePhysicalPlan(agg.input());
        }
        if (plan instanceof RelJoin join) {
            double cost = costModel.joinCost(join.left(), join.right());
            String joinType = cost < 1000 ? "HASH_JOIN" : "NESTED_LOOP_JOIN";
            return joinType + "(cost=" + String.format("%.1f", cost) + ")\n" +
                   "  left: " + decidePhysicalPlan(join.left()) + "\n" +
                   "  right: " + decidePhysicalPlan(join.right());
        }
        if (plan instanceof RelIndexedScan indexed) {
            double cost = costModel.indexScanCost(indexed.tableName(), indexed.indexCondition());
            return "INDEX_SCAN[" + indexed.tableName() + "](cost=" + cost + ")";
        }
        if (plan instanceof RelFilter filter) {
            return "FILTER\n  " + decidePhysicalPlan(filter.input());
        }
        if (plan instanceof RelScan scan) {
            return "TABLE_SCAN[" + scan.tableName() + "](rows=" + scan.tableMeta().rowCount() + ")";
        }
        return "UNKNOWN";
    }
}
