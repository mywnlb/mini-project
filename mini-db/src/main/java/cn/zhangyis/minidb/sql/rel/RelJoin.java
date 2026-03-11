package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import java.util.List;

public class RelJoin extends RelNode {
    private final RelNode left;
    private final RelNode right;
    private final SqlNode condition;

    public RelJoin(RelNode left, RelNode right, SqlNode condition) {
        this.left = left;
        this.right = right;
        this.condition = condition;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) {
        return new RelJoin(inputs.get(0), inputs.get(1), condition);
    }

    @Override
    public String explain() {
        return "RelJoin(condition=" + condition + ")\n" +
               "  left: " + left.explain() + "\n" +
               "  right: " + right.explain();
    }

    public RelNode left() { return left; }
    public RelNode right() { return right; }
    public SqlNode condition() { return condition; }
}
