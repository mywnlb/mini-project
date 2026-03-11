package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.rel.*;

/**
 * Filter(Project(X)) → Project(Filter(X))
 * 将 Filter 下推到 Project 之下，减少投影前的数据量
 */
public class FilterProjectTransposeRule extends RelOptRule {
    public static final FilterProjectTransposeRule INSTANCE = new FilterProjectTransposeRule();

    @Override
    public boolean matches(RelNode node) {
        return node instanceof RelFilter filter && filter.input() instanceof RelProject;
    }

    @Override
    public RelNode apply(RelNode node) {
        RelFilter filter = (RelFilter) node;
        RelProject project = (RelProject) filter.input();

        // Filter 下推到 Project 之下
        RelFilter pushedFilter = new RelFilter(project.input(), filter.condition());
        return new RelProject(pushedFilter, project.projection());
    }
}
