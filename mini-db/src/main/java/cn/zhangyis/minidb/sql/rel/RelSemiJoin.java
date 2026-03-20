package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import java.util.List;

/**
 * Semi-Join 逻辑节点：只输出左侧行（不合并右侧列），右侧仅用于存在性判断。
 * 语义：对于左侧每一行，如果右侧存在至少一行匹配条件，则输出该左行。
 */
public class RelSemiJoin extends RelNode {
    private final RelNode left;
    private final RelNode right;
    private final SqlNode condition;

    public RelSemiJoin(RelNode left, RelNode right, SqlNode condition) {
        this.left = left;
        this.right = right;
        this.condition = condition;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) {
        return new RelSemiJoin(inputs.get(0), inputs.get(1), condition);
    }

    @Override
    public String explain() {
        return "RelSemiJoin(condition=" + condition + ")\n" +
               "  left: " + left.explain() + "\n" +
               "  right: " + right.explain();
    }

    public RelNode left() { return left; }
    public RelNode right() { return right; }
    public SqlNode condition() { return condition; }
}
