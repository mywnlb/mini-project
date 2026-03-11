package cn.zhangyis.minidb.sql.rel;

import java.util.List;

public class RelDelete extends RelNode {
    private final RelNode input; // Scan or Filter(Scan)

    public RelDelete(RelNode input) {
        this.input = input;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) {
        return new RelDelete(inputs.get(0));
    }

    @Override
    public String explain() {
        return "RelDelete\n  " + input.explain();
    }

    public RelNode input() { return input; }
}
