package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import java.util.List;

/**
 * UNION 关系代数节点
 */
public class RelUnion extends RelNode {
    private final RelNode left;
    private final RelNode right;
    private final boolean all;

    public RelUnion(RelNode left, RelNode right, boolean all) {
        this.left = left;
        this.right = right;
        this.all = all;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) {
        return new RelUnion(inputs.get(0), inputs.get(1), all);
    }

    @Override
    public String explain() {
        return "RelUnion(all=" + all + ")\n" +
               "  " + left.explain() +
               "  " + right.explain();
    }

    public RelNode left() { return left; }
    public RelNode right() { return right; }
    public boolean all() { return all; }
}