package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.ast.*;
import cn.zhangyis.minidb.sql.rel.*;

public class JoinCommuteRule extends RelOptRule {
    public static final JoinCommuteRule INSTANCE = new JoinCommuteRule();

    private JoinCommuteRule() {}

    @Override
    public boolean matches(RelNode node) {
        if (!(node instanceof RelJoin join)) return false;
        // FULL 已对称，CROSS 无条件 → 不交换
        JoinType jt = join.joinType();
        if (jt == JoinType.FULL || jt == JoinType.CROSS) return false;
        return leftRowCount(join) > rightRowCount(join);
    }

    @Override
    public RelNode apply(RelNode node) {
        RelJoin join = (RelJoin) node;
        // LEFT ↔ RIGHT 交换
        JoinType newType = switch (join.joinType()) {
            case LEFT -> JoinType.RIGHT;
            case RIGHT -> JoinType.LEFT;
            case INNER -> JoinType.INNER;
            case FULL -> JoinType.FULL;
            case CROSS -> JoinType.CROSS;
        };
        return new RelJoin(join.right(), join.left(), commuteCondition(join.condition()), newType);
    }

    private long leftRowCount(RelJoin join) {
        return join.left() instanceof RelScan s ? s.tableMeta().rowCount() : Long.MAX_VALUE;
    }

    private long rightRowCount(RelJoin join) {
        return join.right() instanceof RelScan s ? s.tableMeta().rowCount() : Long.MAX_VALUE;
    }

    private SqlNode commuteCondition(SqlNode condition) {
        if (condition instanceof SqlBinaryOp binOp
            && binOp.kind() == SqlKind.BINARY_EQ
            && binOp.left() instanceof SqlIdentifier
            && binOp.right() instanceof SqlIdentifier) {
            return new SqlBinaryOp(SqlKind.BINARY_EQ, binOp.right(), binOp.left());
        }
        return condition;
    }
}
