package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import java.util.List;

/**
 * Anti-Join 逻辑节点：只输出在右侧无匹配的左侧行。
 * 语义：对于左侧每一行，如果右侧没有任何行匹配条件，则输出该左行。
 */
public class RelAntiJoin extends RelNode {
    private final RelNode left;
    private final RelNode right;
    private final SqlNode condition;

    public RelAntiJoin(RelNode left, RelNode right, SqlNode condition) {
        this.left = left;
        this.right = right;
        this.condition = condition;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) {
        return new RelAntiJoin(inputs.get(0), inputs.get(1), condition);
    }

    @Override
    public String explain() {
        return "RelAntiJoin(condition=" + condition + ")\n" +
               "  left: " + left.explain() + "\n" +
               "  right: " + right.explain();
    }

    public RelNode left() { return left; }
    public RelNode right() { return right; }
    public SqlNode condition() { return condition; }
}
