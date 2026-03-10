package cn.zhangyis.minidb.sql.rel;

import java.util.List;

public abstract class RelNode {
    public abstract RelNode copy(List<RelNode> inputs);
    public abstract String explain();

    public RelTraitSet traitSet() {
        return RelTraitSet.DEFAULT;
    }
}

interface RelTraitSet {}
class DefaultRelTraitSet implements RelTraitSet {}