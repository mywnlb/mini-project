package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.rel.*;

import java.util.*;

/**
 * JOIN 图：扁平化左深 RelJoin 树为 relations + edges
 */
public class JoinGraph {

    public record JoinEdge(int leftIdx, int rightIdx, SqlNode condition) {
    }

    private final List<RelNode> relations;
    private final List<JoinEdge> edges;

    public JoinGraph(List<RelNode> relations, List<JoinEdge> edges) {
        this.relations = relations;
        this.edges = edges;
    }

    public List<RelNode> relations() {
        return relations;
    }

    public List<JoinEdge> edges() {
        return edges;
    }

    /**
     * 从左深 RelJoin 树扁平化为 JoinGraph
     * 只拆解连续的 INNER JOIN，OUTER JOIN 作为叶子节点
     */
    public static JoinGraph flatten(RelJoin root) {
        List<RelNode> relations = new ArrayList<>();
        List<SqlNode> conditions = new ArrayList<>();
        flattenRecursive(root, relations, conditions);

        // 构建 edges：每个条件中的 equi-pair 关联到对应的 relation index
        List<JoinEdge> edges = new ArrayList<>();
        for (SqlNode cond : conditions) {
            // 将条件分解为 AND 链
            List<SqlNode> atoms = new ArrayList<>();
            decomposeAnd(cond, atoms);
            for (SqlNode atom : atoms) {
                int[] pair = resolveEdge(atom, relations);
                if (pair != null) {
                    edges.add(new JoinEdge(pair[0], pair[1], atom));
                }
            }
        }
        return new JoinGraph(relations, edges);
    }

    private static void flattenRecursive(RelNode node, List<RelNode> relations, List<SqlNode> conditions) {
        if (node instanceof RelJoin join && join.joinType() == JoinType.INNER) {
            flattenRecursive(join.left(), relations, conditions);
            flattenRecursive(join.right(), relations, conditions);
            if (join.condition() != null) {
                conditions.add(join.condition());
            }
        } else {
            relations.add(node);
        }
    }

    private static void decomposeAnd(SqlNode node, List<SqlNode> atoms) {
        if (node instanceof SqlBinaryOp binOp && binOp.kind() == SqlKind.AND) {
            decomposeAnd(binOp.left(), atoms);
            decomposeAnd(binOp.right(), atoms);
        } else {
            atoms.add(node);
        }
    }

    /**
     * 解析条件关联的两个 relation index
     */
    private static int[] resolveEdge(SqlNode condition, List<RelNode> relations) {
        if (!(condition instanceof SqlBinaryOp binOp) || binOp.kind() != SqlKind.BINARY_EQ) {
            return null;
        }
        if (!(binOp.left() instanceof SqlIdentifier leftId) || !(binOp.right() instanceof SqlIdentifier rightId)) {
            return null;
        }
        int leftIdx = findRelation(leftId.name(), relations);
        int rightIdx = findRelation(rightId.name(), relations);
        if (leftIdx >= 0 && rightIdx >= 0 && leftIdx != rightIdx) {
            return new int[]{leftIdx, rightIdx};
        }
        return null;
    }

    private static int findRelation(String identifier, List<RelNode> relations) {
        String upper = identifier.toUpperCase();
        for (int i = 0; i < relations.size(); i++) {
            if (relationOutputs(relations.get(i), upper)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean relationOutputs(RelNode node, String identifier) {
        if (node instanceof RelScan scan) {
            if (identifier.contains(".")) {
                String prefix = identifier.substring(0, identifier.indexOf('.'));
                return scan.outputName().equalsIgnoreCase(prefix);
            }
            return scan.tableMeta().columns().stream()
                    .anyMatch(c -> c.name().equalsIgnoreCase(identifier));
        }
        if (node instanceof RelFilter f) return relationOutputs(f.input(), identifier);
        if (node instanceof RelProject p) return relationOutputs(p.input(), identifier);
        if (node instanceof RelDerivedScan d) {
            if (identifier.contains(".")) {
                return identifier.substring(0, identifier.indexOf('.')).equalsIgnoreCase(d.alias());
            }
        }
        return false;
    }

    /**
     * 返回连接两个子集的所有 JoinEdge
     */
    public List<JoinEdge> edgesBetween(BitSet s1, BitSet s2) {
        List<JoinEdge> result = new ArrayList<>();
        for (JoinEdge e : edges) {
            boolean leftInS1 = s1.get(e.leftIdx());
            boolean rightInS2 = s2.get(e.rightIdx());
            boolean leftInS2 = s2.get(e.leftIdx());
            boolean rightInS1 = s1.get(e.rightIdx());
            if ((leftInS1 && rightInS2) || (leftInS2 && rightInS1)) {
                result.add(e);
            }
        }
        return result;
    }

    /**
     * 快速判断两个子集是否有连接（连通性检查）
     */
    public boolean hasConnection(BitSet s1, BitSet s2) {
        for (JoinEdge e : edges) {
            boolean leftInS1 = s1.get(e.leftIdx());
            boolean rightInS2 = s2.get(e.rightIdx());
            boolean leftInS2 = s2.get(e.leftIdx());
            boolean rightInS1 = s1.get(e.rightIdx());
            if ((leftInS1 && rightInS2) || (leftInS2 && rightInS1)) {
                return true;
            }
        }
        return false;
    }
}