package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.optimize.cost.CostModel;
import cn.zhangyis.minidb.sql.rel.*;

import java.util.*;

/**
 * 多 JOIN 重排序规则（贪心算法）
 * 仅重排连续 INNER JOIN 链，OUTER JOIN 作为屏障不参与
 */
public class JoinReorderRule extends RelOptRule {
    private final CostModel costModel;

    public JoinReorderRule(CostModel costModel) {
        this.costModel = costModel;
    }

    @Override
    public boolean matches(RelNode node) {
        if (!(node instanceof RelJoin)) return false;
        return countInnerJoinLeaves((RelJoin) node) >= 3;
    }

    @Override
    public RelNode apply(RelNode node) {
        RelJoin root = (RelJoin) node;
        JoinGraph graph = JoinGraph.flatten(root);

        // 不够 3 个 relation 或超过 10 个时放弃
        if (graph.relations().size() < 3 || graph.relations().size() > 10) {
            return node;
        }

        return greedyReorder(graph);
    }

    private RelNode greedyReorder(JoinGraph graph) {
        // 活跃 relation 集合（按 index 管理）
        List<RelNode> relations = new ArrayList<>(graph.relations());
        List<JoinGraph.JoinEdge> edges = new ArrayList<>(graph.edges());

        // 将 relation index 映射到当前合并后的 relation
        Map<Integer, Integer> aliasMap = new HashMap<>();
        for (int i = 0; i < relations.size(); i++) {
            aliasMap.put(i, i);
        }

        Set<Integer> alive = new LinkedHashSet<>();
        for (int i = 0; i < relations.size(); i++) alive.add(i);

        while (alive.size() > 1) {
            double bestCost = Double.MAX_VALUE;
            int bestI = -1, bestJ = -1;
            JoinGraph.JoinEdge bestEdge = null;

            // 找代价最低的 join pair
            for (JoinGraph.JoinEdge edge : edges) {
                int li = resolve(aliasMap, edge.leftIdx());
                int ri = resolve(aliasMap, edge.rightIdx());
                if (!alive.contains(li) || !alive.contains(ri) || li == ri) continue;

                double leftRows = costModel.estimateRows(relations.get(li));
                double rightRows = costModel.estimateRows(relations.get(ri));
                double cost = leftRows * rightRows * 0.1;

                if (cost < bestCost) {
                    bestCost = cost;
                    bestI = li;
                    bestJ = ri;
                    bestEdge = edge;
                }
            }

            if (bestEdge == null) {
                // 无 edge 连接 → 合并最小的两个（笛卡尔积）
                Iterator<Integer> it = alive.iterator();
                bestI = it.next();
                bestJ = it.next();
                double minSize = Double.MAX_VALUE;
                for (int a : alive) {
                    for (int b : alive) {
                        if (a >= b) continue;
                        double size = costModel.estimateRows(relations.get(a)) + costModel.estimateRows(relations.get(b));
                        if (size < minSize) {
                            minSize = size;
                            bestI = a;
                            bestJ = b;
                        }
                    }
                }
            }

            // 合并 bestI + bestJ
            SqlNode mergedCondition = collectConditions(edges, aliasMap, bestI, bestJ);
            RelNode merged = new RelJoin(relations.get(bestI), relations.get(bestJ), mergedCondition, JoinType.INNER);
            relations.set(bestI, merged);
            alive.remove(bestJ);

            // 重定向所有指向 bestJ 的 alias
            for (Map.Entry<Integer, Integer> entry : aliasMap.entrySet()) {
                if (entry.getValue() == bestJ) {
                    entry.setValue(bestI);
                }
            }
        }

        return relations.get(alive.iterator().next());
    }

    /**
     * 收集两个 relation 之间的所有 join 条件，合并为 AND
     */
    private SqlNode collectConditions(List<JoinGraph.JoinEdge> edges, Map<Integer, Integer> aliasMap, int i, int j) {
        SqlNode result = null;
        for (JoinGraph.JoinEdge edge : edges) {
            int li = resolve(aliasMap, edge.leftIdx());
            int ri = resolve(aliasMap, edge.rightIdx());
            if ((li == i && ri == j) || (li == j && ri == i)) {
                result = result == null ? edge.condition() : new SqlBinaryOp(SqlKind.AND, result, edge.condition());
            }
        }
        return result;
    }

    private int resolve(Map<Integer, Integer> aliasMap, int idx) {
        int cur = idx;
        while (aliasMap.containsKey(cur) && aliasMap.get(cur) != cur) {
            cur = aliasMap.get(cur);
        }
        return cur;
    }

    private int countInnerJoinLeaves(RelJoin join) {
        int count = 0;
        if (join.joinType() != JoinType.INNER) return 1;
        count += (join.left() instanceof RelJoin lj) ? countInnerJoinLeaves(lj) : 1;
        count += (join.right() instanceof RelJoin rj) ? countInnerJoinLeaves(rj) : 1;
        return count;
    }
}
