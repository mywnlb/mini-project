package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlSetOperation.SetOpType;
import java.util.List;

/**
 * UNION / EXCEPT / INTERSECT 关系代数节点
 */
public class RelUnion extends RelNode {
    private final RelNode left;
    private final RelNode right;
    private final boolean all;
    private final SetOpType opType;

    public RelUnion(RelNode left, RelNode right, boolean all) {
        this(left, right, all, SetOpType.UNION);
    }

    public RelUnion(RelNode left, RelNode right, boolean all, SetOpType opType) {
        this.left = left;
        this.right = right;
        this.all = all;
        this.opType = opType;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) {
        return new RelUnion(inputs.get(0), inputs.get(1), all, opType);
    }

    @Override
    public String explain() {
        return "Rel" + opType + "(all=" + all + ")\n" +
               "  " + left.explain() +
               "  " + right.explain();
    }

    public RelNode left() { return left; }
    public RelNode right() { return right; }
    public boolean all() { return all; }
    public SetOpType opType() { return opType; }
}
