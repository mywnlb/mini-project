package cn.zhangyis.minidb.sql.rel;

import cn.zhangyis.minidb.sql.ast.SqlNode;
import cn.zhangyis.minidb.sql.ast.SqlNodeList;
import cn.zhangyis.minidb.sql.catalog.TableMeta;
import java.util.List;

public class RelUpdate extends RelNode {
    private final RelNode input; // Scan or Filter(Scan)
    private final SqlNodeList assignments;

    public RelUpdate(RelNode input, SqlNodeList assignments) {
        this.input = input;
        this.assignments = assignments;
    }

    @Override
    public RelNode copy(List<RelNode> inputs) {
        return new RelUpdate(inputs.get(0), assignments);
    }

    @Override
    public String explain() {
        return "RelUpdate(sets=" + assignments.size() + ")\n  " + input.explain();
    }

    public RelNode input() { return input; }
    public SqlNodeList assignments() { return assignments; }
}
