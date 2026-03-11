package cn.zhangyis.minidb.sql.optimize.cost;

import cn.zhangyis.minidb.sql.ast.SqlBinaryOp;
import cn.zhangyis.minidb.sql.ast.SqlIdentifier;
import cn.zhangyis.minidb.sql.ast.SqlKind;
import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.exec.PhysicalPlanner.JoinAlgorithm;
import cn.zhangyis.minidb.sql.rel.*;

import java.util.Map;

public class CostModel {
    private final Map<String, Long> tableStats = Map.of(
        "users", 10000L,
        "orders", 50000L
    );

    // ==================== 基数估计 ====================

    public double estimateRows(RelNode node) {
        if (node instanceof RelScan s) return s.tableMeta().rowCount();
        if (node instanceof RelIndexedScan) return 10;
        if (node instanceof RelFilter f) return estimateRows(f.input()) * selectivity(f.condition());
        if (node instanceof RelJoin j) return estimateRows(j.left()) * estimateRows(j.right()) * 0.01;
        if (node instanceof RelAggregate a) return estimateRows(a.input()) * 0.1;
        if (node instanceof RelSort s) return estimateRows(s.input());
        if (node instanceof RelProject p) return estimateRows(p.input());
        return 10000;
    }

    // ==================== 选择率估计 ====================

    private double selectivity(SqlNode condition) {
        if (condition instanceof SqlBinaryOp binOp) {
            return switch (binOp.kind()) {
                case BINARY_EQ -> 0.1;   // 等值条件选择率 10%
                case BINARY_LT, BINARY_GT, BINARY_LE, BINARY_GE -> 0.3;
                case BINARY_NE -> 0.9;
                case AND -> selectivity(binOp.left()) * selectivity(binOp.right());
                case OR -> Math.min(1.0, selectivity(binOp.left()) + selectivity(binOp.right()));
                default -> 0.3;
            };
        }
        return 0.3;
    }

    // ==================== 算子代价 ====================

    public double fullScanCost(String tableName) {
        return tableStats.getOrDefault(tableName, 100000L) * 0.01;
    }

    public double indexScanCost(String tableName, SqlNode condition) {
        return condition.toString().contains("id") ? 10.0 : 100.0;
    }

    public double filterCost(double inputRows, SqlNode condition) {
        return inputRows * selectivity(condition);
    }

    public double aggregateCost(RelNode input) {
        return estimateRows(input) * 0.5;
    }

    public double sortCost(RelNode input) {
        double rows = estimateRows(input);
        return rows > 0 ? rows * Math.log(rows) * 0.01 : 0;
    }

    // ==================== JOIN 算法代价对比 ====================

    /**
     * 根据代价模型选择最优 JOIN 算法
     * - NLJoin: O(M*N)，适用非等值 JOIN
     * - HashJoin: O(M+N)，适用等值 JOIN，内表建哈希表
     * - SortMergeJoin: O(M*logM + N*logN)，适用数据倾斜/已排序
     * - IndexNLJoin: O(M*logN)，适用外表小、内表有索引
     */
    public JoinAlgorithm chooseJoinAlgorithm(RelNode left, RelNode right, SqlNode condition) {
        boolean isEquiJoin = isEquiJoinCondition(condition);
        double leftRows = estimateRows(left);
        double rightRows = estimateRows(right);

        if (!isEquiJoin) {
            return JoinAlgorithm.NESTED_LOOP;
        }

        double nlCost = nestedLoopJoinCost(leftRows, rightRows);
        double hashCost = hashJoinCost(leftRows, rightRows);
        double smCost = sortMergeJoinCost(leftRows, rightRows);
        double indexCost = indexNestedLoopJoinCost(leftRows, rightRows, right);

        // 选代价最小的
        double minCost = hashCost;
        JoinAlgorithm best = JoinAlgorithm.HASH_JOIN;

        if (smCost < minCost) {
            minCost = smCost;
            best = JoinAlgorithm.SORT_MERGE;
        }
        if (indexCost < minCost) {
            minCost = indexCost;
            best = JoinAlgorithm.INDEX_NESTED_LOOP;
        }
        if (nlCost < minCost) {
            best = JoinAlgorithm.NESTED_LOOP;
        }

        return best;
    }

    /**
     * 返回各 JOIN 算法的代价明细（用于 explain）
     */
    public JoinCostDetail joinCostDetail(RelNode left, RelNode right, SqlNode condition) {
        double leftRows = estimateRows(left);
        double rightRows = estimateRows(right);
        boolean isEquiJoin = isEquiJoinCondition(condition);

        return new JoinCostDetail(
            nestedLoopJoinCost(leftRows, rightRows),
            isEquiJoin ? hashJoinCost(leftRows, rightRows) : Double.MAX_VALUE,
            isEquiJoin ? sortMergeJoinCost(leftRows, rightRows) : Double.MAX_VALUE,
            isEquiJoin ? indexNestedLoopJoinCost(leftRows, rightRows, null) : Double.MAX_VALUE,
            leftRows, rightRows, isEquiJoin
        );
    }

    // ==================== 各 JOIN 算法代价公式 ====================

    private double nestedLoopJoinCost(double leftRows, double rightRows) {
        // O(M * N)
        return leftRows * rightRows * 0.001;
    }

    private double hashJoinCost(double leftRows, double rightRows) {
        // Build: O(N), Probe: O(M), 内存: O(min(M,N))
        double buildSide = Math.min(leftRows, rightRows);
        double probeSide = Math.max(leftRows, rightRows);
        return buildSide * 0.02 + probeSide * 0.01;
    }

    private double sortMergeJoinCost(double leftRows, double rightRows) {
        // Sort: O(M*logM + N*logN), Merge: O(M+N)
        double sortLeft = leftRows > 0 ? leftRows * Math.log(leftRows) * 0.005 : 0;
        double sortRight = rightRows > 0 ? rightRows * Math.log(rightRows) * 0.005 : 0;
        double merge = (leftRows + rightRows) * 0.005;
        return sortLeft + sortRight + merge;
    }

    private double indexNestedLoopJoinCost(double leftRows, double rightRows, RelNode right) {
        // O(M * logN)，仅当内表有索引时有效
        boolean hasIndex = right instanceof RelIndexedScan;
        double indexFactor = hasIndex ? 1.0 : 3.0; // 无索引时惩罚
        return leftRows * (rightRows > 0 ? Math.log(rightRows) : 1) * 0.01 * indexFactor;
    }

    // ==================== 辅助方法 ====================

    private boolean isEquiJoinCondition(SqlNode condition) {
        if (condition instanceof SqlBinaryOp binOp && binOp.kind() == SqlKind.BINARY_EQ) {
            return binOp.left() instanceof SqlIdentifier && binOp.right() instanceof SqlIdentifier;
        }
        return false;
    }

    public record JoinCostDetail(
        double nestedLoopCost,
        double hashJoinCost,
        double sortMergeCost,
        double indexNLCost,
        double leftRows,
        double rightRows,
        boolean isEquiJoin
    ) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("  leftRows=%.0f, rightRows=%.0f, equiJoin=%s\n", leftRows, rightRows, isEquiJoin));
            sb.append(String.format("  NL=%.1f, Hash=%.1f, SortMerge=%.1f, IndexNL=%.1f",
                nestedLoopCost,
                hashJoinCost == Double.MAX_VALUE ? -1 : hashJoinCost,
                sortMergeCost == Double.MAX_VALUE ? -1 : sortMergeCost,
                indexNLCost == Double.MAX_VALUE ? -1 : indexNLCost));
            return sb.toString();
        }
    }
}
