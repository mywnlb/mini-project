package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.rel.*;

import java.util.*;

/**
 * Filter(Join(L, R)) → Join(Filter(L), R) 或 Join(L, Filter(R))
 * 根据 Filter 条件引用的表，将 Filter 下推到 Join 的对应输入侧
 *
 * 改进点：
 * 1. 使用 AST 遍历收集 SqlIdentifier 的 qualifier，替代脆弱的字符串 contains
 * 2. 支持 AND 条件自动拆分：WHERE a.x=1 AND b.y=2 可分别下推到左右两侧
 */
public class FilterJoinPushdownRule extends RelOptRule {
    public static final FilterJoinPushdownRule INSTANCE = new FilterJoinPushdownRule();

    @Override
    public boolean matches(RelNode node) {
        return node instanceof RelFilter filter && filter.input() instanceof RelJoin;
    }

    @Override
    public RelNode apply(RelNode node) {
        RelFilter filter = (RelFilter) node;
        RelJoin join = (RelJoin) filter.input();
        SqlNode condition = filter.condition();

        String leftTable = extractTableName(join.left());
        String rightTable = extractTableName(join.right());

        // 展平 AND 条件并分类
        List<SqlNode> conditions = flattenAnd(condition);
        List<SqlNode> leftConditions = new ArrayList<>();
        List<SqlNode> rightConditions = new ArrayList<>();
        List<SqlNode> bothConditions = new ArrayList<>();

        for (SqlNode cond : conditions) {
            Side side = determineSidePrecise(cond, leftTable, rightTable);
            switch (side) {
                case LEFT -> leftConditions.add(cond);
                case RIGHT -> rightConditions.add(cond);
                case BOTH -> bothConditions.add(cond);
            }
        }

        // 构建新的 Filter 条件
        SqlNode leftFilterCond = buildAnd(leftConditions);
        SqlNode rightFilterCond = buildAnd(rightConditions);
        SqlNode remainingCond = buildAnd(bothConditions);

        RelNode newLeft = leftFilterCond != null
            ? new RelFilter(join.left(), leftFilterCond)
            : join.left();

        RelNode newRight = rightFilterCond != null
            ? new RelFilter(join.right(), rightFilterCond)
            : join.right();

        SqlNode finalJoinCondition = join.condition();
        if (remainingCond != null) {
            finalJoinCondition = finalJoinCondition == null
                ? remainingCond
                : new SqlBinaryOp(SqlKind.AND, finalJoinCondition, remainingCond);
        }

        if (leftFilterCond == null && rightFilterCond == null && remainingCond == null) {
            return node; // 没有变化
        }

        return new RelJoin(newLeft, newRight, finalJoinCondition);
    }

    private String extractTableName(RelNode node) {
        if (node instanceof RelScan scan) return scan.outputName();
        if (node instanceof RelFilter f) return extractTableName(f.input());
        if (node instanceof RelIndexedScan s) return s.outputName();
        return "";
    }

    /** 精确判断条件属于哪一侧（基于 qualifier） */
    private Side determineSidePrecise(SqlNode condition, String leftTbl, String rightTbl) {
        Set<String> qualifiers = collectQualifiers(condition, leftTbl, rightTbl);

        boolean refsLeft = !leftTbl.isEmpty() && qualifiers.contains(leftTbl.toUpperCase());
        boolean refsRight = !rightTbl.isEmpty() && qualifiers.contains(rightTbl.toUpperCase());

        if (refsLeft && !refsRight) return Side.LEFT;
        if (refsRight && !refsLeft) return Side.RIGHT;
        return Side.BOTH;
    }

    /** 递归收集 SqlIdentifier 中的 qualifier（表别名） */
    private Set<String> collectQualifiers(SqlNode node, String leftTbl, String rightTbl) {
        Set<String> qualifiers = new HashSet<>();
        collectQualifiersInternal(node, qualifiers, leftTbl, rightTbl);
        return qualifiers;
    }

    private void collectQualifiersInternal(SqlNode node, Set<String> qualifiers, String leftTbl, String rightTbl) {
        if (node == null) return;

        if (node instanceof SqlIdentifier id) {
            String name = id.name();
            if (name.contains(".")) {
                String qualifier = name.substring(0, name.indexOf('.')).trim().toUpperCase();
                if (!qualifier.isEmpty()) {
                    qualifiers.add(qualifier);
                }
            } else {
                // 无限定符的情况，尝试匹配表名（简单启发式）
                String upperName = name.toUpperCase();
                if (!leftTbl.isEmpty() && upperName.contains(leftTbl.toUpperCase())) {
                    qualifiers.add(leftTbl.toUpperCase());
                }
                if (!rightTbl.isEmpty() && upperName.contains(rightTbl.toUpperCase())) {
                    qualifiers.add(rightTbl.toUpperCase());
                }
            }
        } else if (node instanceof SqlBinaryOp bin) {
            collectQualifiersInternal(bin.left(), qualifiers, leftTbl, rightTbl);
            collectQualifiersInternal(bin.right(), qualifiers, leftTbl, rightTbl);
        } else if (node instanceof SqlNodeList list) {
            for (SqlNode n : list.nodes()) {
                collectQualifiersInternal(n, qualifiers, leftTbl, rightTbl);
            }
        }
    }

    /** 将 AND 条件展平为列表 */
    private List<SqlNode> flattenAnd(SqlNode condition) {
        List<SqlNode> result = new ArrayList<>();
        flattenAndInternal(condition, result);
        return result.isEmpty() ? List.of(condition) : result;
    }

    private void flattenAndInternal(SqlNode node, List<SqlNode> result) {
        if (node instanceof SqlBinaryOp bin && bin.kind() == SqlKind.AND) {
            flattenAndInternal(bin.left(), result);
            flattenAndInternal(bin.right(), result);
        } else {
            result.add(node);
        }
    }

    /** 将多个条件合并为 AND 树 */
    private SqlNode buildAnd(List<SqlNode> conditions) {
        if (conditions.isEmpty()) return null;
        if (conditions.size() == 1) return conditions.get(0);

        SqlNode result = conditions.get(0);
        for (int i = 1; i < conditions.size(); i++) {
            result = new SqlBinaryOp(SqlKind.AND, result, conditions.get(i));
        }
        return result;
    }

    private enum Side { LEFT, RIGHT, BOTH }

    // 为了兼容性，保留旧方法
    private String leftTable = "";
    private String rightTable = "";
}
