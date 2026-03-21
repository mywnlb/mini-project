package cn.zhangyis.minidb.sql.optimize.cost;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.catalog.ColumnStatistics;
import cn.zhangyis.minidb.sql.catalog.StatisticsStore;
import cn.zhangyis.minidb.sql.catalog.TableStatistics;
import cn.zhangyis.minidb.sql.exec.PhysicalPlanner.JoinAlgorithm;
import cn.zhangyis.minidb.sql.rel.*;

public class CostModel {
    private final boolean enableIndexLookup;
    private final StatisticsStore statsStore;

    public CostModel() {
        this(false);
    }

    public CostModel(boolean enableIndexLookup) {
        this(enableIndexLookup, null);
    }

    public CostModel(boolean enableIndexLookup, StatisticsStore statsStore) {
        this.enableIndexLookup = enableIndexLookup;
        this.statsStore = statsStore;
    }

    // ==================== 基数估计 ====================

    public double estimateRows(RelNode node) {
        if (node instanceof RelScan s) return s.tableMeta().rowCount();
        if (node instanceof RelFilter f) return estimateRows(f.input()) * selectivity(f.condition());
        if (node instanceof RelJoin j) {
            int keys = countEquiKeys(j.condition());
            double sel = keys > 0 ? Math.pow(0.1, keys) : 0.01;
            double innerRows = estimateRows(j.left()) * estimateRows(j.right()) * sel;
            return switch (j.joinType()) {
                case INNER -> innerRows;
                case LEFT -> Math.max(estimateRows(j.left()), innerRows);
                case RIGHT -> Math.max(estimateRows(j.right()), innerRows);
                case FULL -> Math.max(estimateRows(j.left()) + estimateRows(j.right()), innerRows);
                case CROSS -> estimateRows(j.left()) * estimateRows(j.right());
            };
        }
        if (node instanceof RelSemiJoin s) {
            int keys = countEquiKeys(s.condition());
            double sel = keys > 0 ? Math.min(0.5, Math.pow(0.3, keys)) : 0.3;
            return estimateRows(s.left()) * sel;
        }
        if (node instanceof RelAntiJoin a) {
            int keys = countEquiKeys(a.condition());
            double sel = keys > 0 ? Math.min(0.5, Math.pow(0.3, keys)) : 0.3;
            return estimateRows(a.left()) * (1 - sel);
        }
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
                case BINARY_EQ -> {
                    if (statsStore != null) {
                        ColumnStatistics cs = resolveColumnStats(binOp);
                        if (cs != null) yield cs.selectivityEq();
                    }
                    yield 0.1; // 回退
                }
                case BINARY_LT, BINARY_GT, BINARY_LE, BINARY_GE -> {
                    if (statsStore != null) {
                        ColumnStatistics cs = resolveColumnStats(binOp);
                        if (cs != null) {
                            Comparable<?> value = extractLiteralValue(binOp);
                            if (value != null) {
                                boolean isLessThan = binOp.kind() == SqlKind.BINARY_LT
                                    || binOp.kind() == SqlKind.BINARY_LE;
                                yield cs.selectivityRange(value, isLessThan);
                            }
                        }
                    }
                    yield 0.3; // 回退
                }
                case BINARY_NE -> 0.9;
                case AND -> selectivity(binOp.left()) * selectivity(binOp.right());
                case OR -> Math.min(1.0, selectivity(binOp.left()) + selectivity(binOp.right()));
                default -> 0.3;
            };
        }
        return 0.3;
    }

    /**
     * 从 binOp 中提取列的 ColumnStatistics
     */
    private ColumnStatistics resolveColumnStats(SqlBinaryOp binOp) {
        SqlIdentifier colId = null;
        if (binOp.left() instanceof SqlIdentifier id) colId = id;
        else if (binOp.right() instanceof SqlIdentifier id) colId = id;
        if (colId == null) return null;

        String name = colId.name();
        String tableName;
        String colName;
        if (name.contains(".")) {
            String[] parts = name.split("\\.", 2);
            tableName = parts[0];
            colName = parts[1];
        } else {
            return null; // 需要 qualified name 才能查找统计
        }

        TableStatistics ts = statsStore.getTableStatistics(tableName);
        if (ts == null) return null;
        return ts.column(colName);
    }

    private Comparable<?> extractLiteralValue(SqlBinaryOp binOp) {
        SqlNode valueNode = (binOp.left() instanceof SqlIdentifier) ? binOp.right() : binOp.left();
        if (valueNode instanceof cn.zhangyis.minidb.sql.ast.SqlLiteral lit) {
            try {
                return Double.parseDouble(lit.value());
            } catch (NumberFormatException e) {
                return lit.value();
            }
        }
        return null;
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
     * <p>阶段4完成后：受控开启 INDEX_NESTED_LOOP。
     * 只有当 enableIndexLookup=true 且 join key 匹配主键时，才会优先选择索引路径。
     * 这避免了“伪索引”问题，符合 roadmap 阶段4要求。</p>
     */
    public JoinAlgorithm chooseJoinAlgorithm(RelNode left, RelNode right, SqlNode condition) {
        return chooseJoinAlgorithm(left, right, condition, JoinType.INNER);
    }

    public JoinAlgorithm chooseJoinAlgorithm(RelNode left, RelNode right, SqlNode condition, JoinType joinType) {
        // CROSS JOIN 无 equi-key，强制 NL
        if (joinType == JoinType.CROSS) return JoinAlgorithm.NESTED_LOOP;

        int keyCount = countEquiKeys(condition);
        boolean isEquiJoin = keyCount > 0;
        double leftRows = estimateRows(left);
        double rightRows = estimateRows(right);

        if (!isEquiJoin) {
            return JoinAlgorithm.NESTED_LOOP;
        }

        double nlCost = nestedLoopJoinCost(leftRows, rightRows);
        double hashCost = hashJoinCost(leftRows, rightRows, keyCount);
        double smCost = sortMergeJoinCost(leftRows, rightRows);
        // OUTER JOIN 时禁用 IndexNL（无法追踪未匹配行）
        double indexLookupCost = enableIndexLookup && joinType == JoinType.INNER
            && supportsLookupJoin(left, right, condition)
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
        int keyCount = countEquiKeys(condition);
        boolean isEquiJoin = keyCount > 0;

        return new JoinCostDetail(
            nestedLoopJoinCost(leftRows, rightRows),
            isEquiJoin ? hashJoinCost(leftRows, rightRows, keyCount) : Double.MAX_VALUE,
            isEquiJoin ? sortMergeJoinCost(leftRows, rightRows) : Double.MAX_VALUE,
            enableIndexLookup && supportsLookupJoin(left, right, condition)
                ? indexNestedLoopCost(left, right, leftRows, rightRows, condition)
                : Double.MAX_VALUE,
            leftRows, rightRows, isEquiJoin
        );
    }

    // ==================== 各 JOIN 算法代价公式 ====================

    private double nestedLoopJoinCost(double leftRows, double rightRows) {
        // O(M * N)，常量调整为0.01以确保IndexNL在可用时被优先选择
        return leftRows * rightRows * 0.01;
    }

    private double hashJoinCost(double leftRows, double rightRows, int keyCount) {
        // Build: O(N), Probe: O(M), 多 key 时 probe 碰撞更少
        double buildSide = Math.min(leftRows, rightRows);
        double probeSide = Math.max(leftRows, rightRows);
        return buildSide * 0.02 + probeSide * (0.01 / keyCount);
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

    /**
     * 判断条件中是否包含至少一个 equi-key（两侧都是 SqlIdentifier 的 EQ）。
     * 支持纯 equi 条件和混合条件（AND 中部分 equi + 部分 non-equi）。
     */
    private boolean isEquiJoinCondition(SqlNode condition) {
        return countEquiKeys(condition) > 0;
    }

    /**
     * 递归统计条件中 equi-key 的数量。
     */
    private int countEquiKeys(SqlNode condition) {
        if (!(condition instanceof SqlBinaryOp binOp)) return 0;
        if (binOp.kind() == SqlKind.BINARY_EQ) {
            return (binOp.left() instanceof SqlIdentifier && binOp.right() instanceof SqlIdentifier) ? 1 : 0;
        }
        if (binOp.kind() == SqlKind.AND) {
            return countEquiKeys(binOp.left()) + countEquiKeys(binOp.right());
        }
        return 0;
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
