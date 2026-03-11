package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.rel.*;

public class JoinCommuteRule extends RelOptRule {
    public static final JoinCommuteRule INSTANCE = new JoinCommuteRule();

    private JoinCommuteRule() {}

    @Override
    public boolean matches(RelNode node) {
        if (!(node instanceof RelJoin join)) return false;
        return leftRowCount(join) > rightRowCount(join);
    }

    @Override
    public RelNode apply(RelNode node) {
        RelJoin join = (RelJoin) node;
        return new RelJoin(join.right(), join.left(), join.condition());
    }

    private long leftRowCount(RelJoin join) {
        return join.left() instanceof RelScan s ? s.tableMeta().rowCount() : Long.MAX_VALUE;
    }

    private long rightRowCount(RelJoin join) {
        return join.right() instanceof RelScan s ? s.tableMeta().rowCount() : Long.MAX_VALUE;
    }
}
