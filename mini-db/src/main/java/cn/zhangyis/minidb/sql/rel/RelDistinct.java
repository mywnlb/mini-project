package cn.zhangyis.minidb.sql.rel;

import java.util.List;

public class RelDistinct extends RelNode {
    private final RelNode input;

    public RelDistinct(RelNode input) {
        this.input = input;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) {
        return new RelDistinct(inputs.get(0));
    }

    @Override
    public String explain() {
        return "RelDistinct\n" +
               "  " + input.explain();
    }

    public RelNode input() { return input; }
}
