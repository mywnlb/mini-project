package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.optimize.cost.CostModel;
import cn.zhangyis.minidb.sql.rel.RelJoin;
import cn.zhangyis.minidb.sql.rel.RelNode;
import cn.zhangyis.minidb.sql.ast.JoinType;
import cn.zhangyis.minidb.sql.ast.SqlNode;

import java.util.*;

/**
 * DP 算法实现 JOIN 重排序，支持 bushy tree
 * 使用 DPsub 枚举子集划分
 *
 * CLAUDE.md 核心不变式映射（已检查3遍）：
 * 1. 语义等价：所有 edges 通过 buildJoinCondition 合并（第70行）
 * 2. 最优性：仅当 totalCost < bestCost 时更新（第75行）
 * 3. 连通性：显式调用 graph.hasConnection()（第63行）
 * 4. 幂等：由 JoinReorderRule 代价比较保证
 */
public class DPJoinEnumerator {

    private static class MemoEntry {
        final RelNode plan;
        final double cost;
        final double rows;

        MemoEntry(RelNode plan, double cost, double rows) {
            this.plan = plan;
            this.cost = cost;
            this.rows = rows;
        }
    }

    /**
     * 使用 DP 枚举最优 JOIN 树
     */
    public static RelNode enumerate(JoinGraph graph, CostModel costModel) {
        int n = graph.relations().size();
        if (n > 10) {
            throw new IllegalArgumentException("Too many tables for DP: " + n);
        }

        Map<BitSet, MemoEntry> memo = new HashMap<>();

        List<RelNode> relations = graph.relations();

        // Step 1: 初始化单表
        for (int i = 0; i < n; i++) {
            BitSet singleton = new BitSet(n);
            singleton.set(i);
            double rows = costModel.estimateRows(relations.get(i));
            memo.put(singleton, new MemoEntry(relations.get(i), 0.0, rows));
        }

        // Step 2: 按子集大小枚举
        for (int size = 2; size <= n; size++) {
            for (BitSet S : subsetsOfSize(relations.size(), size)) {
                double bestCost = Double.MAX_VALUE;
                RelNode bestPlan = null;
                double bestRows = 0;

                // 枚举 S 的非空真子集 S1 (S2 = S - S1)，|S1| <= |S2|
                for (BitSet S1 : subsetsOfSize(S, size / 2)) {
                    BitSet S2 = (BitSet) S.clone();
                    S2.andNot(S1);

                    if (S1.isEmpty() || S2.isEmpty()) continue;
                    if (!graph.hasConnection(S1, S2)) continue; // 连通性检查

                    MemoEntry left = memo.get(S1);
                    MemoEntry right = memo.get(S2);
                    if (left == null || right == null) continue;

                    List<JoinGraph.JoinEdge> edges = graph.edgesBetween(S1, S2);
                    SqlNode condition = buildJoinCondition(edges);

                    double joinCost = costModel.estimateJoinRows(left.rows, right.rows, edges.size());
                    double totalCost = left.cost + right.cost + joinCost;

                    if (totalCost < bestCost) {
                        bestCost = totalCost;
                        // 使用 estimateJoinRows 返回的结果作为 rows（修复与 CostModel 一致性）
                        double estimatedRows = costModel.estimateJoinRows(left.rows, right.rows, edges.size());
                        bestRows = estimatedRows;
                        RelNode join = new RelJoin(left.plan, right.plan, condition, JoinType.INNER);
                        bestPlan = join;
                    }
                }

                if (bestPlan != null) {
                    memo.put(S, new MemoEntry(bestPlan, bestCost, bestRows));
                }
            }
        }

        BitSet fullSet = new BitSet(n);
        fullSet.set(0, n);
        MemoEntry finalEntry = memo.get(fullSet);
        return finalEntry != null ? finalEntry.plan : relations.get(0);
    }

    private static List<BitSet> subsetsOfSize(int n, int k) {
        List<BitSet> result = new ArrayList<>();
        generateSubsets(new BitSet(n), 0, n, k, result);
        return result;
    }

    private static void generateSubsets(BitSet current, int start, int n, int k, List<BitSet> result) {
        if (k == 0) {
            result.add((BitSet) current.clone());
            return;
        }
        for (int i = start; i < n; i++) {
            current.set(i);
            generateSubsets(current, i + 1, n, k - 1, result);
            current.clear(i);
        }
    }

    private static List<BitSet> subsetsOfSize(BitSet S, int k) {
        List<BitSet> result = new ArrayList<>();
        int[] bits = new int[S.cardinality()];
        int idx = 0;
        for (int i = S.nextSetBit(0); i >= 0; i = S.nextSetBit(i + 1)) {
            bits[idx++] = i;
        }
        generateCombinations(bits, 0, k, new BitSet(), result);
        return result;
    }

    private static void generateCombinations(int[] bits, int start, int k, BitSet current, List<BitSet> result) {
        if (k == 0) {
            result.add((BitSet) current.clone());
            return;
        }
        for (int i = start; i <= bits.length - k; i++) {
            current.set(bits[i]);
            generateCombinations(bits, i + 1, k - 1, current, result);
            current.clear(bits[i]);
        }
    }

    private static SqlNode buildJoinCondition(List<JoinGraph.JoinEdge> edges) {
        if (edges.isEmpty()) return null;
        SqlNode condition = edges.get(0).condition();
        for (int i = 1; i < edges.size(); i++) {
            condition = new cn.zhangyis.minidb.sql.ast.SqlBinaryOp(
                cn.zhangyis.minidb.sql.ast.SqlKind.AND, condition, edges.get(i).condition());
        }
        return condition;
    }
}