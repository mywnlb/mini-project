package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlNodeList;
import java.util.List;

public class RelProject extends RelNode {
    private final RelNode input;
    private final SqlNodeList projection;

    public RelProject(RelNode input, SqlNodeList projection) {
        this.input = input;
        this.projection = projection;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) {
        return new RelProject(inputs.get(0), projection);
    }

    @Override
    public String explain() {
        return "RelProject(fields=" + projection.nodes().size() + ")\n" +
               "  " + input.explain();
    }

    public RelNode input() { return input; }
    public SqlNodeList projection() { return projection; }
}
