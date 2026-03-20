package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.rel.*;

import java.util.*;

/**
 * 投影裁剪规则：移除连续 Project 中的冗余列。
 *
 * <p>场景：Project(cols=[a, b]) → Project(cols=[a, b, c, d])
 * <p>优化：合并为 Project(cols=[a, b])，消除内层多余列
 *
 * <p>也消除恒等投影（Project(SELECT *) 直接返回 input）。
 */
public class ProjectionPruningRule extends RelOptRule {

    public static final ProjectionPruningRule INSTANCE = new ProjectionPruningRule();

    @Override
    public boolean matches(RelNode node) {
        if (!(node instanceof RelProject outer)) return false;
        // 消除恒等 SELECT * Project
        if (isStarProjection(outer.projection())) return true;
        // 合并连续 Project
        return outer.input() instanceof RelProject;
    }

    @Override
    public RelNode apply(RelNode node) {
        RelProject outer = (RelProject) node;

        // 消除 SELECT * → 直接返回 input
        if (isStarProjection(outer.projection())) {
            return outer.input();
        }

        // 合并连续 Project
        if (outer.input() instanceof RelProject inner) {
            // 内层 Project 是 SELECT *，直接用外层替换
            if (isStarProjection(inner.projection())) {
                return new RelProject(inner.input(), outer.projection());
            }
            // 外层是 SELECT *，直接用内层替换
            // 这已经在上面处理了

            // 否则保持外层 Project，跳过内层
            return new RelProject(inner.input(), outer.projection());
        }

        return node;
    }

    private boolean isStarProjection(SqlNodeList projection) {
        return projection.size() == 1 && projection.get(0).kind() == SqlKind.STAR;
    }
}
