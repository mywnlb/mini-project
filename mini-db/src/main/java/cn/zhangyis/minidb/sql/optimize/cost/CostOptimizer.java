package cn.zhangyis.minidb.sql.optimize.cost;

import cn.zhangyis.minidb.sql.ast.SqlLiteral;
import cn.zhangyis.minidb.sql.exec.PhysicalPlanner.JoinAlgorithm;
import cn.zhangyis.minidb.sql.rel.*;

/**
 * CBO 代价优化器：基于 CostModel 做物理计划决策
 * 核心职责：为 RelJoin 选择最优 JOIN 算法
 *
 * <p>默认构造器保持索引路径关闭；只有显式启用后才会把 INDEX_NESTED_LOOP
 * 纳入候选集合。</p>
 */
public class CostOptimizer {
    private final CostModel costModel;

    public CostOptimizer() {
        this(false);
    }

    public CostOptimizer(boolean enableIndexLookup) {
        this.costModel = new CostModel(enableIndexLookup);
    }

    public CostModel costModel() {
        return costModel;
    }

    /**
     * 为逻辑计划中的每个 JOIN 选择最优算法
     * 返回带注解的物理计划描述
     */
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
            double inputRows = costModel.estimateRows(sort.input());
            double cost = costModel.sortCost(sort.input());
            String lim = sort.limit() != null ? ", limit=" + sort.limit() : "";
            // TopN 优化：有 LIMIT 时用堆排序
            String algo = "MemSort";
            if (sort.limit() instanceof SqlLiteral lit) {
                int limitVal = Integer.parseInt(lit.value());
                if (limitVal < inputRows * 0.1) {
                    algo = "TopN(heap)";
                    cost = inputRows * 0.01 + limitVal * Math.log(limitVal) * 0.001;
                }
            }
            return algo + "(cost=" + String.format("%.1f", cost) + lim + ")\n" +
                   "  " + decidePhysicalPlan(sort.input());
        }
        if (plan instanceof RelProject project) {
            return "PHYSICAL_PROJECT\n  " + decidePhysicalPlan(project.input());
        }
        if (plan instanceof RelDistinct distinct) {
            double cost = costModel.distinctCost(distinct.input());
            return "HASH_DISTINCT(cost=" + String.format("%.1f", cost) + ")\n" +
                   "  " + decidePhysicalPlan(distinct.input());
        }
        if (plan instanceof RelAggregate agg) {
            double cost = costModel.aggregateCost(agg.input());
            return "HASH_AGGREGATE(cost=" + String.format("%.1f", cost) + ")\n" +
                   "  " + decidePhysicalPlan(agg.input());
        }
        if (plan instanceof RelJoin join) {
            JoinAlgorithm algo = costModel.chooseJoinAlgorithm(join.left(), join.right(), join.condition(), join.joinType());
            CostModel.JoinCostDetail detail = costModel.joinCostDetail(join.left(), join.right(), join.condition());
            return algo.name() + "(type=" + join.joinType() + ", chosen by CBO)\n" +
                   "  costs: " + detail + "\n" +
                   "  left: " + decidePhysicalPlan(join.left()) + "\n" +
                   "  right: " + decidePhysicalPlan(join.right());
        }
        if (plan instanceof RelSemiJoin semi) {
            return "SEMI_HASH_JOIN(condition=" + semi.condition() + ")\n" +
                   "  left: " + decidePhysicalPlan(semi.left()) + "\n" +
                   "  right: " + decidePhysicalPlan(semi.right());
        }
        if (plan instanceof RelAntiJoin anti) {
            return "ANTI_HASH_JOIN(condition=" + anti.condition() + ")\n" +
                   "  left: " + decidePhysicalPlan(anti.left()) + "\n" +
                   "  right: " + decidePhysicalPlan(anti.right());
        }
        if (plan instanceof RelIndexedScan scan) {
            return "INDEX_SCAN[" + scan.tableName() + "](condition=" + scan.indexCondition() + ")";
        }
        if (plan instanceof RelFilter filter) {
            return "FILTER\n  " + decidePhysicalPlan(filter.input());
        }
        if (plan instanceof RelScan scan) {
            return "TABLE_SCAN[" + scan.tableName() + "](rows=" + scan.tableMeta().rowCount() + ")";
        }
        return "UNKNOWN";
    }

    /**
     * 为 RelJoin 选择最优 JOIN 算法（供 PhysicalPlanner 调用）
     */
    public JoinAlgorithm chooseJoinAlgorithm(RelJoin join) {
        return costModel.chooseJoinAlgorithm(join.left(), join.right(), join.condition(), join.joinType());
    }
}
