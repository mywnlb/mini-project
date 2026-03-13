package cn.zhangyis.minidb.sql.optimize;

import cn.zhangyis.minidb.sql.ast.SqlBinaryOp;
import cn.zhangyis.minidb.sql.ast.SqlIdentifier;
import cn.zhangyis.minidb.sql.ast.SqlKind;
import cn.zhangyis.minidb.sql.ast.SqlNode;
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
        return new RelJoin(join.right(), join.left(), commuteCondition(join.condition()));
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
