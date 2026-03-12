package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.rel.*;

/**
 * Filter(Join(L, R)) → Join(Filter(L), R) 或 Join(L, Filter(R))
 * 根据 Filter 条件引用的表，将 Filter 下推到 Join 的对应输入侧
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

        // 判断条件引用哪一侧的表
        String leftTable = extractTableName(join.left());
        String rightTable = extractTableName(join.right());
        Side side = determineSide(condition, leftTable, rightTable);

        return switch (side) {
            case LEFT -> new RelJoin(
                new RelFilter(join.left(), condition),
                join.right(),
                join.condition()
            );
            case RIGHT -> new RelJoin(
                join.left(),
                new RelFilter(join.right(), condition),
                join.condition()
            );
            case BOTH -> node; // 条件涉及两侧，不下推
        };
    }

    private String extractTableName(RelNode node) {
        if (node instanceof RelScan scan) return scan.outputName();
        if (node instanceof RelFilter f) return extractTableName(f.input());
        if (node instanceof RelIndexedScan s) return s.outputName();
        return "";
    }

    private Side determineSide(SqlNode condition, String leftTable, String rightTable) {
        String condStr = condition.toString().toUpperCase();
        boolean refLeft = leftTable != null && !leftTable.isEmpty()
            && condStr.contains(leftTable.toUpperCase());
        boolean refRight = rightTable != null && !rightTable.isEmpty()
            && condStr.contains(rightTable.toUpperCase());

        if (refLeft && !refRight) return Side.LEFT;
        if (refRight && !refLeft) return Side.RIGHT;

        // 无表前缀的条件：尝试按列名匹配
        if (!refLeft && !refRight) {
            return guessFromColumnName(condition, leftTable, rightTable);
        }
        return Side.BOTH;
    }

    private Side guessFromColumnName(SqlNode condition, String leftTable, String rightTable) {
        // 简单启发式：不带表前缀的条件不下推
        return Side.BOTH;
    }

    private enum Side { LEFT, RIGHT, BOTH }
}
