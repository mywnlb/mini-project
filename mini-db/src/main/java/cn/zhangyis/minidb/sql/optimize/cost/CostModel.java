package cn.zhangyis.minidb.sql.optimize.cost;

import cn.zhangyis.minidb.sql.ast.SqlBinaryOp;
import cn.zhangyis.minidb.sql.ast.SqlIdentifier;
import cn.zhangyis.minidb.sql.ast.SqlKind;
import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.exec.PhysicalPlanner.JoinAlgorithm;
import cn.zhangyis.minidb.sql.rel.*;

public class CostModel {
    private final boolean enableIndexLookup;

    public CostModel() {
        this(false);
    }

    public CostModel(boolean enableIndexLookup) {
        this.enableIndexLookup = enableIndexLookup;
    }

    // ==================== 基数估计 ====================

    public double estimateRows(RelNode node) {
        if (node instanceof RelScan s) return s.tableMeta().rowCount();
        if (node instanceof RelFilter f) return estimateRows(f.input()) * selectivity(f.condition());
        if (node instanceof RelJoin j) return estimateRows(j.left()) * estimateRows(j.right()) * 0.01;
        if (node instanceof RelAggregate a) return estimateRows(a.input()) * 0.1;
        if (node instanceof RelDistinct d) return Math.max(1, estimateRows(d.input()) * 0.7);
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

    /**
     * 全表扫描代价
     * 优先使用 RelScan 中 TableMeta 的真实行数
     */
    public double fullScanCost(RelScan scan) {
        long rowCount = scan.tableMeta().rowCount();
        return rowCount > 0 ? rowCount * 0.01 : 1000.0;
    }

    /**
     * 兼容旧接口（用于字符串表名场景）
     */
    public double fullScanCost(String tableName) {
        return 1000.0; // 默认值，实际应通过 RelScan 调用
    }

    public double filterCost(double inputRows, SqlNode condition) {
        return inputRows * selectivity(condition);
    }

    public double aggregateCost(RelNode input) {
        return estimateRows(input) * 0.5;
    }

    public double distinctCost(RelNode input) {
        return estimateRows(input) * 0.3;
    }

    public double sortCost(RelNode input) {
        double rows = estimateRows(input);
        return rows > 0 ? rows * Math.log(rows) * 0.01 : 0;
    }

    // ==================== JOIN 算法代价对比 ====================

    /**
     * 根据代价模型选择最优 JOIN 算法。
     *
     * <p>默认构造器不会考虑索引路径；启用后仅在 join key 命中主键点查时
     * 才会评估 INDEX_NESTED_LOOP。</p>
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
        double indexLookupCost = enableIndexLookup && supportsLookupJoin(left, right, condition)
            ? indexNestedLoopCost(left, right, leftRows, rightRows, condition)
            : Double.MAX_VALUE;

        double minCost = hashCost;
        JoinAlgorithm best = JoinAlgorithm.HASH_JOIN;

        if (indexLookupCost <= minCost) {
            minCost = indexLookupCost;
            best = JoinAlgorithm.INDEX_NESTED_LOOP;
        }
        if (smCost < minCost) {
            minCost = smCost;
            best = JoinAlgorithm.SORT_MERGE;
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
            enableIndexLookup && supportsLookupJoin(left, right, condition)
                ? indexNestedLoopCost(left, right, leftRows, rightRows, condition)
                : Double.MAX_VALUE,
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

    private double indexNestedLoopCost(RelNode left, RelNode right,
                                       double leftRows, double rightRows, SqlNode condition) {
        if (lookupCandidate(right, rightIdentifier(condition))) {
            return leftRows * 0.005;
        }
        if (lookupCandidate(left, leftIdentifier(condition))) {
            return rightRows * 0.005;
        }
        return Double.MAX_VALUE;
    }

    // ==================== 辅助方法 ====================

    private boolean isEquiJoinCondition(SqlNode condition) {
        if (condition instanceof SqlBinaryOp binOp && binOp.kind() == SqlKind.BINARY_EQ) {
            return binOp.left() instanceof SqlIdentifier && binOp.right() instanceof SqlIdentifier;
        }
        return false;
    }

    private boolean supportsLookupJoin(RelNode left, RelNode right, SqlNode condition) {
        return lookupCandidate(right, rightIdentifier(condition))
            || lookupCandidate(left, leftIdentifier(condition));
    }

    private String leftIdentifier(SqlNode condition) {
        if (condition instanceof SqlBinaryOp binOp
            && binOp.left() instanceof SqlIdentifier id) {
            return id.name();
        }
        return null;
    }

    private String rightIdentifier(SqlNode condition) {
        if (condition instanceof SqlBinaryOp binOp
            && binOp.right() instanceof SqlIdentifier id) {
            return id.name();
        }
        return null;
    }

    private boolean lookupCandidate(RelNode node, String identifier) {
        if (!(node instanceof RelScan scan) || identifier == null) {
            return false;
        }
        String columnName = identifier.contains(".") ? identifier.split("\\.", 2)[1] : identifier;
        return scan.tableMeta().columns().stream()
            .anyMatch(column -> column.isPrimaryKey() && column.name().equalsIgnoreCase(columnName));
    }

    /**
     * JOIN 代价明细
     */
    public record JoinCostDetail(
        double nestedLoopCost,
        double hashJoinCost,
        double sortMergeCost,
        double indexNestedLoopCost,
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
                indexNestedLoopCost == Double.MAX_VALUE ? -1 : indexNestedLoopCost));
            return sb.toString();
        }
    }
}
